package com.calorie.app.data

import android.content.Context
import android.net.Uri
import com.calorie.app.logic.Backup
import com.calorie.app.logic.BackupData
import com.calorie.app.logic.BackupError
import com.calorie.app.logic.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** What an import did, for the message on the settings page. */
data class ImportResult(val entries: Int, val weights: Int, val dishes: Int, val skipped: Int, val profile: Boolean)

/**
 * Diary backup to and from a file the user picks (Storage Access Framework, no permissions).
 * The file holds the diary, weight log, day targets, "My dishes" and the profile; the API key, the Claude
 * answer cache, the retry queue and the barcode cache stay out.
 */
object BackupStore {
    /** Files bigger than this are not ours: years of a diary take a few megabytes. */
    private const val MAX_BYTES = 50L * 1024 * 1024

    private suspend fun snapshot(ctx: Context): BackupData {
        val dao = FoodDb.get(ctx).dao()
        return BackupData(System.currentTimeMillis(), Prefs(ctx).profile, dao.allEntries(), dao.allWeights(), dao.allDishes(), dao.allDayTargets())
    }

    suspend fun exportJson(ctx: Context, uri: Uri) {
        val json = Backup.toJson(snapshot(ctx))
        write(ctx, uri, json)
    }

    suspend fun exportCsv(ctx: Context, uri: Uri, mealName: (String) -> String) {
        write(ctx, uri, Backup.csv(FoodDb.get(ctx).dao().allEntries(), mealName))
    }

    /** Reads and checks a backup file; nothing is changed yet. */
    suspend fun read(ctx: Context, uri: Uri): BackupData {
        val text = withContext(Dispatchers.IO) {
            val stream = ctx.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
            stream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw BackupException(BackupError.NOT_BACKUP, "too big")
                }
                out.toString(Charsets.UTF_8.name())
            }
        }
        return withContext(Dispatchers.Default) { Backup.parse(text) }
    }

    /**
     * replace - the file takes the place of the diary, weights, dishes and profile. Otherwise
     * only new records are added, and the profile only if none was entered on this phone.
     */
    suspend fun import(ctx: Context, add: BackupData, replace: Boolean): ImportResult {
        val prefs = Prefs(ctx)
        val dao = FoodDb.get(ctx).dao()
        val result = if (replace) {
            dao.importBackup(true, add.entries, add.weights, add.dishes, add.targets)
            ImportResult(add.entries.size, add.weights.size, add.dishes.size, 0, add.profile != null)
        } else {
            val m = Backup.merge(snapshot(ctx), add)
            dao.importBackup(false, m.entries, m.weights, m.dishes, m.targets)
            ImportResult(m.entries.size, m.weights.size, m.dishes.size, m.skippedEntries, add.profile != null && prefs.profile.isEmpty)
        }
        if (result.profile) prefs.profile = add.profile!!
        return result
    }

    private suspend fun write(ctx: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        // "wt": truncate, or a shorter file over a longer one leaves a tail of the old one.
        val stream = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
