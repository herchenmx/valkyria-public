package com.example.hevycompanion

import androidx.test.core.app.ApplicationProvider
import com.example.hevycompanion.data.AuthPrefs
import com.example.hevycompanion.data.PushErrorCategory
import com.example.hevycompanion.data.RefreshErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks in the atomicity invariants of AuthPrefs that the widget, worker, and
 * in-app refresh paths all depend on. The widget status formatter is tested
 * in isolation; this suite covers the preference-layer contract.
 */
@RunWith(RobolectricTestRunner::class)
class AuthPrefsTest {

    private lateinit var prefs: AuthPrefs

    @Before
    fun setUp() {
        prefs = AuthPrefs(ApplicationProvider.getApplicationContext())
        prefs.clear()
    }

    @Test
    fun `fresh state is empty and not logged in`() {
        assertNull(prefs.accessToken)
        assertNull(prefs.refreshToken)
        assertNull(prefs.expiresAt)
        assertFalse(prefs.isLoggedIn)
        assertEquals(0L, prefs.lastTokenRefreshedAt)
        assertEquals(0L, prefs.lastTokenPushedAt)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
        assertNull(prefs.lastTokenRefreshError)
        assertNull(prefs.lastTokenRefreshErrorCategory)
        assertEquals(0L, prefs.lastTokenPushErrorAt)
        assertNull(prefs.lastTokenPushError)
        assertNull(prefs.lastTokenPushErrorCategory)
        assertEquals(
            "trusted watch allowlist starts empty for TOFU",
            emptySet<String>(), prefs.trustedWatchNodeIds
        )
    }

    @Test
    fun `trustedWatchNodeIds roundtrips and is cleared by clear()`() {
        prefs.addTrustedWatch("node-abc123")
        assertEquals(setOf("node-abc123"), prefs.trustedWatchNodeIds)

        prefs.clear()
        assertEquals(emptySet<String>(), prefs.trustedWatchNodeIds)
    }

    @Test
    fun `two watches can be trusted at once and neither evicts the other`() {
        // The whole point of the set: ray and shiner are used interchangeably,
        // so trusting the second must not unpin the first (and must not
        // require a logout to swap between them).
        prefs.addTrustedWatch("ray-node")
        prefs.addTrustedWatch("shiner-node")

        assertEquals(setOf("ray-node", "shiner-node"), prefs.trustedWatchNodeIds)
        assertTrue(prefs.isTrustedWatch("ray-node"))
        assertTrue(prefs.isTrustedWatch("shiner-node"))
    }

    @Test
    fun `addTrustedWatch is idempotent`() {
        prefs.addTrustedWatch("ray-node")
        prefs.addTrustedWatch("ray-node")
        assertEquals(setOf("ray-node"), prefs.trustedWatchNodeIds)
    }

    @Test
    fun `isTrustedWatch rejects unknown and null nodes`() {
        prefs.addTrustedWatch("ray-node")
        assertFalse(prefs.isTrustedWatch("evil-node"))
        assertFalse("null is never trusted", prefs.isTrustedWatch(null))
    }

    @Test
    fun `legacy single pin migrates into the allowlist on read`() {
        // Installs from before the set existed have `trusted_watch_node_id`.
        // Reading must adopt it rather than silently dropping the pairing.
        prefs.clear()
        prefs.seedLegacyPinForTest("legacy-node")

        assertEquals(setOf("legacy-node"), prefs.trustedWatchNodeIds)
        assertTrue(prefs.isTrustedWatch("legacy-node"))
    }

    @Test
    fun `adding to a migrated allowlist keeps the legacy node`() {
        prefs.clear()
        prefs.seedLegacyPinForTest("legacy-node")
        prefs.addTrustedWatch("second-node")

        assertEquals(setOf("legacy-node", "second-node"), prefs.trustedWatchNodeIds)
    }

    @Test
    fun `addTrustedWatches seeds several nodes at once`() {
        prefs.addTrustedWatches(listOf("ray-node", "shiner-node"))
        assertEquals(setOf("ray-node", "shiner-node"), prefs.trustedWatchNodeIds)
    }

    @Test
    fun `seeded flag roundtrips and is cleared by clear()`() {
        assertFalse(prefs.trustedWatchesSeeded)
        prefs.trustedWatchesSeeded = true
        assertTrue(prefs.trustedWatchesSeeded)
        prefs.clear()
        assertFalse(prefs.trustedWatchesSeeded)
    }

    @Test
    fun `save persists all three token fields`() {
        prefs.save("at", "rt", "2030-01-01T00:00:00Z")
        assertEquals("at", prefs.accessToken)
        assertEquals("rt", prefs.refreshToken)
        assertEquals("2030-01-01T00:00:00Z", prefs.expiresAt)
        assertTrue(prefs.isLoggedIn)
    }

    @Test
    fun `isLoggedIn requires both access and refresh tokens`() {
        prefs.save("at", "rt", "exp")
        assertTrue(prefs.isLoggedIn)
        prefs.clear()
        assertFalse(prefs.isLoggedIn)
    }

    @Test
    fun `clear wipes every field including timestamps and error state`() {
        prefs.save("at", "rt", "exp")
        prefs.lastTokenPushedAt = 123L
        prefs.markRefreshError(RefreshErrorCategory.NETWORK, "boom", 456L)
        prefs.markPushError(PushErrorCategory.NO_WATCH, "no watch", 789L)
        prefs.clear()

        assertFalse(prefs.isLoggedIn)
        assertEquals(0L, prefs.lastTokenRefreshedAt)
        assertEquals(0L, prefs.lastTokenPushedAt)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
        assertNull(prefs.lastTokenRefreshError)
        assertNull(prefs.lastTokenRefreshErrorCategory)
        assertEquals(0L, prefs.lastTokenPushErrorAt)
        assertNull(prefs.lastTokenPushError)
        assertNull(prefs.lastTokenPushErrorCategory)
    }

