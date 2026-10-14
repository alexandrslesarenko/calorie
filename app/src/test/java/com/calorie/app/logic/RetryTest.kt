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
            // A used up daily quota waits in the queue when no other recognizer can answer.
            setOf(AiFailure.RATE_LIMIT, AiFailure.OVERLOADED, AiFailure.NETWORK, AiFailure.QUOTA_DAY),
            AiFailure.entries.filter { it.retryable }.toSet(),
        )
    }

    @Test
    fun swapsOnTheThirdOverload() {
        // First try failed (1), the queue retried once (2): the third overload moves on.
        assertFalse(Retry.swapBusy(1, lastBusy = true))
        assertTrue(Retry.swapBusy(2, lastBusy = true))
        assertTrue(Retry.swapBusy(5, lastBusy = true))
        // The last failure was the network, not an overload.
        assertFalse(Retry.swapBusy(5, lastBusy = false))
    }
}
