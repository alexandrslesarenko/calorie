package com.calorie.app.logic

/**
 * When to resend a queued Claude request after a temporary failure (overload, 429, no network).
 * Failed requests are not billed, so retrying is free; the delay grows so a long outage
 * does not drain the battery, and after GIVE_UP_MS the user decides.
 */
object Retry {
    const val FIRST_MS = 30_000L
    const val MAX_MS = 15 * 60_000L
    const val GIVE_UP_MS = 6 * 60 * 60_000L

    /** Delay after the given failed attempt (1-based): 30 s, 1, 2, 4, 8 min, then every 15 min. */
    fun delayMs(attempt: Int): Long {
        val n = (attempt - 1).coerceIn(0, 20)
        return (FIRST_MS shl n).coerceAtMost(MAX_MS)
    }

    /** The request has been failing since sinceMs for too long: stop and let the user decide. */
    fun giveUp(sinceMs: Long, nowMs: Long): Boolean = nowMs - sinceMs >= GIVE_UP_MS
}
