package com.calorie.app.logic

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** A diary entry as it goes to Health Connect: one NutritionRecord. Times in epoch ms. */
data class HcFood(
    val id: String,
    val start: Long,
    val end: Long,
    val name: String,
    val meal: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
) {
    /** Everything the record carries: a change in any of it is sent again. */
    val print: String get() = listOf(start, end, name, meal, r(kcal), r(protein), r(fat), r(carbs)).joinToString("|")
}

/** A weigh-in as it goes to Health Connect: one WeightRecord. */
data class HcWeight(val id: String, val time: Long, val kg: Double) {
    val print: String get() = "$time|${r(kg)}"
}

private fun r(v: Double) = Math.round(v * 100) / 100.0

/**
 * What to change in Health Connect: records to write (new or changed) and to delete, by
 * clientRecordId. Writing with the same id and a higher version replaces the record, so
 * repeating a write never makes a duplicate.
 */
data class HcPlan(val upsert: Set<String>, val delete: Set<String>)

/**
 * What was sent: clientRecordId -> print. full - a full pass is owed (export just switched
 * on, access granted, the state lost): everything is written again and Calorie's records that
 * the diary no longer has are deleted.
 */
data class HcSent(val full: Boolean, val sent: Map<String, String>) {
    fun toJson(): String = JSONObject()
        .put("full", full)
        .put("sent", JSONObject().also { o -> sent.forEach { (k, v) -> o.put(k, v) } })
        .toString()

    companion object {
        val FULL = HcSent(true, emptyMap())

        /** Unreadable or missing - a full pass, never "all sent". */
        fun parse(s: String?): HcSent {
            if (s.isNullOrBlank()) return FULL
            return try {
                val o = JSONObject(s)
                val sent = o.optJSONObject("sent") ?: return FULL
                HcSent(o.optBoolean("full", true), sent.keys().asSequence().associateWith { sent.getString(it) })
            } catch (e: org.json.JSONException) {
                FULL
            }
        }
    }
}

/**
 * Diary and weight log -> Health Connect records. Pure: what to send and when, without the
 * client. Times are deterministic, so an unchanged entry gives the same print on every pass.
 */
object HcExport {
    private const val MINUTE = 60_000L

    /** Hour of a meal whose entry was saved on another day (lunch logged the next morning, import without ts). */
    private val MEAL_HOUR = mapOf("breakfast" to 8, "lunch" to 13, "dinner" to 19, "snack" to 16)
    private const val WEIGHT_HOUR = 7

    fun foodId(entryId: Long) = "entry-$entryId"
    fun weightId(day: Long) = "weight-$day"

    /**
     * Start and end of the record for an entry of day saved at ts. The record must fall on the
     * entry's day, not on the day it was typed in: ts is used only when it lies in that day.
     * The minute that ends at ts (Health Connect wants start before end): ending, not starting,
     * there keeps it out of the future without clamping to now, which would move a fresh entry
     * on every pass and send it again.
     */
    fun foodTime(ts: Long, day: Long, meal: String, zone: ZoneId, now: Long): Pair<Long, Long> {
        val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val placed = if (ts in dayStart until dayEnd) ts else minOf(dayStart + (MEAL_HOUR[meal] ?: 12) * 60 * MINUTE, now)
        val end = placed.coerceIn(dayStart + MINUTE, dayEnd)
        return end - MINUTE to end
    }

    /** The weight log has no time: morning of the day, but not later than now. */
    fun weightTime(day: Long, zone: ZoneId, now: Long): Long {
        val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        return minOf(dayStart + WEIGHT_HOUR * 60 * MINUTE, now).coerceAtLeast(dayStart)
    }

    /**
     * desired - id -> print of everything the diary has now. own - ids of Calorie's records
     * found in Health Connect on a full pass: they are the truth then (records deleted in the
     * Health Connect app are not deleted again). null - could not be read: only what this
     * phone remembers sending is deleted.
     */
    fun plan(desired: Map<String, String>, state: HcSent, own: Set<String>? = null): HcPlan {
        val upsert = if (state.full) desired.keys else desired.filter { (id, p) -> state.sent[id] != p }.keys
        val known = if (state.full && own != null) own else state.sent.keys
        return HcPlan(upsert.toSet(), known - desired.keys)
    }
}
