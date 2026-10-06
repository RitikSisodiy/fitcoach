package com.fitcoach.app.engine

import com.fitcoach.app.llm.ChatTurn
import com.fitcoach.app.llm.LlmException
import com.fitcoach.app.llm.LlmProvider
import com.fitcoach.app.llm.LlmRequest
import com.fitcoach.app.llm.MediaPart
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * Prompts and schemas exported from the Python reference engine (tools/export_prompts.py -> assets/prompts.json),
 * so both implementations share one source of truth.
 */
class Prompts(json: JSONObject) {
    val extractionSystem: String = json.getString("extraction_system")
    val extractionSchema: JSONObject = json.getJSONObject("extraction_schema")
    val focusedSchemas: JSONObject = json.getJSONObject("focused_schemas")
    val focusedQuestions: JSONObject = json.getJSONObject("focused_questions")
    val coachSystem: String = json.getString("coach_system").replace("over chat (Telegram)", "inside a phone app")
    val decideInstructions: String = json.getString("decide_instructions")
    val decideSchema: JSONObject = json.getJSONObject("decide_schema")

    companion object {
        fun load(input: InputStream) = Prompts(JSONObject(input.bufferedReader().readText()))
    }
}

/** LLM extraction (port of Extractor + focused_extract). */
class Extractor(private val llm: LlmProvider, private val prompts: Prompts, private val foods: FoodTable?) {

    private fun aliases(name: String): List<String> = foods?.aliasesFor(name) ?: emptyList()

    suspend fun extract(
        text: String, media: List<MediaPart>, knownTags: List<String>, activeCommitments: JSONArray, openNudges: JSONArray,
        localTime: String, activeIds: Set<Long>, pendingInferred: JSONArray, knownPatterns: JSONArray, todaysFood: JSONArray,
    ): Pair<Extraction, JSONObject> {
        val prompt = "LOCAL_TIME: $localTime\n" +
            "KNOWN_TAGS: ${JSONArray(knownTags)}\n" +
            "ACTIVE_COMMITMENTS: $activeCommitments\n" +
            "OPEN_NUDGES: $openNudges\n" +
            "PENDING_INFERRED: $pendingInferred\n" +
            "KNOWN_PATTERNS: $knownPatterns\n" +
            "FOOD_LOGGED_TODAY: $todaysFood\n\n" +
            "USER_MESSAGE: ${text.ifBlank { "(see attached media)" }}"
        val raw = llm.generate(
            LlmRequest(prompts.extractionSystem, prompt, media = media, jsonSchema = prompts.extractionSchema, temperature = 0.0, purpose = "extract"),
        ).json()
        val message = if (media.isNotEmpty()) null else text
        return ExtractionValidator.validate(raw, activeIds, message, ::aliases) to raw
    }

    /** Small single-purpose extraction for answers to a question the coach just asked. Returns profile updates. */
    suspend fun focused(topic: String, text: String): Map<String, Any?> {
        val schema = prompts.focusedSchemas.optJSONObject(topic) ?: return emptyMap()
        val raw = llm.generate(
            LlmRequest(
                "You extract one specific thing from a user's chat reply (often Hinglish). Never invent content.",
                "${prompts.focusedQuestions.optString(topic)}\n\nUSER_MESSAGE: $text",
                jsonSchema = schema, temperature = 0.0, purpose = "extract",
            ),
        ).json()
        val input = if (topic == "usual_meals") JSONObject().put("usual_meals", raw.optJSONArray("meals") ?: JSONArray()) else raw
        return ExtractionValidator.validate(input, emptySet(), text).profileUpdates
    }
}

/** LLM-facing coaching: replies, nudges, weekly reviews - always through the safety guard (port of coach.py). */
class Coach(private val llm: LlmProvider, private val prompts: Prompts) {

