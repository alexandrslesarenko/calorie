package com.calorie.app.data

import android.content.Context
import com.calorie.app.BuildConfig
import com.calorie.app.logic.Nutrients
import com.calorie.app.logic.OffParser
import com.calorie.app.logic.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Barcode lookup in Open Food Facts (open database, no key).
 * The database asks clients to identify themselves with a User-Agent like "App/version".
 */
object OffApi {
    private const val TIMEOUT_MS = 15_000
    private const val FIELDS = "product_name,product_name_ru,product_name_en,generic_name,brands," +
        "nutriments,serving_quantity,serving_quantity_unit"

    /** Cache first, then network. null - not in the database. Network errors are thrown. */
    suspend fun find(ctx: Context, barcode: String): Product? {
        val dao = FoodDb.get(ctx).dao()
        dao.product(barcode)?.let { c ->
            return Product(c.barcode, c.name, c.brand, Nutrients(c.kcal, c.protein, c.fat, c.carbs), c.servingG)
        }
        val lang = Locale.getDefault().language
        val json = withContext(Dispatchers.IO) { fetch(barcode, lang) } ?: return null
        val p = OffParser.parse(barcode, json, lang) ?: return null
        dao.putProduct(
            CachedProduct(p.barcode, p.name, p.brand, p.per100.kcal, p.per100.protein, p.per100.fat, p.per100.carbs, p.servingG, System.currentTimeMillis())
        )
        return p
    }

    private fun fetch(barcode: String, lang: String): String? {
        val code = barcode.filter { it.isDigit() }
        val fields = FIELDS.replace("product_name_ru", "product_name_$lang")
        val url = URL("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=$fields")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "Calorie/${BuildConfig.VERSION_NAME} (Android)")
        return try {
            // An unknown code comes back as 404 with status 0 in the body.
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
