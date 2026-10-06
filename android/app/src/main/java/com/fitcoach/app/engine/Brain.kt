package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.core.strings
import com.fitcoach.app.core.strOrNull
import com.fitcoach.app.data.Store
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
import java.time.LocalTime

/**
 * Proactive brain (port of coach/engine/brain.py): code proposes and gates, the LLM chooses and writes.
 * Android additions: place arrivals (geofences), sleep and late-night screen time feed the candidates directly,
 * so the coach reacts to what the phone observes without the user typing anything.
 */
data class Candidate(
    val intent: String,
    val priority: Int,
    val why: String,
    val facts: JSONObject = JSONObject(),
    val fallbackText: String = "",
    val commitmentId: Long? = null,
    val level: Int = 1,
    val version: String? = null,
    val slot: String? = null,
) {
    val family: String get() = Engagement.intentFamily(intent)
    fun forLlm(idx: Int): JSONObject = JSONObject().put("id", idx).put("intent", intent).put("why", why).put("facts", facts)
}

data class Decision(val candidate: Candidate?, val message: String, val reason: String, val proposedRule: String?)

class Brain(private val store: Store, private val llm: LlmProvider, private val prompts: Prompts, private val learner: SlotLearner) {

    private fun t(s: String): LocalTime = TimeUtil.hhmm(s)
    private fun hm(minutes: Int): LocalTime = LocalTime.of((minutes.coerceIn(0, 23 * 60 + 59)) / 60, minutes.coerceIn(0, 23 * 60 + 59) % 60)
    private fun intentsOn(day: String) = store.interventionsOn(day).mapNotNull { it.str("intent") }

    // ============================================================ candidates
    fun candidates(now: Instant, eng: Engagement, profile: JSONObject): List<Candidate> {
        val today = store.date(now)
        val lt = TimeUtil.localTime(now, store.tz)
        val sentToday = intentsOn(today).toSet()
        val out = mutableListOf<Candidate>()
        fun add(c: Candidate?) { if (c != null && c.intent !in sentToday) out += c }
        commitmentCandidates(now, today, lt, profile).forEach(::add)
        arrivals(now, today).forEach(::add)
        add(recap(today, lt, profile, eng))
        add(morningPlan(today, lt))
        predictedContext(today, lt).forEach(::add)
        add(inactivity(now, today, lt))
        add(reengage(today, lt, eng))
        add(proposeCommitment(today, lt))
        add(onboarding(today, lt, profile))
        add(staleSync(now, today, lt))
        return out.sortedByDescending { it.priority }
    }

