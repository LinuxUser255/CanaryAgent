package com.canaryagent.markers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerGeneratorTest {
    @Test
    fun `tokens are prefixed lowercase base36 of the requested length`() {
        repeat(200) {
            val t = MarkerGenerator.next()
            assertEquals(12, t.length)
            assertTrue(t.startsWith("cnry"))
            assertTrue(MarkerGenerator.isWellFormed(t), t)
        }
    }

    @Test
    fun `tokens are unique in practice`() {
        val set = (1..2000).map { MarkerGenerator.next() }.toSet()
        assertEquals(2000, set.size)
    }

    @Test
    fun `nextUnique avoids collisions`() {
        var first: String? = null
        val t = MarkerGenerator.nextUnique { candidate -> if (first == null) { first = candidate; true } else false }
        assertTrue(t != first)
    }

    @Test
    fun `well-formedness rejects punctuation, uppercase and short tokens`() {
        assertFalse(MarkerGenerator.isWellFormed("cnry-7f3a9k2m"))
        assertFalse(MarkerGenerator.isWellFormed("CNRY7F3A9K2M"))
        assertFalse(MarkerGenerator.isWellFormed("cnry7f3"))
        assertFalse(MarkerGenerator.isWellFormed("hello7f3a9k2m"))
    }
}