    @Test
    fun `markRefreshSuccess sets timestamp and clears prior error atomically`() {
        prefs.markRefreshError(RefreshErrorCategory.AUTH_EXPIRED, "HTTP 401", 1_000L)
        assertEquals(1_000L, prefs.lastTokenRefreshErrorAt)
        assertEquals("HTTP 401", prefs.lastTokenRefreshError)
        assertEquals(RefreshErrorCategory.AUTH_EXPIRED, prefs.lastTokenRefreshErrorCategory)

        prefs.markRefreshSuccess(2_000L)

        assertEquals(2_000L, prefs.lastTokenRefreshedAt)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
        assertNull(prefs.lastTokenRefreshError)
        assertNull("category must be cleared on success", prefs.lastTokenRefreshErrorCategory)
    }

    @Test
    fun `markRefreshError preserves the last-success timestamp`() {
        // The widget surfaces "last OK: …" alongside the error. If
        // markRefreshError ever clobbers the success timestamp, that bearing
        // is lost.
        prefs.markRefreshSuccess(5_000L)
        prefs.markRefreshError(RefreshErrorCategory.NETWORK, "Network unreachable", 6_000L)

        assertEquals(5_000L, prefs.lastTokenRefreshedAt)
        assertEquals(6_000L, prefs.lastTokenRefreshErrorAt)
        assertEquals("Network unreachable", prefs.lastTokenRefreshError)
        assertEquals(RefreshErrorCategory.NETWORK, prefs.lastTokenRefreshErrorCategory)
    }

    @Test
    fun `markPushSuccess clears prior push error atomically`() {
        prefs.markPushError(PushErrorCategory.NO_WATCH, "no watch", 1_000L)
        assertEquals(PushErrorCategory.NO_WATCH, prefs.lastTokenPushErrorCategory)

        prefs.markPushSuccess(2_000L)

        assertEquals(2_000L, prefs.lastTokenPushedAt)
        assertEquals(0L, prefs.lastTokenPushErrorAt)
        assertNull(prefs.lastTokenPushError)
        assertNull(prefs.lastTokenPushErrorCategory)
    }

    @Test
    fun `markPushError preserves last-success push timestamp`() {
        prefs.markPushSuccess(5_000L)
        prefs.markPushError(PushErrorCategory.FAILED, "timeout", 6_000L)

        assertEquals(5_000L, prefs.lastTokenPushedAt)
        assertEquals(6_000L, prefs.lastTokenPushErrorAt)
        assertEquals("timeout", prefs.lastTokenPushError)
        assertEquals(PushErrorCategory.FAILED, prefs.lastTokenPushErrorCategory)
    }

    @Test
    fun `refresh and push error stamps are independent`() {
        // Critical invariant for the widget: a watch-push failure must not
        // wipe the refresh-success stamp or vice versa.
        prefs.markRefreshSuccess(1_000L)
        prefs.markPushError(PushErrorCategory.NO_WATCH, "no watch", 2_000L)

        assertEquals(1_000L, prefs.lastTokenRefreshedAt)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
        assertNull(prefs.lastTokenRefreshErrorCategory)
        assertEquals(PushErrorCategory.NO_WATCH, prefs.lastTokenPushErrorCategory)
    }

    @Test
    fun `lastTokenPushedAt is writable and independent of refresh state`() {
        prefs.markRefreshSuccess(1_000L)
        prefs.lastTokenPushedAt = 2_000L
        assertEquals(1_000L, prefs.lastTokenRefreshedAt)
        assertEquals(2_000L, prefs.lastTokenPushedAt)
    }

    // ── TOFU pending-approval slot ─────────────────────────────────────────

    @Test
    fun `pendingWatchNodeId starts null and roundtrips`() {
        assertNull(prefs.pendingWatchNodeId)
        prefs.pendingWatchNodeId = "watch-node-1"
        assertEquals("watch-node-1", prefs.pendingWatchNodeId)
        prefs.pendingWatchNodeId = null
        assertNull(prefs.pendingWatchNodeId)
    }

    @Test
    fun `addTrustedWatch promotes pending atomically`() {
        prefs.pendingWatchNodeId = "watch-node-1"
        prefs.addTrustedWatch("watch-node-1")
        assertEquals(setOf("watch-node-1"), prefs.trustedWatchNodeIds)
        assertNull("pending slot must be cleared after promotion", prefs.pendingWatchNodeId)
    }

    @Test
    fun `approving one node leaves a DIFFERENT pending request intact`() {
        // Two watches raced /request_auth; the later one is sitting in the
        // pending slot and the user approves the earlier one from a stale
        // notification. The later watch's request is still legitimate — with
        // a set-based allowlist the user can trust both, so approving one
        // must NOT silently discard the other's pending request. (Under the
        // old single-pin model this cleared unconditionally, which is exactly
        // how the second watch used to get locked out.)
        prefs.pendingWatchNodeId = "watch-node-later"
        prefs.addTrustedWatch("watch-node-earlier")

        assertEquals(setOf("watch-node-earlier"), prefs.trustedWatchNodeIds)
        assertEquals(
            "the other watch's request must survive",
            "watch-node-later", prefs.pendingWatchNodeId
        )
    }

    @Test
    fun `clear() also wipes pending`() {
        prefs.pendingWatchNodeId = "watch-node-1"
        prefs.addTrustedWatch("watch-node-2")
        prefs.clear()
        assertNull(prefs.pendingWatchNodeId)
        assertEquals(emptySet<String>(), prefs.trustedWatchNodeIds)
    }
}
