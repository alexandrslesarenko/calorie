package com.calorie.app.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.calorie.app.logic.HcExport
import com.calorie.app.logic.HcFood
import com.calorie.app.logic.HcSent
import com.calorie.app.logic.HcWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * State of the export for the settings card. access - write access is given; null - not
 * checked yet. at - last successful pass (0 - none in this run), failed - the last pass failed.
 */
data class HcExportStatus(
    val hc: HcStatus? = null,
    val access: Boolean? = null,
    val food: Int = 0,
    val weights: Int = 0,
    val at: Long = 0,
    val failed: Boolean = false,
)

/**
 * Mirrors the diary (NutritionRecord per entry) and the weight log (WeightRecord per day) to
 * Health Connect. Each record has a stable clientRecordId (entry-<id>, weight-<day>) and the
 * write time as its version, so writing it again replaces it. What was sent is remembered in
 * a file (id -> print); a pass sends only the difference and deletes what left the diary.
 *
 * Runs in its own scope, one pass at a time: requests during a pass make one more pass after
 * it. A pass is not tied to the activity: leaving the app must not cut a write in half.
 */
object HealthConnectExport {
    private const val TAG = "HealthConnectExport"
    private const val FILE = "hc_export.json"
    /** Records per insert call. */
    private const val CHUNK = 500

    val PERMISSIONS = setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private val lock = Any()

    private val _status = MutableStateFlow(HcExportStatus())
    val status: StateFlow<HcExportStatus> = _status

    private val MEALS = mapOf(
        Meal.BREAKFAST.key to MealType.MEAL_TYPE_BREAKFAST,
        Meal.LUNCH.key to MealType.MEAL_TYPE_LUNCH,
        Meal.DINNER.key to MealType.MEAL_TYPE_DINNER,
        Meal.SNACK.key to MealType.MEAL_TYPE_SNACK,
    )

