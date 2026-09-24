package com.example.hevycompanion

import com.example.hevycompanion.wear.ApiSyncStatusFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule this pins: never show a bare date on the "API synced" line when the
 * most recent attempt failed. The regression it guards against is the one that
 * hid a broken auto-sync for months — the line read `lastSyncedAt` alone, which
 * only moves on success, so a stale good date sat there looking healthy while
 * every attempt since had 404'd.
 */
class ApiSyncStatusFormatterTest {

    private val fmt: (Long) -> String = { "T$it" }

    private fun label(synced: Long, attempt: Long, error: String?) =
        ApiSyncStatusFormatter.label(synced, attempt, error, fmt)

    @Test fun `never synced and never attempted reads Never`() {
        assertEquals("Never", label(0L, 0L, null))
    }

    @Test fun `successful sync shows the success timestamp`() {
        assertEquals("T500", label(500L, 500L, null))
    }

    @Test fun `blank error is treated as success, not as a failure`() {
        assertEquals("T500", label(500L, 500L, "   "))
    }

    @Test fun `failure after a prior success does not show a bare date`() {
        val out = label(500L, 900L, "HTTP 404")
        assertTrue(out, out.startsWith("FAILED"))
        assertTrue(out, out.contains("T900"))      // when it last tried
        assertTrue(out, out.contains("last OK T500"))
        assertTrue(out, out.contains("HTTP 404"))
        // The whole point: it must not read as a plain successful sync.
        assertTrue(out, out != "T500")
    }

    @Test fun `failure with no prior success says never synced`() {
        val out = label(0L, 900L, "HTTP 404")
        assertTrue(out, out.startsWith("FAILED"))
        assertTrue(out, out.contains("never synced"))
        assertTrue(out, !out.contains("last OK"))
    }

    @Test fun `long reasons are truncated so the line stays readable`() {
        val out = label(0L, 900L, "x".repeat(400))
        assertTrue(out, out.length < 120)
        assertTrue(out, out.contains("…"))
    }

    @Test fun `a failure with no attempt stamp still reports the failure`() {
        val out = label(0L, 0L, "boom")
        assertTrue(out, out.startsWith("FAILED"))
        assertTrue(out, out.contains("boom"))
    }
}
