package com.calorie.app.logic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecognizersTest {
    @Test
    fun chosenFirstThenTheRestInListOrder() {
        assertEquals(
            listOf(Recognizer.HAIKU_5_5, Recognizer.GEMINI_LITE),
            Recognizer.order(Recognizer.HAIKU_5_5) { true },
        )
        assertEquals(
            listOf(Recognizer.GEMINI_LITE, Recognizer.HAIKU_5_5),
            Recognizer.order(Recognizer.GEMINI_LITE) { true },
        )
    }

    @Test
    fun providersWithoutKeySkipped() {
        assertEquals(listOf(Recognizer.HAIKU_5_5), Recognizer.order(Recognizer.GEMINI_LITE) { it == Provider.CLAUDE })
        // The chosen one has no key: the others with a key are still asked.
        assertEquals(listOf(Recognizer.GEMINI_LITE), Recognizer.order(Recognizer.HAIKU_5_5) { it == Provider.GEMINI })
        assertTrue(Recognizer.order(Recognizer.HAIKU_5_5) { false }.isEmpty())
    }

    @Test
    fun storedKeysReadBack() {
        Recognizer.entries.forEach { assertEquals(it, Recognizer.byKey(it.key)) }
        assertEquals(Recognizer.DEFAULT, Recognizer.byKey(null))
        assertEquals(Recognizer.DEFAULT, Recognizer.byKey("sonnet-9"))
        // Haiku 4.5 was removed: a user who had chosen it gets the default.
        assertEquals(Recognizer.HAIKU_5_5, Recognizer.byKey("haiku-4-5"))
    }

    private class Gone(val who: Recognizer) : Exception()
    private class Busy : Exception()

    private val all = listOf(Recognizer.HAIKU_5_5, Recognizer.GEMINI_LITE)

    @Test
    fun goneMovesToTheNextAndSaysWho() = runBlocking {
        val goneSeen = mutableListOf<Recognizer>()
        val a = askInOrder(all, { it is Gone }, { r, _ -> goneSeen += r }, { _, _ -> }) { r ->
            if (r == Recognizer.HAIKU_5_5) throw Gone(r) else "answer of ${r.key}"
        }
        assertEquals("answer of gemini-flash-lite", a.value)
        assertEquals(Recognizer.GEMINI_LITE, a.by)
        assertEquals(listOf(Recognizer.HAIKU_5_5), a.skipped.map { it.by })
        assertTrue(a.skipped.single().error is Gone)
        assertEquals(listOf(Recognizer.HAIKU_5_5), goneSeen)
    }

    @Test
    fun otherFailureEndsTheRound() = runBlocking {
        val asked = mutableListOf<Recognizer>()
        var failedBy: Recognizer? = null
        val busy = Busy()
        try {
            askInOrder(all, { it is Gone }, { _, _ -> }, { r, _ -> failedBy = r }) { r ->
                asked += r
                if (r == Recognizer.HAIKU_5_5) throw Gone(r) else throw busy
            }
            fail("no exception")
        } catch (e: Busy) {
            assertSame(busy, e)
        }
        assertEquals(listOf(Recognizer.HAIKU_5_5, Recognizer.GEMINI_LITE), asked)
        assertEquals(Recognizer.GEMINI_LITE, failedBy)
    }

    @Test
    fun allGoneThrowsTheFirst() = runBlocking {
        try {
            askInOrder(all, { it is Gone }, { _, _ -> }, { _, _ -> }) { r -> throw Gone(r) }
            fail("no exception")
        } catch (e: Gone) {
            assertEquals(Recognizer.HAIKU_5_5, e.who)
        }
    }

    @Test
    fun fallbackSurvivesTheDetailColumn() {
        val f = Fallback(Recognizer.HAIKU_5_5, Recognizer.GEMINI_LITE, "OVERLOADED")
        assertEquals(f, Fallback.decode(f.encode()))
        assertNull(Fallback.decode(null))
        assertNull(Fallback.decode("some error text from the API"))
        // A queued answer from before Haiku 4.5 was removed loses its note, not the answer.
        assertNull(Fallback.decode("gemini-flash-lite haiku-4-5 OVERLOADED"))
    }
}