    /** Asks for a pass; cheap when nothing changed (no Health Connect call besides the access check). */
    fun request(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (!started) {
                started = true
                scope.launch { for (k in kick) pass(app) }
            }
        }
        kick.trySend(Unit)
    }

    /**
     * The next pass writes everything again and deletes Calorie's records the diary no longer
     * has: export switched on, access given. Kept in the file until a pass succeeds.
     */
    fun owe(ctx: Context) {
        val st = state(ctx)
        save(ctx, st.copy(full = true))
    }

    /** Export switched off: forget what was sent, records already in Health Connect stay there. */
    fun forget(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
        _status.value = HcExportStatus()
    }

    private fun state(ctx: Context): HcSent =
        HcSent.parse(runCatching { File(ctx.filesDir, FILE).readText() }.getOrNull())

    private fun save(ctx: Context, s: HcSent) {
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(s.toJson())
        tmp.renameTo(f)
    }

    private suspend fun pass(ctx: Context) {
        if (!Prefs(ctx).hcExport) return
        val sdk = HealthConnectSource.sdkStatus(ctx)
        if (sdk != HcStatus.OK) {
            _status.value = _status.value.copy(hc = sdk, access = null)
            return
        }
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.w(TAG, "permissions unknown", e)
            return
        }
        if (!granted.containsAll(PERMISSIONS)) {
            _status.value = _status.value.copy(hc = sdk, access = false)
            return
        }
        try {
            val (food, weights) = desired(ctx)
            val n = sync(ctx, client, food, weights)
            _status.value = HcExportStatus(sdk, true, food.size, weights.size, System.currentTimeMillis(), false)
            Log.i(TAG, "${food.size} entries, ${weights.size} weigh-ins; wrote ${n.first}, deleted ${n.second}")
        } catch (e: Exception) {
            Log.w(TAG, "export failed", e)
            _status.value = _status.value.copy(hc = sdk, access = true, failed = true)
        }
    }

    private suspend fun desired(ctx: Context): Pair<Map<String, HcFood>, Map<String, HcWeight>> {
        val dao = FoodDb.get(ctx).dao()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val food = dao.allEntries().associate { e ->
            val (start, end) = HcExport.foodTime(e.ts, e.day, e.meal, zone, now)
            val id = HcExport.foodId(e.id)
            id to HcFood(id, start, end, e.name, Meal.byKey(e.meal).key, e.kcal, e.protein, e.fat, e.carbs)
        }
        val weights = dao.allWeights().associate { w ->
            val id = HcExport.weightId(w.day)
            id to HcWeight(id, HcExport.weightTime(w.day, zone, now), w.kg)
        }
        return food to weights
    }

    /** Returns (written, deleted). Progress is saved per chunk: a failure midway keeps what got through. */
    private suspend fun sync(
        ctx: Context, client: HealthConnectClient, food: Map<String, HcFood>, weights: Map<String, HcWeight>,
    ): Pair<Int, Int> {
        val state = state(ctx)
        val desired = food.mapValues { it.value.print } + weights.mapValues { it.value.print }
        val own = if (state.full) ownIds(ctx, client) else null
        val plan = HcExport.plan(desired, state, own)
        // Old ids stay until deleted: a full pass cut midway still knows what to clean up.
        val sent = state.sent.toMutableMap()

        val zone = ZoneId.systemDefault()
        val version = System.currentTimeMillis()
        var written = 0
        for (chunk in plan.upsert.sorted().chunked(CHUNK)) {
            val records = chunk.mapNotNull { id -> food[id]?.let { record(it, zone, version) } ?: weights[id]?.let { record(it, zone, version) } }
            if (records.isNotEmpty()) client.insertRecords(records)
            chunk.forEach { sent[it] = desired.getValue(it) }
            written += records.size
            save(ctx, HcSent(state.full, sent))
        }
        val (gone, goneW) = plan.delete.partition { it.startsWith("entry-") }
        try {
            gone.chunked(CHUNK).forEach { client.deleteRecords(NutritionRecord::class, emptyList(), it) }
            goneW.chunked(CHUNK).forEach { client.deleteRecords(WeightRecord::class, emptyList(), it) }
        } catch (e: Exception) {
            // The next pass asks Health Connect what is really there instead of retrying the same ids.
            save(ctx, HcSent(true, sent))
            throw e
        }
        // Everything outside the diary is deleted or was not there: only the diary stays remembered.
        sent.keys.retainAll(desired.keys)
        save(ctx, HcSent(false, sent))
        return written to plan.delete.size
    }

    /**
     * clientRecordIds of the records Calorie wrote, found in Health Connect. null - could not be
     * read (an app may read its own data with write access only, but if this phone says
     * otherwise the cleanup is skipped rather than read access asked for).
     */
    private suspend fun ownIds(ctx: Context, client: HealthConnectClient): Set<String>? = try {
        val origin = setOf(DataOrigin(ctx.packageName))
        val all = TimeRangeFilter.before(Instant.now().plusSeconds(86_400))
        (readAll(client, NutritionRecord::class, all, origin) + readAll(client, WeightRecord::class, all, origin))
            .mapNotNull { it.metadata.clientRecordId }.toSet()
            .also { Log.i(TAG, "${it.size} own records found") }
    } catch (e: Exception) {
        Log.w(TAG, "own records not readable", e)
        null
    }

    private suspend fun <T : Record> readAll(client: HealthConnectClient, type: KClass<T>, range: TimeRangeFilter, origin: Set<DataOrigin>): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, range, dataOriginFilter = origin, pageSize = 5000, pageToken = token))
            out += page.records
            token = page.pageToken
        } while (token != null)
        return out
    }

    /** null - Health Connect rejects the values (out of its ranges): the entry is skipped, not the whole pass. */
    private fun record(f: HcFood, zone: ZoneId, version: Long): Record? = try {
        val start = Instant.ofEpochMilli(f.start)
        val end = Instant.ofEpochMilli(f.end)
        NutritionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            metadata = Metadata.manualEntry(f.id, version),
            name = f.name.take(MAX_NAME),
            mealType = MEALS[f.meal] ?: MealType.MEAL_TYPE_UNKNOWN,
            energy = Energy.kilocalories(f.kcal.coerceIn(0.0, MAX_KCAL)),
            protein = Mass.grams(f.protein.coerceIn(0.0, MAX_G)),
            totalFat = Mass.grams(f.fat.coerceIn(0.0, MAX_G)),
            totalCarbohydrate = Mass.grams(f.carbs.coerceIn(0.0, MAX_G)),
        )
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "entry ${f.id} skipped", e)
        null
    }

    private fun record(w: HcWeight, zone: ZoneId, version: Long): Record? = try {
        val time = Instant.ofEpochMilli(w.time)
        WeightRecord(time, zone.rules.getOffset(time), Mass.kilograms(w.kg), Metadata.manualEntry(w.id, version))
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "weight ${w.id} skipped", e)
        null
    }

    private const val MAX_NAME = 1000
    private const val MAX_KCAL = 100_000.0
    private const val MAX_G = 100_000.0
}
