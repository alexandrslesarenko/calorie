package com.calorie.app.data

import androidx.annotation.StringRes
import com.calorie.app.R

/** Meal of the day. key is stored in the database - do not rename. */
enum class Meal(val key: String, @StringRes val label: Int) {
    BREAKFAST("breakfast", R.string.meal_breakfast),
    LUNCH("lunch", R.string.meal_lunch),
    DINNER("dinner", R.string.meal_dinner),
    SNACK("snack", R.string.meal_snack);

    companion object {
        fun byKey(k: String?) = entries.firstOrNull { it.key == k } ?: SNACK

        /** Meal by hour: this way it almost never has to be picked by hand. */
        fun byHour(h: Int) = when (h) {
            in 4..10 -> BREAKFAST
            in 11..15 -> LUNCH
            in 17..21 -> DINNER
            else -> SNACK
        }
    }
}

/** Where an entry came from. key is stored in the database. */
enum class Source(val key: String) {
    PHOTO("photo"), BARCODE("barcode"), TEXT("text"), MANUAL("manual"), REPEAT("repeat");

    companion object {
        fun byKey(k: String?) = entries.firstOrNull { it.key == k } ?: MANUAL
    }
}
