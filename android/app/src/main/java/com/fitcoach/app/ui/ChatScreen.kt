package com.fitcoach.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import com.fitcoach.app.engine.Inbound
import com.fitcoach.app.llm.MediaPart
import com.fitcoach.app.voice.CallActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate

private data class ChatItem(
    val id: Long, val incoming: Boolean, val text: String, val day: String, val time: String, val channel: String,
    val quickReplies: List<String>, val system: Boolean,
)

/** Example first messages shown on an empty chat (UI hints only; tapping one fills the input box). */
private val STARTERS = listOf("I had 2 rotis and dal for lunch", "My goal is to lose 5 kg", "I walk after office on weekdays", "How was my week?")

@Composable
fun ChatScreen(app: FitCoachApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by app.dataVersion.collectAsState()
    var items by remember { mutableStateOf(emptyList<ChatItem>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) } // shown at once, before the coach has answered
    var recorder by remember { mutableStateOf<Recorder?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(pending) { if (pending != null) listState.animateScrollToItem(items.size + 1) }
    LaunchedEffect(version) {
        items = withContext(Dispatchers.IO) {
            val rows = app.store.allMessages(300)
            val lastId = rows.lastOrNull()?.long("id")
            val today = LocalDate.now(app.store.tz).toString()
            val yesterday = LocalDate.now(app.store.tz).minusDays(1).toString()
            rows.map { r ->
                // Quick replies only make sense on the latest message; older ones are history.
                val bj = r.str("buttons_json")?.takeIf { r.long("id") == lastId }?.let { JSONArray(it) }
                val at = TimeUtil.parse(r.str("created_at")!!)
                val d = app.store.date(at)
                ChatItem(
                    r.long("id")!!, r.str("direction") == "in", r.str("text") ?: "",
                    when (d) { today -> "Today"; yesterday -> "Yesterday"; else -> app.store.fmtLocal(at, "EEE, dd MMM") },
                    app.store.fmtLocal(at, "HH:mm"), r.str("channel") ?: "app",
                    if (bj == null) emptyList() else (0 until bj.length()).map { bj.getJSONObject(it).getString("label") },
                    r.str("kind") == "system",
                )
            }
        }
        if (items.isNotEmpty()) listState.scrollToItem(items.size - 1)
    }

    fun submit(text: String, kind: String = "text", media: List<MediaPart> = emptyList()) {
        if (busy || (text.isBlank() && media.isEmpty())) return
        busy = true
        pending = listOf(text.trim(), when (kind) { "photo" -> "📷 Photo"; "voice" -> "🎤 Voice note"; else -> "" })
            .filter { it.isNotBlank() }.joinToString("\n")
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { app.service().handleMessage(Inbound(text.trim(), Instant.now(), kind, media)) }
                    .onFailure { app.store.addMessage(Instant.now(), "out", "system", "Something went wrong: ${it.message}") }
            }
            busy = false
            pending = null
            app.notifyDataChanged()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { runCatching { loadJpeg(ctx, uri) }.getOrNull() }
            if (jpeg != null) submit(input, "photo", listOf(MediaPart(jpeg, "image/jpeg"))).also { input = "" }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun toggleRecording() {
        val r = recorder
        if (r != null) {
            recorder = null
            val audio = r.stop()
            if (audio != null) submit(input, "voice", listOf(MediaPart(audio, "audio/aac"))).also { input = "" }
        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            recorder = Recorder(ctx).also { it.start() }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Coach", subtitle = "Always on · remembers you") {
            RoundIconButton(FcIcons.Phone, "Talk to your coach", { ctx.startActivity(CallActivity.userCall(ctx)) },
                container = Fc.Accent, tint = Fc.OnAccent)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (items.isEmpty()) item { EmptyChat { input = it } }
            itemsIndexed(items, key = { _, m -> m.id }) { i, m ->
                if (i == 0 || items[i - 1].day != m.day) DayDivider(m.day)
                Bubble(m) { reply -> submit(reply) }
            }
            pending?.let { p ->
                item(key = "pending") {
                    Bubble(ChatItem(-1, true, p, "", "sending…", "app", emptyList(), false)) { }
                }
            }
            if (busy) item { Typing() }
        }
        Composer(
            input = input, onInput = { input = it }, recording = recorder != null, busy = busy,
            onPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onMic = ::toggleRecording, onSend = { submit(input); input = "" },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(onPick: (String) -> Unit) {
    FcCard(Modifier.padding(top = 8.dp), background = Fc.HeroGradient) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Fc.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(FcIcons.Spark, null, Modifier.size(22.dp), tint = Fc.Accent)
        }
        Text("Talk to me like a friend", style = MaterialTheme.typography.titleLarge, color = Fc.Text)
        Text(
            "Tell me what you ate, where you're going or what you want to change. Photos and voice notes work too. " +
                "I remember what you say, watch your steps, sleep and places, and reach out here, on Telegram or with a call when it helps.",
            style = MaterialTheme.typography.bodyMedium, color = Fc.TextMuted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            STARTERS.forEach { Pill(it, Fc.Accent, onClick = { onPick(it) }) }
        }
    }
}

@Composable
private fun DayDivider(day: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(day, Modifier.clip(CircleShape).background(Fc.Surface).padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = Fc.TextMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubble(m: ChatItem, onReply: (String) -> Unit) {
    val mine = m.incoming
    val shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier.widthIn(max = 310.dp).clip(shape)
                .background(when { mine -> Fc.Accent; m.system -> Fc.Bad.copy(alpha = 0.12f); else -> Fc.SurfaceHigh })
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (m.channel == "call") Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Icon(FcIcons.Phone, null, Modifier.size(12.dp), tint = if (mine) Fc.OnAccent.copy(alpha = 0.7f) else Fc.Violet)
                Spacer(Modifier.width(4.dp))
                Text("Voice call", style = MaterialTheme.typography.labelSmall, color = if (mine) Fc.OnAccent.copy(alpha = 0.7f) else Fc.Violet)
            }
            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = if (mine) Fc.OnAccent else Fc.Text)
        }
        val via = when (m.channel) { "telegram" -> " · Telegram"; "notification" -> " · notification"; else -> "" }
        Text(m.time + via, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Fc.TextFaint)
        if (m.quickReplies.isNotEmpty()) FlowRow(
            Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            m.quickReplies.forEach { label ->
                Text(label, Modifier.clip(CircleShape).border(1.dp, Fc.Accent.copy(alpha = 0.6f), CircleShape).clickable { onReply(label) }
                    .padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = Fc.Accent)
            }
        }
    }
}

