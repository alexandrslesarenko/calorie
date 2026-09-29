package com.calorie.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeysTest {
    @Test
    fun dishIgnoresCaseAndSpaces() {
        assertEquals(Keys.dish("Гречка с котлетой"), Keys.dish("  гречка   С котлетой "))
    }

    @Test
    fun descriptionNormalized() {
        assertEquals(Keys.description("Борщ  и хлеб", "Russian"), Keys.description("борщ и хлеб ", "Russian"))
        assertNotEquals(Keys.description("борщ и хлеб", "Russian"), Keys.description("борщ и хлеб", "English"))
    }

    @Test
    fun photoDependsOnBytesAndHint() {
        val a = byteArrayOf(1, 2, 3)
        assertEquals(Keys.photo(a, null, "English"), Keys.photo(byteArrayOf(1, 2, 3), "", "English"))
        assertNotEquals(Keys.photo(a, null, "English"), Keys.photo(byteArrayOf(1, 2, 4), null, "English"))
        assertNotEquals(Keys.photo(a, null, "English"), Keys.photo(a, "300 g", "English"))
    }

    @Test
    fun photoAndTextNeverCollide() {
        assertNotEquals(Keys.photo(ByteArray(0), "x", "English"), Keys.description("x", "English"))
    }
}
