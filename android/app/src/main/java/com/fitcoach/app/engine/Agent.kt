package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.core.strings
import com.fitcoach.app.data.Row
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.dbl
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import com.fitcoach.app.llm.LlmException
import com.fitcoach.app.llm.LlmProvider
import com.fitcoach.app.llm.LlmRequest
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** What the agent decided on one wake-up. */
data class AgentDecision(
    val act: Boolean, val reason: String, val intent: String, val commitmentId: Long?, val message: String?,
    val quickReplies: List<String>, val channel: String, val expectedOutcome: String?, val nextCheckMinutes: Int, val nextCheckReason: String,
) {
    fun asJson(): JSONObject = JSONObject().put("act", act).put("reason", reason).put("intent", intent)
        .put("commitment_id", commitmentId ?: JSONObject.NULL).put("message", message ?: JSONObject.NULL)
        .put("quick_replies", JSONArray(quickReplies)).put("channel", channel).put("expected_outcome", expectedOutcome ?: JSONObject.NULL)
        .put("next_check_minutes", nextCheckMinutes).put("next_check_reason", nextCheckReason)

    companion object {
        fun parse(o: JSONObject): AgentDecision = AgentDecision(
            act = o.optBoolean("act", false),
            reason = o.optString("reason").take(400),
            intent = o.optString("intent").ifBlank { "check_in" }.take(40),
            commitmentId = if (o.has("commitment_id") && !o.isNull("commitment_id")) o.optLong("commitment_id") else null,
            message = o.optString("message").trim().takeIf { it.isNotEmpty() && it != "null" },
            quickReplies = (o.optJSONArray("quick_replies") ?: JSONArray()).strings().map { it.trim().take(40) }.filter { it.isNotEmpty() }.take(3),
            channel = if (o.optString("channel") == "telegram") "telegram" else "notification",
            expectedOutcome = o.optString("expected_outcome").takeIf { it.isNotBlank() && it != "null" }?.take(200),
            nextCheckMinutes = o.optInt("next_check_minutes", 120).coerceIn(15, 720),
            nextCheckReason = o.optString("next_check_reason").take(200),
        )
    }
}

/**
 * Retrieve -> reason -> decide, and learn. The agent sees one compact SITUATION built from the database and decides
 * whether to contact the person now, what to say, through which channel, and when to look again. It also reflects
 * daily on what its messages achieved and keeps the lessons as memory (coach insights).
 */
class Agent(private val store: Store, private val llm: LlmProvider, private val prompts: Prompts) {

    private val learner = SlotLearner(store)

