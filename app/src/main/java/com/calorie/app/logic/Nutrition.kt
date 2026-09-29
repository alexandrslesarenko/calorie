package com.calorie.app.logic

import kotlin.math.roundToInt

/** Nutrition values: kcal and grams of protein, fat and carbs. */
data class Nutrients(val kcal: Double, val protein: Double, val fat: Double, val carbs: Double) {
    operator fun plus(o: Nutrients) = Nutrients(kcal + o.kcal, protein + o.protein, fat + o.fat, carbs + o.carbs)

    operator fun times(k: Double) = Nutrients(kcal * k, protein * k, fat * k, carbs * k)

    companion object {
        val ZERO = Nutrients(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Food in an entry draft. Stored per 100 g, not per portion: then editing the grams
 * simply rescales the portion, and values do not drift from rounding.
 */
data class FoodDraft(val name: String, val grams: Double, val per100: Nutrients) {
    val total: Nutrients get() = per100 * (grams / 100.0)

    companion object {
        /**
         * From values for the whole portion (that is how the model answers). A portion
         * without weight (a drink in ml, "1 piece") counts as 100 g, so editing still works.
         */
        fun fromPortion(name: String, grams: Double, portion: Nutrients): FoodDraft {
            val g = if (grams > 0) grams else 100.0
            return FoodDraft(name, g, portion * (100.0 / g))
        }
    }
}

object Energy {
    const val KJ_PER_KCAL = 4.184

    fun kjToKcal(kj: Double) = kj / KJ_PER_KCAL
}

/** Sum over a list of portions. */
fun List<Nutrients>.sum(): Nutrients = fold(Nutrients.ZERO) { a, b -> a + b }

fun Double.kcalText(): String = roundToInt().toString()

/** Up to two decimals without trailing zeros: 0.25, 0.5, 1. */
fun Double.shortText(): String {
    val r = (this * 100).roundToInt() / 100.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

/** Grams for display: to one decimal, without ".0". */
fun Double.gramsText(): String {
    val r = (this * 10).roundToInt() / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}
