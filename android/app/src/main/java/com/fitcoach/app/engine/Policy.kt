package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.Commitment
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * Deterministic facts and limits for the agent: commitment history (computed, never guessed by the LLM),
 * the per-mode daily message cap (a rate limit the user controls), and the timing learner for commitments.
 */
object Policy {
    val MODE_BUDGET_CAP = mapOf("gentle" to 1, "normal" to 3, "accountability" to 4, "strong" to 4)
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
        val dominantSkipReason: String?,
        val slotSuccess: Map<String, String>,
    ) {
        fun asJson(): JSONObject = JSONObject()
            .put("scheduled_days_considered", scheduledDaysConsidered).put("consecutive_misses", consecutiveMisses)
            .put("misses_7d", misses7d).put("done_7d", done7d).put("no_data_7d", noData7d)
            .put("ignored_streak", ignoredStreak)
            .put("dominant_skip_reason", dominantSkipReason ?: JSONObject.NULL).put("slot_success", JSONObject(slotSuccess))
    }

    fun analyseCommitment(store: Store, c: Commitment, today: String, lookbackDays: Int = 14): Pattern {
        val todayD = LocalDate.parse(today)
        // Only nudges with buttons can be acted on or ignored; in-reply reminders ("contextual") are excluded.
        val history = store.interventionHistory(c.id, TimeUtil.daysAgo(today, lookbackDays.toLong()))
            .filter { it.str("kind") != "contextual" }
        val nudgedDays = history.mapNotNull { it.str("local_date") }.toSet()
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
        val reasons = store.commitmentHistory(c.id, TimeUtil.daysAgo(today, lookbackDays.toLong())).mapNotNull { it.str("reason_category") }
        val dominant = reasons.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val slotOutcomes = LinkedHashMap<String, MutableList<Int>>()
        history.forEach { r ->
            val slot = r.str("slot"); val o = r.str("outcome")
            if (slot != null && o != null) slotOutcomes.getOrPut(slot) { mutableListOf() } += if (o == "achieved") 1 else 0
        }
        return Pattern(
            days.size, consecutive,
            recent.count { it.second in setOf("missed", "skipped", "postponed") },
            recent.count { it.second in setOf("done", "smaller") },
            recent.count { it.second == "no_data" },
            ignored, dominant,
            slotOutcomes.mapValues { (_, v) -> "${v.sum()}/${v.size}" },
        )
    }
}

/**
 * Learns which 30-minute slots work for each commitment: Beta-Bernoulli counts with forgetting, updated from
 * evaluated outcomes. The agent sees the estimates as evidence; it is not a send/no-send rule.
 */
class SlotLearner(private val store: Store) {

    /** Posterior mean success rate per slot, with the number of observations behind it. */
    fun estimates(commitmentId: Long): JSONObject = JSONObject().also { o ->
        store.slotStats(commitmentId).forEach { (slot, ab) ->
            val (a, b) = ab
            o.put(slot, JSONObject().put("success_rate", Math.round(a / (a + b) * 100) / 100.0).put("evidence", Math.round((a + b - 2 * PRIOR) * 10) / 10.0))
        }
    }

    fun update(now: Instant, commitmentId: Long, slot: String, success: Double) {
        val (a0, b0) = store.slotStats(commitmentId)[slot] ?: (PRIOR to PRIOR)
        val a = PRIOR + (a0 - PRIOR) * DECAY + success
        val b = PRIOR + (b0 - PRIOR) * DECAY + (1 - success)
        store.setSlotStats(now, commitmentId, slot, a, b)
    }

    companion object {
        const val PRIOR = 1.0
        const val DECAY = 0.9
    }
}