    private fun commitmentCandidates(now: Instant, today: String, lt: LocalTime, profile: JSONObject): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val todays = store.interventionsOn(today)
        for (c in store.activeCommitments()) {
            if (c.windowStart == null || c.windowEnd == null || !Policy.isScheduledOn(c, LocalDate.parse(today))) continue
            if (store.commitmentOutcome(c.id, today) in setOf("done", "smaller", "skipped")) continue
            if (todays.any { it.long("commitment_id") == c.id && it.str("kind") in setOf("scheduled", "follow_up") }) continue
            val start = t(c.windowStart); val end = t(c.windowEnd)
            if (!TimeUtil.inWindow(lt, start, end)) continue
            val pattern = Policy.analyseCommitment(store, c, today)
            val d = Policy.decideLevel(c, pattern, profile, today)
            if (d.backOff && store.interventionsOn(TimeUtil.daysAgo(today, 1)).any { it.long("commitment_id") == c.id && it.str("kind") == "scheduled" }) {
                store.logDecision(now, "back_off", "Commitment ${c.id}: skipping today. ${d.reason}", pattern.asJson())
                continue
            }
            val current = TimeUtil.slotOf(lt)
            val slots = TimeUtil.slotsBetween(start, end)
            val remaining = if (current in slots) slots.subList(slots.indexOf(current), slots.size) else listOf(current)
            val (send, why) = learner.shouldSendNow(c.id, current, remaining)
            if (!send) continue
            result += Candidate(
                "commitment:${c.id}", 80 + d.level, "Commitment due now (level ${d.level}: ${d.reason}; timing: $why)",
                JSONObject().put("commitment", c.asContext()).put("offer_version", d.version).put("pattern", d.pattern.asJson()).put("level", d.level),
                "${c.title}: how about ${d.version} now?", c.id, d.level, d.version, current,
            )
        }
        return result
    }

    /** The phone saw the user arrive somewhere they have a rule or a lapse pattern for: act before the decision. */
    private fun arrivals(now: Instant, today: String): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val recent = store.contextSince(today).filter {
            it.str("source") == "geofence" && Duration.between(TimeUtil.parse(it.str("occurred_at")!!), now) <= Duration.ofMinutes(40)
        }
        for (ev in recent.distinctBy { it.str("tag") }) {
            val tag = ev.str("tag")!!
            val rules = store.commitmentsWithTrigger(tag)
            val lapse = Patterns.lapsePatternFor(store, tag)
            if (rules.isEmpty() && lapse == null) continue
            val facts = JSONObject().put("situation", tag).put("detected_by", "phone location (just arrived)")
            rules.firstOrNull()?.let { facts.put("user_rule", it.userWords ?: it.action) }
            lapse?.let { facts.put("observed", it.optString("claim")) }
            result += Candidate(
                "arrived:$tag", 86, "Phone detected arrival at '$tag' just now; the user has a rule or a known pattern here",
                facts, "At ${tag.replace('_', ' ')} - your plan: ${facts.optString("user_rule", "keep it light")}.",
            )
        }
        return result
    }

    fun recapItems(today: String): JSONObject {
        val cov = Habits.dayCoverage(store, today)
        val habits = Habits.habitualMeals(store, today, TimeUtil.isWeekend(today)).ifEmpty { Habits.habitualMeals(store, today) }
        val defaults = JSONObject()
        cov.missing.filter { it in habits }.forEach { defaults.put(it, habits.getValue(it).describe()) }
        val pending = JSONArray()
        store.pendingInferred(today).take(3).forEach { r ->
            pending.put(JSONObject().put("id", r.long("id")).put("kind", r.str("kind")).put("summary", r.str("summary"))
                .put("time", store.fmtLocal(TimeUtil.parse(r.str("occurred_at")!!), "HH:mm")))
        }
        return JSONObject().put("missing_meals", JSONArray(cov.missing)).put("defaults", defaults).put("pending_inferred", pending)
    }

    private fun recap(today: String, lt: LocalTime, profile: JSONObject, eng: Engagement): Candidate? {
        for (o in 1 until eng.recapEveryDays) if ("recap" in intentsOn(TimeUtil.daysAgo(today, o.toLong()))) return null
        val start = t((profile.strOrNull("recap_time") ?: "21:00"))
        val end = hm(minOf(start.hour * 60 + start.minute + 90, 23 * 60 + 59))
        if (!TimeUtil.inWindow(lt, start, end)) return null
        val items = recapItems(today)
        val missing = items.getJSONArray("missing_meals").strings()
        val pending = items.getJSONArray("pending_inferred")
        if (missing.isEmpty() && pending.length() == 0) return null
        val defaults = items.getJSONObject("defaults")
        val missingS = missing.joinToString(", ").ifEmpty { "nothing" }
        val fallback = "Quick recap: " + (defaults.keys().asSequence().joinToString("; ") { "$it - usual (${defaults.getString(it)})?" }
            .ifEmpty { "what did you have for $missingS?" })
        return Candidate("recap", 70, "Evening recap: meals not logged today: $missingS; ${pending.length()} inferred item(s) to confirm", items, fallback)
    }

    private fun morningPlan(today: String, lt: LocalTime): Candidate? {
        if (!TimeUtil.inWindow(lt, t("08:30"), t("11:00"))) return null
        val facts = JSONObject()
        val preds = Patterns.predictedContexts(store, today)
        if (preds.isNotEmpty()) facts.put("predicted_today", JSONArray(preds.map { JSONObject().put("situation", it.tag).put("around", it.typicalTime) }))
        store.calendarDay(today)?.let { cal ->
            if (cal.optDouble("meeting_hours", 0.0) >= 6) {
                val free = cal.optJSONArray("free_slots") ?: JSONArray()
                facts.put("long_workday", JSONObject().put("meeting_hours", cal.optDouble("meeting_hours")).put("free_slots", JSONArray((0 until minOf(4, free.length())).map { free.get(it) })))
            }
        }
        store.healthSum("sleep_min", today)?.let { if (it in 1.0..(6 * 60.0)) facts.put("short_sleep_hours", Math.round(it / 6) / 10.0) }
        store.screenDay(TimeUtil.daysAgo(today, 1))?.let { s ->
            val late = s.long("late_night_minutes") ?: 0
            if (late >= 45) facts.put("late_night_phone_minutes_yesterday", late)
        }
        val yesterdayLapse = store.recentDecisions(50).any {
            it.str("kind") == "lapse" && store.date(TimeUtil.parse(it.str("created_at")!!)) == TimeUtil.daysAgo(today, 1)
        }
        if (yesterdayLapse) facts.put("yesterday", "an off-plan moment was reported; today is a normal day")
        if (facts.length() == 0) return null
        val due = store.activeCommitments().filter { it.windowStart != null && Policy.isScheduledOn(it, LocalDate.parse(today)) }.map { it.title }
        if (due.isNotEmpty()) facts.put("commitments_today", JSONArray(due))
        return Candidate("morning_plan", 40, "Morning: there is something specific to plan for today", facts,
            "Today's plan: " + facts.keys().asSequence().joinToString("; ") { "$it: ${facts.get(it)}" }.take(200))
    }

    private fun predictedContext(today: String, lt: LocalTime): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val mentioned = store.contextSince(today).mapNotNull { it.str("tag") }.toSet()
        for (p in Patterns.predictedContexts(store, today)) {
            if (p.tag in mentioned) continue
            val typical = t(p.typicalTime)
            val m = typical.hour * 60 + typical.minute
            if (!TimeUtil.inWindow(lt, hm(maxOf(m - 120, 0)), hm(maxOf(m - 15, 0)))) continue
            val rules = store.commitmentsWithTrigger(p.tag)
            var lapse = Patterns.lapsePatternFor(store, p.tag)
            if (p.kind == "weekday_payee" && lapse == null) lapse = JSONObject().put("claim", p.claim)
            if (rules.isEmpty() && lapse == null) continue
            val facts = JSONObject().put("situation", p.tag).put("pattern", p.claim)
            rules.firstOrNull()?.let { facts.put("user_rule", it.userWords ?: it.action) }
            lapse?.let { facts.put("observed", it.optString("claim")) }
            result += Candidate(
                "predicted_context:${p.tag}", 75, "'${p.tag}' is predicted around ${p.typicalTime} today; acting before the situation", facts,
                "${p.tag.replace('_', ' ').replaceFirstChar { it.uppercase() }} today? Your plan: ${facts.optString("user_rule", "keep it light")}.",
            )
        }
        return result
    }

    private fun inactivity(now: Instant, today: String, lt: LocalTime): Candidate? {
        if (!TimeUtil.inWindow(lt, t("15:00"), t("19:30"))) return null
        val last = store.lastHealthSync() ?: return null
        if (Duration.between(TimeUtil.parse(last), now) > Duration.ofHours(3)) return null
        val steps = store.healthSum("steps", today) ?: 0.0
        val known = (1..14).mapNotNull { store.healthSum("steps", TimeUtil.daysAgo(today, it.toLong())) }.filter { it > 0 }
        if (known.size < 5) return null
        val typical = known.average()
        if (steps >= maxOf(1500.0, 0.35 * typical)) return null
        val facts = JSONObject().put("steps_so_far", steps.toInt()).put("typical_daily_steps", typical.toInt())
        store.calendarDay(today)?.let { cal ->
            val cur = TimeUtil.fmtHhmm(lt)
            val busy = cal.optJSONArray("busy") ?: JSONArray()
            if ((0 until busy.length()).any { val b = busy.getJSONArray(it); b.getString(0) <= cur && cur < b.getString(1) }) return null
            val free = cal.optJSONArray("free_slots") ?: JSONArray()
            val now1 = (0 until free.length()).map { free.getJSONArray(it) }.firstOrNull { it.getString(0) <= cur && cur < it.getString(1) }
            facts.put("calendar_free_until", now1?.getString(1) ?: JSONObject.NULL)
        }
        return Candidate("inactivity", 45, "Low movement today (${steps.toInt()} steps vs ~${typical.toInt()} typical)", facts,
            "Long sitting day - 5 minute walk now?")
    }

    private fun reengage(today: String, lt: LocalTime, eng: Engagement): Candidate? {
        val days = eng.daysSinceInbound ?: return null
        if (days < 3 || !TimeUtil.inWindow(lt, t("10:00"), t("20:00"))) return null
        for (o in 0 until maxOf(eng.minDaysBetween, 2)) if ("reengage" in intentsOn(TimeUtil.daysAgo(today, o.toLong()))) return null
        return Candidate("reengage", if (eng.state in setOf("silent", "dormant")) 90 else 60,
            "No message from the user for ${Math.round(days)} days; offer a one-tap way back",
            JSONObject().put("engagement", eng.state), "Quick check-in - how's it going? One tap is enough.")
    }

    private fun proposeCommitment(today: String, lt: LocalTime): Candidate? {
        if (!TimeUtil.inWindow(lt, t("10:00"), t("18:00"))) return null
        for (p in store.patterns("active", "context_lapse") + store.patterns("active", "weekday_payee")) {
            val tag = JSONObject(p.str("data_json") ?: "{}").optString("tag")
            if (tag.isBlank()) continue
            if (store.commitmentsWithTrigger(tag).isNotEmpty()) continue
            val key = "propose_commitment:$tag"
            if (store.interventionsSince(TimeUtil.daysAgo(today, 10)).any { it.str("intent") == key }) continue
            return Candidate(key, 50, "Repeated pattern without a plan: ${p.str("claim")}",
                JSONObject().put("pattern", p.str("claim")).put("situation", tag).put("pattern_id", p.long("id")),
                "I've noticed: ${p.str("claim")}. Want a simple default for ${tag.replace('_', ' ')} days?")
        }
        return null
    }

    private fun onboarding(today: String, lt: LocalTime, profile: JSONObject): Candidate? {
        if (!TimeUtil.inWindow(lt, t("10:00"), t("20:00"))) return null
        val topics = mutableListOf<Pair<String, String>>()
        if (profile.strOrNull("goal_text") == null && profile.isNull("goal_weight_kg")) topics += "goal" to "main goal (e.g. lose weight, how much, by when)"
        if (store.metricSeries("weight_kg", "2000-01-01").isEmpty() && store.healthBetween("weight_kg", "2000-01-01", today).isEmpty()) {
            topics += "weight" to "current weight"
        }
        val keys = store.activeFacts().mapNotNull { it.str("key") }
        val declared = profile.optJSONObject("usual_meals") ?: JSONObject()
        if (declared.length() < 2 && Habits.habitualMeals(store, today).size < 2) {
            topics += "usual_meals" to "what they USUALLY eat for breakfast/lunch/dinner (so evening check-ins become one tap)"
        }
        if (keys.none { it.startsWith("work") } && store.calendarDay(today) == null) topics += "work_routine" to "work routine (WFH/office, usual hours)"
        if (store.activeCommitments().isEmpty() && store.inboundTimesSince("2000-01-01").size >= 5) {
            topics += "first_habit" to "one small daily habit they'd like help with (e.g. a 10-min walk after lunch)"
        }
        val recent = store.interventionsSince(TimeUtil.daysAgo(today, 14)).filter { (it.str("intent") ?: "").startsWith("onboarding:") }
        for ((key, ask) in topics) {
            val asked = recent.filter { it.str("intent") == "onboarding:$key" }
            if (asked.size >= 2 || asked.any { it.str("local_date")!! >= TimeUtil.daysAgo(today, 2) }) continue
            return Candidate("onboarding:$key", 30, "Profile is missing basics the coach needs",
                JSONObject().put("ask_about", ask).put("still_missing", JSONArray(topics.map { it.second })),
                "Quick one so I can help better: $ask?")
        }
        return null
    }

    private fun staleSync(now: Instant, today: String, lt: LocalTime): Candidate? {
        val last = store.lastHealthSync() ?: return null
        val age = Duration.between(TimeUtil.parse(last), now)
        if (age < Duration.ofHours(30) || !TimeUtil.inWindow(lt, t("10:00"), t("19:00"))) return null
        for (o in 0..2) if ("stale_sync" in intentsOn(TimeUtil.daysAgo(today, o.toLong()))) return null
        return Candidate("stale_sync", 20, "No new Health Connect data for ${age.toHours()} h (steps/sleep now unknown)",
            JSONObject().put("hours_since_sync", age.toHours()),
            "I haven't seen new step data for over a day - check that Google Fit / your watch app still writes to Health Connect?")
    }

    // ================================================================ gating
    fun reservedSlots(now: Instant, profile: JSONObject, eng: Engagement): Int {
        val today = store.date(now)
        val hhmm = TimeUtil.fmtHhmm(TimeUtil.localTime(now, store.tz))
        val todays = store.interventionsOn(today)
        var reserved = 0
        for (c in store.activeCommitments()) {
            if (c.windowStart == null || c.windowEnd == null || !Policy.isScheduledOn(c, LocalDate.parse(today))) continue
            if (c.windowEnd <= hhmm || store.commitmentOutcome(c.id, today) in setOf("done", "smaller", "skipped")) continue
            if (todays.any { it.long("commitment_id") == c.id && it.str("kind") in setOf("scheduled", "follow_up") }) continue
            reserved++
        }
        val recapStart = (profile.strOrNull("recap_time") ?: "21:00")
        val recapAllowed = eng.allowedIntents == null || "recap" in eng.allowedIntents
        if (recapAllowed && hhmm < recapStart && todays.none { it.str("intent") == "recap" }) reserved++
        return reserved
    }

    private fun silenceKey(intent: String, now: Instant) = "$intent|${store.date(now)}|${now.atZone(store.tz).hour / 2}"

    fun gate(candidates: List<Candidate>, eng: Engagement, now: Instant, budgetLeft: Int? = null, reserved: Int = 0): Pair<List<Candidate>, List<String>> {
        val kept = mutableListOf<Candidate>(); val dropped = mutableListOf<String>()
        for (c in candidates) {
            when {
                budgetLeft != null && c.priority < 70 && budgetLeft - reserved <= 0 ->
                    dropped += "${c.intent}: budget kept for higher-value messages later today ($reserved reserved)"
                eng.allowedIntents != null && c.family !in eng.allowedIntents && c.family != "arrived" ->
                    dropped += "${c.intent}: not allowed while ${eng.state}"
                c.family in eng.retiredIntents -> dropped += "${c.intent}: message type retired (no responses recently)"
                store.jobDone("brain_silence", silenceKey(c.intent, now)) -> dropped += "${c.intent}: LLM chose silence recently"
                else -> kept += c
            }
        }
        return kept to dropped
    }

    // ================================================================ decide
    suspend fun decide(candidates: List<Candidate>, digest: JSONObject, now: Instant): Decision {
        val shortlist = candidates.take(3)
        val prompt = prompts.decideInstructions +
            "\n- arrived: the phone just detected arrival at this place; remind them of their own rule in one line.\n" +
            "\nDIGEST (computed by code; trust it):\n" + digest.toString(1) +
            "\n\nCANDIDATES:\n" + JSONArray(shortlist.mapIndexed { i, c -> c.forLlm(i) }).toString(1)
        val raw: JSONObject
        val choice: Int
        val reason: String
        try {
            raw = llm.generate(LlmRequest(prompts.coachSystem, prompt, jsonSchema = prompts.decideSchema, temperature = 0.5, purpose = "decide")).json()
            choice = raw.optInt("choice", -1)
            reason = raw.optString("reason").take(300)
        } catch (e: LlmException) {
            val top = shortlist.first()
            return Decision(top, top.fallbackText, "LLM unavailable (${e.javaClass.simpleName}); template for top candidate", null)
        }
        if (choice < 0 || choice >= shortlist.size) {
            shortlist.forEach { store.markJob(now, "brain_silence", silenceKey(it.intent, now)) }
            return Decision(null, "", reason.ifBlank { "LLM chose silence" }, null)
        }
        val chosen = shortlist[choice]
        var message = raw.optString("message").trim()
        val rule = raw.optString("proposed_rule").trim().ifBlank { null }
        val v = if (message.isEmpty()) listOf("empty") else Safety.check(message)
        if (v.isNotEmpty()) {
            store.logDecision(now, "safety_rewrite", "Proactive message replaced by template", JSONObject().put("violations", JSONArray(v)))
            message = chosen.fallbackText
        }
        return Decision(chosen, message, reason, rule)
    }
}
