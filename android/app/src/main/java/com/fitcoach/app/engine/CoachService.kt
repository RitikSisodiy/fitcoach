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

/**
 * A message to deliver. [channel]: "app" (the user is in the app chat), "notification", or "telegram".
 * [quickReplies] are tap-to-send answers written by the LLM; a tap is handled exactly like a typed message.
 */
data class Outbound(
    val text: String, val channel: String = "app", val quickReplies: List<String> = emptyList(),
    val interventionId: Long? = null, val messageId: Long? = null,
)

data class Inbound(
    val text: String, val receivedAt: Instant, val kind: String = "text", val media: List<MediaPart> = emptyList(),
    val externalId: String? = null, val channel: String = "app",
)

/**
 * The coach backend shared by every channel (app chat, notifications, Telegram) - one DB, one memory, one agent.
 *
 *   Message -> extract (LLM) -> validate -> store events + memory -> reply (LLM) with retrieved memory
 *   Tick    -> evaluate outcomes, learn -> (if due) situation -> agent decides act / timing / channel / message (LLM)
 *              -> code enforces limits -> deliver
 * See docs/AGENT.md.
 */
class CoachService(
    val store: Store,
    val llm: LlmProvider,
    val foods: FoodTable,
    prompts: Prompts,
    val learner: SlotLearner = SlotLearner(store),
    private val ignoreAfter: Duration = Duration.ofMinutes(180),
    private val minGap: Duration = Duration.ofMinutes(60),
) {
    private val extractor = Extractor(llm, prompts, foods)
    private val coach = Coach(llm, prompts)
    val agent = Agent(store, llm, prompts)
    private val lock = Mutex()

    companion object {
        val MEAL_SLOT_TIMES = mapOf("breakfast" to "09:00", "lunch" to "13:30", "snack" to "17:30", "dinner" to "21:00", "drink" to "16:00")
        private val MINUTES_RE = Regex("(\\d+)\\s*(?:min|minute|mins|minutes)", RegexOption.IGNORE_CASE)

        /** Agent wake-up rules (infrastructure, not coaching): see docs/AGENT.md. */
        val MAX_SILENT_EVAL = Duration.ofHours(4)
        const val MAX_EVALS_PER_DAY = 48

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
            .put("memory", memory(now))
            .put("usual_meals", profile.optJSONObject("usual_meals") ?: JSONObject())
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

    /**
     * Retrieve: the person's memory in three tiers. Long-term facts, temporary states that are still valid,
     * and what the coach has learned about coaching this person (written by the reflection step).
     */
    fun memory(now: Instant): JSONObject {
        val rows = store.activeFacts(now)
        fun item(r: Row) = JSONObject().put("key", r.str("key")).put("value", r.str("value")).put("category", r.str("category"))
        return JSONObject()
            .put("long_term", JSONArray(rows.filter { it.str("valid_until") == null && it.str("category") != "coach_insight" }.map(::item)))
            .put("temporary", JSONArray(rows.filter { it.str("valid_until") != null }.map {
                item(it).put("until", store.fmtLocal(TimeUtil.parse(it.str("valid_until")!!), "EEE dd MMM HH:mm"))
            }))
            .put("coach_insights", JSONArray(rows.filter { it.str("category") == "coach_insight" }.map { it.str("value") }))
    }

    /** Weight from chat and from Health Connect, one value per day. */
    fun weightSeries(sinceDay: String): List<Pair<String, Double>> {
        val m = LinkedHashMap<String, Double>()
        store.metricSeries("weight_kg", sinceDay).forEach { m[it.first] = it.second }
        store.healthBetween("weight_kg", sinceDay, "9999-12-31").forEach { r -> r.dbl("value")?.let { m.putIfAbsent(r.str("local_date")!!, it) } }
        return m.toSortedMap().toList()
    }

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
    /** Observe + understand + remember + reply, for every channel (app, notification quick reply, Telegram). */
    suspend fun handleMessage(msg: Inbound): List<Outbound> = lock.withLock {
        val now = msg.receivedAt
        if (msg.externalId != null && store.messageExists(msg.externalId)) return@withLock emptyList()
        val hist = history()
        val storedText = msg.text.ifBlank { "[${msg.kind}]" }
        val commitments = store.activeCommitments()
        val profile = store.profile()
        val usual = profile.optJSONObject("usual_meals") ?: JSONObject()
        val usualFoods = usual.keys().asSequence().flatMap { slot ->
            val a = usual.optJSONArray(slot) ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.lowercase() }.asSequence()
        }.toSet()
        val ctx = JSONObject()
            .put("LOCAL_TIME", lt(now, "EEE HH:mm"))
            .put("KNOWN_TAGS", JSONArray(store.knownTags()))
            .put("ACTIVE_COMMITMENTS", JSONArray(commitments.map { it.asContext() }))
            .put("OPEN_NUDGES", JSONArray(store.openInterventions().filter { it.str("kind") != "contextual" }.map {
                JSONObject().put("intervention_id", it.long("id")).put("commitment_id", it.long("commitment_id")).put("text", it.str("message_text"))
                    .put("quick_replies", it.str("quick_replies_json")?.let(::JSONArray) ?: JSONArray())
            }))
            .put("PENDING_INFERRED", JSONArray(store.pendingInferred().map {
                JSONObject().put("inferred_id", it.long("id")).put("summary", it.str("summary")).put("when", store.fmtLocal(TimeUtil.parse(it.str("occurred_at")!!)))
            }))
            .put("KNOWN_PATTERNS", JSONArray(store.patterns("active").take(10).map { JSONObject().put("pattern_id", it.long("id")).put("claim", it.str("claim")) }))
            .put("FOOD_LOGGED_TODAY", JSONArray(store.foodOn(store.date(now)).map {
                JSONObject().put("item", it.str("item_name")).put("quantity", it.dbl("quantity")).put("unit", it.str("unit")).put("meal", it.str("meal_slot"))
            }))
            .put("USUAL_MEALS", usual)
            .put("ACTIVE_MEMORY", JSONArray(store.activeFacts(now).filter { it.str("category") != "coach_insight" }.map {
                JSONObject().put("key", it.str("key")).put("value", it.str("value")).put("temporary", it.str("valid_until") != null)
            }))
        var aiDown = false
        val extraction = try {
            extractor.extract(msg.text, msg.media, ctx, commitments.map { it.id }.toSet(), usualFoods).first
        } catch (e: CapabilityException) {
            store.logDecision(now, "capability_error", "Media not supported: ${e.message}")
            Extraction().also { it.dropped += "media not supported" }
        } catch (e: LlmException) {
            aiDown = true
            store.logDecision(now, "extraction_failed", "Extraction failed: ${e.message}")
            Extraction().also { it.dropped += "extraction failed" }
        }
        // Data integrity: a "change" to the mode that is already active is a no-op the LLM sometimes invents.
        if (extraction.coachingModeRequest == profile.optString("coaching_mode", "normal")) extraction.coachingModeRequest = null

        val guidance = store.transaction {
            val mid = store.addMessage(now, "in", msg.kind, storedText, msg.externalId, channel = msg.channel)
            store.markOpenAnswered(now)
            store.addObservation(now, "user_message", "${msg.channel}: ${storedText.take(120)}", significant = false)
            applyExtraction(now, extraction, mid)
        }
        val data = if (extraction.dataNeeded.isNotEmpty()) runQueries(now, extraction.dataNeeded) else null
        val (reply, problems) = if (aiDown) null to listOf("llm_error") else coach.reply(
            msg.text.ifBlank { "(sent a ${msg.kind})" }, buildContext(now), extraction.summary(), hist, guidance, data,
        )
        if (problems.isNotEmpty()) store.logDecision(now, "reply_problem", problems.joinToString("; ").take(300))
        store.logDecision(now, "reply", guidance.joinToString("; ").ifEmpty { "plain reply" }, JSONObject().put("extraction", extraction.summary()))
        val text = reply ?: if (problems.any { it.startsWith("llm_error") }) Coach.AI_UNAVAILABLE else Coach.NO_SAFE_REPLY
        listOf(send(now, text, if (reply == null) "system" else "text", msg.channel))
    }

    private fun send(now: Instant, text: String, kind: String, channel: String, quickReplies: List<String> = emptyList(), iid: Long? = null): Outbound {
        val qr = if (quickReplies.isEmpty()) null else JSONArray(quickReplies.map { JSONObject().put("label", it).put("data", it) })
        val mid = store.addMessage(now, "out", kind, text, buttons = qr, channel = channel, interventionId = iid)
        return Outbound(text, channel, quickReplies, iid, mid)
    }

    private fun applyExtraction(now: Instant, ex: Extraction, mid: Long): List<String> {
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
        ex.facts.forEach { f ->
            val until = f.validDays?.let { TimeUtil.iso(now.plusSeconds((it * 86400).toLong())) }
            store.upsertFact(now, f.category, f.key, f.value, "user_stated", f.confidence, mid, until)
        }

        val tagsNow = mutableListOf<String>()
        ex.context.forEach { c ->
            store.addContext(now, c.tag, c.timing, c.description, mid, c.tomorrow, c.timeHint)
            if (c.timing in setOf("now", "planned") && !c.tomorrow) tagsNow += c.tag
        }
        val already = store.interventionsOn(today).filter { it.str("kind") == "contextual" }.mapNotNull { it.long("commitment_id") }.toSet()
        tagsNow.flatMap { store.commitmentsWithTrigger(it) }.distinctBy { it.id }.filter { it.id !in already }.forEach { c ->
            guidance += "The user is entering a situation covered by their own rule (commitment ${c.id}): '${c.userWords ?: c.action}'."
            store.addIntervention(now, mapOf(
                "commitment_id" to c.id, "kind" to "contextual", "level" to 2, "slot" to TimeUtil.slotOf(TimeUtil.localTime(now, store.tz)),
                "style" to "own_rule_reminder", "version_offered" to c.versions.firstOrNull(), "message_text" to "(in reply) reminder of rule: ${c.action}",
                "reason" to "context tag '${c.triggerTag}' matched an active if-then commitment",
            ))
        }

        ex.commitments.forEach { c ->
            val strong = c.strongRequested && profile.optBoolean("strong_mode_authorized", false)
            val cid = store.addCommitment(now, mapOf(
                "kind" to c.kind, "title" to c.title, "trigger_tag" to c.triggerTag, "action" to c.action, "schedule_days" to c.scheduleDays,
                "window_start" to c.windowStart, "window_end" to c.windowEnd, "enforcement" to if (strong) "strong" else "normal",
                "user_words" to c.userWords, "source_message_id" to mid, "activity_kind" to c.activityKind,
            ), c.versions)
            guidance += "Saved commitment $cid: '${c.title}'" + (if (c.windowStart != null) " (window ${c.windowStart}-${c.windowEnd})" else "") +
                (if (c.triggerTag != null) " (situation: ${c.triggerTag})" else "") + "."
            if (c.strongRequested && !strong) guidance += "The user asked for strict enforcement, but strong coaching mode is not on (they can ask for it)."
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
            guidance += "Commitment $cid updated (${values.keys.joinToString()})."
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
                guidance += "The user said observed pattern $pid is wrong; it was removed."
            }
        }
        if (ex.lapse) {
            guidance += "The user reported going off-plan" + (if (ex.allOrNothing) " and talks as if the day/week is ruined" else "") +
                ". Safety: never suggest compensating (skipping meals, extra punishment)."
            store.logDecision(now, "lapse", ex.lapseNote.ifBlank { "lapse reported" }, JSONObject().put("all_or_nothing", ex.allOrNothing))
        }
        ex.pauseDays?.let { d ->
            val until = pause(now, d)
            guidance += "Proactive messages are paused until ${store.fmtLocal(until)}, as the user asked."
        }
        ex.coachingModeRequest?.let { m ->
            // Asking for strong mode in their own (grounded) words is the authorisation; it can be reversed the same way.
            if (m == "strong") store.setProfile(now, "strong_mode_authorized", true)
            setMode(now, m)
            guidance += "Coaching mode is now $m, as the user asked."
        }
        return guidance
    }

    private fun outcomeGuidance(u: CommitmentUpdate): String =
        "Recorded commitment ${u.commitmentId} as ${u.outcome}" + (u.reasonCategory?.let { " (reason: $it)" } ?: "") + "."

    /** Records a commitment outcome and credits today's proactive messages about it (learning signal). */
    private fun recordOutcome(now: Instant, cid: Long, outcome: String, source: String, version: String? = null, reason: String? = null, localDate: String? = null) {
        store.logCommitment(now, cid, outcome, source, version, reason, localDate)
        store.addObservation(now, "commitment_$outcome", "commitment $cid $outcome ($source)", significant = source == "observed")
        if (outcome !in setOf("done", "smaller")) return
        val day = localDate ?: store.date(now)
        store.interventionsOn(day).filter { it.long("commitment_id") == cid && it.str("kind") != "contextual" && it.str("outcome") in setOf(null, "answered", "ignored") }
            .forEach { iv ->
                store.setInterventionOutcome(now, iv.long("id")!!, "achieved")
                iv.str("slot")?.let { learner.update(now, cid, it, 1.0) }
            }
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
                store.addObservation(at, "place_arrival", "arrived at $tag at $hint")
            }
            store.markJob(now, "place_enter", "$tag|${TimeUtil.iso(at)}")
        } else {
            // A stay of 20+ minutes at a gym counts as a workout session (observed).
            val enter = store.contextSince(today).lastOrNull { it.str("tag") == tag && it.str("source") == "geofence" } ?: return@withLock
            val minutes = Duration.between(TimeUtil.parse(enter.str("occurred_at")!!), at).toMinutes()
            store.addObservation(at, "place_leave", "left $tag at $hint after $minutes min")
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
        if (store.upsertHealth(now, "exercise", "ar|$type|${TimeUtil.iso(start)}", start, end, start, minutes.toDouble(),
                JSONObject().put("type", type).put("source", "activity_recognition"))) {
            store.addObservation(end, "activity_session", "$type for $minutes min ending ${TimeUtil.fmtHhmm(TimeUtil.localTime(end, store.tz))}")
        }
    }

    // ============================================================== agent loop
    /**
     * One pass of the agent loop: housekeeping (expire, evaluate outcomes, learn), then - if something changed, the
     * agent's own chosen check time has come, or it has been silent too long - let the LLM decide whether to act.
     * Code only enforces limits (pause, quiet hours, daily cap, minimum gap, quota); it never picks the message.
     */
    suspend fun tick(now: Instant, force: Boolean = false): List<Outbound> = lock.withLock {
        expireInterventions(now)
        dailyJobs(now)
        autoCompleteFromHealth(now)
        processInferred(now)
        reflectIfDue(now)
        val profile = store.profile()
        profile.strOrNull("paused_until")?.let { if (TimeUtil.parse(it).isAfter(now)) return@withLock emptyList() }
        val quiet = TimeUtil.inWindow(TimeUtil.localTime(now, store.tz), TimeUtil.hhmm(profile.optString("quiet_start", "22:30")),
            TimeUtil.hhmm(profile.optString("quiet_end", "07:30")))
        if (quiet) return@withLock emptyList() // no evaluation (and no LLM cost) while the user sleeps

        val lastObsId = store.kv("agent_last_obs_id")?.toLong() ?: 0L
        val newObs = store.observationsAfter(lastObsId)
        val nextCheck = store.kv("agent_next_check_at")?.let(TimeUtil::parse)
        val lastEval = store.kv("agent_last_eval_at")?.let(TimeUtil::parse)
        val why = when {
            force -> "forced"
            newObs.any { it.long("significant") == 1L } -> "new observation: " + newObs.last { it.long("significant") == 1L }.str("summary")
            nextCheck == null -> "first evaluation"
            !now.isBefore(nextCheck) -> "scheduled check: " + (store.kv("agent_next_check_reason") ?: "")
            lastEval == null || Duration.between(lastEval, now) > MAX_SILENT_EVAL -> "no evaluation for a long time"
            else -> null
        } ?: return@withLock emptyList()
        val evalKey = "agent_evals_" + store.date(now)
        val evals = store.kv(evalKey)?.toInt() ?: 0
        if (evals >= MAX_EVALS_PER_DAY) return@withLock emptyList()
        store.setKv(now, evalKey, (evals + 1).toString())

        val eng = Engagement.compute(store, now, Policy.effectiveBudget(profile))
        val limits = sendLimits(now, eng)
        val situation = agent.situation(now, buildContext(now), newObs, eng, limits, channels(), weeklyStats(now))
        store.setKv(now, "agent_last_eval_at", TimeUtil.iso(now))
        newObs.lastOrNull()?.long("id")?.let { store.setKv(now, "agent_last_obs_id", it.toString()) }
        val d = agent.decide(situation)
        if (d == null) {
            scheduleCheck(now, 30, "retry after the AI was unavailable")
            store.logDecision(now, "agent_error", "LLM unavailable; retrying in 30 min (woke for: $why)")
            return@withLock emptyList()
        }
        scheduleCheck(now, d.nextCheckMinutes, d.nextCheckReason)
        val canSend = limits.getBoolean("can_send_now")
        val data = d.asJson().put("woke_for", why)
        if (!d.act || d.message.isNullOrBlank()) {
            store.logDecision(now, "agent_silent", d.reason, data)
            return@withLock emptyList()
        }
        if (!canSend) {
            store.logDecision(now, "agent_blocked", "Wanted to send but ${limits.optString("why_not")}: ${d.reason}", data)
            return@withLock emptyList()
        }
        val channel = if (d.channel == "telegram" && channels().getBoolean("telegram_linked")) "telegram" else "notification"
        val iid = store.addIntervention(now, mapOf(
            "commitment_id" to d.commitmentId?.takeIf { id -> store.commitment(id) != null }, "kind" to "proactive", "intent" to d.intent,
            "level" to 0, "slot" to TimeUtil.slotOf(TimeUtil.localTime(now, store.tz)), "style" to d.intent, "message_text" to d.message,
            "reason" to "${d.reason} | woke for: $why".take(600), "channel" to channel,
            "quick_replies_json" to JSONArray(d.quickReplies).toString(), "expected_outcome" to d.expectedOutcome,
        ))
        store.logDecision(now, "agent_act", "${d.intent} via $channel: ${d.reason}", data)
        listOf(send(now, d.message, "nudge", channel, d.quickReplies, iid))
    }

    private fun scheduleCheck(now: Instant, minutes: Int, reason: String) {
        store.setKv(now, "agent_next_check_at", TimeUtil.iso(now.plusSeconds(minutes.coerceIn(15, 720) * 60L)))
        store.setKv(now, "agent_next_check_reason", reason.take(200))
    }

    /** Hard limits on sending (rate limits the user controls through mode, quiet hours and pause). */
    fun sendLimits(now: Instant, eng: Engagement): JSONObject {
        val profile = store.profile()
        val today = store.date(now)
        val sent = store.interventionsOn(today).count { it.str("kind") in Engagement.PROACTIVE_KINDS }
        val last = store.lastProactiveSentAt()?.let(TimeUtil::parse)
        val sinceLast = last?.let { Duration.between(it, now).toMinutes() }
        val recentDays = (1 until eng.minDaysBetween).any { o ->
            store.interventionsOn(TimeUtil.daysAgo(today, o.toLong())).any { it.str("kind") in Engagement.PROACTIVE_KINDS }
        }
        val whyNot = when {
            sent >= eng.dailyBudget -> "daily cap reached ($sent/${eng.dailyBudget})"
            sinceLast != null && sinceLast < minGap.toMinutes() -> "last message was only $sinceLast min ago (min gap ${minGap.toMinutes()})"
            recentDays -> "user is ${eng.state}; at most one message every ${eng.minDaysBetween} days"
            else -> null
        }
        return JSONObject().put("can_send_now", whyNot == null).put("why_not", whyNot ?: JSONObject.NULL)
            .put("messages_sent_today", sent).put("daily_cap", eng.dailyBudget)
            .put("minutes_since_last_message", sinceLast ?: JSONObject.NULL)
            .put("quiet_hours", "${profile.optString("quiet_start", "22:30")}-${profile.optString("quiet_end", "07:30")}")
    }

    /** Which channels the agent can use; set by the Android layer (Telegram pairing). */
    var telegramLinked: () -> Boolean = { false }

    private fun channels(): JSONObject = JSONObject().put("telegram_linked", telegramLinked())
        .put("last_inbound_channel", store.lastInboundChannel() ?: JSONObject.NULL)

    /** Learn: once a day, the LLM rewrites the coach insights from the evaluated outcomes of recent interventions. */
    private suspend fun reflectIfDue(now: Instant) {
        val today = store.date(now)
        if (store.jobDone("reflect", today)) return
        val recent = store.interventionsSince(TimeUtil.daysAgo(today, 14)).filter { it.str("kind") != "contextual" && it.str("outcome") != null }
        if (recent.size < 3) return
        store.markJob(now, "reflect", today)
        val n = agent.reflect(now, recent, Engagement.compute(store, now, Policy.effectiveBudget(store.profile())))
        store.logDecision(now, "agent_reflect", if (n == null) "reflection failed (AI unavailable)" else "updated $n coach insights from ${recent.size} evaluated messages")
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
        evaluateOutcomes(now)
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

    /** Unanswered proactive messages become "ignored" after a while (the user's attention is the cost). */
    private fun expireInterventions(now: Instant) {
        for (row in store.openInterventions()) {
            val id = row.long("id")!!
            val age = Duration.between(TimeUtil.parse(row.str("sent_at")!!), now)
            if (row.str("kind") == "contextual") {
                if (age > Duration.ofHours(12)) store.updateIntervention(now, id, "expired")
                continue
            }
            if (age > ignoreAfter) {
                store.updateIntervention(now, id, "ignored")
                if (row.str("outcome") == null) store.setInterventionOutcome(now, id, "ignored")
            }
        }
    }

    /**
     * Evaluate: messages about a commitment that did not lead to it being done by the end of the day are
     * "not_achieved" (and teach the timing learner); answered messages keep "answered" (set on reply).
     */
    private fun evaluateOutcomes(now: Instant) {
        val today = store.date(now)
        store.query("SELECT * FROM interventions WHERE local_date < ? AND local_date >= ? AND kind != 'contextual' AND commitment_id IS NOT NULL " +
            "AND (outcome IS NULL OR outcome IN ('answered','ignored'))", today, TimeUtil.daysAgo(today, 7)).forEach { iv ->
            val cid = iv.long("commitment_id")!!
            val done = store.commitmentOutcome(cid, iv.str("local_date")!!) in setOf("done", "smaller")
            store.setInterventionOutcome(now, iv.long("id")!!, if (done) "achieved" else "not_achieved")
            iv.str("slot")?.let { learner.update(now, cid, it, if (done) 1.0 else 0.0) }
        }
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
        val flat = (0..6).flatMap { store.interventionsOn(TimeUtil.daysAgo(today, it.toLong())) }.filter { it.str("kind") in Engagement.PROACTIVE_KINDS }
        return JSONObject()
            .put("commitments", commitments)
            .put("weight", JSONObject().put("trend_kg", trend.latestTrend).put("weekly_rate_pct", trend.weeklyRatePct).put("status", trend.status))
            .put("food_logged_days_7", f7)
            .put("avg_steps_7d", if (steps.isNotEmpty()) steps.average().roundToInt() else "unknown")
            .put("step_days_with_data", steps.size)
            .put("avg_sleep_h_7d", if (sleep.isNotEmpty()) Math.round(sleep.average() / 6) / 10.0 else "unknown")
            .put("messages_sent", flat.size)
            .put("messages_answered", flat.count { it.str("outcome") in setOf("answered", "achieved") })
            .put("messages_achieved_goal", flat.count { it.str("outcome") == "achieved" })
            .put("frequent_contexts", JSONObject(store.contextTagCounts(TimeUtil.daysAgo(today, 7))))
            .put("target_change_allowed", allowed).put("target_change_note", why)
            .put("days_since_last_weekly_reflection", store.query("SELECT MAX(local_date) AS d FROM interventions WHERE intent = 'weekly_reflection'")
                .firstOrNull()?.str("d")?.let { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.parse(today)) } ?: "never")
    }
}
