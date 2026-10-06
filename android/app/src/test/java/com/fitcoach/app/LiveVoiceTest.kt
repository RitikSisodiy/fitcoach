package com.fitcoach.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fitcoach.app.data.Db
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.str
import com.fitcoach.app.engine.CoachService
import com.fitcoach.app.engine.FoodTable
import com.fitcoach.app.engine.Inbound
import com.fitcoach.app.engine.Prompts
import com.fitcoach.app.llm.GeminiProvider
import com.fitcoach.app.voice.LiveSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The real voice path with real Gemini: data -> agent decision -> call -> live voice conversation -> transcript ->
 * memory -> the agent's next decision. The "user" is a second Gemini Live session role-playing the person; the two
 * sessions hear each other's audio (24 kHz out, resampled to 16 kHz in, streamed in real time like a microphone).
 *   GEMINI_API_KEY=... ./gradlew testDebugUnitTest --tests '*LiveVoiceTest*'
 * Writes build/live-voice-transcript.txt for review and build/live-user-voice.wav (a spoken user turn) for the
 * Telegram voice test.
 */
@RunWith(RobolectricTestRunner::class)
class LiveVoiceTest {
    private val tz = ZoneId.of("Asia/Kolkata")

    /** Streams queued 16 kHz PCM to a session every 40 ms, and silence when nothing is queued (like an open microphone). */
    private class Mic(private val target: () -> LiveSession?) {
        val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile var running = true
        private var pending = ByteArray(0)
        val thread = Thread {
            val chunk = 1280
            while (running) {
                while (pending.size < chunk) { val next = queue.poll() ?: break; pending += next }
                val out = if (pending.size >= chunk) pending.copyOf(chunk).also { pending = pending.copyOfRange(chunk, pending.size) }
                    else ByteArray(chunk).also { pending.copyInto(it); pending = ByteArray(0) }
                target()?.sendAudio(out)
                Thread.sleep(40)
            }
        }.apply { isDaemon = true; start() }
    }

    /** 24 kHz -> 16 kHz PCM16 (linear interpolation). */
    private fun resample(pcm: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = src.remaining()
        val outN = n * 2 / 3
        val out = ByteBuffer.allocate(outN * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outN) {
            val pos = i * 1.5
            val a = src.get(pos.toInt().coerceAtMost(n - 1)).toDouble()
            val b = src.get((pos.toInt() + 1).coerceAtMost(n - 1)).toDouble()
            out.putShort((a + (b - a) * (pos - pos.toInt())).toInt().toShort())
        }
        return out.array()
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    @Test fun agentCallsAndTalksWithRealGeminiLive() = runBlocking<Unit> {
        val key = System.getenv("GEMINI_API_KEY")
        assumeTrue("GEMINI_API_KEY not set", !key.isNullOrBlank())
        val store = Store(Db(ApplicationProvider.getApplicationContext<Context>(), null).writableDatabase, tz)
        val foods = File("src/main/assets/foods_seed.csv").inputStream().use(FoodTable::load)
        val service = CoachService(store, GeminiProvider(key!!, usageFile = File(System.getProperty("java.io.tmpdir"), "fitcoach_live_usage.json")),
            foods, Prompts.fromDir(File("src/main/assets/prompts")))
        service.callsSupported = { true }
        // The test runs at any hour: move quiet hours away from now so the agent is allowed to think.
        val off = java.time.LocalTime.now(tz).plusHours(12)
        store.setProfile(Instant.now(), "quiet_start", "%02d:00".format(off.hour)); store.setProfile(Instant.now(), "quiet_end", "%02d:01".format(off.hour))
        val log = StringBuilder()
        fun agentLog() = store.recentDecisions(10).firstOrNull { it.str("kind")!!.startsWith("agent_") }?.let { "${it.str("kind")}: ${it.str("summary")}" }
        val now = { Instant.now() }

        // Phone/app data: what the person told the coach earlier today.
        service.handleMessage(Inbound("Goal hai 6 kg kam karna. Roz shaam 20 min walk ka plan hai but office ke baad thak jata hu", now(), channel = "telegram"))
        // The agent decides on its own. If it does not choose to call now, the test starts a user-initiated call instead.
        val decided = service.tick(now(), force = true)
        log.append("Agent decision: ${agentLog()}\n")
        val callId = decided.firstOrNull { it.channel == "call" }?.callId?.also { log.append("The agent chose to CALL.\n") }
            ?: service.startUserCall(now()).also { log.append("The agent did not call now; testing a user-started call.\n") }
        service.setCallStatus(now(), callId, "answered")

        val ended = CountDownLatch(1)
        var coachAudio = 0L
        val userVoice = ByteArrayOutputStream()
        var coach: LiveSession? = null
        var user: LiveSession? = null
        val toUser = Mic { user }
        val toCoach = Mic { coach }
        user = LiveSession(key, """You are role-playing a person who just picked up a call from their AI fitness coach. Speak natural Hinglish,
            |short and casual, like a real tired person after office. Facts about you: you have a guitar class tonight so no walk today;
            |you are fine doing a 10 minute walk tomorrow at 7 am. Answer what the coach asks; do not lead the call. If the coach says
            |goodbye, say bye.""".trimMargin(), object : LiveSession.Listener {
            override fun onAudio(pcm24k: ByteArray) {
                toCoach.queue.offer(resample(pcm24k))
                if (userVoice.size() < 24_000 * 2 * 8) userVoice.write(pcm24k)
            }
        }, opening = null)
        coach = LiveSession(key, service.callInstruction(now(), callId), object : LiveSession.Listener {
            override fun onConnected(model: String) { log.append("Coach model: $model\n") }
            override fun onAudio(pcm24k: ByteArray) { coachAudio += pcm24k.size; toUser.queue.offer(resample(pcm24k)) }
            override fun onEndRequested(summary: String) { log.append("end_call: $summary\n"); Thread { Thread.sleep(3000); ended.countDown() }.start() }
            override fun onClosed(error: String?) { log.append("coach closed: $error\n"); ended.countDown() }
        })
        user!!.connect()
        Thread.sleep(1500)
        coach!!.connect()
        val natural = ended.await(150, TimeUnit.SECONDS)
        toUser.running = false; toCoach.running = false
        val c = coach!!
        c.close(); user!!.close()
        val turns = c.transcript
        log.append("Ended naturally: $natural; fallback: ${c.lastFallbackReason}\n\n")
        turns.forEach { log.append("${it.role.uppercase()}: ${it.text}\n") }

        service.ingestCall(now(), callId, turns.map { it.role to it.text }, c.summary, c.model)
        val row = service.call(callId)!!
        log.append("\nStored call: ${row.str("status")} · summary: ${row.str("summary")}\n")
        log.append("Memory after the call: ${service.memory(now()).toString(1)}\n")
        log.append("Ingest decision: ${store.recentDecisions(3).joinToString("\n") { it.str("kind") + ": " + it.str("summary") }}\n")
        // The next agent decision sees the call.
        service.tick(now())
        log.append("Agent after the call: ${agentLog()}\n")
        File("build/live-voice-transcript.txt").writeText(log.toString())
        if (userVoice.size() > 0) File("build/live-user-voice.wav").writeBytes(wav(userVoice.toByteArray(), 24_000))
        println(log)

        assertTrue("coach produced no audio", coachAudio > 0)
        assertTrue("no coach speech transcribed", turns.any { it.role == "coach" })
        assertTrue("the user's speech was not heard", turns.any { it.role == "user" })
        assertEquals("ended", row.str("status"))
        assertTrue(store.query("SELECT * FROM messages WHERE channel = 'call'").isNotEmpty())
    }
}
