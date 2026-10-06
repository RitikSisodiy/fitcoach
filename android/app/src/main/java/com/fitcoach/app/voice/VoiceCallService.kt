package com.fitcoach.app.voice

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * A live coach call in progress (microphone foreground service). Connects the phone audio to a [LiveSession] and,
 * when the call ends, hands the transcript to the coach service so it becomes memory and the agent can follow up.
 */
class VoiceCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: LiveSession? = null
    private var audio: CallAudio? = null
    private var callId: Long = -1
    @Volatile private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANG_UP -> finish(null)
            ACTION_MUTE -> audio?.let { it.muted = !it.muted; update { s -> s.copy(muted = it.muted) } }
            ACTION_SPEAKER -> audio?.let { a -> val on = !CallManager.state.value.speaker; a.setSpeaker(on); update { it.copy(speaker = on) } }
            else -> if (session == null) start(intent?.getLongExtra(CallActivity.EXTRA_CALL, -1) ?: -1)
        }
        return START_NOT_STICKY
    }

    private fun start(id: Long) {
        callId = id
        CallManager.state.value = CallUi(callId = id, phase = "connecting")
        try {
            ServiceCompat.startForeground(this, CallManager.ONGOING_NOTIFICATION_ID, ongoingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            fail("Android did not allow the call to use the microphone: ${e.message}")
            return
        }
        val app = FitCoachApp.instance
        val key = app.apiKey
        if (id < 0 || key == null || !CallManager.hasMic(this)) {
            fail(if (key == null) "No Gemini API key set (Setup tab)" else "Microphone permission is needed for calls")
            return
        }
        scope.launch {
            val instruction = app.service().callInstruction(Instant.now(), id)
            val s = LiveSession(key, instruction, object : LiveSession.Listener {
                override fun onConnected(model: String) {
                    update { it.copy(phase = "live", model = model) }
                    runCatching { audio?.start() }.onFailure { fail("Could not use the microphone: ${it.message}") }
                }
                override fun onAudio(pcm24k: ByteArray) { audio?.play(pcm24k) }
                override fun onInterrupted() { audio?.flush() }
                override fun onTranscript(turns: List<LiveSession.Turn>) = update { it.copy(turns = turns) }
                override fun onEndRequested(summary: String) {
                    update { it.copy(phase = "ending") }
                    scope.launch {
                        // Let the goodbye play out before hanging up.
                        delay(800)
                        var waited = 0
                        while (audio?.busy() == true && waited < 10_000) { delay(200); waited += 200 }
                        finish(summary)
                    }
                }
                override fun onClosed(error: String?) {
                    if (error != null && !finishing && session?.transcript.isNullOrEmpty()) fail("Could not connect the call: $error") else finish(null)
                }
            })
            session = s
            audio = CallAudio(this@VoiceCallService) { s.sendAudio(it) }
            s.connect()
            delay(MAX_CALL_MS)
            finish(null)
        }
    }

    private fun update(f: (CallUi) -> CallUi) { CallManager.state.value = f(CallManager.state.value) }

    private fun fail(note: String) {
        update { it.copy(phase = "failed", note = note) }
        finish(null)
    }

    /** Ends the call once: stops audio, closes the session and remembers what was said. */
    private fun finish(summary: String?) {
        if (finishing) return
        finishing = true
        val s = session
        audio?.stop()
        s?.close()
        val turns = s?.transcript.orEmpty()
        scope.launch {
            withContext(NonCancellable) {
                if (callId >= 0) runCatching {
                    FitCoachApp.instance.service().ingestCall(Instant.now(), callId, turns.map { it.role to it.text }, summary ?: s?.summary,
                        listOfNotNull(s?.model, s?.lastFallbackReason?.let { "fallback: $it" }).joinToString("; "))
                }.onFailure { Log.w("FitCoach", "call ingest failed", it) }
                FitCoachApp.instance.notifyDataChanged()
            }
            if (CallManager.state.value.phase != "failed") update { it.copy(phase = "ended") }
            ServiceCompat.stopForeground(this@VoiceCallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (!finishing) finish(null)
        scope.launch { delay(5_000); scope.cancel() }
        super.onDestroy()
    }

    private fun ongoingNotification() = NotificationCompat.Builder(this, CallManager.CHANNEL_CALLS)
        .setSmallIcon(R.drawable.ic_coach)
        .setContentTitle("Talking with your coach")
        .setStyle(NotificationCompat.CallStyle.forOngoingCall(Person.Builder().setName("FitCoach").setImportant(true).build(),
            PendingIntent.getService(this, 0, Intent(this, VoiceCallService::class.java).setAction(ACTION_HANG_UP), PendingIntent.FLAG_IMMUTABLE)))
        .setContentIntent(PendingIntent.getActivity(this, 0, CallActivity.intent(this, callId, answer = false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setSilent(true)
        .build()

    companion object {
        const val ACTION_HANG_UP = "com.fitcoach.app.CALL_HANG_UP"
        const val ACTION_MUTE = "com.fitcoach.app.CALL_MUTE"
        const val ACTION_SPEAKER = "com.fitcoach.app.CALL_SPEAKER"
        private const val MAX_CALL_MS = 15 * 60_000L

        fun start(ctx: Context, callId: Long) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceCallService::class.java).putExtra(CallActivity.EXTRA_CALL, callId))

        fun send(ctx: Context, action: String) = ctx.startService(Intent(ctx, VoiceCallService::class.java).setAction(action))
    }
}
