package com.fitcoach.app.engine

import com.fitcoach.app.core.arr
import com.fitcoach.app.core.boolOr
import com.fitcoach.app.core.numOrNull
import com.fitcoach.app.core.obj
import com.fitcoach.app.core.objects
import com.fitcoach.app.core.strOrNull
import com.fitcoach.app.core.strings
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Deterministic validation of the LLM's extraction (port of coach/engine/extractor.py: validate_extraction).
 * The LLM proposes; this code disposes. Items that must come from the user's own words are dropped unless
 * their quote is found in the message (D-022).
 */
data class ExtractedFood(
    val name: String, val quantity: Double?, val unit: String?, val mealSlot: String, val eaten: Boolean,
    val estKcal: Pair<Double?, Double?>, val estProtein: Pair<Double?, Double?>, val confidence: Double,
)

data class ExtractedActivity(val kind: String, val description: String, val durationMin: Double?, val steps: Int?, val confidence: Double)
data class ExtractedContext(val tag: String, val timing: String, val timeHint: String?, val tomorrow: Boolean, val description: String)
data class ExtractedCommitment(
    val kind: String, val title: String, var triggerTag: String?, val action: String, val versions: List<String>,
    val scheduleDays: String?, val windowStart: String?, val windowEnd: String?, val strongRequested: Boolean,
    var activityKind: String?, val userWords: String?,
)
data class CommitmentUpdate(val commitmentId: Long, val outcome: String, val version: String?, val reasonCategory: String?)
/** A memory from chat: long-term (no expiry) or temporary (true for [validDays]). */
data class Fact(val category: String, val key: String, val value: String, val confidence: Double, val validDays: Double? = null) {
    val temporary: Boolean get() = validDays != null
}
data class FoodCorrection(val itemName: String, val newQuantity: Double)
data class InferredConfirmation(val inferredId: Long, val isFood: Boolean, val label: String?)
data class DataQuery(val query: String, val days: Int, val offsetDays: Int, val term: String?, val commitmentId: Long?)

class Extraction {
    val foods = mutableListOf<ExtractedFood>()
    val activities = mutableListOf<ExtractedActivity>()
    val bodyMetrics = mutableListOf<Pair<String, Double>>()
    val context = mutableListOf<ExtractedContext>()
    val commitments = mutableListOf<ExtractedCommitment>()
    val commitmentUpdates = mutableListOf<CommitmentUpdate>()
    val facts = mutableListOf<Fact>()
    val foodCorrections = mutableListOf<FoodCorrection>()
    val commitmentChanges = mutableListOf<MutableMap<String, Any?>>()
    val inferredConfirmations = mutableListOf<InferredConfirmation>()
    val profileUpdates = mutableMapOf<String, Any?>()
    val patternFeedback = mutableListOf<Pair<Long, Boolean>>()
    val dataNeeded = mutableListOf<DataQuery>()
    var lapse = false
    var allOrNothing = false
    var lapseNote = ""
    var coachingModeRequest: String? = null
    var pauseDays: Double? = null
    val dropped = mutableListOf<String>()

    fun summary(): JSONObject = JSONObject()
        .put("foods", JSONArray(foods.map { it.name }))
        .put("activities", JSONArray(activities.map { it.kind }))
        .put("body_metrics", JSONArray(bodyMetrics.map { "${it.first}=${it.second}" }))
        .put("context", JSONArray(context.map { it.tag }))
        .put("commitments", JSONArray(commitments.map { it.title }))
        .put("commitment_updates", JSONArray(commitmentUpdates.map { "${it.commitmentId}:${it.outcome}" }))
        .put("memories", JSONArray(facts.map { (if (it.temporary) "temporary " else "") + "${it.category}:${it.key}=${it.value}" }))
        .put("lapse", lapse)
        .put("food_corrections", JSONArray(foodCorrections.map { "${it.itemName}->${it.newQuantity}" }))
        .put("profile_updates", JSONArray(profileUpdates.keys))
        .put("dropped", JSONArray(dropped))
}

object ExtractionValidator {
    const val MIN_CONFIDENCE = 0.35
    val REASONS = setOf("cannot", "forgot", "dont_want", "too_hard", "bad_timing", "unknown")
    val FACT_CATEGORIES = setOf("preference", "constraint", "routine", "schedule", "goal", "health", "life_event", "other")
    val COMMITMENT_KINDS = setOf("if_then", "habit", "precommitment", "boundary")
    private val TOKEN = Regex("[a-z0-9]+(?:\\.[0-9]+)?")

    fun tokens(text: String?): List<String> = TOKEN.findAll((text ?: "").lowercase()).map { it.value }.toList()

