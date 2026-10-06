package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.core.numOrNull
import com.fitcoach.app.core.objects
import com.fitcoach.app.core.strOrNull
import com.fitcoach.app.data.Row
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.dbl
import com.fitcoach.app.data.str
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Port of coach/analytics/habits.py: habitual meals and logging coverage. */
object Habits {
    val MAIN_MEALS = listOf("breakfast", "lunch", "dinner")
    const val MIN_OCCURRENCES = 3
    const val LOOKBACK_DAYS = 28L

    data class MealItem(val name: String, val quantity: Double?, val unit: String?)

    data class HabitualMeal(val slot: String, val items: List<MealItem>, val occurrences: Int, val daysObserved: Int) {
        fun describe(): String = items.joinToString(", ") { i ->
            val q = i.quantity?.let { if (it == Math.floor(it)) "${it.toLong()} " else "$it " } ?: ""
            "$q${i.name}".trim()
        }
    }

    private fun signature(rows: List<Row>): Set<String> = rows.map { it.str("food_key") ?: it.str("item_name")!!.lowercase() }.toSet()

    fun habitualMeals(store: Store, today: String, weekend: Boolean? = null): Map<String, HabitualMeal> {
        val rows = store.foodBetween(TimeUtil.daysAgo(today, LOOKBACK_DAYS), TimeUtil.daysAgo(today, 1))
        val byDaySlot = LinkedHashMap<Pair<String, String>, MutableList<Row>>()
        rows.forEach { r ->
            val slot = r.str("meal_slot")
            if (slot !in MAIN_MEALS || r.str("source") != "user_entered") return@forEach
            if (weekend != null && TimeUtil.isWeekend(r.str("local_date")!!) != weekend) return@forEach
            byDaySlot.getOrPut(r.str("local_date")!! to slot!!) { mutableListOf() } += r
        }
        val declared = store.profile().optJSONObject("usual_meals") ?: JSONObject()
        val result = LinkedHashMap<String, HabitualMeal>()
        for (slot in MAIN_MEALS) {
            val instances = byDaySlot.filterKeys { it.second == slot }.map { it.key.first to it.value }
            val counts = instances.groupingBy { signature(it.second) }.eachCount()
            val top = counts.maxByOrNull { it.value }
            if (top == null || top.value < MIN_OCCURRENCES) {
                val items = declared.optJSONArray(slot)?.objects()?.mapNotNull { o ->
                    o.strOrNull("name")?.let { MealItem(it, o.numOrNull("quantity"), o.strOrNull("unit")) }
                }
                if (!items.isNullOrEmpty()) result[slot] = HabitualMeal(slot, items, 0, 0)
                continue
            }
            val latest = instances.filter { signature(it.second) == top.key }.maxByOrNull { it.first }!!.second
            result[slot] = HabitualMeal(slot, latest.map { MealItem(it.str("item_name")!!, it.dbl("quantity"), it.str("unit")) }, top.value, instances.size)
        }
        return result
    }

    data class Coverage(val logged: Map<String, String>, val missing: List<String>, val itemsLogged: Int, val hasUnslotted: Boolean) {
        fun asJson(): JSONObject = JSONObject().put("logged", JSONObject(logged)).put("missing", JSONArray(missing))
            .put("items_logged", itemsLogged).put("has_unslotted_items", hasUnslotted)
    }

    fun dayCoverage(store: Store, day: String): Coverage {
        val rows = store.foodOn(day)
        val slots = LinkedHashMap<String, String>()
        rows.forEach { r -> val s = r.str("meal_slot") ?: "unknown"; if (s in MAIN_MEALS) slots[s] = r.str("source") ?: "user_entered" }
        return Coverage(slots, MAIN_MEALS.filter { it !in slots }, rows.size, rows.any { (it.str("meal_slot") ?: "unknown") == "unknown" })
    }

    fun coverageStats(store: Store, today: String, days: Int = 14): JSONObject {
        val perSlot = MAIN_MEALS.associateWith { 0 }.toMutableMap()
        for (o in 1..days) dayCoverage(store, TimeUtil.daysAgo(today, o.toLong())).logged.keys.forEach { perSlot[it] = perSlot.getValue(it) + 1 }
        val total = perSlot.values.sum()
        return JSONObject().put("days", days).put("meal_coverage_pct", (100.0 * total / (3 * days)).roundToInt())
            .put("per_slot_days", JSONObject(perSlot as Map<*, *>)).put("least_logged_slot", perSlot.minByOrNull { it.value }!!.key)
    }

