package com.calorie.app.data

import android.content.Context
import com.calorie.app.logic.Activity
import com.calorie.app.logic.Pace
import com.calorie.app.logic.ProfileData
import com.calorie.app.logic.Sex
import java.time.LocalDate

class Prefs(context: Context) {
    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val KEY_UNKNOWN = "unknown"
        const val KEY_OK = "ok"
        const val KEY_BAD = "bad"
    }

    private val sp = context.getSharedPreferences("calorie", Context.MODE_PRIVATE)

    var sex: Sex?
        get() = Sex.byKey(sp.getString("sex", null))
        set(v) = sp.edit().putString("sex", v?.key).apply()

    /**
     * Birth date; null - not set. Profiles saved before the full date keep only the year:
     * then July 1 of that year, which is what the age was computed from before.
     */
    var birthDate: LocalDate?
        get() = when {
            sp.contains("birth_date") -> LocalDate.ofEpochDay(sp.getLong("birth_date", 0))
            sp.getInt("birth_year", 0) > 0 -> LocalDate.of(sp.getInt("birth_year", 0), 7, 1)
            else -> null
        }
        set(v) {
            val e = sp.edit().remove("birth_year")
            if (v == null) e.remove("birth_date") else e.putLong("birth_date", v.toEpochDay())
            e.apply()
        }

    /** Only the birth year is known (old profile): show the year, not a made-up date. */
    val birthYearOnly: Boolean get() = !sp.contains("birth_date") && sp.getInt("birth_year", 0) > 0

    /** Height, cm; 0 - not set. */
    var heightCm: Int
        get() = sp.getInt("height_cm", 0)
        set(v) = sp.edit().putInt("height_cm", v).apply()

    var activity: Activity
        get() = Activity.byKey(sp.getString("activity", null)) ?: Activity.LIGHT
        set(v) = sp.edit().putString("activity", v.key).apply()

    var pace: Pace
        get() = Pace.byKey(sp.getString("pace", null)) ?: Pace.NORMAL
        set(v) = sp.edit().putString("pace", v.key).apply()

    /** Activity level measured by Pulsar instead of the manual one, when there is enough data. */
    var activityFromPulsar: Boolean
        get() = sp.getBoolean("activity_from_pulsar", true)
        set(v) = sp.edit().putBoolean("activity_from_pulsar", v).apply()

    /** Target weight, kg; 0 - not set (then just lose weight at the chosen pace). */
    var goalKg: Float
        get() = sp.getFloat("goal_kg", 0f)
        set(v) = sp.edit().putFloat("goal_kg", v).apply()

    /** User-defined kcal target instead of the calculated one; 0 - calculate from the profile. */
    var customKcal: Int
        get() = sp.getInt("custom_kcal", 0)
        set(v) = sp.edit().putInt("custom_kcal", v).apply()

    /** The user has read and accepted the disclaimer about the calculated target. */
    /**
     * The goal page has been opened at least once. Until then the target silently uses the
     * default pace, and Today asks to set the goal.
     */
    var goalSeen: Boolean
        get() = sp.getBoolean("goal_seen", false)
        set(v) = sp.edit().putBoolean("goal_seen", v).apply()

    var disclaimerAccepted: Boolean
        get() = sp.getBoolean("disclaimer_accepted", false)
        set(v) = sp.edit().putBoolean("disclaimer_accepted", v).apply()

    var theme: String
        get() = sp.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = sp.edit().putString("theme", v).apply()

    /** Result of the last key check: KEY_OK / KEY_BAD / KEY_UNKNOWN. */
    var keyStatus: String
        get() = sp.getString("key_status", KEY_UNKNOWN) ?: KEY_UNKNOWN
        set(v) = sp.edit().putString("key_status", v).apply()

    /** Claude spending counter since usageSince: requests and tokens. */
    val aiRequests: Int get() = sp.getInt("ai_requests", 0)
    val aiInputTokens: Long get() = sp.getLong("ai_in", 0)
    val aiOutputTokens: Long get() = sp.getLong("ai_out", 0)
    val usageSince: Long get() = sp.getLong("usage_since", 0)

    fun addUsage(input: Long, output: Long) {
        val e = sp.edit()
        if (usageSince == 0L) e.putLong("usage_since", System.currentTimeMillis())
        e.putInt("ai_requests", aiRequests + 1)
            .putLong("ai_in", aiInputTokens + input)
            .putLong("ai_out", aiOutputTokens + output)
            .apply()
    }

    /** Profile and goal for a backup file. */
    var profile: ProfileData
        get() = ProfileData(
            sex?.key, birthDate?.toEpochDay(), heightCm, activity.key, pace.key, activityFromPulsar,
            goalKg.toDouble(), customKcal, goalSeen, disclaimerAccepted, theme,
        )
        set(p) {
            sex = Sex.byKey(p.sex)
            birthDate = p.birthDay?.let { LocalDate.ofEpochDay(it) }
            heightCm = p.heightCm
            // Unknown keys (a newer app) keep the current value.
            Activity.byKey(p.activity)?.let { activity = it }
            Pace.byKey(p.pace)?.let { pace = it }
            activityFromPulsar = p.activityFromPulsar
            goalKg = p.goalKg.toFloat()
            customKcal = p.customKcal
            goalSeen = p.goalSeen
            disclaimerAccepted = p.disclaimerAccepted
            if (p.theme in listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)) theme = p.theme
        }

    fun resetUsage() {
        sp.edit().remove("ai_requests").remove("ai_in").remove("ai_out")
            .putLong("usage_since", System.currentTimeMillis()).apply()
    }
}
