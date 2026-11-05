package com.calorie.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    /** CIE76 distance between two colors. */
    private fun dist(a: Int, b: Int): Double {
        val (l1, c1, h1) = Palette.lch(a)
        val (l2, c2, h2) = Palette.lch(b)
        val r1 = Math.toRadians(h1); val r2 = Math.toRadians(h2)
        val da = c1 * Math.cos(r1) - c2 * Math.cos(r2)
        val db = c1 * Math.sin(r1) - c2 * Math.sin(r2)
        return Math.sqrt((l1 - l2) * (l1 - l2) + da * da + db * db)
    }

    @Test
    fun toneIsLightness() {
        for (t in listOf(4.0, 6.0, 22.0, 30.0, 50.0, 80.0, 90.0, 98.0)) {
            val c = Palette.color(t, 40.0, 273.0)
            assertEquals("tone $t", t, Palette.lch(c).first, 0.6)
        }
    }

    @Test
    fun outOfGamutChromaShrinksButKeepsTone() {
        val c = Palette.color(95.0, 80.0, 273.0)
        val (l, ch, _) = Palette.lch(c)
        assertEquals(95.0, l, 0.6)
        assertTrue(ch < 80.0)
    }

    @Test
    fun closeToMaterialBaselineDark() {
        // Material 3 baseline dark scheme (seed #6750A4).
        val p = Palette.of(0xFF6750A4.toInt(), dark = true)
        val expected = listOf(
            "primary" to (p.primary to 0xD0BCFF), "primaryContainer" to (p.primaryContainer to 0x4F378B),
            "secondary" to (p.secondary to 0xCCC2DC), "secondaryContainer" to (p.secondaryContainer to 0x4A4458),
            "background" to (p.background to 0x141218), "onSurface" to (p.onSurface to 0xE6E0E9),
            "surfaceContainerHighest" to (p.surfaceContainerHighest to 0x36343B),
            "surfaceVariant" to (p.surfaceVariant to 0x49454F), "outline" to (p.outline to 0x938F99),
        )
        for ((name, pair) in expected) {
            val d = dist(pair.first, pair.second or 0xFF000000.toInt())
            assertTrue("$name ${hex(pair.first)} vs ${hex(pair.second)}: $d", d < 8.0)
        }
    }

    @Test
    fun darkCardsDarkerThanSamsungs() {
        // One UI 9 gave #1D1D20 and #404044 from this seed (2026-11-05).
        val p = Palette.of(0xFF94B8FF.toInt(), dark = true)
        assertTrue(Palette.lch(p.background).first < Palette.lch(0xFF1D1D20.toInt()).first - 3)
        assertTrue(Palette.lch(p.surfaceContainerHighest).first < Palette.lch(0xFF404044.toInt()).first - 3)
    }

    @Test
    fun graySeedGivesGrayScheme() {
        val p = Palette.of(0xFF808080.toInt(), dark = true)
        assertTrue(Palette.lch(p.primary).second < 1.0)
        assertTrue(Palette.lch(p.surface).second < 1.0)
    }

    @Test
    fun hueFollowsSeed() {
        val seed = 0xFF94B8FF.toInt()
        val h = Palette.lch(seed).third
        val p = Palette.of(seed, dark = false)
        val (_, c, ph) = Palette.lch(p.primary)
        assertTrue(c > 20)
        assertEquals(h, ph, 2.0)
    }
}