    private suspend fun generateSafe(
        request: LlmRequest, fallback: String = Safety.SAFE_FALLBACK_REPLY, downFallback: String = Safety.LLM_DOWN_REPLY,
    ): Pair<String, List<String>> {
        val violations = mutableListOf<String>()
        var req = request
        repeat(2) {
            val text = try {
                llm.generate(req).text.trim()
            } catch (e: LlmException) {
                violations += "llm_error: ${e.message}"
                return downFallback to violations
            }
            val v = Safety.check(text)
            if (v.isEmpty() && text.isNotEmpty()) return text to violations
            violations += v.ifEmpty { listOf("empty reply") }
            req = req.copy(userText = req.userText + "\n\nYOUR PREVIOUS DRAFT WAS REJECTED by the safety/tone check for: " +
                v.joinToString("; ") + ". Rewrite without that.")
        }
        return fallback to violations
    }

    suspend fun reply(
        userText: String, context: JSONObject, extractionSummary: JSONObject, history: List<ChatTurn>,
        guidance: List<String>, dataResults: JSONObject? = null,
    ): Pair<String, List<String>> {
        val prompt = buildString {
            append("CONTEXT (from the database; trust this over memory):\n${context.toString(1)}\n\n")
            append("WHAT WAS JUST RECORDED FROM THIS MESSAGE:\n${extractionSummary.toString(1)}\n\n")
            if (dataResults != null && dataResults.length() > 0) {
                append("DATA YOU ASKED FOR (computed from the database; use these numbers exactly, and say when data is missing):\n${dataResults.toString(1)}\n\n")
            }
            append("COACHING GUIDANCE FOR THIS TURN (decided by the coaching policy):\n- ")
            append(if (guidance.isNotEmpty()) guidance.joinToString("\n- ") else "Respond naturally; acknowledge briefly what was logged.")
            append("\n\nUSER MESSAGE:\n$userText")
        }
        return generateSafe(LlmRequest(prompts.coachSystem, prompt, history = history, temperature = 0.6, purpose = "reply"))
    }

    suspend fun nudge(commitment: JSONObject, level: Int, version: String, pattern: JSONObject, context: JSONObject, reason: String): Pair<String, List<String>> {
        val instructions = when (level) {
            1 -> "Gentle, one-line check-in on the planned action. Make it easy to say yes."
            2 -> "Reference the specific situation/pattern. Suggest the offered version as the default."
            3 -> "Accountability: state the observed pattern factually (numbers), without blame. Say the approach should change " +
                "and propose one concrete change (time, size, or trigger). Ask one question about what gets in the way."
            4 -> "Strong recommendation: clearly and warmly recommend doing the offered (smaller) version now rather than skipping."
            else -> "User-authorised enforcement: remind them they asked you to hold them to this. Ask for the minimum version, " +
                "or an explicit reason and a reschedule if they truly cannot. Firm, never harsh."
        }
        val prompt = "Write a proactive message (level $level: ${Policy.LEVEL_NAMES[level]}).\n" +
            "Instruction: $instructions\nWhy this message now (policy summary): $reason\n" +
            "Commitment: ${commitment.toString(1)}\nVersion to offer: $version\nObserved pattern: ${pattern.toString(1)}\n" +
            "Today so far: ${context.toString(1)}\n" +
            "The message will be shown with buttons: Done / Smaller version / Later / Skip today. Do not list the buttons."
        val template = "${commitment.optString("title")}: how about $version now?"
        return generateSafe(LlmRequest(prompts.coachSystem, prompt, temperature = 0.7, maxOutputTokens = 1024, purpose = "nudge"), template, template)
    }

    suspend fun weeklyReview(stats: JSONObject): Pair<String, List<String>> {
        val prompt = "Write the weekly review message. Structure: one line on what went well (with numbers), one line on the main " +
            "pattern that got in the way, then propose ONE small experiment for next week (specific trigger, action, and how " +
            "we'll measure it) and ask if they agree. Under 120 words. Do not change calorie targets unless " +
            "stats.target_change_allowed is true.\nSTATS: ${stats.toString(1)}"
        return generateSafe(LlmRequest(prompts.coachSystem, prompt, temperature = 0.5, maxOutputTokens = 500, purpose = "review"))
    }
}
