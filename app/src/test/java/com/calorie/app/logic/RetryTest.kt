package com.calorie.app.logic

import com.calorie.app.ai.AiFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryTest {
    @Test
    fun delayDoublesUpToCap() {
        assertEquals(30_000L, Retry.delayMs(1))
        assertEquals(60_000L, Retry.delayMs(2))
        assertEquals(8 * 60_000L, Retry.delayMs(5))
        assertEquals(Retry.MAX_MS, Retry.delayMs(6))
        assertEquals(Retry.MAX_MS, Retry.delayMs(1000))
    }

    @Test
    fun zeroAttemptIsFirstDelay() {
        assertEquals(Retry.FIRST_MS, Retry.delayMs(0))
    }

    @Test
    fun givesUpAfterDeadline() {
        assertFalse(Retry.giveUp(0, Retry.GIVE_UP_MS - 1))
        assertTrue(Retry.giveUp(0, Retry.GIVE_UP_MS))
    }

    @Test
    fun onlyTemporaryFailuresAreRetried() {
        assertEquals(
            setOf(AiFailure.RATE_LIMIT, AiFailure.OVERLOADED, AiFailure.NETWORK),
            AiFailure.entries.filter { it.retryable }.toSet(),
        )
    }
}
