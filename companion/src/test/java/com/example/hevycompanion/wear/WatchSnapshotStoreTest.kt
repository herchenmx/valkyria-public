package com.example.hevycompanion.wear

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the blob round-trip for the watch's cache snapshot. The companion never
 * parses the payload — the watch owns the schema — so the only contract here is
 * "whatever bytes went in come back out, and a corrupt/absent blob degrades to
 * null rather than throwing". A regression here silently returns an empty seed
 * forever and the watch re-fetches its whole cache on every cold start with no
 * diagnostic.
 */
@RunWith(RobolectricTestRunner::class)
class WatchSnapshotStoreTest {

    private lateinit var store: WatchSnapshotStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("watch_snapshot", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = WatchSnapshotStore(ctx)
    }

    @Test fun `fresh store returns null`() {
        assertNull(store.load())
        assertEquals(0L, store.lastSavedAtMs)
    }

    @Test fun `bytes round-trip verbatim`() {
        val payload = """{"folders":[],"routines":[],"snapshotVersion":1}""".toByteArray()
        store.save(payload)
        assertArrayEquals(payload, store.load())
    }

    @Test fun `binary-safe for non-UTF8 bytes`() {
        // Base64 is the storage encoding, so arbitrary byte values must survive.
        val payload = ByteArray(256) { it.toByte() }
        store.save(payload)
        assertArrayEquals(payload, store.load())
    }

    @Test fun `save stamps lastSavedAtMs`() {
        assertEquals(0L, store.lastSavedAtMs)
        store.save("x".toByteArray())
        assertTrue("save must stamp a timestamp", store.lastSavedAtMs > 0L)
    }

    @Test fun `empty payload is ignored and does not clobber a stored snapshot`() {
        val good = "real snapshot".toByteArray()
        store.save(good)
        val stampAfterGood = store.lastSavedAtMs

        store.save(ByteArray(0))

        assertArrayEquals("empty save must not wipe the good snapshot", good, store.load())
        assertEquals("empty save must not restamp", stampAfterGood, store.lastSavedAtMs)
    }

    @Test fun `later save replaces the earlier snapshot`() {
        store.save("first".toByteArray())
        store.save("second".toByteArray())
        assertArrayEquals("second".toByteArray(), store.load())
    }

    @Test fun `corrupt base64 degrades to null rather than throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("watch_snapshot", android.content.Context.MODE_PRIVATE)
            .edit().putString("snapshot_b64", "!!!not base64!!!").commit()

        assertNull(WatchSnapshotStore(ctx).load())
    }
}
