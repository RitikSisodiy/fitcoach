package com.fitcoach.app.engine

import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Deterministic nutrition estimation with explicit uncertainty (port of coach/nutrition/estimator.py).
 * The LLM identifies foods and household quantities; this turns them into kcal/protein RANGES.
 */
data class FoodRow(
    val key: String, val aliases: List<String>, val defaultUnit: String, val gramsPerUnit: Double,
    val kcalLow: Double, val kcalHigh: Double, val proteinLow: Double, val proteinHigh: Double, val category: String,
)

data class NutritionEstimate(
    val foodKey: String?, val kcalLow: Double?, val kcalHigh: Double?, val proteinLow: Double?, val proteinHigh: Double?,
    val source: String, val confidence: Double, val note: String = "",
)

class FoodTable(rows: List<FoodRow>) {
    val rows: Map<String, FoodRow> = rows.associateBy { it.key }
    private val aliasIndex = LinkedHashMap<String, String>()

    init {
        rows.forEach { r -> (r.aliases + r.key.replace('_', ' ')).forEach { aliasIndex.putIfAbsent(norm(it), r.key) } }
    }

    /** Returns (row, kind) where kind is exact | close | partial | none. */
    fun match(name: String): Pair<FoodRow?, String> {
        val n = norm(name)
        val candidates = listOf(n) + (if (n.endsWith("es")) listOf(n.dropLast(2)) else emptyList()) + (if (n.endsWith("s")) listOf(n.dropLast(1)) else emptyList())
        candidates.firstOrNull { it in aliasIndex }?.let { return rows[aliasIndex[it]] to "exact" }
        val close = aliasIndex.keys.map { it to similarity(n, it) }.filter { it.second >= 0.86 }.maxByOrNull { it.second }
        if (close != null) return rows[aliasIndex[close.first]] to "close"
        var best: String? = null
        for (alias in aliasIndex.keys) {
            if (alias.length >= 3 && Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(n)) {
                if (best == null || alias.length > best.length) best = alias
            }
        }
        return if (best != null) rows[aliasIndex[best]] to "partial" else null to "none"
    }

    fun aliasesFor(name: String): List<String> {
        val (row, kind) = match(name)
        return if (row != null && kind != "none") row.aliases else emptyList()
    }

    fun estimate(name: String, quantity: Double?, unit: String?, llmKcal: Pair<Double?, Double?> = null to null,
                 llmProtein: Pair<Double?, Double?> = null to null, extractionConfidence: Double = 0.8): NutritionEstimate {
        val (row, kind) = match(name)
        if (row != null && (kind == "exact" || kind == "close")) return fromRow(row, quantity, unit, if (kind == "exact") 0.85 else 0.7, extractionConfidence)
        val (kl, kh) = llmKcal
        if (kl != null && kh != null && kl >= 0 && kl <= kh && kh <= 5000) {
            val (pl, ph) = llmProtein
            return NutritionEstimate(null, (kl * 0.8).roundToInt().toDouble(), (kh * 1.2).roundToInt().toDouble(),
                pl?.let { r1(it * 0.8) }, ph?.let { r1(it * 1.2) }, "llm_estimate", minOf(0.5, extractionConfidence),
                "not in food table; LLM estimate widened by 20%")
        }
        if (row != null) return fromRow(row, quantity, unit, 0.45, extractionConfidence).let { it.copy(note = (it.note + "; partial match to '${row.key}'").trim(';', ' ')) }
        return NutritionEstimate(null, null, null, null, null, "unknown", 0.0, "food not recognised")
    }

