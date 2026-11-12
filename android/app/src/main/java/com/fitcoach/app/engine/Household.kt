package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.core.numOrNull
import com.fitcoach.app.data.Row
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.dbl
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Facts for food and training suggestions (D-045). Code only counts: what is at home and how fast it goes, the protein
 * gap today, and the logged training history. The agent decides what to suggest, when to send a grocery list and what
 * the next workout target is.
 */
object Pantry {
    fun fmt(q: Double?): String = if (q == null) "?" else if (q % 1.0 == 0.0) q.toLong().toString() else "%.1f".format(java.util.Locale.ENGLISH, q)

    /** Each item with its stock, last purchase and, with at least 2 days of use in 14, its daily use and days left. */
    fun context(store: Store, today: String): JSONArray {
        val log = store.pantryLog(TimeUtil.daysAgo(today, 14)).groupBy { it.str("item_key") }
        return JSONArray(store.pantry().map { r ->
            val qty = r.dbl("quantity") ?: 0.0
            val entries = log[r.str("item_key")].orEmpty()
            val used = entries.filter { it.str("reason") == "used" }
            val o = JSONObject().put("item", r.str("name")).put("left", "${fmt(qty)} ${r.str("unit") ?: ""}".trim())
                .put("last_bought", entries.lastOrNull { it.str("reason") == "bought" }?.str("local_date") ?: "not in the last 14 days")
            if (used.map { it.str("local_date") }.distinct().size >= 2) {
                val span = ChronoUnit.DAYS.between(LocalDate.parse(used.first().str("local_date")), LocalDate.parse(today)) + 1
                val perDay = used.sumOf { -(it.dbl("change") ?: 0.0) } / span
                o.put("used_per_day", fmt(Math.round(perDay * 10) / 10.0))
                if (perDay > 0) o.put("days_left", fmt(Math.round(qty / perDay * 10) / 10.0))
            } else o.put("used_per_day", "not enough data yet")
            o
        })
    }
}

object Training {
    /** Per exercise: the last 4 sessions as logged, days since the last one and sessions in 6 weeks. */
    fun context(store: Store, today: String): JSONObject {
        val out = JSONObject()
        store.exerciseSetsSince(TimeUtil.daysAgo(today, 42)).groupBy { it.str("exercise")!! }.forEach { (exercise, rows) ->
            val sessions = rows.groupBy { it.str("local_date")!! }.toSortedMap()
            out.put(exercise, JSONObject()
                .put("last_sessions", JSONArray(sessions.entries.toList().takeLast(4).map { (day, sets) -> "$day: " + sets.joinToString(", ", transform = ::fmt) }))
                .put("days_since_last", ChronoUnit.DAYS.between(LocalDate.parse(sessions.lastKey()), LocalDate.parse(today)))
                .put("sessions_6_weeks", sessions.size))
        }
        return out
    }

    private fun fmt(r: Row): String {
        val sets = r.long("sets") ?: 1
        val body = r.long("reps")?.let { "${sets}x$it" } ?: "${sets}x${r.long("seconds")}s"
        return body + (r.dbl("weight_kg")?.let { " @${Pantry.fmt(it)} kg" } ?: "")
    }
}

object Meals {
    /**
     * What today's food adds up to against the target. Without a personal target, a labelled reference range
     * (1.6-2.0 g protein per kg) so a next-meal suggestion can still aim at a real number.
     */
    fun today(t: Progress.DayTotals, profile: JSONObject, weightKg: Double?): JSONObject {
        val protein = (t.proteinLow + t.proteinHigh) / 2
        val kcal = (t.kcalLow + t.kcalHigh) / 2
        val o = JSONObject()
            .put("protein_eaten_g", JSONArray(listOf(t.proteinLow.roundToInt(), t.proteinHigh.roundToInt())))
            .put("kcal_eaten", JSONArray(listOf(t.kcalLow.roundToInt(), t.kcalHigh.roundToInt())))
            .put("items_without_estimate", t.itemsUnknown)
        val target = profile.numOrNull("protein_target_g")
        when {
            target != null -> o.put("protein_target_g", target.roundToInt()).put("protein_still_needed_g", max(0, (target - protein).roundToInt()))
            weightKg != null -> o.put("protein_reference_g", JSONArray(listOf((1.6 * weightKg).roundToInt(), (2.0 * weightKg).roundToInt())))
                .put("protein_still_needed_g", max(0, (1.6 * weightKg - protein).roundToInt()))
                .put("reference_basis", "no personal target: 1.6-2.0 g/kg body weight, the usual range for fat loss with strength training")
            else -> o.put("protein_target_g", "unknown (no target and no weight)")
        }
        profile.numOrNull("kcal_target")?.let { o.put("kcal_target", it.roundToInt()).put("kcal_left", (it - kcal).roundToInt()) }
        return o
    }
}
