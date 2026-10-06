package com.fitcoach.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.dbl
import com.fitcoach.app.data.str
import com.fitcoach.app.engine.NotificationParser
import com.fitcoach.app.notify.Notifier
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.coroutines.resume

private fun granted(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

// ============================================================ activity recognition
/** Walks, runs and rides detected by the phone become observed exercise sessions (they complete walk commitments). */
object ActivityTracker {
    private val TYPES = mapOf(DetectedActivity.WALKING to "WALKING", DetectedActivity.RUNNING to "RUNNING", DetectedActivity.ON_BICYCLE to "CYCLING")

    fun hasPermission(ctx: Context) = granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, ActivityTransitionReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        if (!hasPermission(ctx)) return
        val transitions = TYPES.keys.flatMap { t ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(t).setActivityTransition(it).build()
            }
        }
        ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(ctx))
            .addOnFailureListener { Log.w("FitCoach", "activity transitions not registered", it) }
    }

    fun typeName(t: Int) = TYPES[t]
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val prefs = context.getSharedPreferences("activity", Context.MODE_PRIVATE)
        val app = FitCoachApp.instance
        val now = Instant.now()
        for (e in result.transitionEvents) {
            val type = ActivityTracker.typeName(e.activityType) ?: continue
            val at = now.minusMillis((SystemClock.elapsedRealtimeNanos() - e.elapsedRealTimeNanos) / 1_000_000)
            if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                prefs.edit().putLong("start_$type", at.toEpochMilli()).apply()
            } else {
                val start = prefs.getLong("start_$type", 0L)
                prefs.edit().remove("start_$type").apply()
                if (start > 0) app.service().onActivitySession(type, Instant.ofEpochMilli(start), at, now)
            }
        }
    }
}

// ============================================================ places (geofences)
/** Saved places (gym, office, chess club, favourite chaat stall). Arrivals drive rule reminders and patterns. */
object Places {
    const val DEFAULT_RADIUS_M = 120.0

    fun hasPermission(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBackground(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 8, Intent(ctx, GeofenceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun registerAll(ctx: Context, store: Store) {
        if (!hasPermission(ctx) || !hasBackground(ctx)) return
        val places = store.places()
        val client = LocationServices.getGeofencingClient(ctx)
        client.removeGeofences(pendingIntent(ctx)).addOnCompleteListener {
            if (places.isEmpty()) return@addOnCompleteListener
            val fences = places.map { p ->
                Geofence.Builder().setRequestId(p.str("tag")!!)
                    .setCircularRegion(p.dbl("lat")!!, p.dbl("lng")!!, (p.dbl("radius_m") ?: DEFAULT_RADIUS_M).toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(60_000)
                    .build()
            }
            val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
            client.addGeofences(req, pendingIntent(ctx)).addOnFailureListener { Log.w("FitCoach", "geofences not registered", it) }
        }
    }

    /** Current location, or null if unavailable. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(ctx: Context): Pair<Double, Double>? {
        if (!hasPermission(ctx)) return null
        return suspendCancellableCoroutine { cont ->
            LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc -> cont.resume(loc?.let { it.latitude to it.longitude }) }
                .addOnFailureListener { cont.resume(null) }
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("FitCoach", "geofence error ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            return
        }
        val entered = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        val tags = event.triggeringGeofences?.map { it.requestId } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = FitCoachApp.instance
                val now = Instant.now()
                tags.forEach { app.service().onPlaceEvent(it, entered, now, now) }
                // An arrival can be the moment a rule matters; let the brain decide right away.
                if (entered) app.service().tick(now).forEach { Notifier.show(context, it) }
                app.notifyDataChanged()
            } finally {
                pending.finish()
            }
        }
    }
}

// ============================================================ notifications (UPI / delivery apps)
/**
 * Reads notifications of a fixed allow-list of apps (UPI, bank SMS, Swiggy/Zomato). Everything else is ignored
 * without being stored. Parsed signals become pending inferred events, confirmed with one tap.
 */
class FoodNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in NotificationParser.ALL_PACKAGES) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = (extras.getCharSequence("android.bigText") ?: extras.getCharSequence("android.text"))?.toString() ?: ""
        try {
            val app = FitCoachApp.instance
            val status = NotificationParser.ingest(app.store, pkg, title, text, Instant.ofEpochMilli(sbn.postTime), Instant.now())
            if (status.startsWith("stored")) app.notifyDataChanged()
        } catch (e: Exception) {
            Log.w("FitCoach", "notification parse failed", e)
        }
    }

    companion object {
        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, FoodNotificationListener::class.java).flattenToString()
            return flat.split(":").any { it == me }
        }
    }
}

