package com.fitcoach.app.sensors

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.Store
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Reads steps, sleep, weight and exercise sessions from Health Connect on the phone itself
 * (Google Fit, Samsung Health, OHealth, watch apps all write there). Replaces the webhook forwarder.
 */
object HealthSync {
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )
    const val BACKGROUND_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Set<String> =
        if (!available(ctx)) emptySet() else HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()

    fun backgroundSupported(ctx: Context): Boolean = available(ctx) &&
        HealthConnectClient.getOrCreate(ctx).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
        HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    /** Returns a short summary for the log. Safe to call often: rows are upserted by natural key. */
    suspend fun sync(ctx: Context, store: Store, now: Instant, days: Long = 7): String {
        if (!available(ctx)) return "health connect not available"
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.isEmpty()) return "no health permissions"
        val tz = store.tz
        val today = LocalDate.now(tz)
        val since = today.minusDays(days).atStartOfDay(tz).toInstant()
        var n = 0

        if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
            for (i in 0..days) {
                val day = today.minusDays(i)
                val start = day.atStartOfDay(tz).toInstant()
                val end = minOf(day.plusDays(1).atStartOfDay(tz).toInstant(), now)
                if (!end.isAfter(start)) continue
                val agg = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end)))
                val steps = agg[StepsRecord.COUNT_TOTAL] ?: continue
                // Today's row records when it was read, so "no new steps while sitting" still counts as fresh data.
                val payload = JSONObject().put("steps", steps)
                if (i == 0L) payload.put("as_of", TimeUtil.iso(now.truncatedTo(ChronoUnit.MINUTES)))
                if (store.upsertHealth(now, "steps", "steps|$day", start, end, start.plusSeconds(43200), steps.toDouble(), payload)) n++
            }
        }
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            val rows = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.after(since.minus(Duration.ofDays(1))))).records
            rows.forEach { r ->
                val minutes = Duration.between(r.startTime, r.endTime).toMinutes().toDouble()
                if (minutes in 60.0..(16 * 60.0)) {
                    // Anchored on the wake-up time: the night belongs to the morning it ends.
                    if (store.upsertHealth(now, "sleep_min", "sleep|${r.metadata.id}", r.startTime, r.endTime, r.endTime, minutes,
                            JSONObject().put("minutes", minutes))) {
                        n++
                        store.addObservation(r.endTime, "sleep", "slept %.1f h, woke at %s".format(minutes / 60, TimeUtil.fmtHhmm(TimeUtil.localTime(r.endTime, store.tz))), significant = false)
                    }
                }
            }
        }
        if (HealthPermission.getReadPermission(WeightRecord::class) in granted) {
            client.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.after(since.minus(Duration.ofDays(23))))).records.forEach { r ->
                val kg = r.weight.inKilograms
                if (kg in 30.0..300.0 && store.upsertHealth(now, "weight_kg", "weight|${r.metadata.id}", r.time, r.time, r.time, kg, JSONObject().put("kg", kg))) {
                    n++
                    store.addObservation(r.time, "weigh_in", "weighed %.1f kg (Health Connect)".format(kg))
                }
            }
        }
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.after(since))).records.forEach { r ->
                val minutes = Duration.between(r.startTime, r.endTime).toMinutes().toDouble()
                val type = exerciseName(r.exerciseType)
                if (minutes in 3.0..600.0 && store.upsertHealth(now, "exercise", "exercise|${r.metadata.id}", r.startTime, r.endTime, r.startTime, minutes,
                        JSONObject().put("type", type).put("title", r.title ?: JSONObject.NULL).put("source", "health_connect"))) {
                    n++
                    store.addObservation(r.endTime, "exercise", "$type ${minutes.toInt()} min (Health Connect)")
                }
            }
        }
        return "health sync: $n new/updated rows"
    }

    private fun exerciseName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "WALKING"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "WALKING_HIKE"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "RUNNING"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "CYCLING"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "YOGA"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "STRENGTH"
        else -> "WORKOUT"
    }

}
