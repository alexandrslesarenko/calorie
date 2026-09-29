package com.calorie.app.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** One minute of a walk or workout from Pulsar: minute start (ms), mode, average heart rate, sample count. */
data class ActiveMinute(val minute: Long, val mode: String, val bpm: Double, val samples: Int)

/** A walk or workout assembled from consecutive minutes of the same mode. */
data class Session(val start: Long, val end: Long, val mode: String, val minutes: Int, val avgBpm: Int, val kcal: Double)

/** Activity level measured from Pulsar: average net kcal per day over [days] full days and the resulting multiplier. */
data class Calibration(val days: Int, val kcalPerDay: Double, val factor: Double)

/**
 * Energy burned in walks and workouts, from heart rate. Keytel et al. (2005) gives gross
 * kJ/min from HR, weight, age and sex; the basal rate share of that minute is subtracted,
 * because BMR is already in the daily target. Only walk and workout minutes come from
 * Pulsar, so resting time is never counted twice.
 */
object Burn {
    const val WALK = "walk"
    const val TRAINING = "training"
    /** Minutes apart of the same mode that still belong to one session. */
    const val SESSION_GAP_MIN = 5
    /** Window for the activity level and the minimum of full days it needs. */
    const val WINDOW_DAYS = 14
    const val MIN_DAYS = 7
    /** Daily life on top of BMR without walks and workouts (the "low" activity level). */
    const val BASE_FACTOR = 1.2
    const val MAX_FACTOR = 1.9
    /**
     * Keytel was fitted on exercise heart rates; near resting HR it still adds over a kcal
     * a minute above BMR. Below this, a minute counts as nothing: better to undercount.
     */
    const val MIN_BPM = 90.0
    /**
     * Share of the Keytel estimate that is counted. On walks it runs about a third above MET
     * tables, and for weight loss an overestimate eats the deficit. Decided 2026-09-29.
     */
    const val CREDIT = 0.7
    private const val SAMPLES_PER_MIN = 60.0
    private const val MS_PER_MIN = 60_000L

    /** Net kcal of one minute: Keytel gross minus the BMR share of a minute, never below zero. */
    fun netKcal(m: ActiveMinute, b: Body): Double {
        if (m.bpm < MIN_BPM) return 0.0
        val kj = if (b.sex == Sex.MALE) {
            -55.0969 + 0.6309 * m.bpm + 0.1988 * b.weightKg + 0.2017 * b.age
        } else {
            -20.4022 + 0.4472 * m.bpm - 0.1263 * b.weightKg + 0.074 * b.age
        }
        // A minute with a sensor dropout covers only part of the minute.
        val share = min(1.0, m.samples / SAMPLES_PER_MIN)
        return max(0.0, kj / Energy.KJ_PER_KCAL - Goals.bmr(b) / 1440) * share * CREDIT
    }

    fun sessions(minutes: List<ActiveMinute>, b: Body): List<Session> {
        val out = mutableListOf<MutableList<ActiveMinute>>()
        for (m in minutes.sortedBy { it.minute }) {
            val cur = out.lastOrNull()
            val prev = cur?.last()
            if (prev != null && prev.mode == m.mode && m.minute - prev.minute <= SESSION_GAP_MIN * MS_PER_MIN) cur.add(m)
            else out += mutableListOf(m)
        }
        return out.map { list ->
            val samples = list.sumOf { it.samples }.coerceAtLeast(1)
            Session(
                start = list.first().minute,
                end = list.last().minute + MS_PER_MIN,
                mode = list.first().mode,
                minutes = list.size,
                avgBpm = (list.sumOf { it.bpm * it.samples } / samples).toInt(),
                kcal = list.sumOf { netKcal(it, b) },
            )
        }
    }

    fun day(ms: Long, zone: ZoneId): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    /** Net kcal per local day. */
    fun perDay(minutes: List<ActiveMinute>, b: Body, zone: ZoneId): Map<Long, Double> =
        minutes.groupBy { day(it.minute, zone) }.mapValues { (_, list) -> list.sumOf { netKcal(it, b) } }

    /**
     * Activity multiplier from the last WINDOW_DAYS full days (today is not over yet), but not
     * from before the first day with Pulsar data: days before the app was used say nothing.
     * Days inside the window without walks count as zero - that is the honest average.
     * null - fewer than MIN_DAYS days, keep the manual level.
     */
    fun calibrate(minutes: List<ActiveMinute>, b: Body, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Calibration? {
        if (minutes.isEmpty()) return null
        val todayDay = today.toEpochDay()
        val first = maxOf(todayDay - WINDOW_DAYS, minutes.minOf { day(it.minute, zone) })
        val days = (todayDay - first).toInt()
        if (days < MIN_DAYS) return null
        val total = perDay(minutes, b, zone).filterKeys { it in first until todayDay }.values.sum()
        val perDay = total / days
        val factor = ((Goals.bmr(b) * BASE_FACTOR + perDay) / Goals.bmr(b)).coerceIn(BASE_FACTOR, MAX_FACTOR)
        return Calibration(days, perDay, factor)
    }
}