@Composable
private fun Typing() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)).background(Fc.SurfaceHigh).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(Fc.TextMuted))
        }
    }
}

@Composable
private fun Composer(input: String, onInput: (String) -> Unit, recording: Boolean, busy: Boolean,
                     onPhoto: () -> Unit, onMic: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp)).background(Fc.SurfaceHigh)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(FcIcons.Image, "Send a photo", onPhoto, container = Fc.SurfaceHigher, size = 40.dp, enabled = !recording)
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (recording) Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Fc.Bad)
                Spacer(Modifier.width(8.dp))
                Text("Recording… tap stop to send", style = MaterialTheme.typography.bodyMedium, color = Fc.Text)
            } else {
                if (input.isEmpty()) Text("Message your coach", style = MaterialTheme.typography.bodyLarge, color = Fc.TextFaint)
                BasicTextField(input, onInput, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = Fc.Text),
                    cursorBrush = SolidColor(Fc.Accent), maxLines = 4)
            }
        }
        if (input.isBlank() || recording) {
            RoundIconButton(if (recording) FcIcons.Stop else FcIcons.Mic, if (recording) "Stop and send" else "Record a voice note", onMic,
                container = if (recording) Fc.Bad else Fc.SurfaceHigher, tint = if (recording) Fc.Text else Fc.Text, size = 40.dp)
        } else {
            RoundIconButton(FcIcons.Send, "Send", onSend, container = Fc.Accent, tint = Fc.OnAccent, size = 40.dp, enabled = !busy)
        }
    }
}

/** Voice notes: raw AAC (ADTS), which Gemini accepts directly as audio/aac. */
private class Recorder(private val ctx: Context) {
    private val file = File(ctx.cacheDir, "voice.aac")
    private var rec: MediaRecorder? = null

    fun start() {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(32000)
        r.setOutputFile(file.absolutePath)
        r.prepare(); r.start()
        rec = r
    }

    fun stop(): ByteArray? = try {
        rec?.stop(); rec?.release(); rec = null
        file.readBytes().takeIf { it.size > 1000 }
    } catch (e: RuntimeException) {
        rec?.release(); rec = null; null
    }
}

/** Downscales a picked photo to at most 1024 px and re-encodes it as JPEG (keeps requests small and fast). */
private fun loadJpeg(ctx: Context, uri: Uri): ByteArray {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val w = info.size.width; val h = info.size.height
        val scale = minOf(1f, 1024f / maxOf(w, h))
        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray() }
}
