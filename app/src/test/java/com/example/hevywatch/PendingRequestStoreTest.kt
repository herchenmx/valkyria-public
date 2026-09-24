package com.example.hevywatch

import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.store.PendingRequestStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks in the durability contract for pending requests:
 *
 *  1. save() is synchronous (commit, not apply) — readers immediately after
 *     save() must observe the data, because the network send happens right
 *     after.
 *  2. clear() removes every field — no leaked half-state after success.
 *  3. round-trip preserves method/url/body verbatim — recovery flows depend on
 *     reading them back unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class PendingRequestStoreTest {

    private lateinit var store: PendingRequestStore

    @Before
    fun setUp() {
        store = PendingRequestStore(ApplicationProvider.getApplicationContext())
        store.clear()
    }

    @Test
    fun `fresh store has no pending`() {
        assertFalse(store.hasPending())
        assertNull(store.getPendingBody())
        assertNull(store.getPendingMethod())
        assertNull(store.getPendingUrl())
    }

    @Test
    fun `save is durable immediately for the synchronous-read invariant`() {
        // Mirrors the real flow: save → send. If we observe nothing here, the
        // pending body would be lost on a crash between save() and the next
        // write barrier.
        store.save("POST", "v2/workout", mapOf("a" to 1, "b" to "two"))

        assertTrue(store.hasPending())
        assertEquals("POST", store.getPendingMethod())
        assertEquals("v2/workout", store.getPendingUrl())
        assertEquals("""{"a":1,"b":"two"}""", store.getPendingBody())
    }

    @Test
    fun `clear wipes every field`() {
        store.save("PUT", "v1/workouts/123", mapOf("x" to 9))
        assertTrue(store.hasPending())

        store.clear()

        assertFalse(store.hasPending())
        assertNull(store.getPendingBody())
        assertNull(store.getPendingMethod())
        assertNull(store.getPendingUrl())
    }

    @Test
    fun `save overwrites previous pending`() {
        store.save("POST", "v2/workout", mapOf("a" to 1))
        store.save("PUT", "v1/workouts/9", mapOf("b" to 2))

        assertEquals("PUT", store.getPendingMethod())
        assertEquals("v1/workouts/9", store.getPendingUrl())
        assertEquals("""{"b":2}""", store.getPendingBody())
    }

    @Test
    fun `save rejects oversized bodies`() {
        // S5: a runaway state bug must not be able to inflate SharedPreferences
        // by dumping a giant JSON. The cap rejects the write and returns false;
        // any earlier pending entry stays intact.
        store.save("POST", "v2/workout", mapOf("a" to 1))

        val huge = List(PendingRequestStore.MAX_BODY_BYTES / 4 + 100) { "xxxxxxxxx" }
        val accepted = store.save("POST", "v2/workout", mapOf("blob" to huge))

        assertFalse("oversized body must be rejected", accepted)
        assertEquals("""{"a":1}""", store.getPendingBody())
    }

    @Test
    fun `save accepts a body well under the cap`() {
        val mid = List(1_000) { "set-$it" }
        val accepted = store.save("POST", "v2/workout", mapOf("sets" to mid))

        assertTrue(accepted)
        assertEquals("POST", store.getPendingMethod())
    }
}
