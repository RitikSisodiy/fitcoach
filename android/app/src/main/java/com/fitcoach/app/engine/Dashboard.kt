package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.core.strOrNull
import com.fitcoach.app.data.dbl
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
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
    /** Headline numbers (label, value), all computed from stored data; "no data" when there is none. */
    val kpis: List<Pair<String, String>>,
    val calls: List<String>,
    /** How fresh each data source is (label, last data). */
    val sources: List<Pair<String, String>>,
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
            kpis = kpis(service, now, days14, kcal14, commitments),
            calls = store.query("SELECT * FROM calls ORDER BY id DESC LIMIT 8").map {
                "${store.fmtLocal(TimeUtil.parse(it.str("rang_at")!!), "dd MMM HH:mm")} · ${if (it.long("intervention_id") == null) "you called" else "coach called"} · " +
                    "${it.str("status")}" + (it.str("summary")?.let { s -> "\n$s" } ?: "")
            },
            sources = sources(service, now),
            quota = service.llm.usage().filterValues { !it.startsWith("0/") }.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "unused today" },
        )
    }

    private fun mid(lo: Double?, hi: Double?) = if (lo == null) null else (lo + (hi ?: lo)) / 2

    private fun pct(n: Int, d: Int) = if (d == 0) "no data" else "${n * 100 / d}% ($n/$d)"

    private fun kpis(service: CoachService, now: Instant, days14: List<String>, kcal14: List<Triple<String, Double?, Double?>>,
                     commitments: List<DashboardData.CommitmentRow>): List<Pair<String, String>> {
        val store = service.store
        val today = store.date(now)
        val profile = store.profile()
        val out = mutableListOf<Pair<String, String>>()

        val series = service.weightSeries(TimeUtil.daysAgo(today, 365))
        val goal = if (profile.isNull("goal_weight_kg")) null else profile.optDouble("goal_weight_kg")
        val trend = Progress.ewma(series.map { it.second }).lastOrNull() ?: 0.0
        out += "Goal progress" to when {
            series.isEmpty() -> "no weight data yet"
            goal == null -> "%.1f kg now (trend); no target weight yet".format(Locale.ENGLISH, trend)
            else -> {
                val start = series.first().second
                val done = if (start == goal) 100 else (((start - trend) / (start - goal)) * 100).roundToInt().coerceIn(-100, 100)
                "%.1f → %.1f kg, target %.1f: %d%% of the way, %.1f kg to go".format(Locale.ENGLISH, start, trend, goal, done, abs(trend - goal))
            }
        }

        fun adherence(days: Int): String {
            val cells = commitments.flatMap { it.days.takeLast(days) }.filter { it != "off" }
            val reported = cells.filter { it != "none" }
            return pct(reported.count { it == "done" || it == "smaller" }, reported.size) +
                (cells.count { it == "none" }.takeIf { it > 0 }?.let { " · $it days unreported" } ?: "")
        }
        if (commitments.isNotEmpty()) {
            out += "Adherence, 7 days" to adherence(7)
            out += "Adherence, 14 days" to adherence(14)
        }

        val last7 = days14.takeLast(7)
        val foodDays = last7.map { d -> store.foodOn(d) }.filter { it.isNotEmpty() }
        out += "Food logging, 7 days" to "${foodDays.size}/7 days"
        if (foodDays.isNotEmpty()) {
            val kcal = kcal14.takeLast(7).mapNotNull { mid(it.second, it.third) }.average()
            val protein = foodDays.map { rows -> rows.sumOf { mid(it.dbl("protein_low"), it.dbl("protein_high")) ?: 0.0 } }.average()
            out += "Average on logged days" to "~${kcal.roundToInt()} kcal" + (profile.opt("kcal_target").takeIf { it is Number }?.let { " (target $it)" } ?: "") +
                ", ~${protein.roundToInt()} g protein" + (profile.opt("protein_target_g").takeIf { it is Number }?.let { " (target $it)" } ?: "")
        }

        val steps = days14.map { store.healthSum("steps", it) }
        val now7 = steps.takeLast(7).filterNotNull()
        val prev7 = steps.take(7).filterNotNull()
        out += "Steps, 7-day average" to if (now7.isEmpty()) "no data" else "${now7.average().roundToInt()}" +
            (if (prev7.isNotEmpty()) " (previous week ${prev7.average().roundToInt()})" else "")

        val sent = store.query("SELECT * FROM interventions WHERE kind != 'contextual' AND local_date >= ?", TimeUtil.daysAgo(today, 6))
        val evaluated = sent.filter { it.str("outcome") != null || it.str("status") != "sent" }
        val replies = sent.mapNotNull { it.dbl("response_minutes") }.sorted()
        out += "Coach messages, 7 days" to "${sent.size} sent · answered ${pct(evaluated.count { it.str("outcome") in setOf("answered", "achieved") || it.str("status") == "answered" }, evaluated.size)}" +
            (if (replies.isNotEmpty()) " · median reply ${replies[replies.size / 2].roundToInt()} min" else "")
        val withGoal = sent.filter { it.str("outcome") in setOf("achieved", "not_achieved") }
        out += "Messages that led to action" to pct(withGoal.count { it.str("outcome") == "achieved" }, withGoal.size)

        val decisions = store.query("SELECT kind, COUNT(*) AS n FROM coach_decisions WHERE created_at >= ? AND kind LIKE 'agent_%' GROUP BY kind",
            TimeUtil.iso(TimeUtil.atLocal(TimeUtil.daysAgo(today, 6), "00:00", store.tz))).associate { it.str("kind")!! to (it.long("n") ?: 0L) }
        out += "Agent, 7 days" to "${decisions["agent_act"] ?: 0} acted · ${decisions["agent_silent"] ?: 0} chose silence · " +
            "${decisions["agent_blocked"] ?: 0} blocked by limits · ${decisions["agent_error"] ?: 0} AI errors"
        return out
    }

    private fun sources(service: CoachService, now: Instant): List<Pair<String, String>> {
        val store = service.store
        fun ago(iso: String?): String {
            if (iso == null) return "never"
            val m = java.time.Duration.between(TimeUtil.parse(iso), now).toMinutes()
            return when { m < 60 -> "$m min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
        }
        fun maxOf(sql: String, vararg args: Any?) = store.one(sql, *args)?.str("t")
        return listOf(
            "Background loop" to ago(store.kv("last_tick_at")),
            "Agent evaluated" to ago(store.kv("agent_last_eval_at")),
            "Health Connect" to ago(store.lastHealthSync()),
            "Places" to ago(maxOf("SELECT MAX(occurred_at) AS t FROM context_events WHERE source = 'geofence'")),
            "Food orders / payments" to ago(maxOf("SELECT MAX(occurred_at) AS t FROM inferred_events")),
            "Calendar" to (maxOf("SELECT MAX(local_date) AS t FROM calendar_days") ?: "never"),
            "Screen time" to (maxOf("SELECT MAX(local_date) AS t FROM screen_days") ?: "never"),
            "Telegram (last message from you)" to ago(maxOf("SELECT MAX(created_at) AS t FROM messages WHERE channel = 'telegram' AND direction = 'in'")),
            "AI errors, 24 h" to "${store.one("SELECT COUNT(*) AS n FROM coach_decisions WHERE created_at >= ? AND kind IN ('agent_error','extraction_failed','reply_problem')",
                TimeUtil.iso(now.minusSeconds(86_400)))?.long("n") ?: 0}",
        )
    }
}
