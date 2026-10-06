package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.core.strOrNull
import com.fitcoach.app.data.dbl
import com.fitcoach.app.data.str
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/** Everything the dashboard shows, read from the database (no demo values). */
data class DashboardData(
    val goal: String?,
    val goalWeightKg: Double?,
    val mode: String,
    val pausedUntil: String?,
    val agentNextCheck: String?,
    val agentPlan: String?,
    val today: List<Pair<String, String>>,
    val weight: List<Pair<String, Double>>,
    val weightTrend: String,
    val steps14: List<Pair<String, Double?>>,
    val kcal14: List<Triple<String, Double?, Double?>>,
    val commitments: List<CommitmentRow>,
    val longTerm: List<String>,
    val temporary: List<String>,
    val insights: List<String>,
    val patterns: List<String>,
    val interventions: List<String>,
    val responseByHour: List<Pair<String, String>>,
    val engagement: String,
    val decisions: List<String>,
    val observations: List<String>,
    val quota: String,
) {
    /** [days]: oldest first; values are done, smaller, skipped, postponed, missed, none or off (not scheduled). */
    data class CommitmentRow(val title: String, val schedule: String, val days: List<String>)
}

object Dashboard {
    fun build(service: CoachService, now: Instant): DashboardData {
        val store = service.store
        val today = store.date(now)
        val profile = store.profile()
        val ctx = service.buildContext(now)
        val t = ctx.getJSONObject("today")
        val food = t.getJSONObject("food")
        val steps = t.getJSONObject("steps")
        val cov = Habits.dayCoverage(store, today)
        val todayRows = mutableListOf<Pair<String, String>>()
        todayRows += "Food (estimated)" to if (food.has("kcal_range")) food.getJSONArray("kcal_range").let { "${it.get(0)}-${it.get(1)} kcal" } +
            food.getJSONArray("protein_g_range").let { ", protein ${it.get(0)}-${it.get(1)} g" } else "nothing logged"
        todayRows += "Meals logged" to (cov.logged.keys.joinToString().ifEmpty { "none" } + if (cov.missing.isNotEmpty()) " · missing ${cov.missing.joinToString()}" else "")
        todayRows += "Steps" to (if (steps.has("value")) "${steps.getDouble("value").roundToInt()}" else "unknown")
        todayRows += "Sleep" to t.opt("sleep_hours_last_night").let { if (it is Number) "$it h" else "unknown" }
        todayRows += "Exercise (observed)" to t.opt("exercise_minutes_observed").let { if (it is Number) "${(it.toDouble()).roundToInt()} min" else "none" }
        ctx.getJSONArray("places_today").let { if (it.length() > 0) todayRows += "Places" to (0 until it.length()).joinToString { i -> it.getString(i) } }
        t.opt("screen_minutes")?.let { if (it is Number) todayRows += "Screen time" to "$it min" }
        todayRows += "Health data" to ctx.getString("health_connect")

        val w = ctx.getJSONObject("weight")
        val weightTrend = if (w.isNull("trend_kg")) "no weight data yet" else
            "trend ${w.get("trend_kg")} kg · ${w.optString("status")}" + (if (!w.isNull("weekly_rate_pct")) " · ${w.get("weekly_rate_pct")} %/week" else "")

        val days14 = (13 downTo 0).map { TimeUtil.daysAgo(today, it.toLong()) }
        val kcal14 = days14.map { d ->
            val rows = store.foodOn(d).filter { it.dbl("kcal_low") != null }
            Triple(d, rows.takeIf { it.isNotEmpty() }?.sumOf { it.dbl("kcal_low")!! }, rows.takeIf { it.isNotEmpty() }?.sumOf { it.dbl("kcal_high") ?: it.dbl("kcal_low")!! })
        }
        val commitments = store.activeCommitments().map { c ->
            val nudged = store.interventionHistory(c.id, days14.first()).filter { it.str("kind") != "contextual" }.mapNotNull { it.str("local_date") }.toSet()
            DashboardData.CommitmentRow(
                c.title,
                listOfNotNull(c.scheduleDays, c.windowStart?.let { "$it-${c.windowEnd}" }, c.triggerTag?.let { "when: $it" }).joinToString(" · "),
                days14.map { d ->
                    when {
                        !Policy.isScheduledOn(c, LocalDate.parse(d)) && c.windowStart != null -> "off"
                        else -> store.commitmentOutcome(c.id, d) ?: if (d in nudged && d < today) "missed" else "none"
                    }
                },
            )
        }
        val memory = service.memory(now)
        fun list(a: org.json.JSONArray, f: (JSONObject) -> String) = (0 until a.length()).map { f(a.getJSONObject(it)) }
        val nextCheck = store.kv("agent_next_check_at")?.let { store.fmtLocal(TimeUtil.parse(it), "EEE HH:mm") }
        val eng = Engagement.compute(store, now, Policy.effectiveBudget(profile))
        return DashboardData(
            goal = profile.strOrNull("goal_text"),
            goalWeightKg = if (profile.isNull("goal_weight_kg")) null else profile.optDouble("goal_weight_kg"),
            mode = profile.optString("coaching_mode", "normal"),
            pausedUntil = profile.strOrNull("paused_until")?.let(TimeUtil::parse)?.takeIf { it.isAfter(now) }?.let { store.fmtLocal(it) },
            agentNextCheck = nextCheck,
            agentPlan = store.kv("agent_next_check_reason"),
            today = todayRows,
            weight = service.weightSeries(TimeUtil.daysAgo(today, 90)),
            weightTrend = weightTrend,
            steps14 = days14.map { it to store.healthSum("steps", it) },
            kcal14 = kcal14,
            commitments = commitments,
            longTerm = list(memory.getJSONArray("long_term")) { "${it.optString("value")}" },
            temporary = list(memory.getJSONArray("temporary")) { "${it.optString("value")} (until ${it.optString("until")})" },
            insights = (0 until memory.getJSONArray("coach_insights").length()).map { memory.getJSONArray("coach_insights").getString(it) },
            patterns = store.patterns(null).take(10).map { "${it.str("claim")} [${it.str("status")}]" },
            interventions = store.query("SELECT * FROM interventions WHERE kind != 'contextual' ORDER BY id DESC LIMIT 15").map {
                val reply = it.dbl("response_minutes")?.let { m -> ", replied after ${m.roundToInt()} min" } ?: ""
                "${store.fmtLocal(TimeUtil.parse(it.str("sent_at")!!), "dd MMM HH:mm")} · ${it.str("channel") ?: "notification"} · ${it.str("intent")} → " +
                    "${it.str("outcome") ?: it.str("status")}$reply\n${it.str("message_text")}"
            },
            responseByHour = service.agent.responseByHour(today).let { o -> o.keys().asSequence().map { it to o.getString(it) }.toList() },
            engagement = "${eng.state} · reply rate ${eng.recentResponseRate ?: "n/a"} · usually replies at ${eng.bestHours.joinToString { "$it:00" }.ifEmpty { "unknown" }}",
            decisions = store.recentDecisions(25).map {
                "${store.fmtLocal(TimeUtil.parse(it.str("created_at")!!), "dd MMM HH:mm")} ${it.str("kind")}: ${it.str("summary")}"
            },
            observations = store.recentObservations(15).map {
                "${store.fmtLocal(TimeUtil.parse(it.str("observed_at")!!), "dd MMM HH:mm")} ${it.str("summary")}"
            },
            quota = service.llm.usage().filterValues { !it.startsWith("0/") }.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "unused today" },
        )
    }
}
