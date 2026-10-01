package com.calorie.app.logic

import com.calorie.app.data.DayTarget
import com.calorie.app.data.Dish
import com.calorie.app.data.Entry
import com.calorie.app.data.WeightMark
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Profile and goal settings as stored in Prefs (keys of Sex, Activity, Pace). Only what the
 * user entered: the API key, the Claude spending counter and the app language stay out.
 */
data class ProfileData(
    val sex: String?,
    val birthDay: Long?,
    val heightCm: Int,
    val activity: String,
    val pace: String,
    val activityFromPulsar: Boolean,
    val activityFromWeight: Boolean,
    val goalKg: Double,
    val customKcal: Int,
    val goalSeen: Boolean,
    val disclaimerAccepted: Boolean,
    val theme: String,
) {
    /** Nothing entered yet: a fresh install, so an import may fill it in even when adding. */
    val isEmpty: Boolean get() = sex == null && birthDay == null && heightCm == 0
}

data class BackupData(
    val exported: Long,
    val profile: ProfileData?,
    val entries: List<Entry>,
    val weights: List<WeightMark>,
    val dishes: List<Dish>,
    /** Target of each past day (format 2); without it old days are measured against today's. */
    val targets: List<DayTarget> = emptyList(),
)

enum class BackupError {
    /** Not JSON or not our file. */
    NOT_BACKUP,
    /** Written by a newer app version with a format this one does not know. */
    TOO_NEW,
}

