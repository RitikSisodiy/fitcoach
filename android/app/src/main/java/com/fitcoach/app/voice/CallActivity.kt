package com.fitcoach.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.data.str
import com.fitcoach.app.ui.FitCoachTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** Incoming-call and in-call screen. Shows over the lock screen like a phone call. */
class CallActivity : ComponentActivity() {
    private var callId by mutableStateOf(-1L)
    private var purpose by mutableStateOf<String?>(null)
    private var ringing by mutableStateOf(false)

    // Plain permission request: registerForActivityResult trips lint (an old transitive Fragment) in release builds.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        if (CallManager.hasMic(this)) connect()
        else CallManager.state.value = CallUi(callId = callId, phase = "failed", note = "Microphone permission is needed for calls")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (savedInstanceState == null) handle(intent)
        setContent { FitCoachTheme { CallScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val app = FitCoachApp.instance
        val id = intent.getLongExtra(EXTRA_CALL, -1)
        val live = CallManager.state.value.phase in setOf("connecting", "live", "ending")
        when {
            live -> return // already in a call: just show it
            intent.getBooleanExtra(EXTRA_USER_STARTED, false) -> { callId = app.service().startUserCall(Instant.now()); answer() }
            id < 0 -> finish()
            else -> {
                callId = id
                val row = app.service().call(id)
                purpose = row?.str("purpose")?.lineSequence()?.firstOrNull()
                ringing = row?.str("status") == "ringing"
                if (intent.getBooleanExtra(EXTRA_ANSWER, false) && ringing) answer()
                else if (!ringing) CallManager.state.value = CallUi(callId = id, phase = "ended", note = "This call is no longer ringing (${row?.str("status")}).")
            }
        }
    }

    private fun answer() {
        ringing = false
        CallManager.stopRinging(this)
        FitCoachApp.instance.service().setCallStatus(Instant.now(), callId, "answered")
        if (CallManager.hasMic(this)) connect() else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
    }

    private fun connect() {
        CallManager.state.value = CallUi(callId = callId, phase = "connecting")
        VoiceCallService.start(this, callId)
    }

    private fun decline() {
        CallManager.stopRinging(this)
        FitCoachApp.instance.service().setCallStatus(Instant.now(), callId, "declined")
        FitCoachApp.instance.notifyDataChanged()
        finish()
    }

    @Composable
    private fun CallScreen() {
        val ui by CallManager.state.collectAsState()
        val list = rememberLazyListState()
        LaunchedEffect(ui.turns.size, ui.turns.lastOrNull()?.text?.length) { if (ui.turns.isNotEmpty()) list.scrollToItem(ui.turns.size - 1) }
        var summary by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(ui.phase) {
            if (ui.phase == "ended") summary = withContext(Dispatchers.IO) { FitCoachApp.instance.service().call(callId)?.str("summary") }
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("FitCoach", style = MaterialTheme.typography.headlineMedium)
            Text(when {
                ringing -> "Coach wants to talk"
                ui.phase == "connecting" -> "Connecting…"
                ui.phase == "live" -> "On call" + (ui.model?.let { " · $it" } ?: "")
                ui.phase == "ending" -> "Saying goodbye…"
                ui.phase == "failed" -> "Call failed"
                else -> "Call ended"
            }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            if (ringing) {
                purpose?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = ::decline, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))) { Text("Decline") }
                    Button(onClick = ::answer, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("Answer") }
                }
                return@Column
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                items(ui.turns) { t ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(if (t.role == "user") "You" else "Coach", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Text(t.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            ui.note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            summary?.let { Text("Saved: $it", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(12.dp))
            if (ui.phase in setOf("connecting", "live", "ending")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OutlinedButton(onClick = { VoiceCallService.send(this@CallActivity, VoiceCallService.ACTION_MUTE) }) { Text(if (ui.muted) "Unmute" else "Mute") }
                    OutlinedButton(onClick = { VoiceCallService.send(this@CallActivity, VoiceCallService.ACTION_SPEAKER) }) { Text(if (ui.speaker) "Earpiece" else "Speaker") }
                    Button(onClick = { VoiceCallService.send(this@CallActivity, VoiceCallService.ACTION_HANG_UP) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))) { Text("End") }
                }
            } else {
                Button(onClick = { CallManager.state.value = CallUi(); finish() }) { Text("Close") }
            }
        }
    }

    companion object {
        const val EXTRA_CALL = "call_id"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_USER_STARTED = "user_started"
        private const val MIC_REQUEST = 7

        fun intent(ctx: Context, callId: Long, answer: Boolean): Intent = Intent(ctx, CallActivity::class.java)
            .putExtra(EXTRA_CALL, callId).putExtra(EXTRA_ANSWER, answer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** "Talk to coach" from the app. */
        fun userCall(ctx: Context): Intent = Intent(ctx, CallActivity::class.java).putExtra(EXTRA_USER_STARTED, true)
    }
}
