package com.calorie.app.logic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where walks and workouts come from. Pulsar is the native source; Health Connect is the
 * compatibility mode for watches and apps Pulsar knows nothing about (Samsung Health and
 * the like). The key is stored in preferences - do not rename.
 */
enum class ActivitySource(val key: String) {
    AUTO("auto"),
    PULSAR("pulsar"),
    HEALTH_CONNECT("hc");

    companion object {
        fun byKey(key: String?): ActivitySource? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A workout from Health Connect: start and end (ms), walk or another kind, the MET of its kind
 * and the kcal the writing app counted for it (active - above rest, total - with rest; null -
 * not written). The kcal matter only when the workout has no heart rate.
 */
data class HcSession(
    val start: Long,
    val end: Long,
    val walk: Boolean,
    val met: Double = Burn.WALK_MET,
    val activeKcal: Double? = null,
    val totalKcal: Double? = null,
)

/** One heart rate sample from Health Connect. */
data class HrSample(val time: Long, val bpm: Double)

/**
 * Health Connect workouts turned into the same minutes Pulsar gives, so Burn treats both alike.
 * Watches write heart rate much more sparsely than Pulsar (every few seconds to every few
 * minutes), so the sample count says nothing about coverage here: a minute counts by how much
 * of it the workout covers. A minute without its own sample borrows the nearest one up to
 * FILL_MIN minutes away.
 *
 * A workout with heart rate on less than HR_SHARE of its minutes (a phone without a watch)
 * goes by the kcal its app counted instead, each minute getting its share, and never above
 * the MET estimate for its kind (Burn.netKcal; decided by the user 2026-11-02). Without kcal
 * it is left out - nothing to go by but the duration.
 */
object HcMinutes {
    const val FILL_MIN = 2
    const val HR_SHARE = 0.5
    private const val MS_PER_MIN = 60_000L
    private const val MS_PER_S = 1_000L

    fun build(sessions: List<HcSession>, samples: List<HrSample>): List<ActiveMinute> {
        val hr = samples.filter { it.bpm in 1.0..300.0 }.sortedBy { it.time }
        val times = LongArray(hr.size) { hr[it].time }
        val out = sortedMapOf<Long, ActiveMinute>()
        // Two apps may write the same walk: each minute is taken once, by the earliest workout.
        for (s in sessions.sortedBy { it.start }) {
            val mode = if (s.walk) Burn.WALK else Burn.TRAINING
            val spans = spans(s)
            val withHr = spans.mapNotNull { (m, covered) -> bpmAt(hr, times, m)?.let { ActiveMinute(m, mode, it, covered, ActivitySource.HEALTH_CONNECT) } }
            val minutes = if (spans.isNotEmpty() && withHr.size >= spans.size * HR_SHARE) withHr else byKcal(s, mode, spans)
            for (x in minutes) if (x.minute !in out) out[x.minute] = x
        }
        return out.values.toList()
    }

    /** Whether the heart rate covers the workout well enough; Health Connect reads kcal only for the rest. */
    fun hasHeartRate(s: HcSession, samples: List<HrSample>): Boolean {
        val hr = samples.filter { it.bpm in 1.0..300.0 }.sortedBy { it.time }
        val times = LongArray(hr.size) { hr[it].time }
        val spans = spans(s)
        return spans.isNotEmpty() && spans.count { bpmAt(hr, times, it.first) != null } >= spans.size * HR_SHARE
    }

    /** Minute starts of the workout with the seconds of each it covers. */
    private fun spans(s: HcSession): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        var m = s.start / MS_PER_MIN * MS_PER_MIN
        while (m < s.end) {
            val covered = ((min(s.end, m + MS_PER_MIN) - max(s.start, m)) / MS_PER_S).toInt()
            if (covered > 0) out += m to covered
            m += MS_PER_MIN
        }
        return out
    }

    private fun byKcal(s: HcSession, mode: String, spans: List<Pair<Long, Int>>): List<ActiveMinute> {
        val gross = s.activeKcal == null
        val kcal = (s.activeKcal ?: s.totalKcal)?.takeIf { it > 0 } ?: return emptyList()
        val seconds = spans.sumOf { it.second }.takeIf { it > 0 } ?: return emptyList()
        return spans.map { (m, covered) ->
            ActiveMinute(m, mode, 0.0, covered, ActivitySource.HEALTH_CONNECT, kcal = kcal * covered / seconds, kcalGross = gross, met = s.met)
        }
    }

    /** Average of the samples inside the minute, else the nearest sample within FILL_MIN. */
    private fun bpmAt(hr: List<HrSample>, times: LongArray, minute: Long): Double? {
        val from = lowerBound(times, minute)
        val to = lowerBound(times, minute + MS_PER_MIN)
        if (to > from) return (from until to).sumOf { hr[it].bpm } / (to - from)
        val mid = minute + MS_PER_MIN / 2
        val near = listOfNotNull(hr.getOrNull(from - 1), hr.getOrNull(from)).minByOrNull { abs(it.time - mid) } ?: return null
        return near.bpm.takeIf { abs(near.time - mid) <= FILL_MIN * MS_PER_MIN + MS_PER_MIN / 2 }
    }

    private fun lowerBound(a: LongArray, key: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Which minutes the calculations get. In AUTO Pulsar wins where it was running and Health
 * Connect fills the rest of the day: Pulsar may be started only for the evening walk while the
 * watch records the day. A Health Connect minute within Burn.SESSION_GAP_MIN of a Pulsar minute
 * is dropped, so a walk recorded by both (with clocks a little apart) is counted once.
 */
object ActivityMerge {
    private const val MS_PER_MIN = 60_000L

    fun merge(source: ActivitySource, pulsar: List<ActiveMinute>, hc: List<ActiveMinute>): List<ActiveMinute> =
        when (source) {
            ActivitySource.PULSAR -> pulsar
            ActivitySource.HEALTH_CONNECT -> hc
            ActivitySource.AUTO -> {
                val taken = pulsar.map { it.minute }.sorted().toLongArray()
                val margin = Burn.SESSION_GAP_MIN * MS_PER_MIN
                fun nearPulsar(t: Long): Boolean {
                    var lo = 0
                    var hi = taken.size
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (taken[mid] < t - margin) lo = mid + 1 else hi = mid
                    }
                    return lo < taken.size && taken[lo] <= t + margin
                }
                (pulsar + hc.filter { !nearPulsar(it.minute) }).sortedBy { it.minute }
            }
        }
}