    fun situation(
        now: Instant, context: JSONObject, newObs: List<Row>, eng: Engagement, limits: JSONObject, channels: JSONObject, weekly: JSONObject,
    ): JSONObject {
        val today = store.date(now)
        val todayD = LocalDate.parse(today)
        val lt = TimeUtil.localTime(now, store.tz)
        val commitments = JSONArray(store.activeCommitments().map { c ->
            c.asContext()
                .put("scheduled_today", Policy.isScheduledOn(c, todayD))
                .put("status_today", store.commitmentOutcome(c.id, today) ?: "not reported")
                .put("window_now", c.windowStart != null && c.windowEnd != null && TimeUtil.inWindow(lt, TimeUtil.hhmm(c.windowStart), TimeUtil.hhmm(c.windowEnd)))
                .put("history_14d", Policy.analyseCommitment(store, c, today).asJson())
                .put("timing_evidence", learner.estimates(c.id))
        })
        val knownSteps = (1..14).mapNotNull { store.healthSum("steps", TimeUtil.daysAgo(today, it.toLong())) }.filter { it > 0 }
        val open = store.openInterventions().filter { it.str("kind") != "contextual" }.map {
            JSONObject().put("message", it.str("message_text")).put("minutes_ago", Duration.between(TimeUtil.parse(it.str("sent_at")!!), now).toMinutes())
        }
        return JSONObject()
            .put("now", context.get("local_time"))
            .put("person", JSONObject().put("goal", context.opt("goal")).put("goal_weight_kg", context.opt("goal_weight_kg"))
                .put("coaching_mode", context.opt("coaching_mode")).put("strong_mode_authorized", store.profile().optBoolean("strong_mode_authorized")))
            .put("memory", context.get("memory"))
            .put("new_observations", JSONArray(newObs.map { "${store.fmtLocal(TimeUtil.parse(it.str("observed_at")!!), "HH:mm")} ${it.str("kind")}: ${it.str("summary")}" }))
            .put("today", context.get("today").let { (it as JSONObject).put("typical_daily_steps_14d", if (knownSteps.size >= 3) knownSteps.average().toInt() else "unknown") })
            .put("meal_coverage_today", context.get("meal_coverage_today"))
            .put("usual_meals", context.get("usual_meals"))
            .put("places_today", context.get("places_today"))
            .put("calendar_today", context.get("calendar_today"))
            .put("commitments", commitments)
            .put("patterns", context.get("observed_patterns"))
            .put("predicted_today", JSONArray(Patterns.predictedContexts(store, today).map { "${it.tag} around ${it.typicalTime} (${it.claim})" }))
            .put("open_loops", JSONObject()
                .put("unanswered_messages", JSONArray(open))
                .put("pending_payments_or_orders", context.get("pending_inferred_events"))
                .put("info_gaps", infoGaps(context)))
            .put("recent_interventions", recentInterventions(now))
            .put("engagement", eng.asContext().put("response_by_hour", responseByHour(today)).put("response_by_channel", responseByChannel(today)))
            .put("recent_conversation", JSONArray(store.allMessages(8).map {
                "${store.fmtLocal(TimeUtil.parse(it.str("created_at")!!), "EEE HH:mm")} ${if (it.str("direction") == "in") "user" else "coach"} (${it.str("channel")}): ${it.str("text")?.take(160)}"
            }))
            .put("weight", context.get("weight"))
            .put("last_7_days", context.get("last_7_days"))
            .put("week", weekly)
            .put("channels", channels)
            .put("limits", limits)
            .put("your_last_plan", store.kv("agent_next_check_reason") ?: JSONObject.NULL)
    }

    /** Things the coach cannot infer from data; the agent decides if and when asking is worth it. */
    private fun infoGaps(context: JSONObject): JSONArray {
        val gaps = JSONArray()
        if (context.isNull("goal") && context.isNull("goal_weight_kg")) gaps.put("main goal unknown")
        if ((context.getJSONObject("weight").opt("latest_raw_kg") ?: JSONObject.NULL) == JSONObject.NULL) gaps.put("no weight recorded yet")
        val usual = context.getJSONObject("usual_meals")
        val missing = listOf("breakfast", "lunch", "dinner").filter { !usual.has(it) }
        if (missing.isNotEmpty()) gaps.put("usual ${missing.joinToString("/")} unknown (makes 'as usual' logging impossible)")
        if (context.getJSONObject("memory").getJSONArray("long_term").length() < 3) gaps.put("little known about routine, work and constraints")
        return gaps
    }

    private fun recentInterventions(now: Instant): JSONArray = JSONArray(
        store.query("SELECT * FROM interventions WHERE kind != 'contextual' ORDER BY id DESC LIMIT 10").reversed().map {
            JSONObject().put("sent", store.fmtLocal(TimeUtil.parse(it.str("sent_at")!!), "EEE HH:mm"))
                .put("hours_ago", Duration.between(TimeUtil.parse(it.str("sent_at")!!), now).toHours())
                .put("intent", it.str("intent")).put("channel", it.str("channel")).put("message", it.str("message_text")?.take(140))
                .put("status", it.str("status")).put("reply_after_min", it.dbl("response_minutes") ?: JSONObject.NULL)
                .put("outcome", it.str("outcome") ?: "pending").put("expected", it.str("expected_outcome") ?: JSONObject.NULL)
        },
    )

    /** Learning statistics from the DB: how often the person answers proactive messages, by hour and by channel. */
    fun responseByHour(today: String): JSONObject {
        val rows = store.query("SELECT sent_at, outcome, response_minutes FROM interventions WHERE kind != 'contextual' AND local_date >= ? AND outcome IS NOT NULL",
            TimeUtil.daysAgo(today, 30))
        val out = JSONObject()
        rows.groupBy { TimeUtil.parse(it.str("sent_at")!!).atZone(store.tz).hour / 3 * 3 }.toSortedMap().forEach { (h, list) ->
            val ok = list.count { it.str("outcome") in setOf("answered", "achieved") }
            out.put("%02d-%02d".format(h, h + 3), "$ok/${list.size} answered")
        }
        return out
    }