    /** Recorded intake + the user's usual meal for each main meal with no record. Assumed meals are never stored. */
    fun estimatedDayIntake(store: Store, day: String, table: FoodTable): JSONObject {
        val rows = store.foodOn(day)
        val low = rows.sumOf { it.dbl("kcal_low") ?: 0.0 }
        val high = rows.sumOf { it.dbl("kcal_high") ?: 0.0 }
        val unknownItems = rows.count { it.dbl("kcal_low") == null }
        val cov = dayCoverage(store, day)
        val habits = habitualMeals(store, day, TimeUtil.isWeekend(day)).ifEmpty { habitualMeals(store, day) }
        val assumed = mutableListOf<String>(); val unknownSlots = mutableListOf<String>()
        var aLow = 0.0; var aHigh = 0.0
        for (slot in cov.missing) {
            val meal = habits[slot]
            if (meal == null) { unknownSlots += slot; continue }
            meal.items.forEach { i ->
                val e = table.estimate(i.name, i.quantity, i.unit)
                if (e.kcalLow != null) { aLow += e.kcalLow; aHigh += e.kcalHigh ?: e.kcalLow }
            }
            assumed += slot
        }
        return JSONObject()
            .put("recorded_kcal_range", JSONArray(listOf(low.roundToInt(), high.roundToInt())))
            .put("assumed_usual_slots", JSONArray(assumed))
            .put("assumed_kcal_range", JSONArray(listOf(aLow.roundToInt(), aHigh.roundToInt())))
            .put("total_kcal_range", if (unknownSlots.isEmpty()) JSONArray(listOf((low + aLow).roundToInt(), (high + aHigh).roundToInt())) else JSONObject.NULL)
            .put("unknown_slots", JSONArray(unknownSlots))
            .put("unestimated_items", unknownItems)
            .put("label", if (assumed.isNotEmpty()) "estimate: recorded + assumed usual meals" else "estimate: recorded only")
    }
}

/** Port of coach/analytics/progress.py: trend weight and day totals. */
object Progress {
    const val EWMA_ALPHA = 0.1
    const val MIN_DAYS_FOR_TARGET_CHANGE = 14
    const val MIN_WEIGHINS_FOR_TREND = 5
    const val SAFE_LOSS_RATE_MAX_PCT_PER_WEEK = 1.0

    data class WeightTrend(
        var latestRaw: Double? = null, var latestTrend: Double? = null, var avg7d: Double? = null,
        var weeklyRateKg: Double? = null, var weeklyRatePct: Double? = null, var daysCovered: Int = 0,
        var weighIns: Int = 0, var status: String = "insufficient_data", val notes: MutableList<String> = mutableListOf(),
    ) {
        fun asJson(): JSONObject = JSONObject().put("latest_raw", latestRaw ?: JSONObject.NULL).put("latest_trend", latestTrend ?: JSONObject.NULL)
            .put("avg_7d", avg7d ?: JSONObject.NULL).put("weekly_rate_kg", weeklyRateKg ?: JSONObject.NULL)
            .put("weekly_rate_pct", weeklyRatePct ?: JSONObject.NULL).put("days_covered", daysCovered)
            .put("weigh_ins", weighIns).put("status", status).put("notes", JSONArray(notes))
    }

    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    fun ewma(values: List<Double>, alpha: Double = EWMA_ALPHA): List<Double> {
        if (values.isEmpty()) return emptyList()
        val out = mutableListOf(values[0])
        values.drop(1).forEach { out += out.last() + alpha * (it - out.last()) }
        return out
    }

    private fun slopePerDay(points: List<Pair<Int, Double>>): Double? {
        if (points.size < 2) return null
        val mx = points.sumOf { it.first.toDouble() } / points.size
        val my = points.sumOf { it.second } / points.size
        val denom = points.sumOf { (it.first - mx) * (it.first - mx) }
        if (denom == 0.0) return null
        return points.sumOf { (it.first - mx) * (it.second - my) } / denom
    }