    private fun fromRow(row: FoodRow, quantity: Double?, unit: String?, matchConf: Double, extractionConfidence: Double): NutritionEstimate {
        val qty = if (quantity != null && quantity > 0) quantity else 1.0
        var widen = if (quantity != null) 0.0 else UNKNOWN_QTY_WIDEN
        val u = normalizeUnit(unit)
        val factor: Double
        var note = if (quantity == null) "quantity assumed 1" else ""
        when {
            u == null || u == row.defaultUnit || (u == "piece" && row.defaultUnit in setOf("piece", "slice")) -> factor = qty
            u == "kg" -> { factor = qty * 1000 / row.gramsPerUnit; widen += CONVERSION_WIDEN; note = "converted from kg" }
            u in GENERIC_UNIT_GRAMS -> { factor = qty * GENERIC_UNIT_GRAMS.getValue(u) / row.gramsPerUnit; widen += CONVERSION_WIDEN; note = "converted $u->${row.defaultUnit}" }
            else -> { factor = qty; widen += 2 * CONVERSION_WIDEN; note = "unknown unit '$unit', assumed ${row.defaultUnit}" }
        }
        return NutritionEstimate(
            row.key, (row.kcalLow * factor * (1 - widen)).roundToInt().toDouble(), (row.kcalHigh * factor * (1 + widen)).roundToInt().toDouble(),
            r1(row.proteinLow * factor * (1 - widen)), r1(row.proteinHigh * factor * (1 + widen)), "food_table",
            ((minOf(matchConf, extractionConfidence) * (1 - widen)) * 100).roundToInt() / 100.0, note,
        )
    }

    companion object {
        const val CONVERSION_WIDEN = 0.15
        const val UNKNOWN_QTY_WIDEN = 0.10
        val GENERIC_UNIT_GRAMS = mapOf(
            "g" to 1.0, "gram" to 1.0, "ml" to 1.0, "katori" to 150.0, "bowl" to 200.0, "cup" to 150.0, "glass" to 250.0,
            "plate" to 250.0, "handful" to 30.0, "tbsp" to 15.0, "tsp" to 5.0, "slice" to 30.0, "scoop" to 30.0,
            "serving" to 150.0, "can" to 330.0, "packet" to 70.0,
        )
        val UNIT_SYNONYMS = mapOf(
            "pc" to "piece", "pcs" to "piece", "pieces" to "piece", "nos" to "piece", "no" to "piece", "unit" to "piece",
            "grams" to "g", "gm" to "g", "gms" to "g", "bowls" to "bowl", "katoris" to "katori", "cups" to "cup",
            "glasses" to "glass", "plates" to "plate", "spoon" to "tbsp", "tablespoon" to "tbsp", "teaspoon" to "tsp",
            "chammach" to "tbsp", "mutthi" to "handful", "slices" to "slice", "scoops" to "scoop", "packets" to "packet",
        )

        fun norm(t: String): String = t.lowercase().replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()
        fun normalizeUnit(u: String?): String? = u?.let { norm(it) }?.takeIf { it.isNotEmpty() }?.let { UNIT_SYNONYMS[it] ?: it }
        private fun r1(v: Double) = (v * 10).roundToInt() / 10.0

        /** Ratcliff/Obershelp-style similarity (like Python difflib's ratio), good enough for short food names. */
        fun similarity(a: String, b: String): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            fun matches(x: String, y: String): Int {
                if (x.isEmpty() || y.isEmpty()) return 0
                var bestI = 0; var bestJ = 0; var bestK = 0
                for (i in x.indices) for (j in y.indices) {
                    var k = 0
                    while (i + k < x.length && j + k < y.length && x[i + k] == y[j + k]) k++
                    if (k > bestK) { bestI = i; bestJ = j; bestK = k }
                }
                if (bestK == 0) return 0
                return bestK + matches(x.substring(0, bestI), y.substring(0, bestJ)) + matches(x.substring(bestI + bestK), y.substring(bestJ + bestK))
            }
            return 2.0 * matches(a, b) / (a.length + b.length)
        }

        fun load(input: InputStream): FoodTable {
            val lines = input.bufferedReader().readLines().filter { it.isNotBlank() }
            val header = lines.first().split(",")
            val rows = lines.drop(1).map { line ->
                val c = line.split(",")
                val m = header.zip(c).toMap()
                FoodRow(m.getValue("key"), m.getValue("aliases").split("|").map(String::trim).filter(String::isNotEmpty), m.getValue("default_unit"),
                    m.getValue("grams_per_unit").toDouble(), m.getValue("kcal_low").toDouble(), m.getValue("kcal_high").toDouble(),
                    m.getValue("protein_low").toDouble(), m.getValue("protein_high").toDouble(), m.getValue("category"))
            }
            return FoodTable(rows)
        }
    }
}
