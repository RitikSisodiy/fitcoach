package com.fitcoach.app.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * One live voice conversation with the Gemini Live API (BidiGenerateContent over a WebSocket): microphone PCM in,
 * spoken PCM out, with transcripts of both sides. The model decides what to say and when the call is over
 * (the `end_call` tool); this class only moves bytes and keeps the transcript. Pure Kotlin, so it runs in JVM tests.
 *
 * Model fallback: if a model refuses the setup or answers with a transcript but no audio, the next model is tried
 * and the conversation restarts (see D-038).
 */
class LiveSession(
    private val apiKey: String,
    private val instruction: String,
    private val listener: Listener,
    private val models: List<String> = DEFAULT_MODELS,
    private val voice: String? = null,
    /** Text that makes the model speak first (the coach called); null to wait for the other side. */
    private val opening: String? = OPENING,
    private val client: OkHttpClient = defaultClient,
) {
    data class Turn(val role: String, val text: String)

    interface Listener {
        fun onConnected(model: String) {}
        fun onAudio(pcm24k: ByteArray) {}
        /** The user talked over the coach: drop queued audio. */
        fun onInterrupted() {}
        fun onTranscript(turns: List<Turn>) {}
        /** The coach finished a spoken turn. */
        fun onTurnComplete() {}
        /** The coach decided the conversation is over (after its goodbye). */
        fun onEndRequested(summary: String) {}
        /** The connection is gone; [error] is null for a normal close. */
        fun onClosed(error: String?) {}
    }

    private val lock = Any()
    private var ws: WebSocket? = null
    private var modelIndex = 0
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var coachAudioBytes = 0L
    private var coachTurns = 0
    private val turns = mutableListOf<Turn>()

    val model: String get() = models[modelIndex.coerceAtMost(models.lastIndex)]
    val transcript: List<Turn> get() = synchronized(lock) { turns.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() } }
    var summary: String? = null
        private set
    /** Why the last model switch happened (for the call log). */
    var lastFallbackReason: String? = null
        private set

    fun connect() = synchronized(lock) {
        ready = false
        coachAudioBytes = 0
        coachTurns = 0
        turns.clear()
        val request = Request.Builder().url("$URL?key=$apiKey").build()
        ws = client.newWebSocket(request, Socket(model))
    }

    /** 16 kHz mono PCM16 little-endian from the microphone. Ignored until the session is ready. */
    fun sendAudio(pcm16k: ByteArray) {
        if (!ready || closed) return
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audio",
            JSONObject().put("data", Base64.getEncoder().encodeToString(pcm16k)).put("mimeType", "audio/pcm;rate=16000"))).toString())
    }

    fun sendText(text: String) {
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }

    fun close() {
        synchronized(lock) { closed = true }
        ws?.close(1000, "call ended")
    }

    private fun setupMessage(model: String): String {
        val gen = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        voice?.let { gen.put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", it)))) }
        val endCall = JSONObject().put("name", "end_call")
            .put("description", "End the voice call after you have said goodbye. Call it once the conversation is naturally over.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("required", JSONArray().put("summary"))
                .put("properties", JSONObject().put("summary", JSONObject().put("type", "STRING")
                    .put("description", "What was said, decided and learned in this call, in English, 1-3 sentences."))))
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(endCall))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        ).toString()
    }

    /** Appends a transcription fragment to the current turn of [role], starting a new turn when the speaker changes. */
    private fun appendTranscript(role: String, text: String) {
        val snapshot = synchronized(lock) {
            val last = turns.lastOrNull()
            if (last != null && last.role == role) turns[turns.lastIndex] = last.copy(text = last.text + text) else turns += Turn(role, text)
            transcript
        }
        listener.onTranscript(snapshot)
    }

    private fun fallback(reason: String): Boolean {
        synchronized(lock) {
            if (closed || modelIndex >= models.lastIndex) return false
            modelIndex++
        }
        ws?.cancel()
        listener.onTranscript(emptyList())
        connect()
        lastFallbackReason = reason
        return true
    }

    private inner class Socket(private val modelName: String) : WebSocketListener() {
        private fun current(webSocket: WebSocket) = webSocket === ws

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setupMessage(modelName))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!current(webSocket)) return
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (msg.has("setupComplete")) {
                ready = true
                listener.onConnected(modelName)
                opening?.let(::sendText)
            }
            msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                val responses = JSONArray()
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    if (c.optString("name") == "end_call") summary = c.optJSONObject("args")?.optString("summary")?.takeIf { it.isNotBlank() }
                    responses.put(JSONObject().put("id", c.optString("id")).put("name", c.optString("name")).put("response", JSONObject().put("ok", true)))
                }
                webSocket.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
                if (summary != null) listener.onEndRequested(summary!!)
            }
            val content = msg.optJSONObject("serverContent") ?: return
            content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { appendTranscript("user", it) }
            content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { appendTranscript("coach", it) }
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                    val pcm = Base64.getDecoder().decode(data)
                    coachAudioBytes += pcm.size
                    listener.onAudio(pcm)
                }
            }
            if (content.optBoolean("interrupted")) listener.onInterrupted()
            if (content.optBoolean("turnComplete")) {
                coachTurns++
                // A model that speaks only in text cannot run a voice call; the first turn tells us.
                val spoke = synchronized(lock) { turns.any { it.role == "coach" } }
                if (coachTurns == 1 && coachAudioBytes == 0L && spoke && fallback("$modelName returned no audio")) return
                listener.onTurnComplete()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (!current(webSocket)) return
            if (!ready && !closed && fallback("$modelName: $code $reason")) return
            listener.onClosed(if (closed || code == 1000) null else "$code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current(webSocket)) return
            if (!ready && !closed && fallback("$modelName: ${t.message}")) return
            listener.onClosed(if (closed) null else (t.message ?: t.javaClass.simpleName))
        }
    }

    companion object {
        const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /** Low-latency voice model first; the older Live model is the fallback (D-038). */
        val DEFAULT_MODELS = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview")
        const val OPENING = "(The user just answered your call. Start talking now.)"
        private val defaultClient by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build() }
    }
}
