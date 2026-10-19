package com.calorie.app.logic

import org.json.JSONArray
import org.json.JSONObject

enum class Confidence { LOW, MEDIUM, HIGH }

data class AiItem(val draft: FoodDraft, val confidence: Confidence)

/** What the model recognized: dishes and a short remark (for example, "no food in the photo"). */
data class AiResult(val items: List<AiItem>, val note: String?)

/**
 * Parses the report_food tool input. The model fills the schema, but without strict it may
 * deviate: the nested array as a string, numbers with units ("200 g"). Those are accepted;
 * an empty name or kcal without a number are skipped instead of failing.
 */
object AiParser {
    private val LEADING_NUMBER = Regex("""^\s*(\d+(?:[.,]\d+)?)""")

    fun parse(json: String): AiResult {
        val root = JSONObject(json)
        val arr = when (val v = root.opt("items")) {
            is JSONArray -> v
            is String -> runCatching { JSONArray(v) }.getOrNull()
            else -> null
        }
        val items = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val kcal = num(o, "kcal")
                if (name.isEmpty() || kcal == null) continue
                val grams = num(o, "grams")?.takeIf { it > 0 } ?: 0.0
                val portion = Nutrients(kcal, pos(o, "protein_g"), pos(o, "fat_g"), pos(o, "carbs_g"))
                val conf = when (o.optString("confidence").lowercase()) {
                    "high" -> Confidence.HIGH
                    "low" -> Confidence.LOW
                    else -> Confidence.MEDIUM
                }
                add(AiItem(FoodDraft.fromPortion(name, grams, portion), conf))
            }
        }
        val note = root.optString("note").trim().takeIf { it.isNotEmpty() }
        return AiResult(items, note)
    }

    private fun pos(o: JSONObject, key: String): Double = num(o, key)?.takeIf { it > 0 } ?: 0.0

    /** Non-negative number from a field: a number or a string starting with one. */
    private fun num(o: JSONObject, key: String): Double? = when (val v = o.opt(key)) {
        is Number -> v.toDouble()
        is String -> LEADING_NUMBER.find(v)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        else -> null
    }?.takeIf { !it.isNaN() && it >= 0 }
}

/**
 * Spending in nano-dollars per token (prices of each Recognizer; Claude prompts here are a few
 * thousand tokens, under the 100K price step): whole numbers, so the counter adds up without rounding.
 */
object Pricing {
    fun nanoUsd(inputTokens: Long, outputTokens: Long, r: Recognizer = Recognizer.DEFAULT): Long =
        inputTokens * r.inputNano + outputTokens * r.outputNano

    /** Counters from before per-model prices were all Haiku 4.5 ($1 / $5 per million, no longer in the list). */
    fun legacyNanoUsd(inputTokens: Long, outputTokens: Long): Long = inputTokens * 1000 + outputTokens * 5000

    fun usd(nano: Long): Double = nano / 1e9
}