    fun weightTrend(series: List<Pair<String, Double>>, today: String): WeightTrend {
        val r = WeightTrend(weighIns = series.size)
        if (series.isEmpty()) { r.notes += "No weight data."; return r }
        val first = LocalDate.parse(series.first().first)
        val todayD = LocalDate.parse(today)
        r.daysCovered = ChronoUnit.DAYS.between(first, todayD).toInt() + 1
        r.latestRaw = series.last().second
        val byDay = series.associate { LocalDate.parse(it.first) to it.second }
        var last = series.first().second
        val filled = (0..ChronoUnit.DAYS.between(first, LocalDate.parse(series.last().first)).toInt()).map { o ->
            last = byDay[first.plusDays(o.toLong())] ?: last; last
        }
        val trend = ewma(filled)
        r.latestTrend = r2(trend.last())
        val last7 = series.filter { ChronoUnit.DAYS.between(LocalDate.parse(it.first), todayD) < 7 }.map { it.second }
        r.avg7d = if (last7.isEmpty()) null else r2(last7.average())
        slopePerDay(trend.mapIndexed { i, t -> i to t }.takeLast(14))?.let { s ->
            r.weeklyRateKg = r2(s * 7); r.weeklyRatePct = r2(s * 7 / trend.last() * 100)
        }
        if (r.weighIns < MIN_WEIGHINS_FOR_TREND || r.daysCovered < 7) {
            r.status = "insufficient_data"
            r.notes += "Only ${r.weighIns} weigh-ins over ${r.daysCovered} days; trend not reliable yet."
            return r
        }
        val rate = r.weeklyRatePct ?: 0.0
        r.status = when {
            rate < -SAFE_LOSS_RATE_MAX_PCT_PER_WEEK -> { r.notes += "Losing faster than 1% of bodyweight per week; consider eating a bit more."; "losing_too_fast" }
            rate <= -0.15 -> "losing"
            rate >= 0.15 -> "gaining"
            else -> "stable"
        }
        return r
    }

    fun canAdjustTargets(t: WeightTrend, foodLoggedDays14: Int): Pair<Boolean, String> = when {
        t.daysCovered < MIN_DAYS_FOR_TARGET_CHANGE || t.weighIns < 8 -> false to "Not enough weight data (need ~14 days and 8+ weigh-ins)."
        foodLoggedDays14 < 8 -> false to "Food data too incomplete to know whether intake or tracking is the issue."
        else -> true to "Sufficient data."
    }

    data class DayTotals(
        var kcalLow: Double = 0.0, var kcalHigh: Double = 0.0, var proteinLow: Double = 0.0, var proteinHigh: Double = 0.0,
        var itemsLogged: Int = 0, var itemsUnknown: Int = 0, var steps: Double? = null, var stepsSource: String = "unknown",
        var activeMinutesReported: Double = 0.0,
    ) {
        fun asContext(): JSONObject {
            val food = if (itemsLogged > 0) JSONObject()
                .put("kcal_range", JSONArray(listOf(kcalLow.roundToInt(), kcalHigh.roundToInt())))
                .put("protein_g_range", JSONArray(listOf(proteinLow.roundToInt(), proteinHigh.roundToInt())))
                .put("items_logged", itemsLogged).put("items_without_estimate", itemsUnknown)
                .put("status", "estimated (only what the user mentioned; unlogged meals unknown)")
            else JSONObject().put("status", "unknown (nothing logged)")
            return JSONObject().put("food", food)
                .put("steps", if (steps != null) JSONObject().put("value", steps).put("source", stepsSource) else JSONObject().put("status", "unknown"))
                .put("reported_activity_minutes", activeMinutesReported)
        }
    }

    fun dayTotals(food: List<Row>, activity: List<Row>, hcSteps: Double?): DayTotals {
        val t = DayTotals()
        food.forEach { r ->
            t.itemsLogged++
            val kl = r.dbl("kcal_low")
            if (kl == null) { t.itemsUnknown++; return@forEach }
            t.kcalLow += kl; t.kcalHigh += r.dbl("kcal_high") ?: kl
            t.proteinLow += r.dbl("protein_low") ?: 0.0; t.proteinHigh += r.dbl("protein_high") ?: 0.0
        }
        var reported = 0.0
        activity.forEach { r -> t.activeMinutesReported += r.dbl("duration_min") ?: 0.0; reported += r.dbl("steps") ?: 0.0 }
        if (hcSteps != null) { t.steps = hcSteps; t.stepsSource = "health_connect" }
        else if (reported > 0) { t.steps = reported; t.stepsSource = "user_reported" }
        return t
    }
}
