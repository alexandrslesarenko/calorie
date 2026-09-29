package com.calorie.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BurnTest {
    private val man = Body(Sex.MALE, 46, 180, 92.5)
    private val woman = Body(Sex.FEMALE, 30, 165, 60.0)
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 9, 29)
    private fun ms(day: LocalDate, hour: Int, min: Int) = day.atTime(hour, min).toInstant(utc).toEpochMilli()

    @Test
    fun keytelMaleByHand() {
        // kJ/min = -55.0969 + 0.6309*120 + 0.1988*92.5 + 0.2017*46 = 48.278 -> 11.539 kcal
        // minus BMR 1825 / 1440 = 1.267 -> 10.272, counted at 70% -> 7.190
        assertEquals(7.190, Burn.netKcal(ActiveMinute(0, Burn.WALK, 120.0, 60), man), 0.01)
    }

    @Test
    fun keytelFemaleByHand() {
        // kJ/min = -20.4022 + 0.4472*110 - 0.1263*60 + 0.074*30 = 23.452 -> 5.605 kcal
        // minus BMR 1320.25 / 1440 = 0.917 -> 4.688, counted at 70% -> 3.282
        assertEquals(3.282, Burn.netKcal(ActiveMinute(0, Burn.WALK, 110.0, 60), woman), 0.01)
    }

    @Test
    fun heartRateBelowThresholdCountsAsNothing() {
        // Keytel would still give about +1.2 kcal/min at 60 bpm.
        assertEquals(0.0, Burn.netKcal(ActiveMinute(0, Burn.WALK, 60.0, 60), man), 0.0)
        assertEquals(0.0, Burn.netKcal(ActiveMinute(0, Burn.WALK, 89.0, 60), man), 0.0)
    }

    @Test
    fun partialMinuteScaled() {
        val full = Burn.netKcal(ActiveMinute(0, Burn.WALK, 120.0, 60), man)
        assertEquals(full / 2, Burn.netKcal(ActiveMinute(0, Burn.WALK, 120.0, 30), man), 1e-9)
    }

    @Test
    fun sessionsSplitByGapAndMode() {
        val m = { h: Int, mi: Int, mode: String -> ActiveMinute(ms(today, h, mi), mode, 110.0, 60) }
        val list = listOf(m(10, 0, "walk"), m(10, 1, "walk"), m(10, 5, "walk"), m(10, 6, "training"), m(11, 0, "walk"))
        val s = Burn.sessions(list, man)
        assertEquals(listOf("walk", "training", "walk"), s.map { it.mode })
        assertEquals(3, s[0].minutes)
        assertEquals(ms(today, 10, 6), s[0].end)
        assertEquals(110, s[0].avgBpm)
    }

    @Test
    fun calibrationNeedsSevenFullDays() {
        val m = listOf(ActiveMinute(ms(today.minusDays(5), 10, 0), Burn.WALK, 120.0, 60))
        assertNull(Burn.calibrate(m, man, today, utc))
    }

    @Test
    fun calibrationAveragesOverWindowIncludingEmptyDays() {
        // One 60-minute walk at 120 bpm ten days ago; window = 10 full days.
        val walk = (0 until 60).map { ActiveMinute(ms(today.minusDays(10), 9, it), Burn.WALK, 120.0, 60) }
        // Today's walk is not counted: the day is not over.
        val todayWalk = ActiveMinute(ms(today, 8, 0), Burn.WALK, 150.0, 60)
        val c = Burn.calibrate(walk + todayWalk, man, today, utc)!!
        assertEquals(10, c.days)
        assertEquals(7.190 * 60 / 10, c.kcalPerDay, 0.1)
        assertEquals((1825 * 1.2 + c.kcalPerDay) / 1825, c.factor, 1e-6)
    }

    @Test
    fun calibrationWindowCappedAtFourteenDays() {
        val old = ActiveMinute(ms(today.minusDays(30), 9, 0), Burn.WALK, 120.0, 60)
        val c = Burn.calibrate(listOf(old), man, today, utc)!!
        assertEquals(14, c.days)
        assertEquals(1.2, c.factor, 1e-9)
    }
}
