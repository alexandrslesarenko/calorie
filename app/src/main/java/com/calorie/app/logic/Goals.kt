package com.calorie.app.logic

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Sex(val key: String) {
    FEMALE("f"), MALE("m");

    companion object {
        fun byKey(k: String?) = entries.firstOrNull { it.key == k }
    }
}

/** Activity multipliers for the basal metabolic rate (the common scale). */
enum class Activity(val key: String, val factor: Double) {
    SEDENTARY("sedentary", 1.2),
    LIGHT("light", 1.375),
    MODERATE("moderate", 1.55),
    HIGH("high", 1.725);

    companion object {
        fun byKey(k: String?) = entries.firstOrNull { it.key == k }
    }
}

/** Desired weight loss pace, kg per week. */
enum class Pace(val key: String, val kgPerWeek: Double) {
    SLOW("slow", 0.25),
    NORMAL("normal", 0.5),
    FAST("fast", 0.75);

    companion object {
        fun byKey(k: String?) = entries.firstOrNull { it.key == k }
    }
}

/** Why the target differs from "expenditure minus the deficit for the chosen pace". */
enum class Limit {
    NONE,
    /** Target raised to the safe minimum. */
    FLOOR,
    /** Pace capped at 1% of body weight per week. */
    RATE,
    /** Underweight by BMI: no deficit. */
    UNDERWEIGHT,
    /** Goal reached: maintenance target. */
    GOAL_REACHED,
}

data class Body(val sex: Sex, val age: Int, val heightCm: Int, val weightKg: Double)

data class Target(
    val kcal: Int,
    val bmr: Int,
    val tdee: Int,
    /** Expected weight loss at this target, kg per week. */
    val kgPerWeek: Double,
    val limit: Limit,
    val proteinG: Int,
    /** Weeks to the goal at this pace; null - goal reached or zero pace. */
    val weeksToGoal: Int?,
)

/**
 * Daily target for weight loss. Basal metabolic rate by Mifflin-St Jeor, expenditure is BMR times
 * the activity multiplier, the deficit comes from the pace (7700 kcal per kg of fat).
 * Safety limits: not below 1200 kcal (women) / 1500 (men) and not
 * below BMR, no faster than 1% of weight per week, no deficit when BMI is below 18.5.
 */
object Goals {
    const val KCAL_PER_KG = 7700.0
    const val MIN_KCAL_FEMALE = 1200
    const val MIN_KCAL_MALE = 1500
    const val MAX_WEEKLY_SHARE = 0.01
    const val BMI_UNDER = 18.5
    const val BMI_REF = 25.0
    /** Protein during a deficit, g per kg: less muscle is lost. */
    const val PROTEIN_PER_KG = 1.6

    fun bmr(b: Body): Double =
        10 * b.weightKg + 6.25 * b.heightCm - 5 * b.age + if (b.sex == Sex.MALE) 5 else -161

    fun bmi(heightCm: Int, weightKg: Double): Double {
        val m = heightCm / 100.0
        return weightKg / (m * m)
    }

    /**
     * Protein is based on weight, capped at the weight for BMI 25: with a lot of excess weight
     * 1.6 g for every kilogram would give an unrealistic number.
     */
    fun proteinG(heightCm: Int, weightKg: Double): Int {
        val m = heightCm / 100.0
        val ref = min(weightKg, BMI_REF * m * m)
        return (ref * PROTEIN_PER_KG).roundToInt()
    }

    fun target(b: Body, activity: Activity, pace: Pace, goalKg: Double?): Target {
        val bmr = bmr(b)
        val tdee = bmr * activity.factor
        val protein = proteinG(b.heightCm, b.weightKg)
        fun maintain(limit: Limit) = Target(round10(tdee), bmr.roundToInt(), tdee.roundToInt(), 0.0, limit, protein, null)

        if (bmi(b.heightCm, b.weightKg) < BMI_UNDER) return maintain(Limit.UNDERWEIGHT)
        if (goalKg != null && b.weightKg <= goalKg) return maintain(Limit.GOAL_REACHED)

        var limit = Limit.NONE
        var rate = pace.kgPerWeek
        val maxRate = b.weightKg * MAX_WEEKLY_SHARE
        if (rate > maxRate) {
            rate = maxRate
            limit = Limit.RATE
        }
        var kcal = tdee - rate * KCAL_PER_KG / 7
        val floor = max(if (b.sex == Sex.MALE) MIN_KCAL_MALE.toDouble() else MIN_KCAL_FEMALE.toDouble(), bmr)
        if (kcal < floor) {
            kcal = min(floor, tdee)
            limit = Limit.FLOOR
            rate = (tdee - kcal) * 7 / KCAL_PER_KG
        }
        val weeks = if (goalKg != null && rate > 0) ((b.weightKg - goalKg) / rate).let { kotlin.math.ceil(it).toInt() } else null
        return Target(round10(kcal), bmr.roundToInt(), tdee.roundToInt(), rate, limit, protein, weeks)
    }

    /** Age from the birth year; the birthday is assumed to be July 1 (off by half a year at most). */
    fun age(birthYear: Int, today: LocalDate = LocalDate.now()): Int =
        today.year - birthYear - if (today.monthValue < 7) 1 else 0

    private fun round10(v: Double) = (v / 10).roundToInt() * 10
}
