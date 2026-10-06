package com.fitcoach.app.core

import org.json.JSONArray
import org.json.JSONObject

/** Null-safe accessors: org.json returns "null" strings and NaN for JSON null, which is never what we want. */
fun JSONObject.strOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
fun JSONObject.numOrNull(k: String): Double? = if (!has(k) || isNull(k)) null else when (val v = opt(k)) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}
fun JSONObject.boolOr(k: String, default: Boolean = false): Boolean = if (!has(k) || isNull(k)) default else optBoolean(k, default)
fun JSONObject.arr(k: String): JSONArray = optJSONArray(k) ?: JSONArray()
fun JSONObject.obj(k: String): JSONObject? = optJSONObject(k)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { if (isNull(it)) null else optString(it).takeIf(String::isNotBlank) }

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
}

fun Iterable<*>.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it ?: JSONObject.NULL) } }

/** Parse JSON that may be wrapped in markdown fences or prose. */
fun parseJsonLoose(text: String): Any {
    var t = text.trim()
    Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL).find(t)?.let { t = it.groupValues[1].trim() }
    return try {
        if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
    } catch (e: Exception) {
        val start = listOf(t.indexOf('{'), t.indexOf('[')).filter { it >= 0 }.minOrNull() ?: throw IllegalArgumentException("not JSON")
        val end = maxOf(t.lastIndexOf('}'), t.lastIndexOf(']'))
        val s = t.substring(start, end + 1)
        if (s.startsWith("[")) JSONArray(s) else JSONObject(s)
    }
}
