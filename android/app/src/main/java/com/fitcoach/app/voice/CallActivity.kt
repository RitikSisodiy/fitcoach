package com.fitcoach.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.fitcoach.app.ui.Fc
import com.fitcoach.app.ui.FcButton
import com.fitcoach.app.ui.FcIcons
import com.fitcoach.app.ui.RoundIconButton
import kotlinx.coroutines.delay
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
        var liveSince by remember { mutableStateOf(0L) }
        LaunchedEffect(ui.phase) {
            if (ui.phase == "live" && liveSince == 0L) liveSince = System.currentTimeMillis()
            if (ui.phase == "ended") summary = withContext(Dispatchers.IO) { FitCoachApp.instance.service().call(callId)?.str("summary") }
        }
        val elapsed by produceState(0L, liveSince, ui.phase) {
            while (liveSince > 0 && ui.phase in setOf("live", "ending")) { value = (System.currentTimeMillis() - liveSince) / 1000; delay(1000) }
        }
        val active = ui.phase in setOf("connecting", "live", "ending")
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF16220F), Fc.Bg, Fc.Bg))).safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (ringing) 48.dp else 8.dp))
            Avatar(pulsing = ringing || ui.phase == "live", diameter = if (ringing) 128.dp else 84.dp)
            Spacer(Modifier.height(16.dp))
            Text("FitCoach", style = MaterialTheme.typography.headlineMedium, color = Fc.Text)
            Text(when {
                ringing -> "Coach wants to talk"
                ui.phase == "connecting" -> "Connecting…"
                ui.phase == "live" -> "%d:%02d".format(elapsed / 60, elapsed % 60)
                ui.phase == "ending" -> "Saying goodbye…"
                ui.phase == "failed" -> "Call failed"
                else -> "Call ended"
            }, style = MaterialTheme.typography.titleMedium, color = if (ui.phase == "failed") Fc.Bad else Fc.Accent)
            Spacer(Modifier.height(16.dp))
            if (ringing) {
                purpose?.let {
                    Text(it, Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Fc.Surface.copy(alpha = 0.8f)).padding(18.dp),
                        style = MaterialTheme.typography.bodyLarge, color = Fc.Text, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallButton(FcIcons.PhoneDown, "Decline", Fc.Bad, ::decline)
                    CallButton(FcIcons.Phone, "Answer", Fc.Good, ::answer)
                }
                return@Column
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.turns) { t ->
                    val mine = t.role == "user"
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Text(if (mine) "You" else "Coach", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall,
                            color = if (mine) Fc.Accent else Fc.Violet)
                        Text(t.text, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (mine) Fc.Accent.copy(alpha = 0.14f) else Fc.SurfaceHigh).padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge, color = Fc.Text)
                    }
                }
            }
            ui.note?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Fc.Bad, textAlign = TextAlign.Center) }
            summary?.let {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(20.dp)).background(Fc.Surface).padding(16.dp)) {
                    Text("SAVED TO MEMORY", style = MaterialTheme.typography.labelSmall, color = Fc.TextMuted)
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Fc.Text)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (active) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallButton(if (ui.muted) FcIcons.MicOff else FcIcons.Mic, if (ui.muted) "Unmute" else "Mute",
                        if (ui.muted) Fc.Text else Fc.SurfaceHigher, { VoiceCallService.send(this@CallActivity, VoiceCallService.ACTION_MUTE) },
                        tint = if (ui.muted) Fc.Bg else Fc.Text, size = 64.dp)
                    CallButton(FcIcons.PhoneDown, "End", Fc.Bad, { VoiceCallService.send(this@CallActivity, VoiceCallService.ACTION_HANG_UP) }, size = 64.dp)
                    CallButton(FcIcons.Speaker, if (ui.speaker) "Earpiece" else "Speaker",
                        if (ui.speaker) Fc.Text else Fc.SurfaceHigher, { VoiceCallService.send(this@CallActivity, VoiceCallService.ACTION_SPEAKER) },
                        tint = if (ui.speaker) Fc.Bg else Fc.Text, size = 64.dp)
                }
            } else {
                FcButton("Close", onClick = { CallManager.state.value = CallUi(); finish() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun Avatar(pulsing: Boolean, diameter: Dp) {
        val t = rememberInfiniteTransition(label = "pulse")
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "p")
        Box(Modifier.size(diameter * 1.6f), contentAlignment = Alignment.Center) {
            if (pulsing) Canvas(Modifier.size(diameter * 1.6f)) {
                val r0 = diameter.toPx() / 2
                for (k in 0..1) {
                    val f = (p + k * 0.5f) % 1f
                    drawCircle(Fc.Accent.copy(alpha = 0.35f * (1 - f)), radius = r0 + r0 * 0.6f * f)
                }
            }
            Box(Modifier.size(diameter).clip(CircleShape).background(Fc.AccentGlow), contentAlignment = Alignment.Center) {
                Icon(FcIcons.Spark, null, Modifier.size(diameter * 0.42f), tint = Fc.OnAccent)
            }
        }
    }

    @Composable
    private fun CallButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, tint: Color = Fc.Text, size: Dp = 72.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIconButton(icon, label, onClick, container = color, tint = tint, size = size)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Fc.TextMuted)
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
