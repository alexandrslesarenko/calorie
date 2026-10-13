package com.calorie.app.ui

import com.calorie.app.data.Dish
import com.calorie.app.logic.Cooking
import com.calorie.app.logic.FoodDraft
import com.calorie.app.logic.Nutrients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DishFormTest {
    private val old = Dish("cookie", "Cookie", 450.0, 6.7, 20.0, 60.0, 40.0, 0, 0L, false)

    @Test
    fun perPortionSavedPer100() {
        val form = DishForm("Cookie", "180", "2.68", "8", "24", "40", "", perPortion = true)
        val d = form.toDish(old)!!
        assertEquals(450.0, d.kcal, 1e-9)
        assertEquals(6.7, d.protein, 1e-9)
        assertEquals(20.0, d.fat, 1e-9)
        assertEquals(60.0, d.carbs, 1e-9)
        assertEquals(40.0, d.lastGrams, 1e-9)
    }

    @Test
    fun switchConvertsTypedValues() {
        val per100 = DishForm.of(old)
        val portion = per100.withPerPortion(true)
        assertEquals("180", portion.kcal)
        assertEquals("2.68", portion.protein)
        val back = portion.withPerPortion(false)
        assertEquals(per100, back)
        assertEquals(old.protein, portion.toDish(old)!!.protein, 1e-9)
    }

    @Test
    fun switchKeepsTextWithoutPortion() {
        val form = DishForm("Cookie", "180", "x", "", "", "", "").withPerPortion(true)
        assertEquals("180", form.kcal)
        assertEquals("x", form.protein)
        assertNull(form.toDish(old))
    }

    private val pasta = Dish("pasta", "Pasta", 350.0, 12.0, 1.5, 71.0, 100.0, 0, 0L, false)

    @Test
    fun afterCookingSavesCookedValues() {
        val d = DishForm("Pasta", "350", "12", "1.5", "71", "250", "", afterCooking = true, cooked = "250").toDish(pasta)!!
        assertEquals(140.0, d.kcal, 1e-9)
        assertEquals(4.8, d.protein, 1e-9)
        assertEquals(250.0, d.lastGrams, 1e-9)
    }

    @Test
    fun afterCookingPerServingEatsTheServingCooked() {
        // A label serving of 80 g dry, 100 g cook to 250 g: 200 g on the plate.
        val d = DishForm("Pasta", "280", "", "", "", "80", "", perPortion = true, afterCooking = true, cooked = "250").toDish(pasta)!!
        assertEquals(140.0, d.kcal, 1e-9)
        assertEquals(200.0, d.lastGrams, 1e-9)
        assertEquals(280.0, d.toDraft().total.kcal, 1e-9)
    }

    @Test
    fun afterCookingNeedsTheCookedWeight() {
        assertNull(DishForm("Pasta", "350", "", "", "", "250", "", afterCooking = true).toDish(pasta))
        assertNull(DishForm("Pasta", "350", "", "", "", "250", "", afterCooking = true, cooked = "5").toDish(pasta))
    }

    @Test
    fun reviewItemCooked() {
        val raw = Nutrients(350.0, 12.0, 1.5, 71.0)
        val item = ReviewItem(FoodDraft("Pasta", 230.0, raw))
        val on = item.afterCooking(true)
        assertFalse(on.ready)
        assertEquals(raw, on.draft.per100)
        val cooked = on.withCooked(250.0)
        assertTrue(cooked.ready)
        assertEquals(140.0, cooked.draft.per100.kcal, 1e-9)
        assertEquals(322.0, cooked.draft.total.kcal, 1e-9)
        // Retyping starts from the label again, not from the already converted values.
        assertEquals(175.0, cooked.withCooked(200.0).draft.per100.kcal, 1e-9)
        assertFalse(cooked.withCooked(null).ready)
        assertEquals(item, cooked.afterCooking(false))
    }

    @Test
    fun looksCookedOnlyForLowValuesThatGainWater() {
        assertTrue(Cooking.looksCooked(140.0, 250.0))
        assertFalse(Cooking.looksCooked(350.0, 250.0))
        // Chicken breast loses weight when fried.
        assertFalse(Cooking.looksCooked(110.0, 70.0))
        assertEquals(140.0, DishForm("Pasta", "280", "", "", "", "200", "", perPortion = true).rawKcal()!!, 1e-9)
    }
}