class BackupException(val error: BackupError, message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** What adding a backup to the existing data changes: only the new records. */
data class BackupMerge(
    val entries: List<Entry>,
    val weights: List<WeightMark>,
    /** New dishes and existing ones that become favorites. */
    val dishes: List<Dish>,
    val targets: List<DayTarget>,
    val skippedEntries: Int,
)

/**
 * Backup file of the diary: JSON with dates as ISO strings, so it stays readable and does not
 * depend on the database layout. Values of an entry are for the whole portion, of a dish per
 * 100 g, as in the database. Parsing is strict about the file kind and tolerant about details:
 * a broken record is skipped, not the whole file.
 */
object Backup {
    const val APP = "calorie"
    const val FORMAT = 2
    /** Byte order mark: Excel needs it to read UTF-8, and a file edited on Windows may start with it. */
    private const val BOM = 0xFEFF.toChar()

    fun toJson(d: BackupData): String {
        val root = JSONObject()
            .put("app", APP)
            .put("format", FORMAT)
            .put("exported", d.exported)
        d.profile?.let { p ->
            root.put(
                "profile",
                JSONObject()
                    .put("sex", p.sex ?: JSONObject.NULL)
                    .put("birthDate", p.birthDay?.let { LocalDate.ofEpochDay(it).toString() } ?: JSONObject.NULL)
                    .put("heightCm", p.heightCm)
                    .put("activity", p.activity)
                    .put("pace", p.pace)
                    .put("activityFromPulsar", p.activityFromPulsar)
                    .put("activityFromWeight", p.activityFromWeight)
                    .put("goalKg", p.goalKg)
                    .put("customKcal", p.customKcal)
                    .put("goalSeen", p.goalSeen)
                    .put("disclaimerAccepted", p.disclaimerAccepted)
                    .put("theme", p.theme)
            )
        }
        root.put("entries", JSONArray(d.entries.map { e ->
            JSONObject()
                .put("date", LocalDate.ofEpochDay(e.day).toString())
                .put("ts", e.ts)
                .put("meal", e.meal)
                .put("name", e.name)
                .put("grams", e.grams)
                .put("kcal", e.kcal)
                .put("protein", e.protein)
                .put("fat", e.fat)
                .put("carbs", e.carbs)
                .put("source", e.source)
        }))
        root.put("weights", JSONArray(d.weights.map { w ->
            JSONObject().put("date", LocalDate.ofEpochDay(w.day).toString()).put("kg", w.kg)
        }))
        root.put("dishes", JSONArray(d.dishes.map { x ->
            JSONObject()
                .put("name", x.name)
                .put("kcal100", x.kcal)
                .put("protein100", x.protein)
                .put("fat100", x.fat)
                .put("carbs100", x.carbs)
                .put("lastGrams", x.lastGrams)
                .put("uses", x.uses)
                .put("lastUsed", x.lastUsed)
                .put("favorite", x.favorite)
        }))
        root.put("targets", JSONArray(d.targets.map { t ->
            JSONObject().put("date", LocalDate.ofEpochDay(t.day).toString()).put("kcal", t.kcal)
        }))
        return root.toString(1)
    }

    fun parse(json: String): BackupData {
        val root = try {
            JSONObject(json.trimStart(BOM))
        } catch (e: JSONException) {
            throw BackupException(BackupError.NOT_BACKUP, e.message, e)
        }
        if (root.optString("app") != APP) throw BackupException(BackupError.NOT_BACKUP, "app")
        val format = root.optInt("format", 0)
        if (format < 1) throw BackupException(BackupError.NOT_BACKUP, "format")
        if (format > FORMAT) throw BackupException(BackupError.TOO_NEW, "format $format")

        val entries = objects(root, "entries").mapNotNull { o ->
            val day = date(o, "date") ?: return@mapNotNull null
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val grams = num(o, "grams")?.takeIf { it > 0 } ?: return@mapNotNull null
            val kcal = num(o, "kcal") ?: return@mapNotNull null
            Entry(
                id = 0,
                ts = o.optLong("ts", 0),
                day = day,
                meal = o.optString("meal"),
                name = name,
                grams = grams,
                kcal = kcal,
                protein = num(o, "protein") ?: 0.0,
                fat = num(o, "fat") ?: 0.0,
                carbs = num(o, "carbs") ?: 0.0,
                source = o.optString("source"),
            )
        }
        val weights = objects(root, "weights").mapNotNull { o ->
            val day = date(o, "date") ?: return@mapNotNull null
            val kg = num(o, "kg")?.takeIf { it > 0 } ?: return@mapNotNull null
            WeightMark(day, kg)
        }.distinctBy { it.day }
        val dishes = objects(root, "dishes").mapNotNull { o ->
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val kcal = num(o, "kcal100") ?: return@mapNotNull null
            Dish(
                id = Keys.dish(name),
                name = name,
                kcal = kcal,
                protein = num(o, "protein100") ?: 0.0,
                fat = num(o, "fat100") ?: 0.0,
                carbs = num(o, "carbs100") ?: 0.0,
                lastGrams = num(o, "lastGrams")?.takeIf { it > 0 } ?: 100.0,
                uses = o.optInt("uses", 1).coerceAtLeast(1),
                lastUsed = o.optLong("lastUsed", 0),
                favorite = o.optBoolean("favorite", false),
            )
        }.distinctBy { it.id }
        val targets = objects(root, "targets").mapNotNull { o ->
            val day = date(o, "date") ?: return@mapNotNull null
            val kcal = o.optInt("kcal", 0).takeIf { it > 0 } ?: return@mapNotNull null
            DayTarget(day, kcal)
        }.distinctBy { it.day }
        val profile = root.optJSONObject("profile")?.let { p ->
            ProfileData(
                sex = p.optString("sex").takeIf { it.isNotEmpty() && !p.isNull("sex") },
                birthDay = date(p, "birthDate"),
                heightCm = p.optInt("heightCm", 0).coerceAtLeast(0),
                activity = p.optString("activity"),
                pace = p.optString("pace"),
                activityFromPulsar = p.optBoolean("activityFromPulsar", true),
                activityFromWeight = p.optBoolean("activityFromWeight", true),
                goalKg = p.optDouble("goalKg", 0.0).takeIf { !it.isNaN() && it > 0 } ?: 0.0,
                customKcal = p.optInt("customKcal", 0).coerceAtLeast(0),
                goalSeen = p.optBoolean("goalSeen", false),
                disclaimerAccepted = p.optBoolean("disclaimerAccepted", false),
                theme = p.optString("theme"),
            )
        }
        return BackupData(root.optLong("exported", 0), profile, entries, weights, dishes, targets)
    }

    /**
     * Records of the backup that are not there yet. An entry is the same when day, meal, name
     * and grams match; counted, so two identical yogurts in one meal both survive a merge.
     * Weight and target of an existing day and an existing dish are kept as they are (a dish
     * only gains the favorite mark).
     */
    fun merge(have: BackupData, add: BackupData): BackupMerge {
        val left = have.entries.groupingBy(::entryKey).eachCount().toMutableMap()
        val entries = add.entries.filter { e ->
            val k = entryKey(e)
            val n = left[k] ?: 0
            if (n > 0) left[k] = n - 1
            n == 0
        }
        val days = have.weights.map { it.day }.toSet()
        val targetDays = have.targets.map { it.day }.toSet()
        val dishes = have.dishes.associateBy { it.id }
        return BackupMerge(
            entries = entries,
            weights = add.weights.filter { it.day !in days },
            dishes = add.dishes.mapNotNull { d ->
                val old = dishes[d.id]
                when {
                    old == null -> d
                    d.favorite && !old.favorite -> old.copy(favorite = true)
                    else -> null
                }
            },
            targets = add.targets.filter { it.day !in targetDays },
            skippedEntries = add.entries.size - entries.size,
        )
    }

    private fun entryKey(e: Entry) = "${e.day}|${e.meal}|${Keys.dish(e.name)}|${(e.grams * 10).roundToLong()}"

    /**
     * Diary as CSV for spreadsheets: comma separated, dot decimals, UTF-8 with a BOM (without it
     * Excel reads Cyrillic as garbage). mealName - the meal key in the app language.
     */
    fun csv(entries: List<Entry>, mealName: (String) -> String): String {
        val sb = StringBuilder().append(BOM)
        sb.append("date,meal,name,grams,kcal,protein,fat,carbs,source\r\n")
        entries.sortedWith(compareBy({ it.day }, { it.ts })).forEach { e ->
            listOf(
                LocalDate.ofEpochDay(e.day).toString(), csvField(mealName(e.meal)), csvField(e.name),
                dec(e.grams), dec(e.kcal), dec(e.protein), dec(e.fat), dec(e.carbs), e.source,
            ).joinTo(sb, ",")
            sb.append("\r\n")
        }
        return sb.toString()
    }

    /** RFC 4180: a field with a comma, quote or line break goes in quotes, quotes doubled. */
    fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun dec(v: Double): String = String.format(Locale.US, "%.1f", v).removeSuffix(".0")

    private fun objects(root: JSONObject, key: String): List<JSONObject> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun date(o: JSONObject, key: String): Long? = try {
        if (o.isNull(key)) null else LocalDate.parse(o.optString(key)).toEpochDay()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun num(o: JSONObject, key: String): Double? = o.optDouble(key, Double.NaN).takeIf { !it.isNaN() && it >= 0 }
}
