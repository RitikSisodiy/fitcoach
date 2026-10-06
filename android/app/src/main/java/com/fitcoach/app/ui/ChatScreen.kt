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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.voice.CallActivity
import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import com.fitcoach.app.engine.Inbound
import com.fitcoach.app.llm.MediaPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

private data class ChatItem(val id: Long, val incoming: Boolean, val text: String, val time: String, val channel: String, val quickReplies: List<String>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(app: FitCoachApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by app.dataVersion.collectAsState()
    var items by remember { mutableStateOf(emptyList<ChatItem>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<Recorder?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(version) {
        items = withContext(Dispatchers.IO) {
            val rows = app.store.allMessages(300)
            val lastId = rows.lastOrNull()?.long("id")
            rows.map { r ->
                // Quick replies only make sense on the latest message; older ones are history.
                val bj = r.str("buttons_json")?.takeIf { r.long("id") == lastId }?.let { JSONArray(it) }
                ChatItem(
                    r.long("id")!!, r.str("direction") == "in", r.str("text") ?: "",
                    app.store.fmtLocal(TimeUtil.parse(r.str("created_at")!!), "dd MMM HH:mm"), r.str("channel") ?: "app",
                    if (bj == null) emptyList() else (0 until bj.length()).map { bj.getJSONObject(it).getString("label") },
                )
            }
        }
        if (items.isNotEmpty()) listState.scrollToItem(items.size - 1)
    }

    fun submit(text: String, kind: String = "text", media: List<MediaPart> = emptyList()) {
        if (busy || (text.isBlank() && media.isEmpty())) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { app.service().handleMessage(Inbound(text.trim(), Instant.now(), kind, media)) }
                    .onFailure { app.store.addMessage(Instant.now(), "out", "system", "Something went wrong: ${it.message}") }
            }
            busy = false
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

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (items.isEmpty()) item {
                Text(
                    "Talk to your coach like you would to a friend - what you ate, where you're going, what you want to change. " +
                        "Photos and voice notes work too. It remembers what you tell it, watches your steps, sleep, places and " +
                        "food orders on its own, and messages you here, in notifications or on Telegram when it matters.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(items, key = { it.id }) { m -> Bubble(m) { reply -> submit(reply) } }
            if (busy) item { Text("Coach is thinking…", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { ctx.startActivity(CallActivity.userCall(ctx)) }) { Text("📞") }
            TextButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("📷") }
            TextButton(onClick = {
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
            }) { Text(if (recorder != null) "⏹" else "🎤") }
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(if (recorder != null) "Recording… tap ⏹ to send" else "Message") }, maxLines = 4,
            )
            TextButton(onClick = { submit(input); input = "" }, enabled = !busy && input.isNotBlank()) { Text("Send") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubble(m: ChatItem, onReply: (String) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.incoming) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .background(
                    if (m.incoming) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(14.dp),
                )
                .padding(10.dp),
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyMedium)
            Text(m.time + when (m.channel) { "telegram" -> " · Telegram"; "notification" -> " · notification"; "call" -> " · call"; else -> "" },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.quickReplies.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.quickReplies.forEach { label -> AssistChip(onClick = { onReply(label) }, label = { Text(label) }) }
            }
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
