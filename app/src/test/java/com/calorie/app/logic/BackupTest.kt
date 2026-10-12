package com.calorie.app.logic

import com.calorie.app.data.DayTarget
import com.calorie.app.data.Dish
import com.calorie.app.data.Entry
import com.calorie.app.data.WeightMark
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class BackupTest {
    private val day = LocalDate.of(2026, 9, 30).toEpochDay()

    private fun entry(name: String, grams: Double = 200.0, meal: String = "lunch", d: Long = day) =
        Entry(0, 1_000L, d, meal, name, grams, 300.0, 20.0, 10.0, 30.0, "text")

    private fun dish(name: String, favorite: Boolean = false) =
        Dish(Keys.dish(name), name, 150.0, 10.0, 5.0, 15.0, 200.0, 3, 5_000L, favorite)

    private val profile = ProfileData("f", LocalDate.of(1985, 3, 14).toEpochDay(), 168, "light", "normal", true, false, 62.5, 0, true, true, "dark")

    private fun data(entries: List<Entry> = emptyList(), weights: List<WeightMark> = emptyList(), dishes: List<Dish> = emptyList(), p: ProfileData? = null, targets: List<DayTarget> = emptyList()) =
        BackupData(42L, p, entries, weights, dishes, targets)

    @Test
    fun roundTrip() {
        val d = data(listOf(entry("Борщ, со сметаной")), listOf(WeightMark(day, 70.3)), listOf(dish("Гречка", true)), profile, listOf(DayTarget(day, 2080)))
        assertEquals(d, Backup.parse(Backup.toJson(d)))
    }

    @Test
    fun formatThreeKeepsBarcodeAndPhoto() {
        val jpeg = byteArrayOf(-1, -40, -1, 0, 1, 2, 3)
        val d = data(dishes = listOf(dish("Кефир").copy(barcode = "4870001234567", uses = 0), dish("Гречка")))
            .copy(photos = mapOf(Keys.dish("Кефир") to jpeg))
        val back = Backup.parse(Backup.toJson(d))
        assertEquals(d.dishes, back.dishes)
        assertEquals(setOf(Keys.dish("Кефир")), back.photos.keys)
        assertArrayEquals(jpeg, back.photos[Keys.dish("Кефир")])
    }

    @Test
    fun formatTwoDishesStillImport() {
        val d = Backup.parse(
            """{"app":"calorie","format":2,"dishes":[{"name":"Сыр","kcal100":350,"lastGrams":30,"uses":0}]}"""
        )
        val dish = d.dishes.single()
        assertNull(dish.barcode)
        // Before format 3 every dish in the library had been eaten.
        assertEquals(1, dish.uses)
        assertTrue(d.photos.isEmpty())
    }

    @Test
    fun brokenPhotoCostsOnlyThePhoto() {
        val d = Backup.parse(
            """{"app":"calorie","format":3,"dishes":[{"name":"Сыр","kcal100":350,"photo":"not base64 !!"}]}"""
        )
        assertEquals(1, d.dishes.size)
        assertTrue(d.photos.isEmpty())
    }

    @Test
    fun mergeFillsMissingPhotoAndBarcodeButNeverReplaces() {
        val mine = byteArrayOf(1)
        val theirs = byteArrayOf(2)
        val have = data(dishes = listOf(dish("Кефир"), dish("Сыр").copy(barcode = "111111111"))).copy(photos = mapOf(Keys.dish("Сыр") to mine))
        val add = data(dishes = listOf(dish("Кефир").copy(barcode = "4870001234567"), dish("Сыр").copy(barcode = "222222222"), dish("Хлеб")))
            .copy(photos = mapOf(Keys.dish("Кефир") to theirs, Keys.dish("Сыр") to theirs, Keys.dish("Хлеб") to theirs))
        val m = Backup.merge(have, add)
        assertEquals(setOf(Keys.dish("Кефир"), Keys.dish("Хлеб")), m.photos.keys)
        val byId = m.dishes.associateBy { it.id }
        assertEquals("4870001234567", byId[Keys.dish("Кефир")]?.barcode)
        // The barcode already here is kept and nothing else differs, so the dish is not rewritten.
        assertNull(byId[Keys.dish("Сыр")])
        assertTrue(Keys.dish("Хлеб") in byId)
    }

    @Test
    fun mergeDoesNotTakeBarcodeFromOwnDish() {
        val have = data(dishes = listOf(dish("Кефир").copy(barcode = "4870001234567")))
        val add = data(dishes = listOf(dish("Кефир 2.5%").copy(barcode = "4870001234567")))
        assertNull(Backup.merge(have, add).dishes.single().barcode)
    }

    @Test
    fun formatOneHasNoTargets() {
        val d = Backup.parse("""{"app":"calorie","format":1,"weights":[{"date":"2026-09-30","kg":70}]}""")
        assertEquals(1, d.weights.size)
        assertTrue(d.targets.isEmpty())
    }

    @Test
    fun mergeKeepsExistingTargets() {
        val have = data(targets = listOf(DayTarget(day, 2080)))
        val add = data(targets = listOf(DayTarget(day, 2340), DayTarget(day - 1, 2100)))
        assertEquals(listOf(DayTarget(day - 1, 2100)), Backup.merge(have, add).targets)
    }

    @Test
    fun emptyProfileBirthDateSurvives() {
        val p = profile.copy(sex = null, birthDay = null, heightCm = 0)
        val back = Backup.parse(Backup.toJson(data(p = p))).profile!!
        assertNull(back.sex)
        assertNull(back.birthDay)
        assertTrue(back.isEmpty)
    }

    private fun expectError(json: String, e: BackupError) {
        try {
            Backup.parse(json)
            fail("parsed: $json")
        } catch (x: BackupException) {
            assertEquals(e, x.error)
        }
    }

    @Test
    fun rejectsForeignFiles() {
        expectError("not json", BackupError.NOT_BACKUP)
        expectError("""{"app":"other","format":1}""", BackupError.NOT_BACKUP)
        expectError("""{"app":"calorie"}""", BackupError.NOT_BACKUP)
        expectError("""{"app":"calorie","format":99}""", BackupError.TOO_NEW)
    }

    @Test
    fun brokenRecordsSkippedNotWholeFile() {
        val json = """
            {"app":"calorie","format":1,"entries":[
              {"date":"2026-09-30","meal":"lunch","name":"Ok","grams":100,"kcal":50},
              {"date":"30.09.2026","meal":"lunch","name":"Bad date","grams":100,"kcal":50},
              {"date":"2026-09-30","meal":"lunch","name":"","grams":100,"kcal":50},
              {"date":"2026-09-30","meal":"lunch","name":"No kcal","grams":100}
            ],"weights":[{"date":"2026-09-30","kg":-1}]}
        """.trimIndent()
        val d = Backup.parse("\uFEFF" + json)
        assertEquals(listOf("Ok"), d.entries.map { it.name })
        assertTrue(d.weights.isEmpty())
        assertNull(d.profile)
    }

    @Test
    fun mergeSkipsExistingButKeepsRepeatsInFile() {
        val have = data(listOf(entry("Йогурт", 125.0)), listOf(WeightMark(day, 70.0)), listOf(dish("Гречка"), dish("Суп", true)))
        val add = data(
            listOf(entry("йогурт ", 125.0), entry("Йогурт", 125.0), entry("Йогурт", 125.0, meal = "snack"), entry("Чай")),
            listOf(WeightMark(day, 71.0), WeightMark(day - 1, 70.5)),
            listOf(dish("гречка", true), dish("Суп"), dish("Рис")),
        )
        val m = Backup.merge(have, add)
        // One yogurt at lunch is already there: the second one in the file is new.
        assertEquals(listOf("Йогурт" to "lunch", "Йогурт" to "snack", "Чай" to "lunch"), m.entries.map { it.name to it.meal })
        assertEquals(1, m.skippedEntries)
        assertEquals(listOf(WeightMark(day - 1, 70.5)), m.weights)
        assertEquals(listOf("Гречка" to true, "Рис" to false), m.dishes.map { it.name to it.favorite })
    }

    @Test
    fun csvQuotesAndBom() {
        val csv = Backup.csv(listOf(entry("Борщ, \"домашний\"", 250.0))) { if (it == "lunch") "Обед" else it }
        val lines = csv.split("\r\n")
        assertTrue(lines[0].startsWith("\uFEFFdate,meal,name"))
        assertEquals("2026-09-30,Обед,\"Борщ, \"\"домашний\"\"\",250,300,20,10,30,text", lines[1])
    }
}
