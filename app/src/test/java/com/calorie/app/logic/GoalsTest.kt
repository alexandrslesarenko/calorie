package com.calorie.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoalsTest {
    private val man = Body(Sex.MALE, 40, 180, 90.0)
    private val woman = Body(Sex.FEMALE, 30, 165, 60.0)

    @Test
    fun mifflinStJeor() {
        // 10*90 + 6.25*180 - 5*40 + 5 = 1830
        assertEquals(1830.0, Goals.bmr(man), 0.01)
        // 10*60 + 6.25*165 - 5*30 - 161 = 1320.25
        assertEquals(1320.25, Goals.bmr(woman), 0.01)
    }

    @Test
    fun normalDeficit() {
        val t = Goals.target(man, Activity.LIGHT, Pace.NORMAL, 80.0)
        assertEquals(2516, t.tdee)
        // 2516 - 550 = 1966 -> 1970
        assertEquals(1970, t.kcal)
        assertEquals(Limit.NONE, t.limit)
        assertEquals(0.5, t.kgPerWeek, 0.001)
        assertEquals(20, t.weeksToGoal)
    }

    @Test
    fun floorNotBelowBmrOrMinimum() {
        val t = Goals.target(woman, Activity.SEDENTARY, Pace.FAST, 55.0)
        assertEquals(Limit.FLOOR, t.limit)
        assertEquals(1320, t.kcal)
        assertTrue(t.kgPerWeek < 0.75)
    }

    @Test
    fun minimumForMen() {
        val small = Body(Sex.MALE, 70, 160, 60.0)
        val t = Goals.target(small, Activity.SEDENTARY, Pace.NORMAL, 55.0)
        assertTrue(t.kcal >= Goals.MIN_KCAL_MALE)
        assertEquals(Limit.FLOOR, t.limit)
    }

    @Test
    fun rateCappedAtOnePercent() {
        val light = Body(Sex.FEMALE, 30, 170, 70.0)
        val t = Goals.target(light, Activity.HIGH, Pace.FAST, 60.0)
        assertEquals(Limit.RATE, t.limit)
        assertEquals(0.7, t.kgPerWeek, 0.001)
    }

    @Test
    fun goalReachedMeansMaintenance() {
        val t = Goals.target(man, Activity.LIGHT, Pace.NORMAL, 90.0)
        assertEquals(Limit.GOAL_REACHED, t.limit)
        assertEquals(2520, t.kcal)
        assertNull(t.weeksToGoal)
    }

    @Test
    fun underweightGetsNoDeficit() {
        val thin = Body(Sex.FEMALE, 25, 170, 50.0)
        assertEquals(Limit.UNDERWEIGHT, Goals.target(thin, Activity.LIGHT, Pace.NORMAL, 45.0).limit)
    }

    @Test
    fun proteinCappedByReferenceWeight() {
        assertEquals(96, Goals.proteinG(165, 60.0))
        // 25 * 1.8^2 = 81 kg -> 129.6 g, not 1.6 * 120
        assertEquals(130, Goals.proteinG(180, 120.0))
    }

    @Test
    fun ageByBirthDate() {
        val birth = LocalDate.of(1986, 10, 15)
        assertEquals(39, Goals.age(birth, LocalDate.of(2026, 10, 14)))
        assertEquals(40, Goals.age(birth, LocalDate.of(2026, 10, 15)))
        // Born on February 29: a year older on March 1 of a common year.
        val leap = LocalDate.of(1988, 2, 29)
        assertEquals(37, Goals.age(leap, LocalDate.of(2026, 2, 28)))
        assertEquals(38, Goals.age(leap, LocalDate.of(2026, 3, 1)))
    }
}
