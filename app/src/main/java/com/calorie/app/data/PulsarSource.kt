package com.calorie.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.calorie.app.logic.ActiveMinute
import com.calorie.app.logic.Burn
import java.time.LocalDate
import java.time.ZoneId

enum class PulsarStatus {
    NOT_INSTALLED,
    /** Installed, but the signature permission is not granted: Calorie was installed before Pulsar or signed differently. */
    NO_ACCESS,
    OK,
}

data class PulsarData(val status: PulsarStatus, val minutes: List<ActiveMinute> = emptyList())

/**
 * Walks and workouts from the Pulsar heart rate app (same developer, same signing key).
 * The contract mirrors com.puls.app.data.ActivityShare in Pulsar: authority, path,
 * parameters and columns must stay in sync with it.
 */
object PulsarSource {
    const val PACKAGE = "com.puls.app"
    const val PERMISSION = "com.puls.app.permission.READ_ACTIVITY"
    private const val AUTHORITY = "com.puls.app.activity"
    private const val PATH_MINUTES = "minutes"

    /** Blocking provider query - call from a background thread. */
    fun load(ctx: Context, days: Int = Burn.WINDOW_DAYS): PulsarData {
        val installed = runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
        if (!installed) return PulsarData(PulsarStatus.NOT_INSTALLED)
        if (ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return PulsarData(PulsarStatus.NO_ACCESS)
        val from = LocalDate.now().minusDays(days.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(PATH_MINUTES)
            .appendQueryParameter("from", from.toString())
            .appendQueryParameter("to", System.currentTimeMillis().toString())
            .build()
        return try {
            val minutes = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val iMinute = c.getColumnIndexOrThrow("minute")
                val iMode = c.getColumnIndexOrThrow("mode")
                val iBpm = c.getColumnIndexOrThrow("bpm")
                val iSamples = c.getColumnIndexOrThrow("samples")
                buildList {
                    while (c.moveToNext()) add(ActiveMinute(c.getLong(iMinute), c.getString(iMode), c.getDouble(iBpm), c.getInt(iSamples)))
                }
            }
            // null cursor - an older Pulsar without the provider.
            if (minutes == null) PulsarData(PulsarStatus.NO_ACCESS) else PulsarData(PulsarStatus.OK, minutes)
        } catch (_: SecurityException) {
            PulsarData(PulsarStatus.NO_ACCESS)
        } catch (_: IllegalArgumentException) {
            // Unknown authority: Pulsar is installed but has no provider yet.
            PulsarData(PulsarStatus.NO_ACCESS)
        }
    }
}
