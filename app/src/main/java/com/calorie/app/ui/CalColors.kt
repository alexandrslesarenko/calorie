package com.calorie.app.ui

import androidx.compose.ui.graphics.Color
import com.calorie.app.data.Meal

/** Fixed app colors: the same in light and dark themes. */
object CalColors {
    /** Over the daily target. */
    val Over = Color(0xFFE5484D)
    val Warn = Color(0xFFE8912D)
    val Protein = Color(0xFF3F8FE8)
    val Fat = Color(0xFFD4A20F)
    val Carbs = Color(0xFF3BA55C)
    val Photo = Color(0xFFE5484D)
    val Gallery = Color(0xFF8E6BE0)
    val Barcode = Color(0xFF3F8FE8)
    val Text = Color(0xFF26A69A)
    val Manual = Color(0xFF7D8FA3)
    /** Favorite star. */
    val Star = Color(0xFFF5B301)

    /** Meal hues: morning warm to evening cool, so a wrong pick stands out in the list. */
    fun meal(m: Meal) = when (m) {
        Meal.BREAKFAST -> Color(0xFFF0A020)
        Meal.LUNCH -> Color(0xFF2FA36B)
        Meal.DINNER -> Color(0xFF6A78E0)
        Meal.SNACK -> Color(0xFFD0609A)
    }
}
