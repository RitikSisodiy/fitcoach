package com.fitcoach.app.engine

/** Deterministic guard on every outgoing coach message (port of coach/engine/safety.py). */
object Safety {
    const val MIN_SAFE_DAILY_KCAL = 1200

    private val SHAME = listOf(
        "\\blazy\\b", "\\bfailed again\\b", "\\byou failed\\b", "\\bno discipline\\b", "\\black of discipline\\b",
        "\\bruin(ing|ed)? (your|ur) life\\b", "\\bdisappointed (in|with) you\\b", "\\byou disappointed me\\b", "\\bpathetic\\b",
        "\\bshame on you\\b", "\\bworthless\\b", "\\bkaamchor\\b", "\\bnikamma\\b", "\\bsharam\\b",
    ).map(::Regex)

    private val UNSAFE = listOf(
        "\\b(skip|avoid)\\b.{0,20}\\b(water|drinking water|pani)\\b" to "dehydration advice",
        "\\bdehydrat" to "dehydration advice",
        "\\blaxative" to "laxatives",
        "\\bdiuretic" to "diuretics",
        "\\bstarv" to "starvation",
        "\\b(skip|don'?t eat)\\b.{0,30}\\b(all|every|next)\\b.{0,15}\\b(meals?|day)\\b" to "meal skipping to compensate",
        "\\b(stop|change|reduce|increase) (your )?(medication|medicine|dose|dawai)\\b" to "medication change",
        "\\bmake up for (it|that) by (not eating|skipping)" to "compensatory restriction",
        "\\bpurg(e|ing)\\b" to "purging",
    ).map { Regex(it.first) to it.second }

    private val KCAL_TARGET = Regex(
        "\\b(eat|target|limit|stay under|keep it under|restrict to|only)\\b[^.\\n]{0,25}?\\b(\\d{3,4})\\s*(k?cal|calories)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun check(text: String): List<String> {
        val lower = text.lowercase()
        val v = mutableListOf<String>()
        SHAME.forEach { if (it.containsMatchIn(lower)) v += "shame language: ${it.pattern}" }
        UNSAFE.forEach { (re, label) -> if (re.containsMatchIn(lower)) v += "unsafe advice: $label" }
        KCAL_TARGET.findAll(text).forEach { m ->
            val tail = text.substring(m.range.first, minOf(text.length, m.range.last + 21)).lowercase()
            if (m.groupValues[2].toInt() < MIN_SAFE_DAILY_KCAL && "day" in tail) v += "unsafe calorie target: ${m.groupValues[2]}"
        }
        return v
    }

    fun ok(text: String) = check(text).isEmpty()
}
