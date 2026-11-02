package com.calorie.app.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * One minute of a walk or workout: minute start (ms), mode, average heart rate, and how many
 * seconds of the minute it covers (for Pulsar - the sample count, about one a second).
 * A Health Connect workout without heart rate has bpm 0 and instead its share of the kcal its
 * app counted (kcalGross - with the resting part) and the MET of its kind as the upper bound.
 */
data class ActiveMinute(
    val minute: Long,
    val mode: String,
    val bpm: Double,
    val samples: Int,
    val origin: ActivitySource = ActivitySource.PULSAR,
    val kcal: Double? = null,
    val kcalGross: Boolean = false,
    val met: Double? = null,
)

/** A walk or workout assembled from consecutive minutes of the same mode and source. */
data class Session(
    val start: Long,
    val end: Long,
    val mode: String,
    val minutes: Int,
    val avgBpm: Int,
    val kcal: Double,
    val origin: ActivitySource = ActivitySource.PULSAR,
)

/** Activity level measured from walks and workouts: average net kcal per day over [days] full days and the resulting multiplier. */
data class Calibration(val days: Int, val kcalPerDay: Double, val factor: Double)

/**
 * Energy burned in walks and workouts, from heart rate. Keytel et al. (2005) gives gross
 * kJ/min from HR, weight, age and sex; the basal rate share of that minute is subtracted,
 * because BMR is already in the daily target. Only walk and workout minutes come from
 * Pulsar or Health Connect, so resting time is never counted twice.
 */
object Burn {
    const val WALK = "walk"
    const val TRAINING = "training"
    /** Minutes apart of the same mode that still belong to one session. */
    const val SESSION_GAP_MIN = 5
    /** Walking at an ordinary pace (Compendium of Physical Activities). */
    const val WALK_MET = 3.5
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
        if (m.kcal != null) return byKcal(m, b)
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

    /**
     * A minute without heart rate: the kcal its app counted, but not above the MET estimate
     * (MET - 1 kcal per kg per hour above rest). No CREDIT: the lower of the two is already the
     * cautious one.
     */
    private fun byKcal(m: ActiveMinute, b: Body): Double {
        val share = m.samples / 60.0
        val counted = m.kcal!! - if (m.kcalGross) Goals.bmr(b) / 1440 * share else 0.0
        val met = ((m.met ?: WALK_MET) - 1) * b.weightKg / 60 * share
        return max(0.0, min(counted, met))
    }

    fun sessions(minutes: List<ActiveMinute>, b: Body): List<Session> {
        val out = mutableListOf<MutableList<ActiveMinute>>()
        for (m in minutes.sortedBy { it.minute }) {
            val cur = out.lastOrNull()
            val prev = cur?.last()
            if (prev != null && prev.mode == m.mode && prev.origin == m.origin && m.minute - prev.minute <= SESSION_GAP_MIN * MS_PER_MIN) cur.add(m)
            else out += mutableListOf(m)
        }
        return out.map { list ->
            // Minutes without heart rate do not pull the average pulse down; all of them - 0.
            val hr = list.filter { it.bpm > 0 }
            val samples = hr.sumOf { it.samples }.coerceAtLeast(1)
            Session(
                start = list.first().minute,
                end = list.last().minute + MS_PER_MIN,
                mode = list.first().mode,
                minutes = list.size,
                avgBpm = (hr.sumOf { it.bpm * it.samples } / samples).toInt(),
                kcal = list.sumOf { netKcal(it, b) },
                origin = list.first().origin,
            )
        }
    }

    fun day(ms: Long, zone: ZoneId): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    /** Net kcal per local day. */
    fun perDay(minutes: List<ActiveMinute>, b: Body, zone: ZoneId): Map<Long, Double> =
        minutes.groupBy { day(it.minute, zone) }.mapValues { (_, list) -> list.sumOf { netKcal(it, b) } }

    /**
     * Activity multiplier from the last WINDOW_DAYS full days (today is not over yet), but not
     * from before the first day with data: days before the app was used say nothing.
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
