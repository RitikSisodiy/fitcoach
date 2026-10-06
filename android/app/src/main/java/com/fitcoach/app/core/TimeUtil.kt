package com.fitcoach.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Time helpers. Persisted instants are ISO-8601 UTC; local dates use the user's timezone. */
object TimeUtil {
    val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    fun iso(t: Instant): String = DateTimeFormatter.ISO_INSTANT.format(t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    fun parse(s: String): Instant = try {
        Instant.parse(s)
    } catch (e: Exception) {
        ZonedDateTime.parse(s).toInstant()
    }

    fun localDate(t: Instant, zone: ZoneId): String = t.atZone(zone).toLocalDate().toString()

    fun localTime(t: Instant, zone: ZoneId): LocalTime = t.atZone(zone).toLocalTime()

    fun hhmm(s: String): LocalTime {
        val (h, m) = s.split(":")
        return LocalTime.of(h.toInt(), m.toInt())
    }

    fun fmtHhmm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    /** True if t is within [start, end). Handles windows crossing midnight. */
    fun inWindow(t: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (!start.isAfter(end)) !t.isBefore(start) && t.isBefore(end) else !t.isBefore(start) || t.isBefore(end)

    fun slotOf(t: LocalTime, slotMinutes: Int = 30): String {
        val m = (t.hour * 60 + t.minute) / slotMinutes * slotMinutes
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** Slots on the same grid as [slotOf]; the window start is snapped down to the grid. */
    fun slotsBetween(start: LocalTime, end: LocalTime, slotMinutes: Int = 30): List<String> {
        val out = mutableListOf<String>()
        var cursor = (start.hour * 60 + start.minute) / slotMinutes * slotMinutes
        var stop = end.hour * 60 + end.minute
        if (stop <= cursor) stop += 24 * 60
        while (cursor < stop) {
            val m = cursor % (24 * 60)
            out += "%02d:%02d".format(m / 60, m % 60)
            cursor += slotMinutes
        }
        return out
    }

    fun weekdayKey(d: LocalDate): String = WEEKDAYS[d.dayOfWeek.value - 1]

    fun daysAgo(day: String, n: Long): String = LocalDate.parse(day).minusDays(n).toString()

    fun isWeekend(day: String): Boolean = LocalDate.parse(day).dayOfWeek.let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }

    fun atLocal(day: String, hhmm: String, zone: ZoneId): Instant =
        LocalDate.parse(day).atTime(hhmm(hhmm)).atZone(zone).toInstant()
}
