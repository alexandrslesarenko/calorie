package com.calorie.app.ui

import com.calorie.app.data.Dish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
