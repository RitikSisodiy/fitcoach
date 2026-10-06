package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.Row
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deterministic pattern mining with evidence counts (port of coach/analytics/patterns.py).
 * Counts are computed here, never by the LLM. A pattern is `active` only with enough evidence across
 * distinct weeks; rejected patterns are never re-activated.
 */
object Patterns {
    const val LOOKBACK_DAYS = 56L
    val OFFPLAN_KEYS = setOf(
        "samosa", "kachori", "pakora", "vada_pav", "pav_bhaji", "sev", "namkeen", "instant_noodles", "gulab_jamun",
        "jalebi", "ladoo", "pizza", "burger", "french_fries", "soft_drink", "street_snack", "chowmein", "beer",
    )
    const val MIN_LIFT = 0.25
    const val MIN_SUPPORT = 3
    const val MIN_WEEKS = 2
    const val MIN_WILSON = 0.35

    fun wilsonLower(successes: Int, n: Int, z: Double = 1.64): Double {
        if (n == 0) return 0.0
        val p = successes.toDouble() / n
        val denom = 1 + z * z / n
        val centre = p + z * z / (2 * n)
        val margin = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n))
        return maxOf(0.0, (centre - margin) / denom)
    }

    private fun isoWeek(day: String): Pair<Int, Int> = LocalDate.parse(day).let { it.get(IsoFields.WEEK_BASED_YEAR) to it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
    private fun weekday(day: String) = LocalDate.parse(day).dayOfWeek.value - 1
    private fun minutes(hhmm: String) = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    private fun hhmm(m: Double): String { val x = Math.floorMod(m.roundToInt(), 24 * 60); return "%02d:%02d".format(x / 60, x % 60) }
    private fun median(xs: List<Int>): Double { val s = xs.sorted(); val n = s.size; return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0 }
    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    private fun opportunities(first: String, today: String, wd: Int, hits: Int): Int {
        val f = LocalDate.parse(first)
        val span = ChronoUnit.DAYS.between(f, LocalDate.parse(today)).toInt()
        return maxOf(hits, (0 until span).count { f.plusDays(it.toLong()).dayOfWeek.value - 1 == wd })
    }

    private fun pattern(kind: String, key: String, claim: String, support: Int, contradict: Int, weeks: Int, conf: Double, active: Boolean, data: JSONObject) =
        JSONObject().put("kind", kind).put("key", key).put("claim", claim).put("support", support).put("contradict", contradict)
            .put("distinct_weeks", weeks).put("confidence", r2(conf)).put("status", if (active) "active" else "candidate").put("data", data)

    fun mine(store: Store, now: Instant): List<JSONObject> {
        val today = store.date(now)
        val since = TimeUtil.daysAgo(today, LOOKBACK_DAYS)
        val contexts = store.contextSince(since).filter { it.str("timing") != "past" && it.str("local_date")!! < today }
        val results = mutableListOf<JSONObject>()

        // ---- weekday recurrence of situations
        val tagDays = LinkedHashMap<String, LinkedHashMap<String, MutableList<Row>>>()
        contexts.forEach { c -> tagDays.getOrPut(c.str("tag")!!) { LinkedHashMap() }.getOrPut(c.str("local_date")!!) { mutableListOf() } += c }
        for ((tag, days) in tagDays) {
            val first = days.keys.min()
            for (wd in 0..6) {
                val hits = days.keys.filter { weekday(it) == wd }.sorted()
                if (hits.isEmpty()) continue
                val opp = opportunities(first, today, wd, hits.size)
                val times = hits.flatMap { d ->
                    days.getValue(d).map { c ->
                        c.str("time_hint")?.let(::minutes) ?: TimeUtil.localTime(TimeUtil.parse(c.str("occurred_at")!!), store.tz).let { it.hour * 60 + it.minute + 30 }
                    }
                }
                val typical = if (times.isEmpty()) null else hhmm(median(times))
                val conf = wilsonLower(hits.size, opp)
                val weeks = hits.map(::isoWeek).toSet().size
                val active = hits.size >= MIN_SUPPORT && weeks >= MIN_SUPPORT && conf >= MIN_WILSON
                val wdName = TimeUtil.WEEKDAYS[wd]
                results += pattern(
                    "weekday_context", "weekday_context:$tag:$wd",
                    "'$tag' usually happens on $wdName around $typical (${hits.size} of $opp ${wdName}s)",
                    hits.size, opp - hits.size, weeks, conf, active,
                    JSONObject().put("tag", tag).put("weekday", wd).put("typical_time", typical ?: JSONObject.NULL).put("dates", JSONArray(hits.takeLast(6))),
                )
            }
        }

        // ---- situation -> off-plan eating / lapse on the same day
        val lapseTimes = HashMap<String, MutableList<Instant>>()
        store.recentDecisions(1000).filter { it.str("kind") == "lapse" }.forEach { d ->
            val at = TimeUtil.parse(d.str("created_at")!!); lapseTimes.getOrPut(store.date(at)) { mutableListOf() } += at
        }
        val offplan = HashMap<String, MutableList<Row>>()
        store.foodBetween(since, TimeUtil.daysAgo(today, 1)).forEach { f ->
            if (f.str("food_key") in OFFPLAN_KEYS || (f.str("meal_slot") == "snack" && f.str("nutrition_source") == "llm_estimate")) {
                offplan.getOrPut(f.str("local_date")!!) { mutableListOf() } += f
            }
        }
        val interactionDays = store.inboundTimesSince("${since}T00:00:00Z").map { store.date(TimeUtil.parse(it)) }.toSet()
        for ((tag, days) in tagDays) {
            val supportDays = mutableListOf<String>(); val contraDays = mutableListOf<String>(); val items = mutableListOf<String>()
            for ((d, events) in days) {
                val firstMention = events.minOf { TimeUtil.parse(it.str("occurred_at")!!) }
                val hourStart = firstMention.atZone(store.tz).withMinute(0).withSecond(0).toInstant()
                val later = offplan[d].orEmpty().filter { !TimeUtil.parse(it.str("occurred_at")!!).isBefore(hourStart) }
                val lapseAfter = lapseTimes[d].orEmpty().any { !it.isBefore(firstMention) }
                if (later.isNotEmpty() || lapseAfter) { supportDays += d; items += later.mapNotNull { it.str("item_name") } } else contraDays += d
            }
            val n = supportDays.size + contraDays.size
            if (n < 2) continue
            val otherDays = interactionDays.filter { it !in days && it < today }
            val otherLapse = otherDays.count { !offplan[it].isNullOrEmpty() || !lapseTimes[it].isNullOrEmpty() }
            val base = if (otherDays.isEmpty()) 0.0 else otherLapse.toDouble() / otherDays.size
            val weeks = supportDays.map(::isoWeek).toSet().size
            val conf = wilsonLower(supportDays.size, n)
            val lift = supportDays.size.toDouble() / n - base
            val active = supportDays.size >= MIN_SUPPORT && weeks >= MIN_WEEKS && conf >= MIN_WILSON && lift >= MIN_LIFT
            val counts = items.groupingBy { it }.eachCount()
            val common = counts.keys.sortedByDescending { counts[it] }.take(3)
            results += pattern(
                "context_lapse", "context_lapse:$tag",
                "On '$tag' days, off-plan eating happened ${supportDays.size} of $n times" + if (common.isNotEmpty()) " (usually ${common.joinToString(", ")})" else "",
                supportDays.size, contraDays.size, weeks, conf, active,
                JSONObject().put("tag", tag).put("common_items", JSONArray(common)).put("support_days", JSONArray(supportDays.sorted().takeLast(6)))
                    .put("other_day_rate", r2(base)),
            )
        }

        // ---- recurring small payments (passive)
        val payments = store.query(
            "SELECT * FROM inferred_events WHERE kind = 'small_payment' AND status != 'rejected' AND local_date >= ? AND local_date < ?", since, today,
        )
        val byPayee = LinkedHashMap<String, LinkedHashMap<String, MutableList<Row>>>()
        payments.forEach { p ->
            val key = p.str("payee_key") ?: return@forEach
            val label = store.payeeLabel(key)
            if (label != null && (label.long("is_food") ?: 1L) == 0L) return@forEach
            byPayee.getOrPut(key) { LinkedHashMap() }.getOrPut(p.str("local_date")!!) { mutableListOf() } += p
        }
        for ((payee, days) in byPayee) {
            val first = days.keys.min()
            val label = store.payeeLabel(payee)
            val name = (label?.str("label")?.takeIf { it.isNotBlank() } ?: payee).take(40)
            val tag = "snack_stop_" + name.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").take(30).trim('_')
            for (wd in 0..6) {
                val hits = days.keys.filter { weekday(it) == wd }.sorted()
                if (hits.size < 2) continue
                val opp = opportunities(first, today, wd, hits.size)
                val times = hits.flatMap { d -> days.getValue(d).map { TimeUtil.localTime(TimeUtil.parse(it.str("occurred_at")!!), store.tz).let { t -> t.hour * 60 + t.minute } } }
                val typical = hhmm(median(times))
                val conf = wilsonLower(hits.size, opp)
                val weeks = hits.map(::isoWeek).toSet().size
                val active = hits.size >= MIN_SUPPORT && weeks >= MIN_SUPPORT && conf >= MIN_WILSON
                results += pattern(
                    "weekday_payee", "weekday_payee:$payee:$wd",
                    "Small payments to $name on ${TimeUtil.WEEKDAYS[wd]}s around $typical (${hits.size} of $opp) - likely a snack stop",
                    hits.size, opp - hits.size, weeks, conf, active,
                    JSONObject().put("tag", tag).put("weekday", wd).put("typical_time", typical).put("payee", name),
                )
            }
        }

        // ---- rarely logged meal slots
        val activeDays = store.inboundTimesSince("${TimeUtil.daysAgo(today, 14)}T00:00:00Z").map { store.date(TimeUtil.parse(it)) }.toSet()
        if (activeDays.size >= 5) {
            for (slot in Habits.MAIN_MEALS) {
                val logged = activeDays.count { slot in Habits.dayCoverage(store, it).logged }
                if (logged.toDouble() / activeDays.size < 0.3) {
                    results += pattern(
                        "meal_gap", "meal_gap:$slot", "$slot is rarely logged ($logged of ${activeDays.size} active days)",
                        activeDays.size - logged, logged, activeDays.map(::isoWeek).toSet().size, 1 - logged.toDouble() / activeDays.size, true,
                        JSONObject().put("slot", slot),
                    )
                }
            }
        }

        results.forEach { store.upsertPattern(now, it) }
        val fresh = results.map { it.getString("key") }.toSet()
        store.patterns("active").filter { it.str("key") !in fresh }.forEach { store.setPatternStatus(now, it.long("id")!!, "candidate") }
        return results
    }

    data class Predicted(val patternId: Long, val tag: String, val typicalTime: String, val claim: String, val kind: String)

    fun predictedContexts(store: Store, day: String): List<Predicted> {
        val wd = weekday(day)
        return (store.patterns("active", "weekday_context") + store.patterns("active", "weekday_payee")).mapNotNull { p ->
            val data = JSONObject(p.str("data_json") ?: "{}")
            val t = data.optString("typical_time").takeIf { it.isNotBlank() && it != "null" }
            if (data.optInt("weekday", -1) == wd && t != null) Predicted(p.long("id")!!, data.getString("tag"), t, p.str("claim")!!, p.str("kind")!!) else null
        }
    }

    fun lapsePatternFor(store: Store, tag: String): JSONObject? = store.patterns("active", "context_lapse").firstNotNullOfOrNull { p ->
        val data = JSONObject(p.str("data_json") ?: "{}")
        if (data.optString("tag") == tag) JSONObject().put("pattern_id", p.long("id")).put("claim", p.str("claim"))
            .put("common_items", data.optJSONArray("common_items") ?: JSONArray()) else null
    }
}
