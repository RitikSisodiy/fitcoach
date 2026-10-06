package com.fitcoach.app.llm


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/**
 * Gemini over REST, built around the free-tier limits measured live (AI Studio, Oct 2026):
 *   3.x Flash: 5 req/min, 20 req/day per model.   3.x Flash-Lite: 15 req/min, 500 req/day per model.
 * Text work goes to the Lite pool first (fast, ~1000/day); photos, voice and the weekly review go to Flash first.
 * Each model is paced locally (RPM/RPD, persisted), skipped on 429/503/timeout, and the next model is tried.
 * Same design as the Python reference (coach/llm/gemini.py, DECISIONS D-021).
 */
class GeminiProvider(
    private val apiKey: String,
    private val fastModels: List<String> = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite"),
    private val smartModels: List<String> = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash"),
    private val thinkingLevel: String = "low",
    private val paidTier: Boolean = false,
    private val usageFile: File? = null,
    private val transport: Transport = HttpTransport(),
) : LlmProvider {
    override val name = "gemini"

    fun interface Transport {
        /** Returns (httpCode, body). */
        suspend fun post(url: String, apiKey: String, body: String, timeoutMs: Int): Pair<Int, String>
    }

    private class Budget(val rpm: Int, val rpd: Int) {
        val minute = ArrayDeque<Long>()
        var dayKey = ""
        var dayCount = 0
        var blockedUntil = 0L
        var blockedDay = ""
    }

    private val budgets = (smartModels + fastModels).distinct().associateWith { m ->
        if (paidTier) Budget(0, 0) else freeTierLimits(m).let { Budget(it.first, it.second) }
    }
    private val lock = Mutex()

    init {
        loadUsage()
    }

    private fun today(): String = LocalDate.now(QUOTA_ZONE).toString()

    private fun roll(b: Budget, nowMs: Long) {
        if (b.dayKey != today()) { b.dayKey = today(); b.dayCount = 0 }
        while (b.minute.isNotEmpty() && nowMs - b.minute.first() >= 60_000) b.minute.removeFirst()
    }

    /** Milliseconds until usable; null if not usable today. */
    private fun waitMs(b: Budget, nowMs: Long): Long? {
        roll(b, nowMs)
        if (b.blockedDay == today() || (b.rpd > 0 && b.dayCount >= b.rpd)) return null
        var wait = maxOf(0L, b.blockedUntil - nowMs)
        if (b.rpm > 0 && b.minute.size >= b.rpm) wait = maxOf(wait, 60_000 - (nowMs - b.minute.first()) + 50)
        return wait
    }

    fun route(request: LlmRequest): List<String> =
        if (request.media.isNotEmpty() || request.purpose !in FAST_PURPOSES) (smartModels + fastModels).distinct()
        else (fastModels + smartModels).distinct()

    private suspend fun pick(models: List<String>): String? = lock.withLock {
        val now = System.currentTimeMillis()
        val waits = models.associateWith { waitMs(budgets.getValue(it), now) }
        val ready = models.firstOrNull { waits[it] == 0L }
        val chosen = ready ?: waits.filterValues { it != null && it <= MAX_WAIT_MS }.minByOrNull { it.value!! }?.also { delay(it.value!!) }?.key
        chosen?.let { m -> budgets.getValue(m).let { b -> roll(b, System.currentTimeMillis()); b.minute.addLast(System.currentTimeMillis()); b.dayCount++ } }
        saveUsage()
        chosen
    }

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val candidates = route(request)
        val errors = mutableListOf<String>()
        val body = buildBody(request)
        repeat(candidates.size + 2) {
            val model = pick(candidates) ?: return@repeat
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            val result = withTimeoutOrNull(CALL_TIMEOUT_MS) { transport.post(url, apiKey, body(model).toString(), CALL_TIMEOUT_MS.toInt()) }
            val b = budgets.getValue(model)
            if (result == null) {
                errors += "$model: timeout"; b.blockedUntil = System.currentTimeMillis() + SLOW_COOLDOWN_MS; return@repeat
            }
            val (code, text) = result
            if (code == 200) {
                val out = extractText(text)
                if (out.isNullOrBlank()) { errors += "$model: empty"; return@repeat }
                return LlmResponse(out, name, model)
            }
            errors += "$model: HTTP $code ${text.take(80)}"
            when {
                code == 429 && text.contains("PerDay") -> { b.blockedDay = today(); saveUsage() }
                code == 429 -> b.blockedUntil = System.currentTimeMillis() + maxOf(retryDelayMs(text) ?: 0, 30_000)
                code == 504 -> b.blockedUntil = System.currentTimeMillis() + SLOW_COOLDOWN_MS
                code in listOf(500, 503) -> b.blockedUntil = System.currentTimeMillis() + 60_000
                else -> throw LlmException("Gemini call failed: HTTP $code ${text.take(300)}")
            }
        }
        throw LlmException("Gemini unavailable on all models (quota/overload): " + errors.takeLast(4).joinToString(" | "))
    }

    private fun buildBody(request: LlmRequest): (String) -> JSONObject = { model ->
        val contents = JSONArray()
        request.history.forEach { t ->
            contents.put(JSONObject().put("role", if (t.role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
        }
        val parts = JSONArray()
        request.media.forEach { m ->
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", m.mimeType).put("data", java.util.Base64.getEncoder().encodeToString(m.data))))
        }
        parts.put(JSONObject().put("text", request.userText.ifBlank { "(no text)" }))
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val gen = JSONObject().put("temperature", request.temperature).put("maxOutputTokens", request.maxOutputTokens)
        if (thinkingLevel.isNotBlank() && !model.startsWith("gemini-2")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        if (request.jsonSchema != null) {
            gen.put("responseMimeType", "application/json").put("responseJsonSchema", request.jsonSchema)
        }
        JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.system))))
            .put("contents", contents).put("generationConfig", gen)
    }

    override fun usage(): Map<String, String> = budgets.mapValues { (_, b) ->
        roll(b, System.currentTimeMillis())
        "${b.dayCount}/${if (b.rpd > 0) b.rpd else "∞"}" + if (b.blockedDay == today()) " (exhausted)" else ""
    }

    private fun loadUsage() {
        val f = usageFile ?: return
        if (!f.exists()) return
        runCatching {
            val data = JSONObject(f.readText())
            budgets.forEach { (m, b) ->
                val e = data.optJSONObject(m) ?: return@forEach
                if (e.optString("day") == today()) {
                    b.dayKey = today(); b.dayCount = e.optInt("count")
                    if (e.optBoolean("exhausted")) b.blockedDay = today()
                }
            }
        }
    }

    private fun saveUsage() {
        val f = usageFile ?: return
        val data = JSONObject()
        budgets.forEach { (m, b) -> data.put(m, JSONObject().put("day", b.dayKey).put("count", b.dayCount).put("exhausted", b.blockedDay == b.dayKey)) }
        runCatching { f.writeText(data.toString()) }
    }

    class HttpTransport : Transport {
        override suspend fun post(url: String, apiKey: String, body: String, timeoutMs: Int): Pair<Int, String> = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } catch (e: java.net.SocketTimeoutException) {
                504 to "DEADLINE (client timeout)"
            } catch (e: java.io.IOException) {
                503 to "network: ${e.message}"
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        val QUOTA_ZONE: ZoneId = ZoneId.of("America/Los_Angeles") // quotas reset at midnight Pacific
        const val CALL_TIMEOUT_MS = 20_000L
        const val SLOW_COOLDOWN_MS = 300_000L
        const val MAX_WAIT_MS = 20_000L
        val FAST_PURPOSES = setOf("decide", "nudge", "generic", "extract", "reply")

        fun freeTierLimits(model: String): Pair<Int, Int> {
            val m = model.lowercase()
            return if ("lite" in m) (if (m.startsWith("gemini-2.5")) 10 to 20 else 15 to 500) else 5 to 20
        }

        fun extractText(body: String): String? = runCatching {
            val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).map { parts.getJSONObject(it) }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
        }.getOrNull()

        fun retryDelayMs(body: String): Long? =
            Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(body)?.groupValues?.get(1)?.toDouble()?.let { (it * 1000).toLong() }
    }
}
