package com.fitcoach.app.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.R
import com.fitcoach.app.engine.Outbound
import com.fitcoach.app.notify.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** What the call screen shows; written by [VoiceCallService]. */
data class CallUi(
    val callId: Long? = null,
    val phase: String = "idle", // idle, connecting, live, ending, ended, failed
    val turns: List<LiveSession.Turn> = emptyList(),
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val model: String? = null,
    val note: String? = null,
)

/**
 * Incoming coach calls: an Android call-style notification that rings and, where the user allowed it, opens the full-screen
 * call screen. Answer opens [CallActivity]; decline and "no answer" become observations the agent reasons about.
 */
object CallManager {
    const val CHANNEL_CALLS = "coach_calls"
    const val RING_NOTIFICATION_ID = 2001
    const val ONGOING_NOTIFICATION_ID = 2002
    private const val RING_MS = 45_000L

    val state = MutableStateFlow(CallUi())

    fun createChannel(ctx: Context) {
        val ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Coach calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When your coach decides a short voice call would help"
                setSound(ring, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
            })
    }

    fun hasMic(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Whether the agent may choose to call: the phone can ring and the user can be heard. */
    fun canCall(ctx: Context) = Notifier.canPost(ctx) && hasMic(ctx) && FitCoachApp.instance.apiKey != null

    /** Android 14+ lets the user switch off full-screen call screens; without it the call is a heads-up notification. */
    fun canFullScreen(ctx: Context) = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun ring(ctx: Context, out: Outbound) {
        val id = out.callId ?: return Notifier.show(ctx, out)
        val screen = PendingIntent.getActivity(ctx, id.toInt(), CallActivity.intent(ctx, id, answer = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val answer = PendingIntent.getActivity(ctx, id.toInt() + 1, CallActivity.intent(ctx, id, answer = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val decline = PendingIntent.getBroadcast(ctx, id.toInt(), Intent(ctx, CallActionReceiver::class.java)
            .setAction(CallActionReceiver.DECLINE).putExtra(CallActivity.EXTRA_CALL, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val coach = Person.Builder().setName("FitCoach").setImportant(true).build()
        val n = NotificationCompat.Builder(ctx, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_coach)
            .setContentTitle("Coach wants to talk")
            .setContentText(out.text)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(coach, decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setOngoing(true)
            .setTimeoutAfter(RING_MS)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // keep ringing until answered, declined or timed out
        try {
            NotificationManagerCompat.from(ctx).notify(RING_NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Notifier.show(ctx, out)
        }
    }

    fun stopRinging(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(RING_NOTIFICATION_ID)
}

/** Decline from the ringing notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(CallActivity.EXTRA_CALL, -1)
        CallManager.stopRinging(context)
        if (id < 0) return
        val app = FitCoachApp.instance
        app.service().setCallStatus(Instant.now(), id, "declined")
        app.notifyDataChanged()
    }

    companion object {
        const val DECLINE = "com.fitcoach.app.CALL_DECLINE"
    }
}
