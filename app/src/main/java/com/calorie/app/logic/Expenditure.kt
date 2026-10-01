package com.calorie.app.logic

import com.calorie.app.data.WeightMark
import kotlin.math.ceil

/** Daily expenditure measured from the weight trend and what was eaten over [days] days. */
data class WeightTrend(val days: Int, val tdee: Double, val intake: Double, val kgPerWeek: Double)

/** Why there is no estimate yet: have - what there is now, need - what it takes. */
enum class TrendShort { WEIGHINS, SPAN, DIARY }

data class TrendStatus(val trend: WeightTrend?, val short: TrendShort?, val have: Int, val need: Int)

/**
 * Expenditure from the energy balance: what was eaten plus what the weight lost, at 7700 kcal
 * per kg. No formula and no sensors, so it also absorbs a systematic error of the diary
 * itself: the target then comes out in the same "logged" kcal.
 *
 * The weight slope is a least squares fit over the weigh-ins of the window, so one heavy
 * morning does not decide it. Intake is averaged over the same days, from the first weigh-in
 * to the last one (food of the last weigh-in day is after it). Days without a diary are
 * unknown, not zero, and so are days logged at less than half the basal rate: a forgotten
 * dinner would pull the estimate down by hundreds of kcal.
 */
object Expenditure {
    const val WINDOW_DAYS = 28
    /** Days between the first and last weigh-in: water swings of 0.5-1 kg need a long base. */
    const val MIN_DAYS = 14
    const val MIN_WEIGHINS = 7
    /** Share of the days that must have a full diary. */
    const val MIN_LOGGED_SHARE = 0.8
    /** A day below this share of BMR is taken as not fully logged. */
    const val PARTIAL_DAY_SHARE = 0.5

    /** intake - kcal eaten per day; today is not over and is not used. */
    fun estimate(intake: Map<Long, Double>, weights: List<WeightMark>, bmr: Double, today: Long): TrendStatus {
        val from = today - WINDOW_DAYS
        val w = weights.filter { it.day in from until today }.sortedBy { it.day }
        if (w.size < MIN_WEIGHINS) return TrendStatus(null, TrendShort.WEIGHINS, w.size, MIN_WEIGHINS)
        val first = w.first().day
        val span = (w.last().day - first).toInt()
        if (span < MIN_DAYS) return TrendStatus(null, TrendShort.SPAN, span, MIN_DAYS)

        val eaten = (first until w.last().day).mapNotNull { d -> intake[d]?.takeIf { it >= bmr * PARTIAL_DAY_SHARE } }
        val need = ceil(span * MIN_LOGGED_SHARE).toInt()
        if (eaten.size < need) return TrendStatus(null, TrendShort.DIARY, eaten.size, need)

        val slope = slope(w)
        val avg = eaten.average()
        return TrendStatus(WeightTrend(span, avg - slope * Goals.KCAL_PER_KG, avg, slope * 7), null, span, MIN_DAYS)
    }

    /** kg per day by least squares. */
    private fun slope(w: List<WeightMark>): Double {
        val mx = w.map { it.day.toDouble() }.average()
        val my = w.map { it.kg }.average()
        val sxy = w.sumOf { (it.day - mx) * (it.kg - my) }
        val sxx = w.sumOf { (it.day - mx) * (it.day - mx) }
        return sxy / sxx
    }
}
