package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.Row
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.str
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Engagement model (port of coach/engine/engagement.py). When the user goes quiet we send less,
 * make it easier, and switch to one-tap re-entry - never guilt.
 */
data class Engagement(
    val state: String, // engaged | drifting | silent | dormant
    val daysSinceInbound: Double?,
    val recentResponseRate: Double?,
    val recentSent: Int,
    val dailyBudget: Int,
    val minDaysBetween: Int,
    val intentStats: Map<String, IntentStat> = emptyMap(),
    val retiredIntents: Set<String> = emptySet(),
    val bestHours: List<Int> = emptyList(),
) {
    data class IntentStat(var sent: Int = 0, var responded: Int = 0, var lastSent: String? = null, var lastResponded: String? = null)

    fun asContext(): JSONObject = JSONObject()
        .put("state", state)
        .put("days_since_user_message", daysSinceInbound?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
        .put("recent_response_rate", recentResponseRate ?: JSONObject.NULL)
        .put("daily_budget", dailyBudget)
        .put("retired_message_types", JSONArray(retiredIntents.sorted()))
        .put("intent_response_rates", JSONObject(intentStats.filterValues { it.sent > 0 }.mapValues { "${it.value.responded}/${it.value.sent}" }))
        .put("hours_user_usually_replies", JSONArray(bestHours))

    companion object {
        val PROACTIVE_KINDS = setOf("scheduled", "follow_up", "proactive", "review")
        val RESPONDED = setOf("acted", "smaller", "skipped", "snoozed", "answered")
        const val RESPONSE_WINDOW_HOURS = 3L
        const val RETIRE_AFTER_SENDS = 6
        const val RETIRE_DAYS = 7L
        val NEVER_RETIRE = emptySet<String>()

        fun intentFamily(intent: String?): String = (intent ?: "unknown").substringBefore(':')

        private fun responded(r: Row, inbound: List<Instant>): Boolean {
            if (r.str("status") in RESPONDED) return true
            val sent = TimeUtil.parse(r.str("sent_at")!!)
            val until = sent.plus(Duration.ofHours(RESPONSE_WINDOW_HOURS))
            return inbound.any { !it.isBefore(sent) && !it.isAfter(until) }
        }

        fun compute(store: Store, now: Instant, modeBudget: Int): Engagement {
            val today = store.date(now)
            val rows = store.interventionsSince(TimeUtil.daysAgo(today, 21)).filter { it.str("kind") in PROACTIVE_KINDS }
            val inbound = store.inboundTimesSince(TimeUtil.iso(now.minus(Duration.ofDays(22)))).map(TimeUtil::parse)
            val lastIn = listOfNotNull(store.lastInboundAt(), store.lastButtonAt()).maxOrNull()
            val daysSince = lastIn?.let { Duration.between(TimeUtil.parse(it), now).seconds / 86400.0 }

            val judged = rows.filter {
                TimeUtil.parse(it.str("sent_at")!!).isBefore(now.minus(Duration.ofHours(RESPONSE_WINDOW_HOURS))) || it.str("status") != "sent"
            }
            val last8 = judged.takeLast(8)
            val rate = if (last8.isEmpty()) null else Math.round(last8.count { responded(it, inbound) } * 100.0 / last8.size) / 100.0

            val stats = LinkedHashMap<String, IntentStat>()
            judged.forEach { r ->
                val fam = intentFamily(r.str("intent") ?: r.str("kind"))
                val s = stats.getOrPut(fam) { IntentStat() }
                s.sent++; s.lastSent = r.str("sent_at")
                if (responded(r, inbound)) { s.responded++; s.lastResponded = r.str("sent_at") }
            }
            val retired = mutableSetOf<String>()
            stats.keys.filter { it !in NEVER_RETIRE }.forEach { fam ->
                val recent = judged.filter { intentFamily(it.str("intent") ?: it.str("kind")) == fam }.takeLast(RETIRE_AFTER_SENDS)
                if (recent.size == RETIRE_AFTER_SENDS && recent.none { responded(it, inbound) }) {
                    val last = TimeUtil.parse(recent.last().str("sent_at")!!)
                    if (Duration.between(last, now) < Duration.ofDays(RETIRE_DAYS)) retired += fam
                }
            }
            val hours = inbound.groupingBy { it.atZone(store.tz).hour }.eachCount()
            val bestHours = hours.entries.sortedByDescending { it.value }.take(3).map { it.key }

            val state = when {
                daysSince == null -> "engaged"
                daysSince <= 1.5 || (rate != null && rate >= 0.4) -> "engaged"
                daysSince <= 4 -> "drifting"
                daysSince <= 10 -> "silent"
                else -> "dormant"
            }
            // Rate limits only: the quieter the user, the fewer messages are allowed. What to say is the agent's call.
            val (budget, gap) = when (state) {
                "engaged" -> modeBudget to 0
                "drifting" -> minOf(modeBudget, 2) to 0
                "silent" -> minOf(modeBudget, 1) to 0
                else -> minOf(modeBudget, 1) to if ((daysSince ?: 0.0) <= 21) 3 else 7
            }
            return Engagement(state, daysSince, rate, last8.size, budget, gap, stats, retired, bestHours)
        }
    }
}
