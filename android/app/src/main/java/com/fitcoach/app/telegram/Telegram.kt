package com.fitcoach.app.telegram

import com.fitcoach.app.core.strOrNull
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.str
import com.fitcoach.app.engine.CoachService
import com.fitcoach.app.engine.Inbound
import com.fitcoach.app.engine.Outbound
import com.fitcoach.app.llm.MediaPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Telegram Bot API client. The phone itself long-polls the bot, so Telegram and the app share one backend:
 * the same DB, memory, agent and conversation (docs/AGENT.md, D-033). No server, no webhook.
 */
class TelegramApi(private val token: String, private val transport: Transport = HttpTransport()) {

    fun interface Transport {
        /** POST a JSON body to a Bot API method URL; returns (httpCode, body). GET for file downloads when body is null. */
        suspend fun call(url: String, body: String?, readTimeoutMs: Int): Pair<Int, ByteArray>
    }

    class TelegramException(message: String) : Exception(message)

    private suspend fun method(name: String, params: JSONObject, readTimeoutMs: Int = 20_000): Any? {
        val (code, bytes) = transport.call("https://api.telegram.org/bot$token/$name", params.toString(), readTimeoutMs)
        val o = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: throw TelegramException("HTTP $code from $name")
        if (!o.optBoolean("ok")) throw TelegramException("$name failed: ${o.optInt("error_code")} ${o.optString("description")}")
        return o.opt("result")
    }

    suspend fun getMe(): JSONObject = method("getMe", JSONObject()) as JSONObject

    suspend fun getUpdates(offset: Long, timeoutSec: Int = 50): List<JSONObject> {
        val r = method("getUpdates", JSONObject().put("offset", offset).put("timeout", timeoutSec)
            .put("allowed_updates", JSONArray(listOf("message", "callback_query"))), (timeoutSec + 15) * 1000) as JSONArray
        return (0 until r.length()).map { r.getJSONObject(it) }
    }

    /** Sends text; [quickReplies] become an inline keyboard whose callback data points back to [messageRowId]. */
    suspend fun sendMessage(chatId: Long, text: String, quickReplies: List<String> = emptyList(), messageRowId: Long? = null): Long {
        val p = JSONObject().put("chat_id", chatId).put("text", text)
        if (quickReplies.isNotEmpty() && messageRowId != null) {
            val row = JSONArray(quickReplies.mapIndexed { i, label -> JSONObject().put("text", label).put("callback_data", "qr:$messageRowId:$i") })
            p.put("reply_markup", JSONObject().put("inline_keyboard", JSONArray().put(row)))
        }
        return (method("sendMessage", p) as JSONObject).optLong("message_id")
    }

    suspend fun answerCallback(id: String) = method("answerCallbackQuery", JSONObject().put("callback_query_id", id))

    suspend fun clearKeyboard(chatId: Long, messageId: Long) = runCatching {
        method("editMessageReplyMarkup", JSONObject().put("chat_id", chatId).put("message_id", messageId)
            .put("reply_markup", JSONObject().put("inline_keyboard", JSONArray())))
    }

    suspend fun typing(chatId: Long) = runCatching { method("sendChatAction", JSONObject().put("chat_id", chatId).put("action", "typing")) }

    suspend fun downloadFile(fileId: String): ByteArray {
        val path = (method("getFile", JSONObject().put("file_id", fileId)) as JSONObject).getString("file_path")
        val (code, bytes) = transport.call("https://api.telegram.org/file/bot$token/$path", null, 30_000)
        if (code != 200) throw TelegramException("file download HTTP $code")
        return bytes
    }

    class HttpTransport : Transport {
        override suspend fun call(url: String, body: String?, readTimeoutMs: Int): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = readTimeoutMs
                if (body != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                code to (stream?.use { it.readBytes() } ?: ByteArray(0))
            } finally {
                conn.disconnect()
            }
        }
    }
}

/** Persistent Telegram link settings (bot token, paired chat, pairing code, poll offset). */
interface TelegramSettings {
    var token: String?
    var chatId: Long?
    var botUsername: String?
    var pairingCode: String?
    var offset: Long
}

