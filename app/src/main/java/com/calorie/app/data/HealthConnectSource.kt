package com.calorie.app.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.calorie.app.logic.ActiveMinute
import com.calorie.app.logic.HcMinutes
import com.calorie.app.logic.HcSession
import com.calorie.app.logic.HrSample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

enum class HcStatus {
    /** No Health Connect on this phone and none to install. */
    UNAVAILABLE,
    /** Android 12-13: the Health Connect app is missing or too old. */
    NEEDS_INSTALL,
    NO_ACCESS,
    OK,
}

/**
 * history - older data than 30 days before access was granted is readable; calories - the kcal
 * of workouts without heart rate are readable (without them such workouts are not counted).
 */
data class HcData(
    val status: HcStatus,
    val minutes: List<ActiveMinute> = emptyList(),
    val history: Boolean = false,
    val calories: Boolean = false,
)

/**
 * Walks and workouts from Health Connect: workouts written by any app (Samsung Health, a watch
 * app, Pulsar does not write them) with the heart rate inside them, from any app as well
 * (Pulsar writes it, so a walk the phone detected while Pulsar was measuring has a pulse).
 * A workout without heart rate goes by the kcal its app wrote (HcMinutes).
 * Heart rate is read per workout, not for the whole period: Pulsar writes a record every
 * minute of the day, months of that would be hundreds of thousands of records on every resume.
 */
object HealthConnectSource {
    private const val TAG = "HealthConnectSource"
    const val PROVIDER = "com.google.android.apps.healthdata"
    private const val MS_PER_MIN = 60_000L

    val READ = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )
    /** Optional: only workouts without heart rate need them. */
    val CALORIES = setOf(
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )
    private val WALKS = setOf(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING)

    /**
     * MET by workout kind - the cap for kcal counted without heart rate. Ordinary-effort values
     * from the Compendium of Physical Activities; unknown kinds get a light 3.5.
     */
    private val METS = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to 3.5, ExerciseSessionRecord.EXERCISE_TYPE_HIKING to 6.0,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to 8.0, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to 8.0,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to 7.0, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to 6.8,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to 6.0, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to 6.0,
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to 3.5, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to 3.5,
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to 5.0, ExerciseSessionRecord.EXERCISE_TYPE_ROWING to 7.0, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to 7.0,
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to 4.0, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to 9.0,
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to 8.0, ExerciseSessionRecord.EXERCISE_TYPE_DANCING to 5.0,
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to 2.5, ExerciseSessionRecord.EXERCISE_TYPE_PILATES to 3.0,
    )
    private const val DEFAULT_MET = 3.5

    fun sdkStatus(ctx: Context): HcStatus = when (HealthConnectClient.getSdkStatus(ctx)) {
        HealthConnectClient.SDK_AVAILABLE -> HcStatus.OK
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcStatus.NEEDS_INSTALL
        else -> HcStatus.UNAVAILABLE
    }

    /** What to ask for: read access, kcal and, where Health Connect has it, the history beyond 30 days. */
    fun permissions(ctx: Context): Set<String> {
        if (sdkStatus(ctx) != HcStatus.OK) return READ + CALORIES
        val client = HealthConnectClient.getOrCreate(ctx)
        val history = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        return READ + CALORIES + if (history) setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY) else emptySet()
    }

    /** null - the read failed (quota, Health Connect busy): keep what was loaded before. */
    suspend fun load(ctx: Context, days: Int = PulsarSource.HISTORY_DAYS): HcData? {
        val sdk = sdkStatus(ctx)
        if (sdk != HcStatus.OK) return HcData(sdk)
        val client = HealthConnectClient.getOrCreate(ctx)
        // Only the granted list decides "no access": a read failing midway (the app went to the
        // background, quota) must not wipe the activity and rewrite today's target.
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.w(TAG, "permissions unknown", e)
            return null
        }
        if (!granted.containsAll(READ)) return HcData(HcStatus.NO_ACCESS)
        return try {
            val history = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
            val calories = granted.containsAll(CALORIES)
            val from = LocalDate.now().minusDays(days.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val raw = readAll(client, ExerciseSessionRecord::class, TimeRangeFilter.between(from, Instant.now()))
                .map { HcSession(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.exerciseType in WALKS, METS[it.exerciseType] ?: DEFAULT_MET) }
            val samples = windows(raw).flatMap { (start, end) ->
                readAll(client, HeartRateRecord::class, range(start, end))
                    .flatMap { r -> r.samples.map { HrSample(it.time.toEpochMilli(), it.beatsPerMinute.toDouble()) } }
            }
            // Kcal are read only for workouts the heart rate does not cover: two reads each.
            var noHr = 0
            val sessions = raw.map { s ->
                if (!calories || HcMinutes.hasHeartRate(s, samples)) s
                else {
                    noHr++
                    s.copy(
                        activeKcal = kcal(readAll(client, ActiveCaloriesBurnedRecord::class, range(s.start, s.end)), s) { it.startTime to it.endTime to it.energy },
                        totalKcal = kcal(readAll(client, TotalCaloriesBurnedRecord::class, range(s.start, s.end)), s) { it.startTime to it.endTime to it.energy },
                    )
                }
            }
            val minutes = HcMinutes.build(sessions, samples)
            Log.i(
                TAG,
                "${sessions.size} workouts, ${samples.size} heart rate samples, $noHr without heart rate " +
                    "(${sessions.count { it.activeKcal != null }} with active kcal, ${sessions.count { it.totalKcal != null }} with total), " +
                    "${minutes.size} minutes counted, history=$history, calories=$calories, " +
                    "latest without heart rate ${sessions.filter { it.activeKcal != null || it.totalKcal != null }.maxOfOrNull { it.start }?.let { Instant.ofEpochMilli(it) }}",
            )
            HcData(HcStatus.OK, minutes, history, calories)
        } catch (e: Exception) {
            Log.w(TAG, "read failed", e)
            null
        }
    }

    private fun range(start: Long, end: Long) = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))

    /**
     * Kcal of the records inside the workout, each by its overlap with it. Two apps may write
     * the same workout: the largest sum of one app is taken, not the sum of all. null - none.
     */
    private fun <T : Record> kcal(records: List<T>, s: HcSession, of: (T) -> Pair<Pair<Instant, Instant>, Energy>): Double? {
        if (records.isEmpty()) return null
        return records.groupBy { it.metadata.dataOrigin.packageName }.values.maxOf { list ->
            list.sumOf { r ->
                val (span, energy) = of(r)
                val start = span.first.toEpochMilli()
                val end = span.second.toEpochMilli()
                val overlap = (minOf(end, s.end) - maxOf(start, s.start)).coerceAtLeast(0)
                if (end > start) energy.inKilocalories * overlap / (end - start) else energy.inKilocalories
            }
        }.takeIf { it > 0 }
    }

    /** Workout spans widened by the fill margin and merged where they overlap: one read each. */
    private fun windows(sessions: List<HcSession>): List<Pair<Long, Long>> {
        val margin = HcMinutes.FILL_MIN * MS_PER_MIN + MS_PER_MIN
        val out = mutableListOf<Pair<Long, Long>>()
        for (s in sessions.sortedBy { it.start }) {
            val start = s.start - margin
            val end = s.end + margin
            val last = out.lastOrNull()
            if (last != null && start <= last.second) out[out.lastIndex] = last.first to maxOf(last.second, end)
            else out += start to end
        }
        return out
    }

    private suspend fun <T : Record> readAll(client: HealthConnectClient, type: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, range, pageToken = token))
            out += page.records
            token = page.pageToken
        } while (token != null)
        return out
    }
}
