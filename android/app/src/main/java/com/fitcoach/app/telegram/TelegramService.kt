package com.fitcoach.app.telegram

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.R
import com.fitcoach.app.engine.Outbound
import com.fitcoach.app.notify.Notifier
import com.fitcoach.app.voice.CallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Telegram link settings in app-private preferences. */
class PrefsTelegramSettings(ctx: Context) : TelegramSettings {
    private val p = ctx.getSharedPreferences("telegram", Context.MODE_PRIVATE)
    override var token: String?
        get() = p.getString("token", null)?.takeIf { it.isNotBlank() }
        set(v) { p.edit().putString("token", v?.trim()).apply() }
    override var chatId: Long?
        get() = p.getLong("chat_id", 0L).takeIf { it != 0L }
        set(v) { p.edit().putLong("chat_id", v ?: 0L).apply() }
    override var botUsername: String?
        get() = p.getString("bot", null)
        set(v) { p.edit().putString("bot", v).apply() }
    override var pairingCode: String?
        get() = p.getString("code", null)
        set(v) { p.edit().putString("code", v).apply() }
    override var offset: Long
        get() = p.getLong("offset", 0L)
        set(v) { p.edit().putLong("offset", v).apply() }
}

/** Entry points used by the rest of the app. */
object Telegram {
    fun settings(ctx: Context) = PrefsTelegramSettings(ctx)
    fun isLinked(ctx: Context) = settings(ctx).let { it.token != null && it.chatId != null }

    /** Polling health for the dashboard (this process only). */
    @Volatile var lastPollAt: Long = 0L
    @Volatile var lastPollError: String? = null

    fun bridge(ctx: Context): TelegramBridge? {
        val s = settings(ctx)
        val token = s.token ?: return null
        return TelegramBridge(TelegramApi(token), s) { FitCoachApp.instance.service() }
    }

    /** Starts the long-polling service if a token is set. Background starts can be refused by Android; that is fine. */
    fun ensureRunning(ctx: Context) {
        if (settings(ctx).token == null) return
        try {
            ctx.startForegroundService(Intent(ctx, TelegramService::class.java))
        } catch (e: Exception) {
            Log.w("FitCoach", "telegram service not started now: ${e.javaClass.simpleName}")
        }
    }

    fun stop(ctx: Context) = ctx.stopService(Intent(ctx, TelegramService::class.java))
}

/**
 * Keeps the Telegram conversation live: long-polls getUpdates and hands each message to the shared coach backend.
 * A foreground service, because OxygenOS/ColorOS stop background polling otherwise.
 */
class TelegramService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n: Notification = NotificationCompat.Builder(this, Notifier.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_coach).setContentTitle("FitCoach").setContentText("Connected to Telegram")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
        if (loop?.isActive != true) loop = scope.launch { poll() }
        return START_STICKY
    }

    private suspend fun poll() {
        var backoff = 5_000L
        while (scope.isActive) {
            val bridge = Telegram.bridge(this) ?: run { stopSelf(); return }
            val settings = Telegram.settings(this)
            try {
                val updates = TelegramApi(settings.token!!).getUpdates(settings.offset)
                for (u in updates) {
                    val status = runCatching { bridge.handle(u) }.getOrElse { "error: ${it.message}" }
                    Log.i("FitCoach", "telegram update ${u.optLong("update_id")}: $status")
                    settings.offset = u.optLong("update_id") + 1
                }
                if (updates.isNotEmpty()) FitCoachApp.instance.notifyDataChanged()
                Telegram.lastPollAt = System.currentTimeMillis()
                Telegram.lastPollError = null
                backoff = 5_000L
            } catch (e: Exception) {
                Log.w("FitCoach", "telegram poll failed: ${e.message}")
                Telegram.lastPollError = e.message ?: e.javaClass.simpleName
                delay(backoff)
                backoff = minOf(backoff * 2, 300_000L)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { private const val NOTIFICATION_ID = 3001 }
}

/** Delivers proactive messages through the channel the agent chose (falls back to a notification). */
object Delivery {
    suspend fun deliver(ctx: Context, out: Outbound) {
        if (out.channel == "telegram") {
            val sent = runCatching { Telegram.bridge(ctx)?.deliver(out) == true }.getOrElse { Log.w("FitCoach", "telegram send failed", it); false }
            if (sent) return
        }
        if (out.channel == "call") return CallManager.ring(ctx, out)
        if (out.channel != "app") Notifier.show(ctx, out)
    }
}
