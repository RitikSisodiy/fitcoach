package com.fitcoach.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.telegram.Delivery
import com.fitcoach.app.telegram.Telegram
import com.fitcoach.app.sensors.ActivityTracker
import com.fitcoach.app.sensors.CalendarReader
import com.fitcoach.app.sensors.HealthSync
import com.fitcoach.app.sensors.Places
import com.fitcoach.app.sensors.ScreenTime
import com.fitcoach.app.update.Updater
import java.time.Instant
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC = "coach_tick"
    private const val NOW = "coach_tick_now"

    /** 15 minutes is the WorkManager minimum; the policy's slot learner works on 30-minute slots. */
    fun ensureScheduled(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<TickWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<TickWorker>().build())
    }
}

/** One pass of the agent loop: read the phone's sensors, then let the agent decide (see CoachService.tick). */
class TickWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = FitCoachApp.instance
        val now = Instant.now()
        runCatching { HealthSync.sync(applicationContext, app.store, now) }.onFailure { Log.w(TAG, "health sync", it) }
        runCatching { CalendarReader.sync(applicationContext, app.store, now) }.onFailure { Log.w(TAG, "calendar", it) }
        runCatching { ScreenTime.sync(applicationContext, app.store, now) }.onFailure { Log.w(TAG, "screen time", it) }
        runCatching { ActivityTracker.register(applicationContext) }
        runCatching { Places.registerAll(applicationContext, app.store) }
        val out = try {
            app.service().tick(now)
        } catch (e: Exception) {
            Log.e(TAG, "tick failed", e)
            app.store.logDecision(now, "tick_error", "${e.javaClass.simpleName}: ${e.message}".take(300))
            emptyList()
        }
        out.forEach { Delivery.deliver(applicationContext, it) }
        Telegram.ensureRunning(applicationContext)
        Updater.check(applicationContext)
        app.notifyDataChanged()
        return Result.success()
    }

    companion object { private const val TAG = "FitCoachTick" }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.ensureScheduled(context)
        Scheduler.runNow(context)
        Telegram.ensureRunning(context)
    }
}
