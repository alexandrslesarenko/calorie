package com.calorie.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.calorie.app.logic.FoodDraft
import com.calorie.app.logic.Keys
import com.calorie.app.logic.Nutrients
import kotlinx.coroutines.flow.Flow

/**
 * Diary entry. Stores a snapshot of the values for the whole portion, not a product reference:
 * editing a product in the database or cache must not change what was already eaten.
 * day - local date (LocalDate.toEpochDay) of the day the entry belongs to.
 */
@Entity(tableName = "entry", indices = [Index("day")])
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val day: Long,
    val meal: String,
    val name: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    /** Where the entry came from: Source.key. */
    val source: String,
) {
    val nutrients: Nutrients get() = Nutrients(kcal, protein, fat, carbs)

    /** Draft for re-adding or editing: values back per 100 g. */
    fun toDraft(): FoodDraft = FoodDraft.fromPortion(name, grams, nutrients)
}

/** Weight on a date; one entry per day, a repeated one replaces it. */
@Entity(tableName = "weight")
data class WeightMark(@PrimaryKey val day: Long, val kg: Double)

/**
 * Daily kcal target as it was on that day. The target follows weight, age and the Pulsar
 * average, so a past day measured against today's target would change its balance after
 * the fact. Today's row is rewritten while the day lasts; the last value stays.
 */
@Entity(tableName = "day_target")
data class DayTarget(@PrimaryKey val day: Long, val kcal: Int)

/** Product found by barcode: no second network trip. */
@Entity(tableName = "product")
data class CachedProduct(
    @PrimaryKey val barcode: String,
    val name: String,
    val brand: String?,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val servingG: Double?,
    val ts: Long,
)

/**
 * Dish in the "My dishes" library: everything ever logged, with values per 100 g
 * and the last portion weight. Repeating from the library costs no Claude request.
 * id - Keys.dish(name).
 */
@Entity(tableName = "dish")
data class Dish(
    @PrimaryKey val id: String,
    val name: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val lastGrams: Double,
    val uses: Int,
    val lastUsed: Long,
    val favorite: Boolean,
) {
    fun toDraft(): FoodDraft = FoodDraft(name, lastGrams, Nutrients(kcal, protein, fat, carbs))
}

/** Claude answer (report_food tool input) by Keys.photo / Keys.description. */
@Entity(tableName = "ai_cache")
data class AiCached(@PrimaryKey val id: String, val json: String, val ts: Long)

/**
 * Claude request waiting for a retry after a temporary failure, or its answer waiting for
 * the user. day and meal are fixed when the request was made: a lunch confirmed in the
 * evening is still lunch. A photo lives in a file next to it (AiQueue.jpegFile), text or the
 * photo hint in text. since - start of the current retry window (Retry.giveUp).
 */
@Entity(tableName = "pending")
data class Pending(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val meal: String,
    /** Source.key: photo or text. */
    val source: String,
    val text: String,
    val language: String,
    val cacheKey: String,
    val useCache: Boolean,
    val state: String,
    val attempts: Int,
    val since: Long,
    val nextTry: Long,
    /** AiFailure.name of the last failure. */
    val failure: String?,
    val detail: String?,
    /** report_food input once the answer has come. */
    val json: String?,
) {
    companion object {
        const val WAITING = "waiting"
        const val READY = "ready"
        const val FAILED = "failed"
    }
}

data class DayTotal(val day: Long, val kcal: Double, val protein: Double, val fat: Double, val carbs: Double)

@Dao
interface FoodDao {
    @Query("SELECT * FROM entry WHERE day = :day ORDER BY ts")
    fun entries(day: Long): Flow<List<Entry>>

    @Insert
    suspend fun insert(entries: List<Entry>)

    @Update
    suspend fun update(e: Entry)

    @Delete
    suspend fun delete(e: Entry)

    @Query(
        "SELECT day, SUM(kcal) AS kcal, SUM(protein) AS protein, SUM(fat) AS fat, SUM(carbs) AS carbs " +
            "FROM entry WHERE day >= :from AND day <= :to GROUP BY day ORDER BY day DESC"
    )
    fun totals(from: Long, to: Long): Flow<List<DayTotal>>

    /** Favorites first, then by last use. */
    @Query("SELECT * FROM dish ORDER BY favorite DESC, lastUsed DESC")
    fun dishes(): Flow<List<Dish>>

    @Query("SELECT * FROM dish WHERE id = :id")
    suspend fun dish(id: String): Dish?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDish(d: Dish)

    @Delete
    suspend fun deleteDish(d: Dish)