    private fun responseByChannel(today: String): JSONObject {
        val rows = store.query("SELECT channel, outcome FROM interventions WHERE kind != 'contextual' AND local_date >= ? AND outcome IS NOT NULL",
            TimeUtil.daysAgo(today, 30))
        return JSONObject(rows.groupBy { it.str("channel") ?: "notification" }.mapValues { (_, l) ->
            "${l.count { it.str("outcome") in setOf("answered", "achieved") }}/${l.size} answered"
        })
    }

    /** Reason + decide. Returns null when the LLM is unavailable. An unsafe message is rewritten by the LLM or dropped. */
    suspend fun decide(situation: JSONObject): AgentDecision? {
        var prompt = "SITUATION (computed from the database; trust it):\n" + situation.toString(1)
        repeat(2) {
            val raw = try {
                llm.generate(LlmRequest(prompts.coachSystem + "\n\n" + prompts.agentSystem, prompt, jsonSchema = prompts.agentSchema,
                    temperature = 0.4, purpose = "decide")).json()
            } catch (e: LlmException) {
                return null
            }
            val d = AgentDecision.parse(raw)
            val problems = d.message?.let(Safety::check).orEmpty() + d.quickReplies.flatMap(Safety::check)
            if (!d.act || problems.isEmpty()) return d
            prompt += "\n\nYOUR PREVIOUS MESSAGE WAS REJECTED by the safety/tone check for: ${problems.joinToString("; ")}. Decide again."
        }
        return AgentDecision(false, "message failed the safety check twice; staying silent", "none", null, null, emptyList(),
            "notification", null, 60, "retry after a rejected message")
    }

    /** Learn: rewrite the coach insights from evaluated interventions. Returns the number of insights, or null on failure. */
    suspend fun reflect(now: Instant, interventions: List<Row>, eng: Engagement): Int? {
        val today = store.date(now)
        val input = JSONObject()
            .put("interventions", JSONArray(interventions.map {
                JSONObject().put("sent", store.fmtLocal(TimeUtil.parse(it.str("sent_at")!!), "EEE dd MMM HH:mm")).put("channel", it.str("channel"))
                    .put("intent", it.str("intent")).put("message", it.str("message_text")?.take(160)).put("outcome", it.str("outcome"))
                    .put("reply_after_min", it.dbl("response_minutes") ?: JSONObject.NULL).put("expected", it.str("expected_outcome") ?: JSONObject.NULL)
                    .put("commitment_id", it.long("commitment_id") ?: JSONObject.NULL)
            }))
            .put("response_by_hour", responseByHour(today))
            .put("response_by_channel", responseByChannel(today))
            .put("engagement", eng.asContext())
            .put("commitments", JSONArray(store.activeCommitments().map { c ->
                JSONObject().put("id", c.id).put("title", c.title).put("history_14d", Policy.analyseCommitment(store, c, today).asJson())
            }))
            .put("current_insights", JSONArray(store.activeFacts(now).filter { it.str("category") == "coach_insight" }.map {
                JSONObject().put("key", it.str("key")).put("insight", it.str("value"))
            }))
        val raw = try {
            llm.generate(LlmRequest(prompts.reflectSystem, input.toString(1), jsonSchema = prompts.reflectSchema, temperature = 0.2, purpose = "reflect")).json()
        } catch (e: LlmException) {
            return null
        }
        val insights = (raw.optJSONArray("insights") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            .filter { it.optString("insight").isNotBlank() && Safety.ok(it.optString("insight")) }.take(8)
        store.transaction {
            val keep = mutableSetOf<String>()
            insights.forEach { i ->
                val key = i.optString("key").ifBlank { "insight_${keep.size}" }
                keep += com.fitcoach.app.data.normalizeTag(key)
                store.upsertFact(now, "coach_insight", key, "${i.optString("insight")} (evidence: ${i.optString("evidence")})".take(300),
                    "agent_reflection", i.optDouble("confidence", 0.6).coerceIn(0.0, 1.0))
            }
            // Insights the reflection dropped are no longer believed.
            store.activeFacts().filter { it.str("category") == "coach_insight" && it.str("key") !in keep }
                .forEach { store.exec("UPDATE facts SET superseded_by = 0 WHERE id = ?", it.long("id")) }
        }
        store.logDecision(now, "reflection_summary", raw.optString("summary").take(300))
        return insights.size
    }
}
