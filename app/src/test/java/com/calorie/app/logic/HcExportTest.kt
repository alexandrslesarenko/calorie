package com.calorie.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class HcExportTest {
    private val zone = ZoneId.of("Asia/Almaty")
    private val day = LocalDate.of(2026, 11, 3).toEpochDay()
    private fun at(d: Long, h: Int, m: Int = 0) =
        LocalDateTime.of(LocalDate.ofEpochDay(d), java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
    private val later = at(day + 5, 12)

    @Test
    fun entrySavedThatDayKeepsItsTime() {
        val ts = at(day, 13, 25)
        assertEquals(ts - 60_000 to ts, HcExport.foodTime(ts, day, "lunch", zone, later))
    }

    @Test
    fun freshEntryKeepsItsTimeOnEveryPass() {
        // Saved a second ago: the next pass a minute later must give the same record.
        val ts = at(day, 13, 25)
        assertEquals(HcExport.foodTime(ts, day, "lunch", zone, ts + 1_000), HcExport.foodTime(ts, day, "lunch", zone, ts + 61_000))
    }

    @Test
    fun entrySavedNextMorningGoesToItsDayByMeal() {
        // Lunch of the 3rd typed in on the 4th: it stays lunch of the 3rd.
        val (start, end) = HcExport.foodTime(at(day + 1, 9), day, "lunch", zone, later)
        assertEquals(at(day, 13), end)
        assertEquals(end - 60_000, start)
    }

    @Test
    fun importedEntryWithoutTsUsesMealHour() {
        assertEquals(at(day, 19), HcExport.foodTime(0, day, "dinner", zone, later).second)
        assertEquals(at(day, 12), HcExport.foodTime(0, day, "unknown", zone, later).second)
    }

    @Test
    fun recordNeverInFutureNorOutsideDay() {
        // Today, 10:00, a dinner entry saved yesterday for today: not at 19:00 yet.
        val now = at(day, 10)
        val (start, end) = HcExport.foodTime(at(day - 1, 22), day, "dinner", zone, now)
        assertTrue(end <= now)
        assertTrue(start >= at(day, 0))
        // Saved at 00:00:20 - the minute still starts inside the day.
        val (s2, e2) = HcExport.foodTime(at(day, 0) + 20_000, day, "snack", zone, later)
        assertEquals(at(day, 0), s2)
        assertEquals(s2 + 60_000, e2)
    }

    @Test
    fun weightIsMorningButNotFuture() {
        assertEquals(at(day, 7), HcExport.weightTime(day, zone, later))
        assertEquals(at(day, 6, 30), HcExport.weightTime(day, zone, at(day, 6, 30)))
    }

    @Test
    fun incrementalSendsOnlyChangesAndDeletesRemoved() {
        val state = HcSent(false, mapOf("entry-1" to "a", "entry-2" to "b", "weight-5" to "w"))
        val plan = HcExport.plan(mapOf("entry-1" to "a", "entry-2" to "b2", "entry-3" to "c"), state)
        assertEquals(setOf("entry-2", "entry-3"), plan.upsert)
        assertEquals(setOf("weight-5"), plan.delete)
    }

    @Test
    fun fullPassRewritesAllAndDeletesOrphans() {
        // After a "replace" import the entries got new ids: old ones found in HC go away.
        val plan = HcExport.plan(mapOf("entry-10" to "a"), HcSent.FULL, own = setOf("entry-1", "entry-10", "weight-3"))
        assertEquals(setOf("entry-10"), plan.upsert)
        assertEquals(setOf("entry-1", "weight-3"), plan.delete)
        // Remembered as sent but already gone from Health Connect: not deleted again.
        val again = HcExport.plan(mapOf("entry-10" to "a"), HcSent(true, mapOf("entry-7" to "x")), own = setOf("entry-10"))
        assertEquals(emptySet<String>(), again.delete)
    }

    @Test
    fun fullPassWithoutReadDeletesWhatWasSent() {
        val plan = HcExport.plan(mapOf("entry-1" to "a"), HcSent(true, mapOf("entry-1" to "a", "entry-2" to "b")), own = null)
        assertEquals(setOf("entry-1"), plan.upsert)
        assertEquals(setOf("entry-2"), plan.delete)
    }

    @Test
    fun printChangesWithAnyField() {
        val f = HcFood("entry-1", 1, 2, "Soup", "lunch", 120.0, 5.0, 3.0, 15.0)
        assertEquals(f.print, f.copy(kcal = 120.001).print)
        assertTrue(f.print != f.copy(meal = "dinner").print)
        assertTrue(f.print != f.copy(name = "Borscht").print)
        assertTrue(HcWeight("w", 1, 80.0).print != HcWeight("w", 1, 79.9).print)
    }

    @Test
    fun stateRoundTripsAndBrokenStateMeansFull() {
        val s = HcSent(false, mapOf("entry-1" to "1|2|Soup|lunch"))
        assertEquals(s, HcSent.parse(s.toJson()))
        assertEquals(HcSent.FULL, HcSent.parse(null))
        assertEquals(HcSent.FULL, HcSent.parse("not json"))
        assertEquals(HcSent.FULL, HcSent.parse("{}"))
    }
}
