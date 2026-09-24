package com.example.hevywatch

import com.example.hevywatch.data.boundedLruMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the RAM-cap behaviour of the exercise-history LRU. */
class BoundedLruTest {

    @Test fun `evicts past the cap`() {
        val lru = boundedLruMap<Int>(3)
        lru["a"] = 1; lru["b"] = 2; lru["c"] = 3; lru["d"] = 4
        assertEquals(3, lru.size)
        assertFalse("oldest entry evicted", lru.containsKey("a"))
        assertTrue(lru.containsKey("d"))
    }

    @Test fun `access refreshes recency so the touched entry survives`() {
        val lru = boundedLruMap<Int>(3)
        lru["a"] = 1; lru["b"] = 2; lru["c"] = 3
        // Touch "a" → it's now most-recently-used; "b" becomes the eldest.
        lru["a"]
        lru["d"] = 4
        assertTrue("touched entry survives", lru.containsKey("a"))
        assertFalse("untouched eldest evicted", lru.containsKey("b"))
    }

    @Test fun `under the cap nothing is evicted`() {
        val lru = boundedLruMap<Int>(40)
        repeat(10) { lru["k$it"] = it }
        assertEquals(10, lru.size)
    }
}
