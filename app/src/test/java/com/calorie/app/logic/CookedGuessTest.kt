package com.calorie.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookedGuessTest {
    @Test
    fun kindByName() {
        assertEquals(CookKind.PASTA, CookedGuess.kindOf("Макароны Barilla Spaghetti n.5"))
        assertEquals(CookKind.BUCKWHEAT, CookedGuess.kindOf("Крупа гречневая ядрица"))
        assertEquals(CookKind.RICE, CookedGuess.kindOf("Рис длиннозёрный"))
        assertEquals(CookKind.RICE, CookedGuess.kindOf("Basmati rice"))
        // Rice noodles are cooked as noodles.
        assertEquals(CookKind.PASTA, CookedGuess.kindOf("Рисовая лапша"))
        assertEquals(CookKind.MILLET, CookedGuess.kindOf("Пшено шлифованное"))
        assertEquals(CookKind.CHICKPEAS, CookedGuess.kindOf("Нут"))
        assertEquals(CookKind.MEAT, CookedGuess.kindOf("Куриные бедра"))
        assertEquals(CookKind.FISH, CookedGuess.kindOf("Филе трески"))
        assertEquals(CookKind.RICE, CookedGuess.kindOf("大米"))
    }

    @Test
    fun noKindForOtherFood() {
        // "рис" inside a word, "нут" as the start of another word.
        assertNull(CookedGuess.kindOf("Ирис кис-кис"))
        assertNull(CookedGuess.kindOf("Нутелла"))
        // Meat words count only at the start: these are cooked dishes with meat.
        assertNull(CookedGuess.kindOf("Пельмени с мясом"))
        assertNull(CookedGuess.kindOf("Пирожки с мясом"))
        assertNull(CookedGuess.kindOf("Докторская колбаса"))
    }

    @Test
    fun cookedNamesAreNotGuessed() {
        assertNull(CookedGuess.kindOf("Гречка отварная"))
        assertNull(CookedGuess.kindOf("Рис варёный"))
        assertNull(CookedGuess.kindOf("Cooked rice"))
        assertNull(CookedGuess.kindOf("Курица жареная"))
        // "приготовления" is not "готовый".
        assertEquals(CookKind.PASTA, CookedGuess.kindOf("Лапша быстрого приготовления"))
    }

    @Test
    fun kindByCategory() {
        assertEquals(CookKind.PASTA, CookedGuess.kindOfCategories(listOf("en:cereals-and-potatoes", "en:pastas", "en:dry-pastas")))
        assertEquals(CookKind.LENTILS, CookedGuess.kindOfCategories(listOf("en:legumes", "en:lentils", "en:dried-lentils")))
        assertNull(CookedGuess.kindOfCategories(listOf("en:lentils", "en:cooked-lentils")))
        assertNull(CookedGuess.kindOfCategories(listOf("en:meats", "en:prepared-meats", "en:sausages")))
        assertNull(CookedGuess.kindOfCategories(listOf("en:snacks")))
    }

    @Test
    fun offCategoryFromResponse() {
        val json = """{"status":1,"product":{"product_name":"Penne","categories_tags":["en:pastas","en:dry-pastas"],
            "nutriments":{"energy-kcal_100g":350}}}"""
        assertEquals(CookKind.PASTA, OffParser.parse("1", json, "en")!!.cookKind)
    }

    @Test
    fun tableValueAndKcalGuard() {
        assertEquals(CookedValue(250.0, false), CookedGuess.guess("Гречка", 330.0, null, emptyMap()))
        // Already cooked by the numbers: no guess for dry food under 200 kcal.
        assertNull(CookedGuess.guess("Гречка", 110.0, null, emptyMap()))
        // Meat is not checked by kcal.
        assertEquals(CookedValue(70.0, false), CookedGuess.guess("Куриное филе", 110.0, null, emptyMap()))
        // The category wins over the name.
        assertEquals(CookedValue(280.0, false), CookedGuess.guess("Basmati Gold", 350.0, CookKind.RICE, emptyMap()))
        assertNull(CookedGuess.guess("Хлеб", 250.0, null, emptyMap()))
    }

    @Test
    fun ownNumbersComeFirst() {
        var mine = CookedGuess.remember(emptyMap(), "Гречка Мистраль", 330.0, null, 240.0)
        assertEquals(CookedValue(240.0, true), CookedGuess.guess("гречка  мистраль", 330.0, null, mine))
        // Another buckwheat gets the user's number for the kind.
        assertEquals(CookedValue(240.0, true), CookedGuess.guess("Гречка ядрица", 330.0, null, mine))
        // The dish is remembered even without a kind.
        mine = CookedGuess.remember(mine, "Плов", 250.0, null, 180.0)
        assertEquals(CookedValue(180.0, true), CookedGuess.guess("Плов", 250.0, null, mine))
        // Back to the table number: the kind forgets the own one, the dish keeps it.
        mine = CookedGuess.remember(mine, "Гречка Увелка", 330.0, null, 250.0)
        assertEquals(CookedValue(250.0, false), CookedGuess.guess("Гречка Ярмарка", 330.0, null, mine))
        assertEquals(CookedValue(250.0, true), CookedGuess.guess("Гречка Увелка", 330.0, null, mine))
    }

    @Test
    fun oldestDishesDropOut() {
        var mine = emptyMap<String, Double>()
        repeat(CookedGuess.MAX_DISHES + 5) { mine = CookedGuess.remember(mine, "Блюдо $it", 300.0, null, 150.0) }
        assertNull(CookedGuess.guess("Блюдо 0", 300.0, null, mine))
        assertTrue(CookedGuess.guess("Блюдо ${CookedGuess.MAX_DISHES + 4}", 300.0, null, mine)!!.own)
        // Saving again moves a dish to the end.
        mine = CookedGuess.remember(mine, "Блюдо 5", 300.0, null, 150.0)
        repeat(3) { mine = CookedGuess.remember(mine, "Новое $it", 300.0, null, 150.0) }
        assertTrue(CookedGuess.guess("Блюдо 5", 300.0, null, mine)!!.own)
        assertNull(CookedGuess.guess("Блюдо 6", 300.0, null, mine))
    }

    @Test
    fun encodeDecode() {
        val mine = CookedGuess.remember(emptyMap(), "Рис", 340.0, null, 300.0)
        assertEquals(mine, CookedGuess.decode(CookedGuess.encode(mine)))
        assertEquals(emptyMap<String, Double>(), CookedGuess.decode("not json"))
        assertFalse(CookedGuess.decode(null).isNotEmpty())
    }
}
