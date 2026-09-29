package com.calorie.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsersTest {
    @Test
    fun offFullProduct() {
        val json = """{"status":1,"product":{"product_name":"Nutella","product_name_ru":"Нутелла","brands":"Ferrero, Nutella",
            "serving_quantity":"15","nutriments":{"energy-kcal_100g":539,"proteins_100g":6.3,"fat_100g":30.9,"carbohydrates_100g":57.5}}}"""
        val p = OffParser.parse("3017620422003", json, "ru")!!
        assertEquals("Нутелла", p.name)
        assertEquals("Ferrero", p.brand)
        assertEquals(539.0, p.per100.kcal, 0.01)
        assertEquals(15.0, p.servingG!!, 0.01)
    }

    @Test
    fun offEnergyOnlyInKj() {
        val json = """{"status":1,"product":{"product_name":"Water","nutriments":{"energy_100g":"418,4"}}}"""
        val p = OffParser.parse("1", json, "en")!!
        assertEquals(100.0, p.per100.kcal, 0.01)
        assertEquals(0.0, p.per100.protein, 0.0)
        assertNull(p.servingG)
    }

    @Test
    fun offNotFoundOrNoEnergy() {
        assertNull(OffParser.parse("1", """{"status":0,"status_verbose":"product not found"}""", "en"))
        assertNull(OffParser.parse("1", """{"status":1,"product":{"product_name":"X","nutriments":{}}}""", "en"))
    }

    @Test
    fun offServingInPiecesIgnored() {
        val json = """{"status":1,"product":{"product_name":"Eggs","serving_quantity":2,"serving_quantity_unit":"pcs",
            "nutriments":{"energy-kcal_100g":143}}}"""
        assertNull(OffParser.parse("1", json, "en")!!.servingG)
    }

    @Test
    fun aiItemsScaledToPer100() {
        val json = """{"items":[
            {"name":"Borscht","grams":300,"kcal":150,"protein_g":6,"fat_g":6,"carbs_g":18,"confidence":"high"},
            {"name":"","grams":50,"kcal":100},
            {"name":"Bread","grams":0,"kcal":80,"confidence":"weird"}],"note":" ok "}"""
        val r = AiParser.parse(json)
        assertEquals(2, r.items.size)
        val borscht = r.items[0]
        assertEquals(Confidence.HIGH, borscht.confidence)
        assertEquals(50.0, borscht.draft.per100.kcal, 0.01)
        assertEquals(150.0, borscht.draft.total.kcal, 0.01)
        // Without a weight the portion counts as 100 g.
        assertEquals(100.0, r.items[1].draft.grams, 0.0)
        assertEquals(Confidence.MEDIUM, r.items[1].confidence)
        assertEquals("ok", r.note)
    }

    @Test
    fun aiItemsAsStringAndNumbersWithUnits() {
        val inner = """[{"name":"Soup","grams":"250 g","kcal":"120,5 kcal","protein_g":"6g"}]"""
        val json = org.json.JSONObject().put("items", inner).toString()
        val r = AiParser.parse(json)
        assertEquals(1, r.items.size)
        val d = r.items[0].draft
        assertEquals(250.0, d.grams, 0.0)
        assertEquals(120.5, d.total.kcal, 0.01)
        assertEquals(6.0, d.total.protein, 0.01)
    }

    @Test
    fun aiItemWithoutKcalNumberSkipped() {
        val r = AiParser.parse("""{"items":[{"name":"Tea","grams":200,"kcal":"unknown"},{"name":"Negative","kcal":-5}]}""")
        assertEquals(0, r.items.size)
    }

    @Test
    fun draftRescalesOnGramsChange() {
        val d = FoodDraft.fromPortion("Rice", 200.0, Nutrients(260.0, 5.0, 1.0, 56.0))
        assertEquals(195.0, d.copy(grams = 150.0).total.kcal, 0.01)
    }

    @Test
    fun price() {
        assertEquals(0.0035, Pricing.usd(1500, 400), 1e-9)
    }

    @Test
    fun gramsText() {
        assertEquals("150", 150.0.gramsText())
        assertEquals("12.5", 12.46.gramsText())
    }
}