// ============================================================ calendar
/** Busy/free time today from the phone's calendars (Google, Outlook, work profile sync). Read-only, titles never stored. */
object CalendarReader {
    fun hasPermission(ctx: Context) = granted(ctx, Manifest.permission.READ_CALENDAR)

    fun sync(ctx: Context, store: Store, now: Instant) {
        if (!hasPermission(ctx)) return
        val tz = store.tz
        for (offset in 0..1L) {
            val day = LocalDate.now(tz).plusDays(offset)
            val start = day.atStartOfDay(tz).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(start.toString()).appendPath(end.toString()).build()
            val busy = mutableListOf<Pair<Int, Int>>()
            ctx.contentResolver.query(
                uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.AVAILABILITY),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(2) == 1 || c.getInt(3) == CalendarContract.Instances.AVAILABILITY_FREE) continue
                    val b = Instant.ofEpochMilli(c.getLong(0)).atZone(tz); val e = Instant.ofEpochMilli(c.getLong(1)).atZone(tz)
                    val bm = if (b.toLocalDate().isBefore(day)) 0 else b.hour * 60 + b.minute
                    val em = if (e.toLocalDate().isAfter(day)) 24 * 60 else e.hour * 60 + e.minute
                    if (em > bm) busy += bm to em
                }
            }
            val merged = mutableListOf<Pair<Int, Int>>()
            busy.sortedBy { it.first }.forEach { iv ->
                val last = merged.lastOrNull()
                if (last != null && iv.first <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, iv.second) else merged += iv
            }
            val dayStart = 9 * 60; val dayEnd = 21 * 60
            val free = mutableListOf<Pair<Int, Int>>()
            var cursor = dayStart
            merged.forEach { (b, e) -> if (b - cursor >= 30) free += cursor to minOf(b, dayEnd); cursor = maxOf(cursor, e) }
            if (dayEnd - cursor >= 30) free += cursor to dayEnd
            fun hm(m: Int) = "%02d:%02d".format(minOf(m, 23 * 60 + 59) / 60, minOf(m, 23 * 60 + 59) % 60)
            fun arr(l: List<Pair<Int, Int>>) = JSONArray(l.map { JSONArray(listOf(hm(it.first), hm(it.second))) })
            val hours = merged.sumOf { it.second - it.first } / 60.0
            store.setCalendarDay(now, day.toString(), Math.round(hours * 10) / 10.0, merged.firstOrNull()?.let { hm(it.first) },
                merged.lastOrNull()?.let { hm(it.second) }, JSONObject().put("free", arr(free.filter { it.first < dayEnd })).put("busy", arr(merged)))
        }
    }
}

// ============================================================ screen time
/** Total screen-on time and late-night use (after 23:00) per day: a proxy for sleep debt and long sitting. */
object ScreenTime {
    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun sync(ctx: Context, store: Store, now: Instant) {
        if (!hasPermission(ctx)) return
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val tz = store.tz
        for (offset in 0..1L) {
            // A "day" runs 04:00 to 04:00, so late-night use belongs to the evening it started.
            val day = LocalDate.now(tz).minusDays(offset)
            val start = day.atTime(LocalTime.of(4, 0)).atZone(tz).toInstant()
            val end = minOf(start.plus(Duration.ofDays(1)), now)
            if (!end.isAfter(start)) continue
            val lateFrom = day.atTime(LocalTime.of(23, 0)).atZone(tz).toInstant()
            val events = usm.queryEvents(start.toEpochMilli(), end.toEpochMilli())
            val ev = UsageEvents.Event()
            var on: Instant? = null
            var total = 0L; var longest = 0L; var late = 0L
            fun close(until: Instant) {
                val s = on ?: return
                val m = Duration.between(s, until).toMinutes()
                total += m; longest = maxOf(longest, m)
                if (until.isAfter(lateFrom)) late += Duration.between(maxOf(s, lateFrom), until).toMinutes()
                on = null
            }
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                when (ev.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> if (on == null) on = Instant.ofEpochMilli(ev.timeStamp)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> close(Instant.ofEpochMilli(ev.timeStamp))
                }
            }
            close(end)
            store.setScreenDay(now, day.toString(), total.toInt(), longest.toInt(), late.toInt())
        }
    }
}
