package com.fitcoach.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Phone-call audio: microphone at 16 kHz (voice-communication source with echo cancellation, so the coach does not
 * hear itself) and playback at 24 kHz, the Live API's formats. Earpiece by default, speaker on request.
 */
class CallAudio(private val ctx: Context, private val onMic: (ByteArray) -> Unit) {
    @Volatile var muted = false
    @Volatile private var running = false
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var playing = false

    @SuppressLint("MissingPermission") // checked by the caller before the call starts
    fun start() {
        if (running) return
        running = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val inMin = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, CHUNK * 4))
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        val outMin = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(outMin * 4).build()
        record = rec
        track = tr
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        rec.startRecording()
        tr.play()
        Thread({
            val buf = ByteArray(CHUNK)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0 && !muted) onMic(buf.copyOf(n))
            }
        }, "call-mic").start()
        Thread({
            while (running) {
                val pcm = queue.poll(100, TimeUnit.MILLISECONDS)
                if (pcm == null) { playing = false; continue }
                playing = true
                tr.write(pcm, 0, pcm.size)
            }
        }, "call-speaker").start()
    }

    fun play(pcm24k: ByteArray) { queue.offer(pcm24k) }

    /** The user interrupted: drop what the coach had not said yet. */
    fun flush() {
        queue.clear()
        track?.let { it.pause(); it.flush(); it.play() }
    }

    /** True while coach audio is queued or playing (used to let the goodbye finish before hanging up). */
    fun busy() = queue.isNotEmpty() || playing

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            am.availableCommunicationDevices.firstOrNull { it.type == type }?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    fun stop() {
        running = false
        queue.clear()
        runCatching { record?.stop() }; record?.release(); record = null
        runCatching { track?.stop() }; track?.release(); track = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        const val IN_RATE = 16_000
        const val OUT_RATE = 24_000
        /** 40 ms of 16 kHz PCM16. */
        const val CHUNK = 1280
    }
}
