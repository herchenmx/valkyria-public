package com.example.hevycompanion

import androidx.test.core.app.ApplicationProvider
import com.example.hevycompanion.data.AuthPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the prefs-level contract behind the in-app TOFU approval banner.
 *
 * The banner exists because POST_NOTIFICATIONS can be denied on Android 13+,
 * in which case the approval notification is silently dropped and the watch
 * would otherwise sit tokenless with no visible cause. These cases cover the
 * state transitions the banner drives; the ViewModel wiring on top is a thin
 * pass-through to these same calls.
 */
@RunWith(RobolectricTestRunner::class)
class PendingWatchApprovalTest {

    private lateinit var prefs: AuthPrefs

    @Before
    fun setUp() {
        prefs = AuthPrefs(ApplicationProvider.getApplicationContext())
        prefs.clear()
    }

    @Test fun `approving a pending node promotes it and clears pending`() {
        prefs.pendingWatchNodeId = "watch-A"

        prefs.addTrustedWatch("watch-A")

        assertEquals(setOf("watch-A"), prefs.trustedWatchNodeIds)
        assertNull(prefs.pendingWatchNodeId)
    }

    @Test fun `rejecting clears pending without pinning`() {
        prefs.pendingWatchNodeId = "watch-A"

        prefs.pendingWatchNodeId = null

        assertNull(prefs.pendingWatchNodeId)
        assertEquals("reject must never trust", emptySet<String>(), prefs.trustedWatchNodeIds)
    }

    @Test fun `a second request overwrites the pending slot`() {
        // Only one watch can await approval at a time — the later request
        // wins so the banner never shows a stale node id.
        prefs.pendingWatchNodeId = "watch-A"
        prefs.pendingWatchNodeId = "watch-B"

        assertEquals("watch-B", prefs.pendingWatchNodeId)
    }

    @Test fun `an existing pin survives an app update`() {
        // The scenario that made the first post-update launch show no prompt:
        // SharedPreferences persist across an install -r, so a watch pinned
        // under the old auto-pin scheme stays pinned and never re-asks.
        prefs.addTrustedWatch("watch-A")

        val reopened = AuthPrefs(ApplicationProvider.getApplicationContext())

        assertEquals(setOf("watch-A"), reopened.trustedWatchNodeIds)
        assertNull("no approval should be pending", reopened.pendingWatchNodeId)
    }

    @Test fun `logout clears both slots so the next pair re-approves`() {
        prefs.addTrustedWatch("watch-A")
        prefs.pendingWatchNodeId = "watch-B"

        prefs.clear()

        assertEquals(emptySet<String>(), prefs.trustedWatchNodeIds)
        assertNull(prefs.pendingWatchNodeId)
    }
}
