package com.calorie.app.logic

import org.json.JSONObject

/** Open Food Facts product: values per 100 g, serving size from the package if given. */
data class Product(
    val barcode: String,
    val name: String,
    val brand: String?,
    val per100: Nutrients,
    val servingG: Double?,
)

/**
 * Parses an Open Food Facts API v2 response (/api/v2/product/<code>.json).
 * null - the product is not in the database or has no energy value: counting it as zero is worse
 * than honestly offering manual entry.
 */
object OffParser {
    fun parse(barcode: String, json: String, lang: String): Product? {
        val root = JSONObject(json)
        if (root.optInt("status", 0) != 1) return null
        val p = root.optJSONObject("product") ?: return null
        val n = p.optJSONObject("nutriments") ?: return null
        val kcal = num(n, "energy-kcal_100g")
            ?: num(n, "energy-kj_100g")?.let { Energy.kjToKcal(it) }
            // "energy_100g" in the database is always kJ.
            ?: num(n, "energy_100g")?.let { Energy.kjToKcal(it) }
            ?: return null
        val name = listOf("product_name_$lang", "product_name", "generic_name_$lang", "generic_name")
            .firstNotNullOfOrNull { k -> p.optString(k).trim().takeIf { it.isNotEmpty() } }
            ?: barcode
        val brand = p.optString("brands").split(',').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val serving = num(p, "serving_quantity")?.takeIf { it > 0 && servingInGrams(p) }
        return Product(
            barcode, name, brand,
            Nutrients(kcal, num(n, "proteins_100g") ?: 0.0, num(n, "fat_100g") ?: 0.0, num(n, "carbohydrates_100g") ?: 0.0),
            serving,
        )
    }

    /** serving_quantity may be in ml; for drinks 1 ml ~ 1 g, other units (pieces, ounces) are ignored. */
    private fun servingInGrams(p: JSONObject): Boolean {
        val unit = p.optString("serving_quantity_unit").lowercase()
        return unit.isEmpty() || unit == "g" || unit == "ml"
    }

    /** Number from a field: the database has both numbers and strings ("15", "15,5"). */
    private fun num(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toDouble()
            is String -> v.trim().replace(',', '.').toDoubleOrNull()
            else -> null
        }?.takeIf { !it.isNaN() && it >= 0 }
    }
}
