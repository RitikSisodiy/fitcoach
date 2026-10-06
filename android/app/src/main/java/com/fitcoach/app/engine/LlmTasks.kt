package com.fitcoach.app.engine

import com.fitcoach.app.llm.ChatTurn
import com.fitcoach.app.llm.LlmException
import com.fitcoach.app.llm.LlmProvider
import com.fitcoach.app.llm.LlmRequest
import com.fitcoach.app.llm.MediaPart
import org.json.JSONObject
import java.io.File

/** Prompts and JSON schemas, one file each under assets/prompts/ (the single source of truth). */
class Prompts(read: (String) -> String) {
    val extractionSystem = read("extraction_system.txt")
    val extractionSchema = JSONObject(read("extraction_schema.json"))
    val coachSystem = read("coach_system.txt")
    val agentSystem = read("agent_system.txt")
    val agentSchema = JSONObject(read("agent_schema.json"))
    val reflectSystem = read("reflect_system.txt")
    val reflectSchema = JSONObject(read("reflect_schema.json"))

    companion object {
        fun fromAssets(assets: android.content.res.AssetManager) = Prompts { name -> assets.open("prompts/$name").bufferedReader().use { it.readText() } }
        fun fromDir(dir: File) = Prompts { name -> File(dir, name).readText() }
    }
}

/** Understand: the LLM turns a message into structured data; [ExtractionValidator] keeps only what is grounded. */
class Extractor(private val llm: LlmProvider, private val prompts: Prompts, private val foods: FoodTable?) {

    private fun aliases(name: String): List<String> = foods?.aliasesFor(name) ?: emptyList()

    suspend fun extract(text: String, media: List<MediaPart>, context: JSONObject, activeIds: Set<Long>, usualFoods: Set<String>): Pair<Extraction, JSONObject> {
        val prompt = context.keys().asSequence().joinToString("\n") { k -> "$k: ${context.get(k)}" } +
            "\n\nUSER_MESSAGE: ${text.ifBlank { "(see attached media)" }}"
        val raw = llm.generate(
            LlmRequest(prompts.extractionSystem, prompt, media = media, jsonSchema = prompts.extractionSchema, temperature = 0.0, purpose = "extract"),
        ).json()
        val message = if (media.isNotEmpty()) null else text
        return ExtractionValidator.validate(raw, activeIds, message, ::aliases, usualFoods) to raw
    }
}

/** Replies in conversation. Every text passes the safety guard; an unsafe draft is rewritten by the LLM, never templated. */
class Coach(private val llm: LlmProvider, private val prompts: Prompts) {

    /** Returns (text, problems). Text is null when the LLM is unavailable or could not produce a safe reply. */
    suspend fun generateSafe(request: LlmRequest): Pair<String?, List<String>> {
        val problems = mutableListOf<String>()
        var req = request
        repeat(2) {
            val text = try {
                llm.generate(req).text.trim()
            } catch (e: LlmException) {
                problems += "llm_error: ${e.message}"
                return null to problems
            }
            val v = Safety.check(text)
            if (v.isEmpty() && text.isNotEmpty()) return text to problems
            problems += v.ifEmpty { listOf("empty reply") }
            req = req.copy(userText = req.userText + "\n\nYOUR PREVIOUS DRAFT WAS REJECTED by the safety/tone check for: " +
                v.joinToString("; ") + ". Rewrite without that.")
        }
        return null to problems
    }

    suspend fun reply(
        userText: String, context: JSONObject, extractionSummary: JSONObject, history: List<ChatTurn>,
        guidance: List<String>, dataResults: JSONObject? = null,
    ): Pair<String?, List<String>> {
        val prompt = buildString {
            append("MEMORY AND DATA (from the database; trust this over the chat history):\n${context.toString(1)}\n\n")
            append("WHAT WAS JUST RECORDED FROM THIS MESSAGE:\n${extractionSummary.toString(1)}\n\n")
            if (dataResults != null && dataResults.length() > 0) {
                append("DATA YOU ASKED FOR (computed from the database; use these numbers exactly, and say when data is missing):\n${dataResults.toString(1)}\n\n")
            }
            append("NOTES FOR THIS TURN (facts and safety rules from code):\n- ")
            append(if (guidance.isNotEmpty()) guidance.joinToString("\n- ") else "nothing special")
            append("\n\nUSER MESSAGE:\n$userText")
        }
        return generateSafe(LlmRequest(prompts.coachSystem, prompt, history = history, temperature = 0.6, purpose = "reply"))
    }

    companion object {
        /** Infrastructure status lines (not coaching): shown only when the AI cannot answer. */
        const val AI_UNAVAILABLE = "(Saved. The AI is unavailable right now, so I can't reply properly - I'll pick this up when it's back.)"
        const val NO_SAFE_REPLY = "(Saved. I couldn't phrase a reply safely - can you say that another way?)"
    }
}
