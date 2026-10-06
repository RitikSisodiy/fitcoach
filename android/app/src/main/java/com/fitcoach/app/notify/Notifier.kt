package com.fitcoach.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.R
import com.fitcoach.app.engine.Inbound
import com.fitcoach.app.engine.Outbound
import com.fitcoach.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/** Coach messages as notifications with one-tap action buttons (max 3, the rest are in the chat). */
object Notifier {
    const val CHANNEL_COACH = "coach"
    const val CHANNEL_STATUS = "status"
    private const val NOTIFICATION_ID = 1001

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_COACH, "Coach messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Check-ins, reminders and recaps from your coach"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Confirmations after tapping a button"
        })
    }

    fun canPost(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, msg: Outbound, quiet: Boolean = false, id: Int = NOTIFICATION_ID) {
        if (!canPost(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, if (quiet) CHANNEL_STATUS else CHANNEL_COACH)
            .setSmallIcon(R.drawable.ic_coach)
            .setContentTitle("FitCoach")
            .setContentText(msg.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        // Quick replies written by the agent: tapping one sends it as the user's message, without opening the app.
        msg.quickReplies.take(3).forEachIndexed { i, label ->
            val intent = Intent(ctx, ActionReceiver::class.java)
                .setAction("com.fitcoach.app.REPLY.${msg.messageId}.$i")
                .putExtra(ActionReceiver.EXTRA_TEXT, label)
                .putExtra(ActionReceiver.EXTRA_KEY, "nqr:${msg.messageId}:$i")
            val pi = PendingIntent.getBroadcast(ctx, (msg.messageId ?: 0L).toInt() * 4 + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, label, pi)
        }
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call; the message is still in the chat.
        }
    }

    fun cancel(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
}

/** A tapped quick reply is the user's message: it goes through the same pipeline as anything typed. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val key = intent.getStringExtra(EXTRA_KEY)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = FitCoachApp.instance
                Notifier.cancel(context)
                app.service().handleMessage(Inbound(text, Instant.now(), externalId = key, channel = "notification"))
                    .forEach { Notifier.show(context, it, quiet = true) }
                app.notifyDataChanged()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_KEY = "key"
    }
}
