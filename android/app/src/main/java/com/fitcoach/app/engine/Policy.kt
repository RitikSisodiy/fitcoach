package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.Commitment
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic intervention policy: escalation ladder and slot learning (port of coach/engine/policy.py).
 * The LLM never decides whether a proactive message is allowed; it only writes the words.
 */
object Policy {
    val MODE_LEVEL_CAP = mapOf("gentle" to 1, "normal" to 3, "accountability" to 4, "strong" to 5)
    val MODE_BUDGET_CAP = mapOf("gentle" to 1, "normal" to 3, "accountability" to 4, "strong" to 4)
    val LEVEL_NAMES = mapOf(
        0 to "silent", 1 to "gentle_reminder", 2 to "contextual", 3 to "accountability",
        4 to "strong_recommendation", 5 to "commitment_enforcement",
    )
    val SUCCESS_STATUSES = setOf("acted", "smaller")
    val FAILURE_STATUSES = setOf("ignored", "skipped")

    fun effectiveBudget(profile: JSONObject): Int {
        val mode = profile.optString("coaching_mode", "normal")
        return maxOf(0, minOf(profile.optInt("daily_message_budget", 3), MODE_BUDGET_CAP[mode] ?: 3))
    }

    fun isScheduledOn(c: Commitment, day: LocalDate): Boolean {
        val spec = (c.scheduleDays ?: "").trim().lowercase()
        if (spec.isEmpty() || c.windowStart == null) return false
        val key = TimeUtil.weekdayKey(day)
        return when (spec) {
            "daily", "everyday", "every day" -> true
            "weekdays" -> key in TimeUtil.WEEKDAYS.take(5)
            "weekends" -> key in TimeUtil.WEEKDAYS.drop(5)
            else -> key in spec.split(",").map { it.trim().take(3) }.toSet()
        }
    }

    data class Pattern(
        val scheduledDaysConsidered: Int,
        val consecutiveMisses: Int,
        val misses7d: Int,
        val done7d: Int,
        val noData7d: Int,
        val ignoredStreak: Int,
        val lastAccountabilityDate: String?,
        val dominantSkipReason: String?,
        val slotSuccess: Map<String, String>,
    ) {
        fun asJson(): JSONObject = JSONObject()
            .put("scheduled_days_considered", scheduledDaysConsidered).put("consecutive_misses", consecutiveMisses)
            .put("misses_7d", misses7d).put("done_7d", done7d).put("no_data_7d", noData7d)
            .put("ignored_streak", ignoredStreak).put("last_accountability_date", lastAccountabilityDate ?: JSONObject.NULL)
            .put("dominant_skip_reason", dominantSkipReason ?: JSONObject.NULL).put("slot_success", JSONObject(slotSuccess))
    }

    data class LadderDecision(
        val level: Int, val versionIndex: Int, val version: String, val backOff: Boolean, val pattern: Pattern, val reason: String,
    )

    fun analyseCommitment(store: Store, c: Commitment, today: String, lookbackDays: Int = 14): Pattern {
        val todayD = LocalDate.parse(today)
        // Only nudges with buttons can be acted on or ignored; in-reply reminders ("contextual") are excluded.
        val history = store.interventionHistory(c.id, TimeUtil.daysAgo(today, lookbackDays.toLong()))
            .filter { it.str("kind") in setOf("scheduled", "follow_up") }
        val nudgedDays = history.filter { it.str("kind") == "scheduled" }.mapNotNull { it.str("local_date") }.toSet()
        val createdDay = c.createdAt.take(10)
        val days = mutableListOf<Pair<String, String>>()
        for (offset in lookbackDays downTo 1) {
            val d = todayD.minusDays(offset.toLong())
            val iso = d.toString()
            if (iso < createdDay || !isScheduledOn(c, d)) continue
            var outcome = store.commitmentOutcome(c.id, iso)
            if (outcome == "skipped" && store.daySkipReason(c.id, iso) == "cannot") outcome = "excused"
            if (outcome == null) outcome = if (iso in nudgedDays) "missed" else "no_data"
            days += iso to outcome
        }
        var consecutive = 0
        for ((_, o) in days.reversed()) {
            if (o == "no_data" || o == "excused") continue
            if (o == "done" || o == "smaller") break
            consecutive++
        }
        val weekAgo = TimeUtil.daysAgo(today, 7)
        val recent = days.filter { it.first > weekAgo }
        var ignored = 0
        for (row in history.reversed()) {
            val s = row.str("status")
            if (s == "sent") continue
            if (s != "ignored") break
            ignored++
        }
        val lastAcc = history.filter { (it.long("level") ?: 0) >= 3 }.mapNotNull { it.str("local_date") }.maxOrNull()
        val reasons = store.commitmentHistory(c.id, TimeUtil.daysAgo(today, lookbackDays.toLong())).mapNotNull { it.str("reason_category") }
        val dominant = reasons.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val slotOutcomes = LinkedHashMap<String, MutableList<Int>>()
        history.forEach { r ->
            val slot = r.str("slot"); val st = r.str("status")
            if (slot != null && st != null && st in SUCCESS_STATUSES + FAILURE_STATUSES) {
                slotOutcomes.getOrPut(slot) { mutableListOf() } += if (st in SUCCESS_STATUSES) 1 else 0
            }
        }
        return Pattern(
            days.size, consecutive,
            recent.count { it.second in setOf("missed", "skipped", "postponed") },
            recent.count { it.second in setOf("done", "smaller") },
            recent.count { it.second == "no_data" },
            ignored, lastAcc, dominant,
            slotOutcomes.mapValues { (_, v) -> "${v.sum()}/${v.size}" },
        )
    }