/**
 * Turns Telegram updates into coach messages and coach replies back into Telegram messages.
 * Pairing: the app shows a one-time code; only the chat that sends "/start <code>" is accepted afterwards.
 */
class TelegramBridge(private val api: TelegramApi, private val settings: TelegramSettings, private val service: () -> CoachService) {

    /** Handles one update; returns a short status for logs and tests. */
    suspend fun handle(update: JSONObject): String {
        settings.offset = update.optLong("update_id") + 1
        update.optJSONObject("callback_query")?.let { return handleCallback(it, update.optLong("update_id")) }
        val msg = update.optJSONObject("message") ?: return "ignored: no message"
        val chatId = msg.getJSONObject("chat").getLong("id")
        val text = msg.strOrNull("text") ?: msg.strOrNull("caption") ?: ""
        val paired = settings.chatId
        if (paired == null) {
            val code = settings.pairingCode ?: return "ignored: not pairing"
            if (text.trim().removePrefix("/start").trim() != code) return "ignored: wrong pairing code"
            settings.chatId = chatId
            settings.pairingCode = null
            api.sendMessage(chatId, "Connected to FitCoach. You can talk to your coach here or in the app - it is one conversation.")
            return "paired"
        }
        if (chatId != paired) return "ignored: other chat"
        if (text.startsWith("/start")) return "ignored: start"
        api.typing(chatId)
        val media = mutableListOf<MediaPart>()
        var kind = "text"
        msg.optJSONObject("voice")?.let { media += MediaPart(api.downloadFile(it.getString("file_id")), it.optString("mime_type", "audio/ogg")); kind = "voice" }
        msg.optJSONArray("photo")?.let { sizes ->
            val best = sizes.getJSONObject(sizes.length() - 1)
            media += MediaPart(api.downloadFile(best.getString("file_id")), "image/jpeg"); kind = "photo"
        }
        if (text.isBlank() && media.isEmpty()) return "ignored: unsupported message"
        val replies = service().handleMessage(Inbound(text, Instant.ofEpochSecond(msg.optLong("date")).coerceAtMost(Instant.now()), kind, media,
            externalId = "tg:${update.optLong("update_id")}", channel = "telegram"))
        replies.forEach { deliver(it) }
        return "handled: ${replies.size} replies"
    }

    private suspend fun handleCallback(cb: JSONObject, updateId: Long): String {
        val chatId = cb.optJSONObject("message")?.optJSONObject("chat")?.optLong("id")
        api.answerCallback(cb.getString("id"))
        if (chatId == null || chatId != settings.chatId) return "ignored: callback from other chat"
        cb.optJSONObject("message")?.optLong("message_id")?.let { api.clearKeyboard(chatId, it) }
        val label = quickReplyLabel(service().store, cb.optString("data")) ?: return "ignored: unknown quick reply"
        api.typing(chatId)
        val replies = service().handleMessage(Inbound(label, Instant.now(), externalId = "tgcb:$updateId", channel = "telegram"))
        replies.forEach { deliver(it) }
        return "handled quick reply"
    }

    /** Sends a coach message to the paired chat. Returns false if Telegram is not linked. */
    suspend fun deliver(out: Outbound): Boolean {
        val chat = settings.chatId ?: return false
        api.sendMessage(chat, out.text, out.quickReplies, out.messageId)
        return true
    }

    companion object {
        /** "qr:<message row id>:<index>" -> the quick reply text stored with that message. */
        fun quickReplyLabel(store: Store, data: String): String? {
            val p = data.split(":")
            if (p.size != 3 || p[0] != "qr") return null
            val row = store.one("SELECT buttons_json FROM messages WHERE id = ?", p[1].toLongOrNull() ?: return null) ?: return null
            val arr = JSONArray(row.str("buttons_json") ?: return null)
            return arr.optJSONObject(p[2].toIntOrNull() ?: return null)?.optString("label")
        }

        fun newPairingCode(): String = (100000..999999).random().toString()
    }
}
