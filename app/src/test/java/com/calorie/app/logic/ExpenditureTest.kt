package com.calorie.app.logic

import com.calorie.app.data.WeightMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenditureTest {
    private val today = 20_000L
    private val bmr = 1700.0

    /** Weigh-ins every other day over the last [days] days, losing [kgPerWeek]. */
    private fun weights(days: Int, kgPerWeek: Double, step: Int = 2) =
        (days downTo 1 step step).map { back -> WeightMark(today - back, 90.0 - kgPerWeek / 7 * (days - back)) }

    private fun intake(days: Int, kcal: Double = 2000.0) = (1..days).associate { (today - it) to kcal }

    @Test
    fun steadyLossGivesIntakePlusDeficit() {
        // 0.5 kg a week = 550 kcal a day on top of 2000 eaten.
        val s = Expenditure.estimate(intake(28), weights(28, 0.5), bmr, today)
        val t = s.trend!!
        assertEquals(2550.0, t.tdee, 1.0)
        assertEquals(-0.5, t.kgPerWeek, 0.001)
        assertEquals(2000.0, t.intake, 0.001)
        assertNull(s.short)
    }

    @Test
    fun stableWeightGivesIntake() {
        val t = Expenditure.estimate(intake(28, 2300.0), weights(28, 0.0), bmr, today).trend!!
        assertEquals(2300.0, t.tdee, 0.001)
    }

    @Test
    fun noisyWeighInsFollowTheTrend() {
        // Water swings of +-0.6 kg around a 0.5 kg a week loss.
        val w = weights(28, 0.5, step = 1).mapIndexed { i, m -> m.copy(kg = m.kg + if (i % 2 == 0) 0.6 else -0.6) }
        assertEquals(2550.0, Expenditure.estimate(intake(28), w, bmr, today).trend!!.tdee, 60.0)
    }

    @Test
    fun missingAndPartialDaysAreUnknownNotZero() {
        val eaten = intake(28).toMutableMap()
        eaten.remove(today - 5)
        eaten[today - 7] = 400.0 // forgotten meals: below half the basal rate
        val t = Expenditure.estimate(eaten, weights(28, 0.5), bmr, today).trend!!
        assertEquals(2000.0, t.intake, 0.001)
    }

    @Test
    fun todayAndOldDaysAreNotUsed() {
        val eaten = intake(28) + mapOf(today to 5000.0, today - 40 to 5000.0)
        val w = weights(28, 0.5) + WeightMark(today, 50.0) + WeightMark(today - 40, 150.0)
        assertEquals(2550.0, Expenditure.estimate(eaten, w, bmr, today).trend!!.tdee, 1.0)
    }

    @Test
    fun tooFewWeighIns() {
        val s = Expenditure.estimate(intake(28), weights(28, 0.5, step = 7), bmr, today)
        assertNull(s.trend)
        assertEquals(TrendShort.WEIGHINS, s.short)
        assertEquals(4, s.have)
        assertEquals(Expenditure.MIN_WEIGHINS, s.need)
    }

    @Test
    fun tooShortSpan() {
        val s = Expenditure.estimate(intake(28), weights(10, 0.5, step = 1), bmr, today)
        assertEquals(TrendShort.SPAN, s.short)
        assertEquals(9, s.have)
    }

    @Test
    fun tooFewDiaryDays() {
        // Weigh-ins over 26 days (28 back to 2 back), but only 10 days in the diary.
        val s = Expenditure.estimate(intake(10), weights(28, 0.5), bmr, today)
        assertEquals(TrendShort.DIARY, s.short)
        assertEquals(8, s.have) // days 10..3 back: the last weigh-in day and later are after the span
        assertEquals(21, s.need)
    }
}