    /** Is the quote (mostly) in the user's message? null message = media input, can't check. */
    fun grounded(quote: String?, message: String?): Boolean {
        if (message == null) return true
        val q = tokens(quote)
        if (q.isEmpty()) return false
        val msg = tokens(message).toSet()
        return q.count { it in msg }.toDouble() / q.size >= 0.6
    }

    fun foodMentioned(name: String, message: String?, aliases: List<String>): Boolean {
        if (message == null) return true
        val msg = tokens(message).toSet()
        return (listOf(name) + aliases).any { c -> tokens(c).any { it.length > 2 && it in msg } }
    }

    fun hhmm(v: String?): String? {
        val parts = v?.trim()?.split(":") ?: return null
        if (parts.size != 2 || parts.any { p -> p.isEmpty() || !p.all(Char::isDigit) }) return null
        val h = parts[0].toInt(); val m = parts[1].toInt()
        return if (h in 0..23 && m in 0..59) "%02d:%02d".format(h, m) else null
    }

    private fun conf(o: JSONObject, k: String = "confidence", default: Double = 0.6) = (o.numOrNull(k) ?: default).coerceIn(0.0, 1.0)

    /**
     * [usualFoods]: lower-case names from the user's usual meals. Items marked from_usual_meal may come from there when the
     * message says the meal was as usual ("same as always"), even though the food name itself is not in the message.
     */
    fun validate(raw: JSONObject?, activeIds: Set<Long>, message: String?, aliasesFor: (String) -> List<String> = { emptyList() },
                 usualFoods: Set<String> = emptySet()): Extraction {
        val out = Extraction()
        if (raw == null) { out.dropped += "extraction was not an object"; return out }
        fun check(item: JSONObject, what: String): Boolean {
            if (grounded(item.strOrNull("quote"), message)) return true
            out.dropped += "$what: not grounded in the message"
            return false
        }

        for (item in raw.arr("food_items").objects()) {
            val name = item.strOrNull("name")?.trim() ?: continue
            if (!check(item, "food '$name'")) continue
            val fromUsual = item.boolOr("from_usual_meal") && name.lowercase() in usualFoods
            if (!fromUsual && !foodMentioned(name, message, aliasesFor(name))) { out.dropped += "food '$name': not mentioned in the message"; continue }
            val c = conf(item)
            if (c < MIN_CONFIDENCE) { out.dropped += "food '$name' below confidence"; continue }
            var qty = item.numOrNull("quantity")
            var unit = item.strOrNull("unit")?.trim()?.take(20)
            val maxQty = if ((unit ?: "").lowercase() in setOf("g", "gm", "gms", "gram", "grams", "ml")) 3000.0 else 50.0
            if (qty != null && !(qty > 0 && qty <= maxQty)) { out.dropped += "food '$name' quantity out of range"; qty = null; unit = null }
            var kl = item.numOrNull("est_kcal_low"); var kh = item.numOrNull("est_kcal_high")
            if (kl != null && kh != null && kl > kh) { val t = kl; kl = kh; kh = t }
            var pl = item.numOrNull("est_protein_low"); var ph = item.numOrNull("est_protein_high")
            if (pl != null && ph != null && pl > ph) { val t = pl; pl = ph; ph = t }
            out.foods += ExtractedFood(name.take(80), qty, unit, item.strOrNull("meal_slot") ?: "unknown",
                item.strOrNull("eaten") != "planned", kl to kh, pl to ph, c)
        }

        for (a in raw.arr("activities").objects()) {
            if (a.strOrNull("status") != "done") continue
            val c = conf(a)
            if (c < MIN_CONFIDENCE) continue
            val d = a.numOrNull("duration_min")?.takeIf { it > 0 && it <= 600 }
            val s = a.numOrNull("steps")?.takeIf { it > 0 && it <= 80000 }
            val kind = a.strOrNull("kind").takeIf { it in setOf("walk", "workout", "sport", "steps", "other") } ?: "other"
            out.activities += ExtractedActivity(kind, (a.strOrNull("description") ?: "").take(120), d, s?.toInt(), c)
        }

        for (m in raw.arr("body_metrics").objects()) {
            val name = m.strOrNull("metric"); val v = m.numOrNull("value") ?: continue
            val bounds = mapOf("weight_kg" to (30.0..300.0), "waist_cm" to (40.0..200.0))[name] ?: continue
            if (v !in bounds) { out.dropped += "$name=$v out of plausible range"; continue }
            if (conf(m) < 0.6) { out.dropped += "$name=$v low confidence"; continue }
            out.bodyMetrics += name!! to (v * 100).roundToInt() / 100.0
        }

        for (cx in raw.arr("context").objects()) {
            val tag = cx.strOrNull("tag")?.trim() ?: continue
            val timing = cx.strOrNull("timing").takeIf { it in setOf("now", "planned", "past") } ?: "now"
            out.context += ExtractedContext(tag, timing, hhmm(cx.strOrNull("time_hint")),
                cx.strOrNull("day") == "tomorrow" && timing == "planned", (cx.strOrNull("description") ?: "").take(200))
        }

        for (cm in raw.arr("commitments").objects()) {
            val title = cm.strOrNull("title") ?: continue
            val action = cm.strOrNull("action") ?: continue
            if (!check(cm, "commitment '$title'")) continue
            var start = hhmm(cm.strOrNull("window_start")); var end = hhmm(cm.strOrNull("window_end"))
            if ((start == null) != (end == null)) { out.dropped += "commitment '$title' had a half-specified window"; start = null; end = null }
            var schedule = cm.strOrNull("schedule_days")?.trim()?.lowercase()
            if (start != null && schedule == null) schedule = "daily"
            val versions = cm.arr("fallback_versions").strings().map { it.take(120) }
            out.commitments += ExtractedCommitment(
                kind = cm.strOrNull("kind").takeIf { it in COMMITMENT_KINDS } ?: "habit", title = title.take(80),
                triggerTag = cm.strOrNull("trigger_tag")?.trim(), action = action.take(200),
                versions = versions.ifEmpty { listOf(action.take(120)) }, scheduleDays = schedule, windowStart = start, windowEnd = end,
                strongRequested = cm.boolOr("strong_enforcement_requested"),
                activityKind = cm.strOrNull("activity_kind").takeIf { it in setOf("walk", "workout", "food", "other") },
                userWords = cm.strOrNull("user_words")?.take(300),
            )
        }
        for (c in out.commitments) {
            if (c.activityKind == null) {
                val words = tokens("${c.title} ${c.action}").toSet()
                c.activityKind = when {
                    words.intersect(setOf("walk", "walking", "chalna", "chal", "chalunga", "steps")).isNotEmpty() -> "walk"
                    words.intersect(setOf("workout", "gym", "exercise", "yoga", "run", "running", "pushups", "cycling")).isNotEmpty() -> "workout"
                    else -> null
                }
            }
        }
        val situations = out.context.map { it.tag }.toSet()
        out.commitments.filter { it.kind in setOf("if_then", "precommitment") && it.triggerTag == null && situations.size == 1 }
            .forEach { it.triggerTag = situations.first() }

        for (u in raw.arr("commitment_updates").objects()) {
            val id = u.numOrNull("commitment_id")?.toLong() ?: continue
            if (id !in activeIds) { out.dropped += "update for unknown commitment $id"; continue }
            val outcome = u.strOrNull("outcome").takeIf { it in setOf("done", "smaller", "skipped", "postponed") } ?: continue
            out.commitmentUpdates += CommitmentUpdate(id, outcome, u.strOrNull("version")?.take(120), u.strOrNull("reason_category").takeIf { it in REASONS })
        }

        for (f in raw.arr("memories").objects()) {
            val key = f.strOrNull("key") ?: continue; val value = f.strOrNull("value") ?: continue
            if (!check(f, "memory '$key'")) continue
            val c = conf(f)
            if (c < 0.5) continue
            val days = if (f.strOrNull("type") == "temporary") (f.numOrNull("valid_days") ?: 3.0).coerceIn(0.1, 60.0) else null
            out.facts += Fact(f.strOrNull("category").takeIf { it in FACT_CATEGORIES } ?: "other", key.take(48), value.take(300), c, days)
        }

        for (c in raw.arr("food_corrections").objects()) {
            val item = c.strOrNull("item_name")?.trim() ?: continue
            if (!check(c, "food correction")) continue
            val q = c.numOrNull("new_quantity")
            if (q == null || q < 0 || q > 50) { out.dropped += "correction for '$item' had invalid quantity"; continue }
            out.foodCorrections += FoodCorrection(item.take(80), q)
        }

        for (ch in raw.arr("commitment_changes").objects()) {
            if (!check(ch, "commitment change")) continue
            val id = ch.numOrNull("commitment_id")?.toLong() ?: continue
            if (id !in activeIds) { out.dropped += "change for unknown commitment $id"; continue }
            val change = mutableMapOf<String, Any?>("commitment_id" to id)
            val s = hhmm(ch.strOrNull("window_start")); val e = hhmm(ch.strOrNull("window_end"))
            if (s != null && e != null) { change["window_start"] = s; change["window_end"] = e }
            ch.strOrNull("schedule_days")?.let { change["schedule_days"] = it.trim().lowercase().take(60) }
            ch.arr("fallback_versions").strings().takeIf { it.isNotEmpty() }?.let { change["versions"] = it }
            ch.strOrNull("status")?.takeIf { it in setOf("active", "paused", "retired") }?.let { change["status"] = it }
            if (change.size > 1) out.commitmentChanges += change
        }

        for (ic in raw.arr("inferred_confirmations").objects()) {
            if (!check(ic, "inferred confirmation")) continue
            val id = ic.numOrNull("inferred_id")?.toLong() ?: continue
            out.inferredConfirmations += InferredConfirmation(id, ic.boolOr("is_food"), ic.strOrNull("label")?.take(60))
        }

        // Profile: flat fields (goal_*, usual_meals) or a legacy nested object.
        val prof = JSONObject((raw.obj("profile_updates") ?: JSONObject()).toString())
        for (k in listOf("goal_weight_kg", "goal_text", "height_cm")) {
            if (raw.has(k) && !raw.isNull(k)) {
                prof.put(k, raw.get(k))
                if (!prof.has("quote")) raw.strOrNull("goal_quote")?.let { prof.put("quote", it) }
            }
        }
        val meals = raw.arr("usual_meals").objects().filter { grounded(it.strOrNull("quote"), message) }
        if (meals.isNotEmpty()) {
            prof.put("usual_meals", JSONArray(meals))
            if (!prof.has("quote")) prof.put("quote", meals.first().strOrNull("quote"))
        }
        if (prof.length() > 0 && check(prof, "profile update")) {
            prof.strOrNull("goal_text")?.let { out.profileUpdates["goal_text"] = it.trim().take(200) }
            prof.numOrNull("goal_weight_kg")?.takeIf { it in 35.0..250.0 }?.let { out.profileUpdates["goal_weight_kg"] = (it * 10).roundToInt() / 10.0 }
            prof.numOrNull("height_cm")?.takeIf { it in 120.0..230.0 }?.let { out.profileUpdates["height_cm"] = it.roundToInt() }
            val usual = JSONObject()
            for (meal in prof.arr("usual_meals").objects()) {
                val slot = meal.strOrNull("slot")?.takeIf { it in setOf("breakfast", "lunch", "dinner") } ?: continue
                val items = JSONArray()
                meal.arr("items").objects().forEach { it2 ->
                    val n = it2.strOrNull("name") ?: return@forEach
                    val q = it2.numOrNull("quantity")?.takeIf { q -> q > 0 && q <= 20 }
                    items.put(JSONObject().put("name", n.take(60)).put("quantity", q ?: JSONObject.NULL).put("unit", it2.strOrNull("unit") ?: JSONObject.NULL))
                }
                if (items.length() > 0) usual.put(slot, items)
            }
            if (usual.length() > 0) out.profileUpdates["usual_meals"] = usual
        }

        raw.arr("pattern_feedback").objects().forEach { fb ->
            fb.numOrNull("pattern_id")?.let { out.patternFeedback += it.toLong() to fb.boolOr("correct") }
        }

        val allowed = setOf("food_log", "weight", "steps", "sleep", "commitment_history", "patterns", "search_messages")
        raw.arr("data_needed").objects().take(3).forEach { q ->
            val name = q.strOrNull("query")?.takeIf { it in allowed } ?: return@forEach
            out.dataNeeded += DataQuery(name, (q.numOrNull("days") ?: 7.0).toInt().coerceIn(1, 90),
                (q.numOrNull("offset_days") ?: 0.0).toInt().coerceIn(0, 90), q.strOrNull("term")?.take(60), q.numOrNull("commitment_id")?.toLong())
        }

        raw.obj("lapse")?.let { l ->
            out.lapse = l.boolOr("detected"); out.allOrNothing = l.boolOr("all_or_nothing_thinking"); out.lapseNote = (l.strOrNull("note") ?: "").take(200)
        }

        val settings = JSONObject((raw.obj("settings_request") ?: JSONObject()).toString())
        raw.strOrNull("coaching_mode_request")?.let { settings.put("coaching_mode", it) }
        raw.numOrNull("pause_days")?.takeIf { it != 0.0 }?.let { settings.put("pause_days", it) }
        if (settings.length() > 0 && !settings.has("quote")) raw.strOrNull("settings_quote")?.let { settings.put("quote", it) }
        if (settings.length() > 0 && check(settings, "settings request")) {
            settings.strOrNull("coaching_mode")?.takeIf { it in setOf("gentle", "normal", "accountability", "strong") }?.let { out.coachingModeRequest = it }
            settings.numOrNull("pause_days")?.takeIf { it > 0 && it <= 30 }?.let { out.pauseDays = it }
        }
        return out
    }
}
