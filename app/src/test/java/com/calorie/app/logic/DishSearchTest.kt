package com.calorie.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DishSearchTest {
    private fun finds(query: String, name: String) = assertNotNull("$query -> $name", DishSearch.cost(query, name))
    private fun misses(query: String, name: String) = assertNull("$query -> $name", DishSearch.cost(query, name))

    @Test
    fun neighborsOnBothLayouts() {
        assertTrue(DishSearch.near('ш', 'щ'))
        assertTrue(DishSearch.near('р', 'т'))
        assertTrue(DishSearch.near('g', 'h'))
        assertTrue(DishSearch.near('z', 'x'))
        assertTrue(!DishSearch.near('ф', 'ж'))
    }

    @Test
    fun exactAsBefore() {
        assertEquals(0.0, DishSearch.cost("", "Груша")!!, 0.0)
        assertEquals(0.0, DishSearch.cost("ЛИНЫ", "Блины с мясом")!!, 0.0)
        assertEquals(0.0, DishSearch.cost("черный", "Чёрный чай")!!, 0.0)
    }

    @Test
    fun phoneTypos() {
        finds("блмны", "Блины с мясом") // neighbor key
        finds("блиины", "Блины с мясом") // doubled letter
        finds("блиныю", "Блины с мясом") // neighbor pressed too
        finds("блны", "Блины с мясом") // missed letter
        finds("блниы", "Блины с мясом") // swapped
        finds("лепешкп", "Лепешка")
        finds("медовтк", "Торт медовик со сгущенкой")
        finds("ьелый", "Белый хлеб")
        misses("ьеловй", "Белый хлеб") // three slips in six letters are too many
        finds("chovo", "Choco Pie Matcha, Orion")
    }

    @Test
    fun wordsInAnyOrderFromTheirStart() {
        finds("чай чёрн", "Чёрный чай без сахара")
        finds("сахпр чай", "Чёрный чай без сахара")
        misses("чай кофе", "Чёрный чай без сахара")
    }

    @Test
    fun shortWordsStayStrict() {
        misses("ср", "Груша")
        misses("мчо", "Блины с мясом") // one far key on a 3-letter word
        finds("мчс", "Блины с мясом") // one near key is fine
        misses("абвгд", "Груша")
    }

    @Test
    fun fuzzyOnlyWithoutExact() {
        val names = listOf("Конфета", "Груша", "Кофе раф", "Чёрный кофе")
        assertEquals(listOf("Кофе раф", "Чёрный кофе"), DishSearch.filter(names, "кофе") { it })
        assertEquals(listOf("Конфета"), DishSearch.filter(names, "конфнта") { it })
    }

    @Test
    fun fuzzyClosestFirstThenListOrder() {
        val names = listOf("Пирожки с мясом", "Груша", "Пирог вишнёвый")
        assertEquals(listOf("Пирог вишнёвый", "Пирожки с мясом"), DishSearch.filter(names, "пиоог") { it })
    }

    @Test
    fun farKeyIsNotASlip() {
        misses("мясл", "Вареники в сливочном масле")
        finds("мясл", "Блины с мясом")
    }
}
