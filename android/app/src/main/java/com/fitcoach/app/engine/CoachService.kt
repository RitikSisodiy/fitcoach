package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.core.strOrNull
import com.fitcoach.app.data.COACHING_MODES
import com.fitcoach.app.data.Row
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.dbl
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import com.fitcoach.app.llm.CapabilityException
import com.fitcoach.app.llm.ChatTurn
import com.fitcoach.app.llm.LlmException
import com.fitcoach.app.llm.LlmProvider
import com.fitcoach.app.llm.MediaPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class Button(val label: String, val data: String)

data class Outbound(val text: String, val buttons: List<Button> = emptyList(), val interventionId: Long? = null, val messageId: Long? = null)

data class Inbound(val text: String, val receivedAt: Instant, val kind: String = "text", val media: List<MediaPart> = emptyList(), val externalId: String? = null)

/**
 * Orchestration layer (port of coach/engine/service.py), plus handlers for on-device signals
 * (place arrivals, activity transitions) that replace the server-side ingest endpoints.
 *
 *   Inbound message -> extract -> validate -> store -> deterministic guidance -> coach reply
 *   Tick            -> housekeeping -> gate -> brain (candidates, LLM choice) -> at most one message
 *   Button          -> record outcome -> update learner
 */
class CoachService(
    val store: Store,
    val llm: LlmProvider,
    val foods: FoodTable,
    prompts: Prompts,
    val learner: SlotLearner = SlotLearner(store),
    private val ignoreAfter: Duration = Duration.ofMinutes(120),
    private val minGap: Duration = Duration.ofMinutes(90),
) {
    private val extractor = Extractor(llm, prompts, foods)
    private val coach = Coach(llm, prompts)
    val brain = Brain(store, llm, prompts, learner)
    private val lock = Mutex()

    companion object {
        const val REVIEW_WEEKDAY = 7 // Sunday (java.time DayOfWeek value)
        const val SNOOZE_MINUTES = 60L
        val MEAL_SLOT_TIMES = mapOf("breakfast" to "09:00", "lunch" to "13:30", "snack" to "17:30", "dinner" to "21:00", "drink" to "16:00")
        val NUDGE_BUTTONS = listOf("Done" to "done", "Smaller version" to "smaller", "Later" to "later", "Skip today" to "skip")
        val FOCUSED_TOPICS = setOf("usual_meals", "goal")
        private val MINUTES_RE = Regex("(\\d+)\\s*(?:min|minute|mins|minutes)", RegexOption.IGNORE_CASE)

        private val USUAL_RE = Regex("\\b(usually|usual|normally|generally|roz|rozana|daily|hamesha|aksar|mostly)\\b", RegexOption.IGNORE_CASE)
        private val MEAL_RE = Regex("\\b(breakfast|nashta|nashte|lunch|dinner|khana|meal|subah|dopahar|raat)\\b", RegexOption.IGNORE_CASE)
        private val MODE_RE = Regex("\\b(mode|gentle|soft|normal|strict|strong|accountab\\w*|push|hard|sakht|sakhti|naram|kam message|less messages|zyada)\\b", RegexOption.IGNORE_CASE)
        private val GOAL_RE = Regex("\\b(kg|kilo|weight|wazan|vajan|lose|loss|gain|fit|fitness|goal|target|patla|fat|belly|pet|muscle|strong|stamina|healthy)\\b", RegexOption.IGNORE_CASE)

        /** Deterministic guard: a focused extraction only runs on a message that is plausibly about that topic. */
        fun looksLikeAnswer(topic: String, text: String): Boolean = when (topic) {
            "usual_meals" -> MEAL_RE.containsMatchIn(text)
            "goal" -> GOAL_RE.containsMatchIn(text)
            else -> false
        }

        fun nudgeButtons(iid: Long) = NUDGE_BUTTONS.map { Button(it.first, "iv:$iid:${it.second}") }
        fun minutesIn(text: String?): Int? = MINUTES_RE.find(text ?: "")?.groupValues?.get(1)?.toInt()
    }

    private fun lt(now: Instant, pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(now.atZone(store.tz))

    // ================================================================ context
    fun buildContext(now: Instant): JSONObject {
        val today = store.date(now)
        val profile = store.profile()
        val totals = Progress.dayTotals(store.foodOn(today), store.activitiesOn(today), store.healthSum("steps", today))
        val trend = Progress.weightTrend(weightSeries(TimeUtil.daysAgo(today, 60)), today)
        val lastSync = store.lastHealthSync()
        val hc = if (lastSync == null) "no data yet" else {
            val h = Duration.between(TimeUtil.parse(lastSync), now).toMinutes() / 60.0
            "last data %.1f h ago".format(Locale.ENGLISH, h) + if (h > 6) " (stale)" else ""
        }
        val sleep = store.healthSum("sleep_min", today)
        val exercise = store.healthSum("exercise", today)
        val todayCtx = totals.asContext()
            .put("sleep_hours_last_night", sleep?.let { Math.round(it / 6) / 10.0 } ?: "unknown")
            .put("exercise_minutes_observed", exercise ?: "unknown")
        store.screenDay(today)?.let { todayCtx.put("screen_minutes", it.long("screen_minutes")) }
        return JSONObject()
            .put("last_7_days", weekSummary(now))
            .put("local_time", lt(now, "EEE yyyy-MM-dd HH:mm"))
            .put("coaching_mode", profile.optString("coaching_mode", "normal"))
            .put("targets", JSONObject().put("kcal", profile.opt("kcal_target")).put("protein_g", profile.opt("protein_target_g")))
            .put("goal_weight_kg", profile.opt("goal_weight_kg"))
            .put("goal", profile.opt("goal_text"))
            .put("known_facts", JSONArray(store.activeFacts().map {
                JSONObject().put("category", it.str("category")).put("key", it.str("key")).put("value", it.str("value")).put("source", it.str("source"))
            }))
            .put("active_commitments", JSONArray(store.activeCommitments().map { it.asContext() }))
            .put("today", todayCtx)
            .put("weight", JSONObject().put("trend_kg", trend.latestTrend).put("latest_raw_kg", trend.latestRaw).put("avg_7d", trend.avg7d)
                .put("weekly_rate_pct", trend.weeklyRatePct).put("status", trend.status).put("notes", JSONArray(trend.notes)))
            .put("health_connect", hc)
            .put("meal_coverage_today", Habits.dayCoverage(store, today).asJson())
            .put("pending_inferred_events", JSONArray(store.pendingInferred(TimeUtil.daysAgo(today, 1)).take(5).map { it.str("summary") }))
            .put("observed_patterns", JSONArray(store.patterns("active").take(8).map { JSONObject().put("id", it.long("id")).put("claim", it.str("claim")) }))
            .put("places_today", JSONArray(store.contextSince(today).filter { it.str("source") == "geofence" }.map { "${it.str("tag")} ${it.str("time_hint") ?: ""}".trim() }))
            .put("calendar_today", store.calendarDay(today) ?: JSONObject.NULL)
            .put("data_labels", "observed = phone/device data; estimated = computed ranges; inferred = from patterns; unknown = no data")
    }

    /** Weight from chat and from Health Connect, one value per day. */
    fun weightSeries(sinceDay: String): List<Pair<String, Double>> {
        val m = LinkedHashMap<String, Double>()
        store.metricSeries("weight_kg", sinceDay).forEach { m[it.first] = it.second }
        store.healthBetween("weight_kg", sinceDay, "9999-12-31").forEach { r -> r.dbl("value")?.let { m.putIfAbsent(r.str("local_date")!!, it) } }
        return m.toSortedMap().toList()
    }

    private fun recentOnboardingTopic(now: Instant): String? =
        store.interventionsSince(TimeUtil.daysAgo(store.date(now), 1)).reversed().firstOrNull {
            (it.str("intent") ?: "").startsWith("onboarding:") && Duration.between(TimeUtil.parse(it.str("sent_at")!!), now) < Duration.ofHours(12)
        }?.str("intent")?.substringAfter(':')

    fun weekSummary(now: Instant): JSONArray {
        val today = store.date(now)
        val out = JSONArray()
        for (i in 1..7) {
            val d = TimeUtil.daysAgo(today, i.toLong())
            val rows = store.foodOn(d)
            val e = JSONObject().put("date", d).put("weekday", LocalDate.parse(d).dayOfWeek.name.take(3).lowercase())
            if (rows.isNotEmpty()) {
                e.put("food", JSONArray(rows.take(8).map { it.str("item_name") }))
                e.put("kcal_recorded", JSONArray(listOf(rows.sumOf { it.dbl("kcal_low") ?: 0.0 }.roundToInt(), rows.sumOf { it.dbl("kcal_high") ?: 0.0 }.roundToInt())))
            }
            e.put("meals_missing", JSONArray(Habits.dayCoverage(store, d).missing))
            store.healthSum("steps", d)?.let { e.put("steps", it.toInt()) }
            store.healthSum("sleep_min", d)?.let { e.put("sleep_h", Math.round(it / 6) / 10.0) }
            val outcomes = store.query(
                "SELECT c.title AS title, l.outcome AS outcome FROM commitment_log l JOIN commitments c ON c.id = l.commitment_id WHERE l.local_date = ?", d,
            )
            if (outcomes.isNotEmpty()) e.put("commitments", JSONObject(outcomes.associate { it.str("title") to it.str("outcome") }))
            weightSeries(d).firstOrNull()?.takeIf { it.first == d }?.let { e.put("weight_kg", it.second) }
            val places = store.contextSince(d).filter { it.str("local_date") == d && it.str("source") == "geofence" }.mapNotNull { it.str("tag") }.distinct()
            if (places.isNotEmpty()) e.put("places", JSONArray(places))
            out.put(e)
        }
        return out
    }

    private fun history(limit: Int = 10): List<ChatTurn> =
        store.recentMessages(limit).map { ChatTurn(if (it.str("direction") == "in") "user" else "assistant", it.str("text") ?: "") }

    // ========================================================= inbound message
    suspend fun handleMessage(msg: Inbound): List<Outbound> = lock.withLock {
        val now = msg.receivedAt
        if (msg.externalId != null && store.messageExists(msg.externalId)) return@withLock emptyList()
        val hist = history()
        val storedText = msg.text.ifBlank { "[${msg.kind}]" }
        val commitments = store.activeCommitments()
        val openNudges = JSONArray(store.openInterventions().map {
            JSONObject().put("intervention_id", it.long("id")).put("commitment_id", it.long("commitment_id")).put("text", it.str("message_text"))
        })
        val extraction = try {
            extractor.extract(
                msg.text, msg.media, store.knownTags(), JSONArray(commitments.map { it.asContext() }), openNudges,
                lt(now, "EEE HH:mm"), commitments.map { it.id }.toSet(),
                JSONArray(store.pendingInferred().map {
                    JSONObject().put("inferred_id", it.long("id")).put("summary", it.str("summary")).put("when", store.fmtLocal(TimeUtil.parse(it.str("occurred_at")!!)))
                }),
                JSONArray(store.patterns("active").take(10).map { JSONObject().put("pattern_id", it.long("id")).put("claim", it.str("claim")) }),
                JSONArray(store.foodOn(store.date(now)).map {
                    JSONObject().put("item", it.str("item_name")).put("quantity", it.dbl("quantity")).put("unit", it.str("unit")).put("meal", it.str("meal_slot"))
                }),
            ).first
        } catch (e: CapabilityException) {
            store.addMessage(now, "in", msg.kind, storedText, msg.externalId)
            store.logDecision(now, "capability_fallback", "Media not supported by provider; asked for text.")
            return@withLock listOf(send(now, "I can't process that kind of attachment right now. Can you type it in a line?"))
        } catch (e: LlmException) {
            store.logDecision(now, "extraction_failed", "Extraction failed: ${e.message}")
            Extraction().also { it.dropped += "extraction failed" }
        }

        // Focused extraction when the user answers the coach's question, or volunteers their usual meals unprompted.
        val asked = recentOnboardingTopic(now)?.takeIf { looksLikeAnswer(it, msg.text) }
            ?: "usual_meals".takeIf { looksLikeAnswer(it, msg.text) && USUAL_RE.containsMatchIn(msg.text) }
        if (asked in FOCUSED_TOPICS && msg.text.isNotBlank()) {
            val have = extraction.profileUpdates
            val missing = (asked == "usual_meals" && "usual_meals" !in have) || (asked == "goal" && "goal_text" !in have && "goal_weight_kg" !in have)
            if (missing) {
                val extra = try { extractor.focused(asked, msg.text) } catch (e: LlmException) { emptyMap() }
                if (extra.isNotEmpty()) {
                    extraction.profileUpdates.putAll(extra)
                    store.logDecision(now, "focused_extract", "recovered $asked from an answer to the coach's question")
                }
            }
        }

        // A mode change must be asked for in words and actually change something (live runs produced spurious ones).
        extraction.coachingModeRequest?.let { m ->
            if (msg.media.isEmpty() && (!MODE_RE.containsMatchIn(msg.text) || m == store.profile().optString("coaching_mode", "normal"))) {
                extraction.coachingModeRequest = null
                extraction.dropped += "mode request '$m' not stated in the message"
            }
        }

        val outbound = mutableListOf<Outbound>()
        val guidance = store.transaction {
            val mid = store.addMessage(now, "in", msg.kind, storedText, msg.externalId)
            store.markOpenAnswered(now)
            applyExtraction(now, extraction, mid, outbound)
        }
        val data = if (extraction.dataNeeded.isNotEmpty()) runQueries(now, extraction.dataNeeded) else null
        val (reply, violations) = coach.reply(
            msg.text.ifBlank { "(sent a ${msg.kind})" }, buildContext(now), extraction.summary(), hist, guidance, data,
        )
        if (violations.isNotEmpty()) store.logDecision(now, "safety_rewrite", "Reply needed rewrite or fallback.", JSONObject().put("violations", JSONArray(violations)))
        store.logDecision(now, "reply", guidance.joinToString("; ").ifEmpty { "plain acknowledgement" }, JSONObject().put("extraction", extraction.summary()))
        // The reply goes first in the chat; extra outbound items (e.g. confirmation buttons) follow it.
        val replyMsg = send(now, reply)
        val result = listOf(replyMsg) + outbound.map { o -> send(now, o.text, "system", o.buttons, o.interventionId) }
        result
    }

    private fun send(now: Instant, text: String, kind: String = "text", buttons: List<Button> = emptyList(), iid: Long? = null): Outbound {
        val bj = if (buttons.isEmpty()) null else JSONArray(buttons.map { JSONObject().put("label", it.label).put("data", it.data) })
        val mid = store.addMessage(now, "out", kind, text, buttons = bj)
        return Outbound(text, buttons, iid, mid)
    }

    private fun applyExtraction(now: Instant, ex: Extraction, mid: Long, outbound: MutableList<Outbound>): List<String> {
        val guidance = mutableListOf<String>()
        val profile = store.profile()
        val today = store.date(now)

        val unknown = mutableListOf<String>()
        for (f in ex.foods.filter { it.eaten }) {
            val est = foods.estimate(f.name, f.quantity, f.unit, f.estKcal, f.estProtein, f.confidence)
            store.addFood(now, mealTime(now, f.mealSlot), mapOf(
                "meal_slot" to f.mealSlot, "item_name" to f.name, "food_key" to est.foodKey, "quantity" to f.quantity, "unit" to f.unit,
                "kcal_low" to est.kcalLow, "kcal_high" to est.kcalHigh, "protein_low" to est.proteinLow, "protein_high" to est.proteinHigh,
                "nutrition_source" to est.source, "data_status" to if (est.kcalLow != null) "estimated" else "unknown",
                "confidence" to est.confidence, "source_message_id" to mid,
            ))
            if (est.kcalLow == null) unknown += f.name
        }
        if (unknown.isNotEmpty()) guidance += "Could not estimate nutrition for: ${unknown.joinToString(", ")}. Say so honestly; don't guess."

        ex.activities.forEach { a ->
            store.addActivity(now, now, mapOf("kind" to a.kind, "duration_min" to a.durationMin, "steps" to a.steps, "source" to "user_reported",
                "confidence" to a.confidence, "source_message_id" to mid))
        }
        ex.bodyMetrics.forEach { (metric, value) ->
            store.addMetric(now, now, metric, value, "user_reported")
            if (metric == "weight_kg") guidance += "Weight logged. Comment on the TREND (context.weight), not this single reading; daily swings of 1-2 kg are water/food, not fat."
        }
        ex.facts.forEach { store.upsertFact(now, it.category, it.key, it.value, "user_stated", it.confidence, mid) }

        val tagsNow = mutableListOf<String>()
        ex.context.forEach { c ->
            store.addContext(now, c.tag, c.timing, c.description, mid, c.tomorrow, c.timeHint)
            if (c.timing in setOf("now", "planned") && !c.tomorrow) tagsNow += c.tag
        }
        val already = store.interventionsOn(today).filter { it.str("kind") == "contextual" }.mapNotNull { it.long("commitment_id") }.toSet()
        tagsNow.flatMap { store.commitmentsWithTrigger(it) }.distinctBy { it.id }.filter { it.id !in already }.forEach { c ->
            guidance += "The user is entering a situation covered by their own rule (commitment ${c.id}): '${c.userWords ?: c.action}'. " +
                "Remind them of THEIR rule in one line, before the decision."
            store.addIntervention(now, mapOf(
                "commitment_id" to c.id, "kind" to "contextual", "level" to 2, "slot" to TimeUtil.slotOf(TimeUtil.localTime(now, store.tz)),
                "style" to "own_rule_reminder", "version_offered" to c.versions.firstOrNull(), "message_text" to "(in reply) reminder of rule: ${c.action}",
                "reason" to "context tag '${c.triggerTag}' matched an active if-then commitment",
            ))
        }

        ex.commitments.forEach { c ->
            if (c.triggerTag == null && c.kind in setOf("if_then", "precommitment", "boundary")) c.triggerTag = inferTrigger(c, tagsNow)
            val strong = c.strongRequested && profile.optBoolean("strong_mode_authorized", false)
            val cid = store.addCommitment(now, mapOf(
                "kind" to c.kind, "title" to c.title, "trigger_tag" to c.triggerTag, "action" to c.action, "schedule_days" to c.scheduleDays,
                "window_start" to c.windowStart, "window_end" to c.windowEnd, "enforcement" to if (strong) "strong" else "normal",
                "user_words" to c.userWords, "source_message_id" to mid, "activity_kind" to c.activityKind,
            ), c.versions)
            guidance += "Saved commitment $cid: '${c.title}'. Confirm it back in one line" +
                (if (c.windowStart != null) " (check-ins between ${c.windowStart}-${c.windowEnd})" else "") + "."
            if (c.strongRequested && !strong) guidance += "User asked for strong enforcement, but strong accountability mode is not enabled. " +
                "Tell them they can turn it on in Settings > Coaching mode."
        }

        ex.commitmentUpdates.forEach { u ->
            recordOutcome(now, u.commitmentId, u.outcome, "user_reported", u.version, u.reasonCategory)
            guidance += outcomeGuidance(u)
        }
        ex.foodCorrections.forEach { guidance += applyFoodCorrection(now, it) }
        ex.commitmentChanges.forEach { ch ->
            val cid = ch["commitment_id"] as Long
            val values = ch.filterKeys { it != "commitment_id" }
            store.updateCommitment(now, cid, values)
            guidance += "Commitment $cid updated (${values.keys.joinToString()}). Confirm the change in one line."
        }
        ex.inferredConfirmations.forEach { c ->
            val row = store.inferred(c.inferredId) ?: return@forEach
            if (row.str("status") != "pending") return@forEach
            store.resolveInferred(now, c.inferredId, if (c.isFood) "confirmed" else "rejected")
            row.str("payee_key")?.let { store.setPayeeLabel(now, it, c.isFood, c.label) }
            if (c.isFood && ex.foods.isEmpty()) logInferredFood(now, row)
        }
        if (ex.profileUpdates.isNotEmpty()) {
            ex.profileUpdates.forEach { (k, v) ->
                if (k == "usual_meals" && v is JSONObject) {
                    val merged = JSONObject((profile.optJSONObject("usual_meals") ?: JSONObject()).toString())
                    v.keys().forEach { merged.put(it, v.get(it)) }
                    store.setProfile(now, k, merged)
                } else store.setProfile(now, k, v)
            }
            guidance += "Saved to profile: ${ex.profileUpdates.keys.joinToString()}."
        }
        ex.patternFeedback.forEach { (pid, correct) ->
            if (!correct && store.one("SELECT id FROM patterns WHERE id = ?", pid) != null) {
                store.setPatternStatus(now, pid, "rejected")
                guidance += "User said that observed pattern is wrong; it's removed. Acknowledge briefly."
            }
        }
        if (ex.lapse) {
            var line = "Lapse protocol: acknowledge without judgement, no compensation (no skipping meals or extra punishment), name the very next normal action."
            if (ex.allOrNothing) line += " The user shows all-or-nothing thinking: explicitly counter it ('the day isn't ruined; next meal is normal')."
            guidance += line
            store.logDecision(now, "lapse", ex.lapseNote.ifBlank { "lapse reported" }, JSONObject().put("all_or_nothing", ex.allOrNothing))
        }
        ex.pauseDays?.let { d ->
            val until = pause(now, d)
            guidance += "Coach paused until ${store.fmtLocal(until)} as requested. Confirm; mention the Resume button in Settings."
        }
        ex.coachingModeRequest?.let { m ->
            if (m == "strong") {
                guidance += "User wants strong accountability. Ask them to confirm with the button below."
                outbound += Outbound(
                    "Strong accountability: I will push for at least the minimum version and ask for a reason before you drop a commitment. " +
                        "You can turn it off any time in Settings. Enable it?",
                    listOf(Button("Yes, enable", "mode:strong:confirm"), Button("No", "mode:strong:cancel")),
                )
            } else {
                setMode(now, m)
                guidance += "Coaching mode set to $m. Confirm in one line."
            }
        }
        return guidance
    }

    /**
     * A rule like "chai only at chess club" without an explicit trigger: bind it to a situation or saved place whose
     * name appears in the rule's own words (live Gemini runs left the trigger empty).
     */
    private fun inferTrigger(c: ExtractedCommitment, tagsNow: List<String>): String? {
        val words = ExtractionValidator.tokens("${c.title} ${c.action} ${c.userWords ?: ""}").filter { it.length > 2 }.toSet()
        val tags = (tagsNow + store.knownTags()).distinct()
        return tags.firstOrNull { tag -> tag.split('_').filter { it.length > 2 }.let { parts -> parts.isNotEmpty() && parts.all { it in words } } }
            ?: tags.firstOrNull { tag -> tag.split('_').any { it.length > 3 && it in words } }
    }

    private fun outcomeGuidance(u: CommitmentUpdate): String = when (u.outcome) {
        "done", "smaller" -> "Commitment ${u.commitmentId} ${u.outcome}. Acknowledge briefly; a smaller version still counts."
        "postponed" -> "User postponed. Agree on a specific time or the smaller version now; keep it light."
        else -> "Commitment ${u.commitmentId} skipped. " + when (u.reasonCategory) {
            "cannot" -> "Genuinely unable: accept it and help reschedule. No pushing."
            "forgot" -> "Forgot: suggest anchoring it to an existing routine or a better time."
            "dont_want" -> "Doesn't want to: offer the smallest version; ask what makes it unappealing only if this keeps repeating."
            "too_hard" -> "Too hard: offer a smaller version and consider shrinking the default."
            "bad_timing" -> "Timing is the problem: propose a different time."
            else -> "Reason unclear: ask one short question about what got in the way, only if useful."
        }
    }

    private fun recordOutcome(now: Instant, cid: Long, outcome: String, source: String, version: String? = null, reason: String? = null, localDate: String? = null) {
        store.logCommitment(now, cid, outcome, source, version, reason, localDate)
        val status = mapOf("done" to "acted", "smaller" to "smaller", "skipped" to "skipped", "postponed" to "snoozed").getValue(outcome)
        store.openInterventions().filter { it.long("commitment_id") == cid }.forEach { resolveIntervention(now, it, status, reason) }
    }

    private fun resolveIntervention(now: Instant, row: Row, status: String, reason: String? = null) {
        store.updateIntervention(now, row.long("id")!!, status, reason)
        val slot = row.str("slot")
        val cid = row.long("commitment_id")
        if (row.str("kind") != "scheduled" || slot == null || cid == null) return
        if (status in Policy.SUCCESS_STATUSES) learner.update(now, cid, slot, 1.0)
        else if (status in Policy.FAILURE_STATUSES) learner.update(now, cid, slot, 0.0)
    }

    // ======================================================= extraction helpers
    private fun mealTime(now: Instant, slot: String?): Instant {
        val hhmm = MEAL_SLOT_TIMES[slot] ?: return now
        val local = now.atZone(store.tz)
        if (slot == "dinner" && local.hour < 4) return local.minusDays(1).withHour(21).withMinute(0).withSecond(0).withNano(0).toInstant()
        val t = TimeUtil.hhmm(hhmm)
        val cand = local.withHour(t.hour).withMinute(t.minute).withSecond(0).withNano(0)
        return if (!cand.isAfter(local)) cand.toInstant() else now
    }

    private fun applyFoodCorrection(now: Instant, c: FoodCorrection): String {
        val name = c.itemName.lowercase()
        val key = foods.match(c.itemName).first?.key
        val target = store.foodOn(store.date(now)).reversed().firstOrNull {
            it.str("item_name")!!.lowercase() == name || (key != null && it.str("food_key") == key)
        } ?: return "User corrected '${c.itemName}' but it isn't in today's log; say so briefly."
        val id = target.long("id")!!
        if (c.newQuantity == 0.0) { store.deleteFood(id); return "Removed '${target.str("item_name")}' from today's log as corrected." }
        val est = foods.estimate(target.str("item_name")!!, c.newQuantity, target.str("unit"))
        val values = mutableMapOf<String, Any?>("quantity" to c.newQuantity)
        val oldQ = target.dbl("quantity")
        if (est.kcalLow != null && target.str("nutrition_source") == "food_table") {
            values += mapOf("kcal_low" to est.kcalLow, "kcal_high" to est.kcalHigh, "protein_low" to est.proteinLow, "protein_high" to est.proteinHigh)
        } else if (oldQ != null && oldQ > 0 && target.dbl("kcal_low") != null) {
            val factor = c.newQuantity / oldQ
            listOf("kcal_low", "kcal_high", "protein_low", "protein_high").forEach { k -> target.dbl(k)?.let { values[k] = Math.round(it * factor * 10) / 10.0 } }
        }
        store.updateFood(id, values)
        return "Corrected '${target.str("item_name")}' to ${c.newQuantity}. Confirm briefly."
    }

    private fun alreadyLoggedNear(row: Row): Boolean {
        val occurred = TimeUtil.parse(row.str("occurred_at")!!)
        val payment = row.str("kind") == "small_payment"
        val window = if (payment) Duration.ofMinutes(90) else Duration.ofHours(3)
        return store.foodOn(row.str("local_date")!!).any { f ->
            abs(Duration.between(TimeUtil.parse(f.str("occurred_at")!!), occurred).seconds) <= window.seconds &&
                (if (payment) f.str("meal_slot") in setOf("snack", "drink", "unknown") else f.str("meal_slot") in setOf("lunch", "dinner", "unknown"))
        }
    }

    private fun logInferredFood(now: Instant, row: Row, description: String? = null, source: String = "inferred_confirmed", confidence: Double = 0.4): Boolean {
        if (alreadyLoggedNear(row)) {
            store.logDecision(now, "inferred_merged", "inferred ${row.str("kind")} ${row.long("id")} matches food already logged; not double-counted")
            return false
        }
        val payment = row.str("kind") == "small_payment"
        val name = description ?: if (payment) "street snack" else "restaurant meal"
        val est = foods.estimate(name, 1.0, null)
        val occurred = TimeUtil.parse(row.str("occurred_at")!!)
        val slot = if (payment) "snack" else if (occurred.atZone(store.tz).hour < 16) "lunch" else "dinner"
        store.addFood(now, occurred, mapOf(
            "meal_slot" to slot, "item_name" to name, "food_key" to est.foodKey, "quantity" to 1.0, "unit" to null,
            "kcal_low" to est.kcalLow, "kcal_high" to est.kcalHigh, "protein_low" to est.proteinLow, "protein_high" to est.proteinHigh,
            "nutrition_source" to est.source,
            "data_status" to if (est.kcalLow == null) "unknown" else if (source != "inferred_confirmed") "inferred" else "estimated",
            "confidence" to confidence, "source" to source,
        ))
        return true
    }

    // ============================================================ data queries
    fun runQueries(now: Instant, queries: List<DataQuery>): JSONObject {
        val results = JSONObject()
        for (q in queries) {
            val end = TimeUtil.daysAgo(store.date(now), q.offsetDays.toLong())
            val since = TimeUtil.daysAgo(end, (q.days - 1).toLong())
            when (q.query) {
                "food_log" -> {
                    val byDay = JSONObject()
                    store.foodBetween(since, end).forEach { r ->
                        val d = r.str("local_date")!!
                        val e = byDay.optJSONObject(d) ?: JSONObject().put("items", JSONArray()).put("kcal", JSONArray(listOf(0, 0)))
                            .put("protein", JSONArray(listOf(0, 0))).put("unestimated", 0).also { byDay.put(d, it) }
                        e.getJSONArray("items").put(listOfNotNull(r.dbl("quantity")?.toString(), r.str("unit"), r.str("item_name")).joinToString(" ") +
                            " (${r.str("meal_slot")}, ${r.str("source")})")
                        val kl = r.dbl("kcal_low")
                        if (kl == null) e.put("unestimated", e.getInt("unestimated") + 1) else {
                            val k = e.getJSONArray("kcal"); k.put(0, k.getInt(0) + kl.roundToInt()); k.put(1, k.getInt(1) + (r.dbl("kcal_high") ?: kl).roundToInt())
                            val p = e.getJSONArray("protein"); p.put(0, p.getInt(0) + (r.dbl("protein_low") ?: 0.0).roundToInt()); p.put(1, p.getInt(1) + (r.dbl("protein_high") ?: 0.0).roundToInt())
                        }
                    }
                    byDay.keys().asSequence().toList().forEach { d ->
                        byDay.getJSONObject(d).put("meals_missing", JSONArray(Habits.dayCoverage(store, d).missing))
                            .put("estimate_incl_assumed_usual", Habits.estimatedDayIntake(store, d, foods))
                    }
                    results.put("food_log_${q.days}d", if (byDay.length() > 0) byDay else "no food logged in this period")
                }
                "weight" -> {
                    val trend = Progress.weightTrend(weightSeries(TimeUtil.daysAgo(end, 60)), end)
                    results.put("weight", JSONObject().put("readings", JSONArray(weightSeries(since).map { JSONArray(listOf(it.first, it.second)) }))
                        .put("trend_kg", trend.latestTrend).put("weekly_rate_pct", trend.weeklyRatePct).put("status", trend.status))
                }
                "steps", "sleep" -> {
                    val type = if (q.query == "sleep") "sleep_min" else "steps"
                    val known = JSONObject()
                    var sum = 0.0; var n = 0
                    for (i in 0 until q.days) {
                        val d = TimeUtil.daysAgo(end, i.toLong())
                        store.healthSum(type, d)?.let { v ->
                            val shown = if (type == "sleep_min") Math.round(v / 6) / 10.0 else v.roundToInt().toDouble()
                            known.put(d, shown); sum += shown; n++
                        }
                    }
                    results.put(q.query, JSONObject().put("by_day", known).put("unit", if (type == "sleep_min") "hours" else "steps")
                        .put("days_without_data", q.days - n).put("average", if (n > 0) Math.round(sum / n * 10) / 10.0 else "unknown"))
                }
                "commitment_history" -> {
                    val out = JSONObject()
                    store.activeCommitments().filter { q.commitmentId == null || it.id == q.commitmentId }.forEach { c ->
                        out.put(c.title, JSONArray(store.commitmentHistory(c.id, since).map {
                            JSONObject().put("date", it.str("local_date")).put("outcome", it.str("outcome")).put("reason", it.str("reason_category"))
                        }))
                    }
                    results.put("commitment_history", if (out.length() > 0) out else "no commitments")
                }
                "patterns" -> results.put("patterns", JSONArray(store.patterns(null).take(12).map {
                    JSONObject().put("id", it.long("id")).put("claim", it.str("claim")).put("status", it.str("status"))
                }))
                "search_messages" -> q.term?.let { term ->
                    results.put("messages", JSONArray(store.searchMessages(term, 8).map {
                        JSONObject().put("at", store.fmtLocal(TimeUtil.parse(it.str("created_at")!!))).put("text", it.str("text")!!.take(160))
                    }))
                }
            }
        }
        store.logDecision(now, "data_query", "answered ${queries.map { it.query }}")
        return results
    }

    // ================================================================ buttons
    suspend fun handleButton(data: String, now: Instant, messageId: Long? = null): Outbound? = lock.withLock {
        messageId?.let { store.clearButtons(it) }
        val p = data.split(":")
        if (p.size != 3) return@withLock null
        when (p[0]) {
            "rs" -> return@withLock handleReason(p, now)
            "rc", "inf", "re", "pc" -> return@withLock v2Button(p[0], p[1], p[2], now)
            "mode" -> {
                if (p[2] == "confirm") {
                    store.setProfile(now, "strong_mode_authorized", true); setMode(now, "strong")
                    return@withLock send(now, "Strong accountability is on. You can switch back in Settings any time.", "system")
                }
                return@withLock send(now, "Okay, keeping the current mode.", "system")
            }
            "iv" -> {}
            else -> return@withLock null
        }
        val iid = p[1].toLongOrNull() ?: return@withLock null
        val row = store.intervention(iid) ?: return@withLock null
        val action = p[2]
        val commitment = row.long("commitment_id")?.let { store.commitment(it) }
        val status = row.str("status")
        if (action in setOf("done", "smaller") && status in Policy.SUCCESS_STATUSES) return@withLock send(now, "Already noted.", "system")
        val day = row.str("local_date")
        if (action == "done" || action == "smaller") {
            val outcome = action
            val version = if (action == "done" || commitment == null) row.str("version_offered") else commitment.versions.last()
            when {
                commitment != null -> recordOutcome(now, commitment.id, outcome, "button", version, localDate = day)
                status == "sent" -> resolveIntervention(now, row, if (action == "done") "acted" else "smaller")
                else -> store.updateIntervention(now, iid, if (action == "done") "acted" else "smaller")
            }
            return@withLock send(now, if (action == "done") "Logged. Nice." else "Logged the smaller version: $version. That still counts.", "system")
        }
        if (status != "sent") return@withLock send(now, "Already noted.", "system")
        when (action) {
            "later" -> {
                resolveIntervention(now, row, "snoozed")
                commitment?.let { store.logCommitment(now, it.id, "postponed", "button", localDate = day) }
                send(now, "Okay. I'll check once more in about $SNOOZE_MINUTES minutes.", "system")
            }
            "skip" -> {
                resolveIntervention(now, row, "skipped")
                commitment?.let { store.logCommitment(now, it.id, "skipped", "button", localDate = day) }
                send(now, "Noted. What got in the way today?", "system", listOf(
                    Button("Couldn't today", "rs:$iid:cannot"), Button("Forgot", "rs:$iid:forgot"),
                    Button("Didn't feel like it", "rs:$iid:dont_want"), Button("Bad time", "rs:$iid:bad_timing"),
                ))
            }
            else -> null
        }
    }

    private fun v2Button(prefix: String, ident: String, action: String, now: Instant): Outbound? {
        val ref = ident.toLongOrNull() ?: return null
        if (prefix == "inf") {
            val row = store.inferred(ref)
            if (row == null || row.str("status") != "pending") return send(now, "Already noted.", "system")
            val isFood = action == "yes"
            val logged = store.transaction {
                store.resolveInferred(now, ref, if (isFood) "confirmed" else "rejected")
                if (row.str("payee_key") != null && row.str("kind") == "small_payment") store.setPayeeLabel(now, row.str("payee_key")!!, isFood)
                if (isFood) logInferredFood(now, row) else false
            }
            return when {
                isFood && !logged -> send(now, "Got it - that matches what you already told me, so I didn't count it twice.", "system")
                isFood -> send(now, "Logged ${row.str("summary")} as food (rough estimate). Tell me what it was if you want it exact.", "system")
                row.str("payee_key") != null -> send(now, "Got it - I'll ignore payments like that from now on.", "system")
                else -> send(now, "Got it.", "system")
            }
        }
        val row = store.intervention(ref) ?: return null
        if (row.str("status") == "acted" || Duration.between(TimeUtil.parse(row.str("sent_at")!!), now) > Duration.ofHours(24)) {
            return send(now, "Already noted.", "system")
        }
        store.updateIntervention(now, ref, "acted")
        val day = row.str("local_date")!!
        when (prefix) {
            "rc" -> {
                if (action == "usual" || action == "usual_snack") {
                    val habits = Habits.habitualMeals(store, day, TimeUtil.isWeekend(day)).ifEmpty { Habits.habitualMeals(store, day) }
                    val orderSlots = store.pendingInferred(day).filter { it.str("kind") == "food_order" && it.str("local_date") == day }
                        .map { if (TimeUtil.parse(it.str("occurred_at")!!).atZone(store.tz).hour < 16) "lunch" else "dinner" }.toSet()
                    val logged = mutableListOf<String>()
                    store.transaction {
                        for (slot in Habits.dayCoverage(store, day).missing) {
                            val meal = habits[slot] ?: continue
                            if (slot in orderSlots) continue
                            val mt = TimeUtil.atLocal(day, MEAL_SLOT_TIMES.getValue(slot), store.tz)
                            meal.items.forEach { i ->
                                val est = foods.estimate(i.name, i.quantity, i.unit)
                                store.addFood(now, mt, mapOf(
                                    "meal_slot" to slot, "item_name" to i.name, "food_key" to est.foodKey, "quantity" to i.quantity, "unit" to i.unit,
                                    "kcal_low" to est.kcalLow, "kcal_high" to est.kcalHigh, "protein_low" to est.proteinLow, "protein_high" to est.proteinHigh,
                                    "nutrition_source" to est.source, "data_status" to if (est.kcalLow != null) "estimated" else "unknown",
                                    "confidence" to Math.round(est.confidence * 70) / 100.0, "source" to "default_confirmed",
                                ))
                            }
                            logged += "$slot: ${meal.describe()}"
                        }
                        if (action == "usual_snack") {
                            val est = foods.estimate("street snack", 1.0, null)
                            store.addFood(now, TimeUtil.parse(row.str("sent_at")!!), mapOf(
                                "meal_slot" to "snack", "item_name" to "outside snack", "food_key" to est.foodKey, "quantity" to 1.0,
                                "kcal_low" to est.kcalLow, "kcal_high" to est.kcalHigh, "protein_low" to est.proteinLow, "protein_high" to est.proteinHigh,
                                "nutrition_source" to est.source, "data_status" to "estimated", "confidence" to 0.4, "source" to "default_confirmed",
                            ))
                            logged += "snack: outside snack (rough)"
                        }
                    }
                    val text = if (logged.isNotEmpty()) "Logged as usual - " + logged.joinToString("; ") else "Nothing to fill from your usual meals yet"
                    return send(now, "$text. Different? Just tell me.", "system")
                }
                if (action == "offplan") {
                    store.logDecision(now, "lapse", "user marked the day off-plan via recap", JSONObject().put("source", "recap_button"))
                    return send(now, "Okay, noted - one off day doesn't change the trend. Tomorrow starts normal. " +
                        "If you send one line on what it was, the log gets more accurate.", "system")
                }
                return send(now, "Okay, skipped for today.", "system")
            }
            "re" -> return when (action) {
                "pause" -> send(now, "Paused until ${store.fmtLocal(pause(now, 7.0))}. Resume any time in Settings.", "system")
                "off" -> send(now, "Thanks for being straight. No catch-up needed - want me to keep just one small thing for this week? " +
                    "Reply with what feels doable (e.g. 10 min walk).", "system")
                else -> send(now, "Good to hear. I'll keep it light.", "system")
            }
            "pc" -> {
                if (action != "yes") return send(now, "No problem.", "system")
                val intent = row.str("intent") ?: ""
                val tag = if (':' in intent) intent.substringAfter(':') else null
                val rule = row.str("version_offered") ?: "Keep it light"
                val cid = store.addCommitment(now, mapOf("kind" to "precommitment", "title" to rule.take(80), "trigger_tag" to tag,
                    "action" to rule, "enforcement" to "normal", "user_words" to rule), listOf(rule))
                store.logDecision(now, "commitment_from_pattern", "Created commitment $cid for '$tag' from an observed pattern")
                return send(now, "Saved: $rule. I'll remind you when it's relevant.", "system")
            }
        }
        return null
    }

    private fun handleReason(p: List<String>, now: Instant): Outbound? {
        val row = p[1].toLongOrNull()?.let { store.intervention(it) } ?: return null
        val reason = p[2]
        store.updateIntervention(now, row.long("id")!!, row.str("status")!!, reason)
        row.long("commitment_id")?.let { cid ->
            if (!store.setLatestSkipReason(cid, row.str("local_date")!!, reason)) {
                store.logCommitment(now, cid, "skipped", "button", reason = reason, localDate = row.str("local_date"))
            }
        }
        return send(now, when (reason) {
            "cannot" -> "Fair. We'll pick it up tomorrow."
            "forgot" -> "Got it. I'll try a different time so it's easier to remember."
            "dont_want" -> "Understood. Tomorrow I'll offer a smaller default so starting is easier."
            "bad_timing" -> "Got it - I'll shift the timing."
            else -> "Noted."
        }, "system")
    }

    // ============================================================== settings
    fun setMode(now: Instant, mode: String) {
        require(mode in COACHING_MODES) { mode }
        if (mode == "strong" && !store.profile().optBoolean("strong_mode_authorized", false)) throw IllegalStateException("Strong mode requires explicit confirmation")
        store.setProfile(now, "coaching_mode", mode)
        if (mode != "strong") store.setProfile(now, "strong_mode_authorized", false)
        store.logDecision(now, "mode_change", "coaching mode -> $mode")
    }

    fun pause(now: Instant, days: Double): Instant {
        val until = now.plusSeconds((days * 86400).toLong())
        store.setProfile(now, "paused_until", TimeUtil.iso(until))
        store.logDecision(now, "pause", "paused for $days days")
        return until
    }

    fun resume(now: Instant) {
        store.setProfile(now, "paused_until", null)
        store.logDecision(now, "resume", "coach resumed")
    }

    // ===================================================== on-device signals
    /** Geofence transition for a saved place. Arrivals become context events, which drive rules, patterns and nudges. */
    suspend fun onPlaceEvent(tag: String, entered: Boolean, at: Instant, now: Instant) = lock.withLock {
        val today = store.date(at)
        val hint = TimeUtil.fmtHhmm(TimeUtil.localTime(at, store.tz))
        if (entered) {
            val recent = store.contextSince(today).any {
                it.str("tag") == tag && it.str("source") == "geofence" && Duration.between(TimeUtil.parse(it.str("occurred_at")!!), at).abs() < Duration.ofHours(2)
            }
            if (!recent) {
                store.insert("context_events", mapOf("occurred_at" to TimeUtil.iso(at), "local_date" to today, "tag" to tag, "timing" to "now",
                    "description" to "arrived (phone location)", "time_hint" to hint, "source" to "geofence"))
            }
            store.markJob(now, "place_enter", "$tag|${TimeUtil.iso(at)}")
        } else {
            // A stay of 20+ minutes at a gym counts as a workout session (observed).
            val enter = store.contextSince(today).lastOrNull { it.str("tag") == tag && it.str("source") == "geofence" } ?: return@withLock
            val minutes = Duration.between(TimeUtil.parse(enter.str("occurred_at")!!), at).toMinutes()
            if (minutes >= 20 && ("gym" in tag || "fitness" in tag || "yoga" in tag)) {
                store.upsertHealth(now, "exercise", "place|$tag|${enter.str("occurred_at")}", TimeUtil.parse(enter.str("occurred_at")!!), at, at,
                    minutes.toDouble(), JSONObject().put("type", "WORKOUT").put("source", "place:$tag"))
            }
        }
    }

    /** Activity Recognition transitions: a walk/run/ride of 5+ minutes becomes an observed exercise session. */
    fun onActivitySession(type: String, start: Instant, end: Instant, now: Instant) {
        val minutes = Duration.between(start, end).toMinutes()
        if (minutes < 5 || minutes > 600) return
        store.upsertHealth(now, "exercise", "ar|$type|${TimeUtil.iso(start)}", start, end, start, minutes.toDouble(),
            JSONObject().put("type", type).put("source", "activity_recognition"))
    }

    // ============================================================== scheduler
    suspend fun tick(now: Instant): List<Outbound> = lock.withLock {
        expireInterventions(now)
        dailyJobs(now)
        autoCompleteFromHealth(now)
        processInferred(now)
        val profile = store.profile()
        profile.strOrNull("paused_until")?.let { if (TimeUtil.parse(it).isAfter(now)) return@withLock emptyList() }
        val t = TimeUtil.localTime(now, store.tz)
        if (TimeUtil.inWindow(t, TimeUtil.hhmm(profile.optString("quiet_start", "22:30")), TimeUtil.hhmm(profile.optString("quiet_end", "07:30")))) {
            return@withLock emptyList()
        }
        val eng = Engagement.compute(store, now, Policy.effectiveBudget(profile))
        val today = store.date(now)
        val sentToday = store.interventionsOn(today).filter { it.str("kind") in Engagement.PROACTIVE_KINDS }
        if (sentToday.size >= eng.dailyBudget) return@withLock emptyList()
        for (o in 1 until eng.minDaysBetween) {
            if (store.interventionsOn(TimeUtil.daysAgo(today, o.toLong())).any { it.str("kind") in Engagement.PROACTIVE_KINDS }) return@withLock emptyList()
        }
        store.lastProactiveSentAt()?.let { if (Duration.between(TimeUtil.parse(it), now) < minGap) return@withLock emptyList() }
        fun allowed(f: String) = (eng.allowedIntents == null || f in eng.allowedIntents) && f !in eng.retiredIntents
        if (allowed("commitment")) snoozeFollowUp(now, today)?.let { return@withLock listOf(it) }
        if (allowed("weekly_review")) maybeWeeklyReview(now)?.let { return@withLock listOf(it) }

        val (cands, dropped) = brain.gate(brain.candidates(now, eng, profile), eng, now, eng.dailyBudget - sentToday.size, brain.reservedSlots(now, profile, eng))
        if (dropped.isNotEmpty()) store.logDecision(now, "gate", dropped.joinToString("; ").take(500))
        if (cands.isEmpty()) return@withLock emptyList()
        val d = brain.decide(cands, digest(now, eng), now)
        if (d.candidate == null) {
            store.logDecision(now, "brain_silence", d.reason, JSONObject().put("candidates", JSONArray(cands.take(3).map { it.intent })))
            return@withLock emptyList()
        }
        listOf(emit(now, d.candidate, d.message, d.reason, d.proposedRule))
    }

    private fun emit(now: Instant, c: Candidate, text: String, reason: String, rule: String?): Outbound {
        val kind = if (c.family == "commitment") "scheduled" else "proactive"
        val iid = store.addIntervention(now, mapOf(
            "commitment_id" to c.commitmentId, "kind" to kind, "intent" to c.intent, "level" to c.level,
            "slot" to (c.slot ?: TimeUtil.slotOf(TimeUtil.localTime(now, store.tz))), "style" to c.family,
            "version_offered" to (rule ?: c.version), "message_text" to text, "reason" to "${c.why} | chosen: $reason".take(600),
        ))
        store.logDecision(now, "proactive", "${c.intent}: $reason", JSONObject().put("why", c.why))
        return send(now, text, "nudge", buttonsFor(c, iid, rule), iid)
    }

    private fun buttonsFor(c: Candidate, iid: Long, rule: String?): List<Button> = when (c.family) {
        "commitment" -> nudgeButtons(iid)
        "recap" -> {
            val b = mutableListOf<Button>()
            val pending = c.facts.optJSONArray("pending_inferred") ?: JSONArray()
            if ((c.facts.optJSONObject("defaults")?.length() ?: 0) > 0) {
                b += Button("All usual", "rc:$iid:usual")
                if (pending.length() == 0) b += Button("Usual + outside snack", "rc:$iid:usual_snack")
            }
            b += Button("Off-plan day", "rc:$iid:offplan"); b += Button("Skip today", "rc:$iid:skip")
            for (i in 0 until minOf(3, pending.length())) {
                val it = pending.getJSONObject(i)
                b += Button("Food: ${it.optString("summary").take(22)}", "inf:${it.getLong("id")}:yes"); b += Button("Not food", "inf:${it.getLong("id")}:no")
            }
            b
        }
        "reengage" -> listOf(Button("All good", "re:$iid:ok"), Button("Off track", "re:$iid:off"), Button("Pause 1 week", "re:$iid:pause"))
        "propose_commitment" -> if (rule != null) listOf(Button("Yes, set it", "pc:$iid:yes"), Button("No", "pc:$iid:no")) else emptyList()
        "inactivity" -> listOf(Button("Did 5 min", "iv:$iid:done"), Button("Not now", "iv:$iid:later"))
        else -> emptyList()
    }

    private suspend fun snoozeFollowUp(now: Instant, today: String): Outbound? {
        val todays = store.interventionsOn(today)
        for (c in store.activeCommitments()) {
            val mine = todays.filter { it.long("commitment_id") == c.id && it.str("kind") in setOf("scheduled", "follow_up") }
            val snoozed = mine.filter { it.str("status") == "snoozed" }
            if (snoozed.isEmpty() || mine.any { it.str("kind") == "follow_up" } || mine.any { it.str("status") == "sent" }) continue
            if (store.commitmentOutcome(c.id, today) in setOf("done", "smaller", "skipped")) continue
            val at = TimeUtil.parse(snoozed.last().str("responded_at") ?: snoozed.last().str("sent_at")!!)
            val since = Duration.between(at, now).toMinutes()
            if (since < SNOOZE_MINUTES || since > SNOOZE_MINUTES + 120) continue
            val pattern = Policy.analyseCommitment(store, c, today)
            val d = Policy.decideLevel(c, pattern, store.profile(), today)
            val (text, _) = coach.nudge(c.asContext(), d.level, d.version, pattern.asJson(), buildContext(now).getJSONObject("today"),
                "follow-up the user asked for by pressing 'Later'")
            val iid = store.addIntervention(now, mapOf(
                "commitment_id" to c.id, "kind" to "follow_up", "intent" to "commitment:${c.id}", "level" to d.level,
                "slot" to TimeUtil.slotOf(TimeUtil.localTime(now, store.tz)), "style" to "follow_up", "version_offered" to d.version,
                "message_text" to text, "reason" to "follow-up after 'Later'",
            ))
            return send(now, text, "nudge", nudgeButtons(iid), iid)
        }
        return null
    }

    private fun dailyJobs(now: Instant) {
        val today = store.date(now)
        if (store.jobDone("daily", today)) return
        val mined = store.transaction {
            val m = Patterns.mine(store, now)
            store.expireInferred(now, TimeUtil.daysAgo(today, 2))
            store.markJob(now, "daily", today)
            m
        }
        val active = mined.filter { it.optString("status") == "active" }.map { it.getString("key") }
        store.logDecision(now, "daily_jobs", "patterns mined: ${mined.size} (${active.size} active)", JSONObject().put("active", JSONArray(active)))
    }

    private fun processInferred(now: Instant) {
        for (row in store.pendingInferred()) {
            val id = row.long("id")!!
            val payee = row.str("payee_key")
            val age = Duration.between(TimeUtil.parse(row.str("occurred_at")!!), now)
            if (row.str("kind") == "small_payment" && payee != null) {
                val label = store.payeeLabel(payee)
                if (label != null && (label.long("is_food") ?: 0L) == 1L) {
                    store.transaction {
                        store.resolveInferred(now, id, "auto_confirmed")
                        logInferredFood(now, row, label.str("label")?.takeIf { it.isNotBlank() }, "inferred_known_payee", 0.5)
                    }
                    continue
                }
                if (age > Duration.ofHours(18) && recurringSnackPayee(payee)) {
                    store.transaction { store.resolveInferred(now, id, "auto_logged"); logInferredFood(now, row, source = "inferred_unconfirmed", confidence = 0.3) }
                    continue
                }
            }
            if (row.str("kind") == "food_order" && age > Duration.ofHours(18)) {
                store.transaction { store.resolveInferred(now, id, "auto_logged"); logInferredFood(now, row, source = "inferred_unconfirmed", confidence = 0.35) }
            }
        }
    }

    private fun recurringSnackPayee(payee: String) = store.patterns("active", "weekday_payee").any { (it.str("key") ?: "").startsWith("weekday_payee:$payee:") }

    private fun autoCompleteFromHealth(now: Instant) {
        val today = store.date(now)
        val sessions = store.healthBetween("exercise", today, today)
        if (sessions.isEmpty()) return
        val used = mutableSetOf<Long>()
        for (c in store.activeCommitments()) {
            if (c.activityKind !in setOf("walk", "workout") || c.windowStart == null) continue
            if (!Policy.isScheduledOn(c, LocalDate.parse(today))) continue
            if (store.commitmentOutcome(c.id, today) in setOf("done", "smaller")) continue
            val minimum = minutesIn(c.versions.lastOrNull()) ?: 5
            val full = minutesIn(c.versions.firstOrNull()) ?: minimum
            for (rec in sessions) {
                val rid = rec.long("id")!!
                if (rid in used) continue
                val type = JSONObject(rec.str("payload_json") ?: "{}").optString("type").uppercase()
                if (c.activityKind == "walk" && "WALK" !in type) continue
                val minutes = rec.dbl("value") ?: 0.0
                if (minutes >= minimum) {
                    val outcome = if (minutes >= full) "done" else "smaller"
                    used += rid
                    recordOutcome(now, c.id, outcome, "observed", "${minutes.roundToInt()} min (phone)")
                    store.logDecision(now, "auto_complete", "Commitment ${c.id} marked $outcome from a ${minutes.roundToInt()} min session")
                    break
                }
            }
        }
    }

    fun digest(now: Instant, eng: Engagement? = null): JSONObject {
        val today = store.date(now)
        val ctx = buildContext(now)
        val e = eng ?: Engagement.compute(store, now, Policy.effectiveBudget(store.profile()))
        return JSONObject()
            .put("local_time", ctx.get("local_time"))
            .put("engagement", e.asContext())
            .put("today", ctx.get("today"))
            .put("meal_coverage_today", Habits.dayCoverage(store, today).asJson())
            .put("intake_yesterday", Habits.estimatedDayIntake(store, TimeUtil.daysAgo(today, 1), foods))
            .put("logging_last_14_days", Habits.coverageStats(store, today))
            .put("weight", ctx.get("weight"))
            .put("active_patterns", JSONArray(store.patterns("active").take(8).map { it.str("claim") }))
            .put("calendar_today", store.calendarDay(today) ?: JSONObject.NULL)
            .put("places_today", ctx.get("places_today"))
            .put("recent_proactive", JSONArray(store.interventionsSince(TimeUtil.daysAgo(today, 3)).filter { it.str("kind") in Engagement.PROACTIVE_KINDS }
                .takeLast(8).map { JSONObject().put("intent", it.str("intent")).put("status", it.str("status")).put("at", store.fmtLocal(TimeUtil.parse(it.str("sent_at")!!), "EEE HH:mm")) }))
            .put("known_facts", JSONArray((0 until minOf(20, ctx.getJSONArray("known_facts").length())).map { ctx.getJSONArray("known_facts").get(it) }))
            .put("goal", store.profile().opt("goal_text"))
            .put("recent_user_messages", JSONArray(store.query("SELECT text FROM messages WHERE direction = 'in' ORDER BY id DESC LIMIT 3")
                .reversed().map { it.str("text")?.take(120) }))
            .put("language_note", "Write in the same language and register as recent_user_messages (e.g. Hinglish if they use it).")
    }

    private fun expireInterventions(now: Instant) {
        for (row in store.openInterventions()) {
            val age = Duration.between(TimeUtil.parse(row.str("sent_at")!!), now)
            if (row.str("kind") == "contextual") {
                if (age > Duration.ofHours(12)) store.updateIntervention(now, row.long("id")!!, "expired")
                continue
            }
            if (age > ignoreAfter) resolveIntervention(now, row, "ignored")
        }
    }

    private suspend fun maybeWeeklyReview(now: Instant): Outbound? {
        val local = now.atZone(store.tz)
        if (local.dayOfWeek.value != REVIEW_WEEKDAY) return null
        if (!TimeUtil.inWindow(local.toLocalTime(), TimeUtil.hhmm("19:00"), TimeUtil.hhmm("21:30"))) return null
        val today = store.date(now)
        for (o in 0..5) if (store.interventionsOn(TimeUtil.daysAgo(today, o.toLong())).any { it.str("kind") == "review" }) return null
        val stats = weeklyStats(now)
        if (!stats.getBoolean("has_enough_activity")) {
            if (!store.jobDone("review_skipped", today)) { store.markJob(now, "review_skipped", today); store.logDecision(now, "review_skipped", "Too little data this week for a useful review.") }
            return null
        }
        val (text, violations) = coach.weeklyReview(stats)
        if (violations.any { it.startsWith("llm_error") }) {
            store.logDecision(now, "review_deferred", "LLM unavailable; weekly review will retry next tick.")
            return null
        }
        val iid = store.addIntervention(now, mapOf("kind" to "review", "intent" to "weekly_review", "level" to 1,
            "slot" to TimeUtil.slotOf(local.toLocalTime()), "style" to "weekly_review", "message_text" to text, "reason" to "weekly review"))
        store.logDecision(now, "weekly_review", "sent weekly review", JSONObject().put("violations", JSONArray(violations)))
        return send(now, text, "nudge", iid = iid)
    }

    fun weeklyStats(now: Instant): JSONObject {
        val today = store.date(now)
        val commitments = JSONArray(store.activeCommitments().map { c ->
            Policy.analyseCommitment(store, c, today, 7).asJson().put("title", c.title).put("action", c.action)
        })
        val trend = Progress.weightTrend(weightSeries(TimeUtil.daysAgo(today, 60)), today)
        val f7 = store.foodLoggedDays(today, 7)
        val (allowed, why) = Progress.canAdjustTargets(trend, store.foodLoggedDays(today, 14))
        val steps = (0..6).mapNotNull { store.healthSum("steps", TimeUtil.daysAgo(today, it.toLong())) }
        val sleep = (0..6).mapNotNull { store.healthSum("sleep_min", TimeUtil.daysAgo(today, it.toLong())) }
        val flat = (0..6).flatMap { store.interventionsOn(TimeUtil.daysAgo(today, it.toLong())) }.filter { it.str("kind") == "scheduled" }
        return JSONObject()
            .put("commitments", commitments)
            .put("weight", JSONObject().put("trend_kg", trend.latestTrend).put("weekly_rate_pct", trend.weeklyRatePct).put("status", trend.status))
            .put("food_logged_days_7", f7)
            .put("avg_steps_7d", if (steps.isNotEmpty()) steps.average().roundToInt() else "unknown")
            .put("step_days_with_data", steps.size)
            .put("avg_sleep_h_7d", if (sleep.isNotEmpty()) Math.round(sleep.average() / 6) / 10.0 else "unknown")
            .put("nudges_sent", flat.size)
            .put("nudges_followed", flat.count { it.str("status") in Policy.SUCCESS_STATUSES })
            .put("frequent_contexts", JSONObject(store.contextTagCounts(TimeUtil.daysAgo(today, 7))))
            .put("target_change_allowed", allowed).put("target_change_note", why)
            .put("has_enough_activity", commitments.length() > 0 || f7 > 0 || steps.isNotEmpty() || trend.weighIns > 0)
    }

    // ============================================================= status
    fun statusLines(now: Instant): List<String> {
        val ctx = buildContext(now)
        val today = ctx.getJSONObject("today")
        val food = today.getJSONObject("food")
        val steps = today.getJSONObject("steps")
        val out = mutableListOf("Mode: ${ctx.getString("coaching_mode")}")
        store.profile().strOrNull("paused_until")?.let { if (TimeUtil.parse(it).isAfter(now)) out += "Paused until ${store.fmtLocal(TimeUtil.parse(it))}" }
        out += if (food.has("kcal_range")) {
            val k = food.getJSONArray("kcal_range"); val p = food.getJSONArray("protein_g_range")
            "Food today (estimated): ${k.get(0)}-${k.get(1)} kcal, protein ${p.get(0)}-${p.get(1)} g (${food.get("items_logged")} items)"
        } else "Food today: nothing logged"
        out += "Steps: ${if (steps.has("value")) steps.getDouble("value").roundToInt() else "unknown"}"
        today.opt("sleep_hours_last_night").let { out += if (it is Number) "Sleep: $it h" else "Sleep: unknown" }
        val w = ctx.getJSONObject("weight")
        if (!w.isNull("trend_kg")) out += "Weight trend: ${w.get("trend_kg")} kg (${w.getString("status")})"
        out += "Health data: ${ctx.getString("health_connect")}"
        val used = llm.usage().filterValues { !it.startsWith("0/") }
        out += "AI quota today: " + (used.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "unused" })
        store.activeCommitments().takeIf { it.isNotEmpty() }?.let { cs -> out += "Commitments:"; cs.forEach { out += "  #${it.id} ${it.title}" } }
        return out
    }
}
