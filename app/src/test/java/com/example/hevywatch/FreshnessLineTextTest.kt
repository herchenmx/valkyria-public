package com.example.hevywatch

import com.example.hevywatch.ui.components.DIVERGENCE_TOLERANCE_MS
import com.example.hevywatch.ui.components.freshnessText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the freshness-caption text the way every screen renders it under the
 * Refresh chip. Two behaviours that are load-bearing for the UX:
 *
 * 1. The caption drops the older `"Updated "` prefix and renders the bare
 *    relative time — so a user reading three side-by-side cached screens
 *    can compare their staleness at a glance instead of parsing a full
 *    sentence per chip.
 * 2. Pages that paint from multiple endpoints (Folders Page 0, Routine
 *    List, etc.) get `"various"` whenever those endpoints' stamps disagree
 *    by more than the tolerance — the caption is never falsely confident
 *    that the whole screen is from the same fetch.
 */
class FreshnessLineTextTest {

    private val now = 10L * 24L * 3_600_000L  // arbitrary fixed "now"

    @Test fun `single fresh stamp renders just now`() {
        assertEquals("just now", freshnessText(listOf(now - 10_000L), now))
    }

    @Test fun `single stale stamp renders the relative time`() {
        assertEquals(
            "3 hrs ago",
            freshnessText(listOf(now - 3L * 3_600_000L), now),
        )
    }

    @Test fun `all-zero stamps render the em-dash placeholder`() {
        assertEquals("—", freshnessText(listOf(0L, 0L), now))
    }

    @Test fun `empty list renders the em-dash placeholder`() {
        assertEquals("—", freshnessText(emptyList(), now))
    }

    @Test fun `zero stamps are filtered before the divergence check`() {
        // A single live source alongside an unset (0L) stamp is NOT "various"
        // — the unset one means "never refreshed", not "refreshed long ago".
        assertEquals(
            "just now",
            freshnessText(listOf(0L, now - 5_000L), now),
        )
    }

    @Test fun `multiple stamps within tolerance render the oldest as one time`() {
        // Within DIVERGENCE_TOLERANCE_MS — treat as one refresh, render
        // relative time off the oldest stamp.
        val a = now - 90_000L
        val b = now - 100_000L
        assertEquals("1 min ago", freshnessText(listOf(a, b), now))
    }

    @Test fun `multiple stamps outside tolerance render as various`() {
        // Pick a gap clearly past the tolerance so the test isn't an
        // off-by-one canary on the strict-greater-than check.
        val fresh = now - 5_000L
        val stale = fresh - (DIVERGENCE_TOLERANCE_MS * 2)
        assertEquals("various", freshnessText(listOf(fresh, stale), now))
    }

    @Test fun `three sources go various when any pair diverges`() {
        val a = now - 1_000L
        val b = now - 30_000L
        val c = now - (DIVERGENCE_TOLERANCE_MS + 10_000L)
        assertEquals("various", freshnessText(listOf(a, b, c), now))
    }
}