    fun decideLevel(c: Commitment, p: Pattern, profile: JSONObject, today: String): LadderDecision {
        val mode = profile.optString("coaching_mode", "normal")
        val cap = MODE_LEVEL_CAP[mode] ?: 3
        val misses = p.consecutiveMisses
        val ignored = p.ignoredStreak
        val reasons = mutableListOf<String>()
        var level = 1
        if (misses >= 1) { reasons += "$misses consecutive missed day(s): never miss twice"; level = 2 }
        if (p.misses7d >= 3) {
            val last = p.lastAccountabilityDate
            if (last == null || last <= TimeUtil.daysAgo(today, 5)) {
                level = 3; reasons += "${p.misses7d} misses in 7 days: name the pattern, change strategy"
            }
        }
        if (misses >= 2 && level < 4) { level = 4; reasons += "2+ misses in a row: strongly recommend the minimum version" }
        val strongOk = c.enforcement == "strong" && mode == "strong" && profile.optBoolean("strong_mode_authorized", false)
        if (strongOk && misses >= 1) { level = 5; reasons += "user-authorised strong enforcement" }
        if (level > cap) { reasons += "capped at $cap by mode '$mode'"; level = cap }
        val versions = c.versions.ifEmpty { listOf(c.action) }
        val vi = (misses + if (ignored >= 2) 1 else 0).coerceIn(0, versions.size - 1)
        val backOff = ignored >= 4
        if (backOff) reasons += "$ignored nudges ignored in a row: back off and review strategy instead of repeating"
        return LadderDecision(level, vi, versions[vi], backOff, p, reasons.joinToString("; ").ifEmpty { "routine reminder" })
    }
}

/** Beta-Bernoulli Thompson sampling over 30-minute slots, per commitment. */
class SlotLearner(private val store: Store, private val rng: Random = Random.Default) {

    fun shouldSendNow(commitmentId: Long, currentSlot: String, remaining: List<String>): Pair<Boolean, String> {
        if (currentSlot !in remaining) return false to "current slot outside window"
        if (remaining.size == 1) return true to "last slot in window"
        val stats = store.slotStats(commitmentId)
        val samples = remaining.associateWith { s -> stats[s].let { beta(it?.first ?: PRIOR, it?.second ?: PRIOR) } }
        val best = samples.maxByOrNull { it.value }!!.key
        if (best == currentSlot) return true to "slot $currentSlot has best sampled response rate"
        if (rng.nextDouble() < EXPLORATION / remaining.size) return true to "exploring slot $currentSlot"
        return false to "waiting for better slot ($best)"
    }

    fun update(now: Instant, commitmentId: Long, slot: String, success: Double) {
        val (a0, b0) = store.slotStats(commitmentId)[slot] ?: (PRIOR to PRIOR)
        val a = PRIOR + (a0 - PRIOR) * DECAY + success
        val b = PRIOR + (b0 - PRIOR) * DECAY + (1 - success)
        store.setSlotStats(now, commitmentId, slot, a, b)
    }

    private fun beta(a: Double, b: Double): Double {
        val x = gamma(a); val y = gamma(b)
        return if (x + y == 0.0) 0.5 else x / (x + y)
    }

    /** Marsaglia-Tsang gamma sampler (shape k, scale 1). */
    private fun gamma(k: Double): Double {
        if (k < 1) return gamma(k + 1) * Math.pow(rng.nextDouble().coerceAtLeast(1e-12), 1 / k)
        val d = k - 1.0 / 3; val c = 1 / sqrt(9 * d)
        while (true) {
            var x: Double; var v: Double
            do { x = normal(); v = 1 + c * x } while (v <= 0)
            v = v * v * v
            val u = rng.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun normal(): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12); val u2 = rng.nextDouble()
        return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    companion object {
        const val PRIOR = 1.0
        const val DECAY = 0.9
        const val EXPLORATION = 0.15
    }
}
