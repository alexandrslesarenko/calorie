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
    entities = [Entry::class, WeightMark::class, CachedProduct::class, Dish::class, AiCached::class],
    version = 1,
    exportSchema = false,
)
abstract class FoodDb : RoomDatabase() {
    abstract fun dao(): FoodDao

    companion object {
        @Volatile private var instance: FoodDb? = null

        // Migrations are written by hand: any schema change - bump version and add a Migration.
        fun get(context: Context): FoodDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FoodDb::class.java, "food.db")
                .build().also { instance = it }
        }
    }
}
