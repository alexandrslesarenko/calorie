package com.calorie.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ActivityFeedTest {
    private val zone = ZoneOffset.UTC
    private val min = 60_000L
    private val day0 = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun at(h: Int, m: Int, s: Int = 0) = day0 + h * 3_600_000L + m * min + s * 1_000L

    @Test
    fun sparseWatchSamplesStillCoverWholeMinutes() {
        // A watch writing heart rate once a minute: each minute counts in full, not as 1/60.
        val session = HcSession(at(10, 0), at(10, 10), walk = true)
        val samples = (0 until 10).map { HrSample(at(10, it, 30), 110.0) }
        val minutes = HcMinutes.build(listOf(session), samples)
        assertEquals(10, minutes.size)
        assertTrue(minutes.all { it.samples == 60 && it.mode == Burn.WALK && it.origin == ActivitySource.HEALTH_CONNECT })
        val b = Body(Sex.MALE, 45, 180, 90.0)
        val pulsarLike = (0 until 10).map { ActiveMinute(at(10, it), Burn.WALK, 110.0, 60) }
        assertEquals(pulsarLike.sumOf { Burn.netKcal(it, b) }, minutes.sumOf { Burn.netKcal(it, b) }, 1e-9)
    }

    @Test
    fun averagesSamplesInsideTheMinute() {
        val session = HcSession(at(10, 0), at(10, 1), walk = false)
        val m = HcMinutes.build(listOf(session), listOf(HrSample(at(10, 0, 5), 100.0), HrSample(at(10, 0, 50), 120.0))).single()
        assertEquals(110.0, m.bpm, 1e-9)
        assertEquals(Burn.TRAINING, m.mode)
    }

    @Test
    fun gapsAreFilledOnlyFromNearbySamples() {
        // Samples every 6 minutes: 1-2 and 4-5 borrow; with 3 min apart from both, minute 3 has none.
        val session = HcSession(at(10, 0), at(10, 7), walk = true)
        val minutes = HcMinutes.build(listOf(session), listOf(HrSample(at(10, 0, 30), 100.0), HrSample(at(10, 6, 30), 105.0)))
        val starts = minutes.map { ((it.minute - at(10, 0)) / min).toInt() }
        assertEquals(listOf(0, 1, 2, 4, 5, 6), starts)
    }

    @Test
    fun partialMinutesAtTheEdgesCountByCoverage() {
        val session = HcSession(at(10, 0, 30), at(10, 2, 15), walk = true)
        val samples = listOf(HrSample(at(10, 0, 40), 100.0), HrSample(at(10, 1, 30), 100.0), HrSample(at(10, 2, 10), 100.0))
        assertEquals(listOf(30, 60, 15), HcMinutes.build(listOf(session), samples).map { it.samples })
    }

    @Test
    fun theSameWalkFromTwoAppsIsCountedOnce() {
        val samples = (0 until 30).map { HrSample(at(10, it, 20), 115.0) }
        val a = HcSession(at(10, 0), at(10, 30), walk = true)
        val b = HcSession(at(10, 5), at(10, 25), walk = false)
        val minutes = HcMinutes.build(listOf(b, a), samples)
        assertEquals(30, minutes.size)
        assertEquals(30, minutes.map { it.minute }.distinct().size)
        // The earlier workout owns the shared minutes.
        assertTrue(minutes.all { it.mode == Burn.WALK })
    }

    @Test
    fun implausibleHeartRateIsDropped() {
        val m = HcMinutes.build(listOf(HcSession(at(10, 0), at(10, 1), true)), listOf(HrSample(at(10, 0, 10), 0.0)))
        assertTrue(m.isEmpty())
    }

    @Test
    fun autoFillsTheDayAroundPulsar() {
        // Pulsar ran only for the evening walk; the phone recorded a morning walk and the same evening one.
        val pulsar = (0 until 30).map { ActiveMinute(at(19, it), Burn.WALK, 110.0, 60) }
        val hc = (0 until 20).map { ActiveMinute(at(9, it), Burn.WALK, 105.0, 60, ActivitySource.HEALTH_CONNECT) } +
            (0 until 40).map { ActiveMinute(at(18, 55) + it * min, Burn.WALK, 105.0, 60, ActivitySource.HEALTH_CONNECT) }
        val auto = ActivityMerge.merge(ActivitySource.AUTO, pulsar, hc)
        assertEquals(20, auto.count { it.origin == ActivitySource.HEALTH_CONNECT })
        assertTrue(auto.filter { it.origin == ActivitySource.HEALTH_CONNECT }.all { it.minute < at(10, 0) })
        assertEquals(30, auto.count { it.origin == ActivitySource.PULSAR })
        assertEquals(pulsar, ActivityMerge.merge(ActivitySource.PULSAR, pulsar, hc))
        assertEquals(hc, ActivityMerge.merge(ActivitySource.HEALTH_CONNECT, pulsar, hc))
    }

    @Test
    fun hcMinutesFarFromPulsarStay() {
        val pulsar = listOf(ActiveMinute(at(19, 0), Burn.WALK, 110.0, 60))
        val hc = listOf(at(19, 5), at(19, 6)).map { ActiveMinute(it, Burn.WALK, 105.0, 60, ActivitySource.HEALTH_CONNECT) }
        assertEquals(listOf(at(19, 0), at(19, 6)), ActivityMerge.merge(ActivitySource.AUTO, pulsar, hc).map { it.minute })
    }

    private val body = Body(Sex.MALE, 45, 180, 90.0)

    @Test
    fun withoutPulseTheAppKcalCountUpToMet() {
        // 60 min walk, phone only: the app says 150 active kcal, MET 3.5 allows 2.5 x 90 = 225.
        val s = HcSession(at(10, 0), at(11, 0), walk = true, met = 3.5, activeKcal = 150.0)
        val minutes = HcMinutes.build(listOf(s), emptyList())
        assertEquals(60, minutes.size)
        assertEquals(150.0, minutes.sumOf { Burn.netKcal(it, body) }, 1e-6)
        // The app claims 400: capped at the MET estimate.
        val greedy = HcMinutes.build(listOf(s.copy(activeKcal = 400.0)), emptyList())
        assertEquals(225.0, greedy.sumOf { Burn.netKcal(it, body) }, 1e-6)
    }

    @Test
    fun totalKcalLoseTheRestingPart() {
        val s = HcSession(at(10, 0), at(11, 0), walk = true, met = 3.5, totalKcal = 200.0)
        val net = HcMinutes.build(listOf(s), emptyList()).sumOf { Burn.netKcal(it, body) }
        assertEquals(200.0 - Goals.bmr(body) / 24, net, 1e-6)
    }

    @Test
    fun withoutPulseAndKcalNothingIsCounted() {
        assertTrue(HcMinutes.build(listOf(HcSession(at(10, 0), at(11, 0), walk = true)), emptyList()).isEmpty())
    }

    @Test
    fun aSessionWithoutPulseShowsZeroPulse() {
        val s = HcSession(at(10, 0), at(10, 30), walk = true, activeKcal = 60.0)
        val session = Burn.sessions(HcMinutes.build(listOf(s), emptyList()), body).single()
        assertEquals(0, session.avgBpm)
        assertEquals(30, session.minutes)
    }

    @Test
    fun mostlyPulsedWorkoutGoesByPulse() {
        val s = HcSession(at(10, 0), at(10, 10), walk = true, activeKcal = 500.0)
        val samples = (0 until 6).map { HrSample(at(10, it, 30), 110.0) }
        assertTrue(HcMinutes.hasHeartRate(s, samples))
        val minutes = HcMinutes.build(listOf(s), samples)
        assertTrue(minutes.all { it.kcal == null && it.bpm > 0 })
        // 6 minutes with samples, 2 more filled from the nearest one.
        assertEquals(8, minutes.size)
    }

    @Test
    fun sessionsDoNotJoinAcrossSources() {
        val b = Body(Sex.FEMALE, 40, 165, 70.0)
        val minutes = listOf(
            ActiveMinute(at(10, 0), Burn.WALK, 110.0, 60),
            ActiveMinute(at(10, 1), Burn.WALK, 110.0, 60, ActivitySource.HEALTH_CONNECT),
        )
        assertEquals(2, Burn.sessions(minutes, b).size)
    }

    @Test
    fun sourceKeysAreStable() {
        assertEquals(listOf("auto", "pulsar", "hc"), ActivitySource.entries.map { it.key })
        assertEquals(ActivitySource.HEALTH_CONNECT, ActivitySource.byKey("hc"))
    }
}