    @Query("UPDATE dish SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("SELECT * FROM ai_cache WHERE id = :id")
    suspend fun aiCached(id: String): AiCached?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAiCached(c: AiCached)

    /** The answer cache does not grow forever: keep only the latest keep entries. */
    @Query("DELETE FROM ai_cache WHERE id NOT IN (SELECT id FROM ai_cache ORDER BY ts DESC LIMIT :keep)")
    suspend fun trimAiCache(keep: Int)

    /**
     * Remember dishes in the library: values per 100 g and weight are the latest, the counter grows,
     * the favorite mark is kept.
     */
    @Transaction
    suspend fun rememberDishes(drafts: List<FoodDraft>, now: Long) {
        drafts.forEach { d ->
            val key = Keys.dish(d.name)
            val old = dish(key)
            val p = d.per100
            putDish(Dish(key, d.name.trim(), p.kcal, p.protein, p.fat, p.carbs, d.grams, (old?.uses ?: 0) + 1, now, old?.favorite ?: false))
        }
    }

    @Query("SELECT * FROM pending ORDER BY id")
    fun pendingAll(): Flow<List<Pending>>

    @Query("SELECT * FROM pending WHERE id = :id")
    fun pendingFlow(id: Long): Flow<Pending?>

    @Query("SELECT * FROM pending WHERE id = :id")
    suspend fun pending(id: Long): Pending?

    @Query("SELECT * FROM pending WHERE state = 'waiting' AND nextTry <= :now ORDER BY id")
    suspend fun pendingDue(now: Long): List<Pending>

    @Query("SELECT MIN(nextTry) FROM pending WHERE state = 'waiting'")
    suspend fun nextPendingTry(): Long?

    @Insert
    suspend fun insertPending(p: Pending): Long

    @Query("DELETE FROM pending WHERE id = :id")
    suspend fun deletePending(id: Long)

    @Query("UPDATE pending SET state = 'ready', json = :json, failure = NULL, detail = NULL WHERE id = :id")
    suspend fun pendingReady(id: Long, json: String)

    @Query("UPDATE pending SET state = 'failed', failure = :failure, detail = :detail WHERE id = :id")
    suspend fun pendingFailed(id: Long, failure: String?, detail: String?)

    @Query("UPDATE pending SET attempts = :attempts, nextTry = :nextTry, failure = :failure, detail = :detail WHERE id = :id")
    suspend fun pendingRetry(id: Long, attempts: Int, nextTry: Long, failure: String, detail: String?)

    /** Not tried in this round because the service is down: wait with the one that was. */
    @Query("UPDATE pending SET nextTry = :nextTry WHERE id = :id")
    suspend fun pendingPostpone(id: Long, nextTry: Long)

    /** Retry at once; a failed request gets a new retry window. */
    @Query(
        "UPDATE pending SET since = CASE WHEN state = 'failed' THEN :now ELSE since END, " +
            "state = 'waiting', nextTry = :now WHERE id = :id AND state != 'ready'"
    )
    suspend fun pendingRetryNow(id: Long, now: Long)

    @Query("SELECT * FROM entry ORDER BY day, ts")
    suspend fun allEntries(): List<Entry>

    @Query("SELECT * FROM weight ORDER BY day")
    suspend fun allWeights(): List<WeightMark>

    @Query("SELECT * FROM dish ORDER BY name")
    suspend fun allDishes(): List<Dish>

    @Query("SELECT * FROM day_target ORDER BY day")
    suspend fun allDayTargets(): List<DayTarget>

    @Query("SELECT * FROM day_target ORDER BY day")
    fun dayTargets(): Flow<List<DayTarget>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDayTargets(t: List<DayTarget>)

    @Query("SELECT COUNT(*) FROM entry")
    fun entryCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putWeights(w: List<WeightMark>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDishes(d: List<Dish>)

    @Query("DELETE FROM entry")
    suspend fun clearEntries()

    @Query("DELETE FROM weight")
    suspend fun clearWeights()

    @Query("DELETE FROM dish")
    suspend fun clearDishes()

    @Query("DELETE FROM day_target")
    suspend fun clearDayTargets()

    /** Import in one transaction: a broken file must not leave half a diary. */
    @Transaction
    suspend fun importBackup(replace: Boolean, entries: List<Entry>, weights: List<WeightMark>, dishes: List<Dish>, targets: List<DayTarget>) {
        if (replace) {
            clearEntries()
            clearWeights()
            clearDishes()
            clearDayTargets()
        }
        insert(entries)
        putWeights(weights)
        putDishes(dishes)
        putDayTargets(targets)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putWeight(w: WeightMark)

    @Delete
    suspend fun deleteWeight(w: WeightMark)

    @Query("SELECT * FROM weight ORDER BY day")
    fun weights(): Flow<List<WeightMark>>

    @Query("SELECT * FROM weight ORDER BY day DESC LIMIT 1")
    fun lastWeight(): Flow<WeightMark?>

    @Query("SELECT * FROM product WHERE barcode = :barcode")
    suspend fun product(barcode: String): CachedProduct?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProduct(p: CachedProduct)
}

@Database(
    entities = [Entry::class, WeightMark::class, CachedProduct::class, Dish::class, AiCached::class, Pending::class, DayTarget::class],
    version = 3,
    exportSchema = false,
)
abstract class FoodDb : RoomDatabase() {
    abstract fun dao(): FoodDao

    companion object {
        @Volatile private var instance: FoodDb? = null

        /** v2: the retry queue for Claude requests. SQL copied from the generated FoodDb_Impl. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PENDING_SQL)
            }
        }

        /** v3: the target of each day. SQL copied from the generated FoodDb_Impl. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(DAY_TARGET_SQL)
            }
        }

        private const val DAY_TARGET_SQL =
            "CREATE TABLE IF NOT EXISTS `day_target` (`day` INTEGER NOT NULL, `kcal` INTEGER NOT NULL, PRIMARY KEY(`day`))"

        private const val PENDING_SQL = "CREATE TABLE IF NOT EXISTS `pending` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`day` INTEGER NOT NULL, `meal` TEXT NOT NULL, `source` TEXT NOT NULL, `text` TEXT NOT NULL, " +
            "`language` TEXT NOT NULL, `cacheKey` TEXT NOT NULL, `useCache` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
            "`attempts` INTEGER NOT NULL, `since` INTEGER NOT NULL, `nextTry` INTEGER NOT NULL, " +
            "`failure` TEXT, `detail` TEXT, `json` TEXT)"

        // Migrations are written by hand: any schema change - bump version and add a Migration.
        fun get(context: Context): FoodDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FoodDb::class.java, "food.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
