package com.fitcoach.app.llm

import com.fitcoach.app.core.parseJsonLoose
import org.json.JSONObject

open class LlmException(message: String) : Exception(message)
class CapabilityException(message: String) : LlmException(message)

class MediaPart(val data: ByteArray, val mimeType: String) {
    val kind: String get() = mimeType.substringBefore('/')
}

data class ChatTurn(val role: String, val text: String) // role: user | assistant

data class LlmRequest(
    val system: String,
    val userText: String,
    val history: List<ChatTurn> = emptyList(),
    val media: List<MediaPart> = emptyList(),
    val jsonSchema: JSONObject? = null,
    val temperature: Double = 0.4,
    val maxOutputTokens: Int = 4096, // thinking tokens count toward this on Gemini 3.x
    val purpose: String = "generic",
)

data class LlmResponse(val text: String, val provider: String, val model: String) {
    fun json(): JSONObject = (parseJsonLoose(text) as? JSONObject) ?: throw LlmException("Response is not a JSON object")
}

interface LlmProvider {
    val name: String
    suspend fun generate(request: LlmRequest): LlmResponse
    fun usage(): Map<String, String> = emptyMap()
}
