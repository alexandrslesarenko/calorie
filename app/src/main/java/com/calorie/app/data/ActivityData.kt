package com.calorie.app.data

import com.calorie.app.logic.ActiveMinute
import com.calorie.app.logic.ActivitySource

/**
 * Walks and workouts as the screens see them: the chosen source, the state of both sources
 * and the minutes picked from them (ActivityMerge).
 */
data class ActivityData(
    val source: ActivitySource,
    val pulsar: PulsarData,
    val hc: HcData,
    val minutes: List<ActiveMinute>,
) {
    /** The chosen source can give data; it may still have no walks. */
    val usable: Boolean
        get() = when (source) {
            ActivitySource.PULSAR -> pulsar.status == PulsarStatus.OK
            ActivitySource.HEALTH_CONNECT -> hc.status == HcStatus.OK
            ActivitySource.AUTO -> pulsar.status == PulsarStatus.OK || hc.status == HcStatus.OK
        }

    /** Pulsar, Health Connect or both, by where the minutes came from; a product name, not translated. */
    val name: String
        get() {
            val from = minutes.mapTo(HashSet()) { it.origin }
            return when {
                from.size > 1 -> "Pulsar + Health Connect"
                ActivitySource.HEALTH_CONNECT in from -> HC_NAME
                ActivitySource.PULSAR in from -> PULSAR_NAME
                source == ActivitySource.HEALTH_CONNECT -> HC_NAME
                source == ActivitySource.AUTO && pulsar.status != PulsarStatus.OK && hc.status == HcStatus.OK -> HC_NAME
                else -> PULSAR_NAME
            }
        }

    companion object {
        const val PULSAR_NAME = "Pulsar"
        const val HC_NAME = "Health Connect"
        val NONE = ActivityData(ActivitySource.AUTO, PulsarData(PulsarStatus.NOT_INSTALLED), HcData(HcStatus.UNAVAILABLE), emptyList())
    }
}
