package com.example.hevycompanion

import com.example.hevycompanion.data.PushErrorCategory
import com.example.hevycompanion.data.RefreshErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the display-decision logic for the home-screen widget. Two axes
 * are tested:
 *
 *  1. Refresh-side state machine: empty / past-success / fresh-error /
 *     stale-error / cold-start / needs-sign-in.
 *  2. Per-category mapping from [RefreshErrorCategory] / [PushErrorCategory]
 *     to user-facing strings, per the agreed taxonomy.
 *
 * The push side gets its own row of cases since push errors are independent
 * of refresh errors — a successful refresh + a failed push must still show
 * the push problem.
 */
class WidgetStatusFormatterTest {

    private val fmt: (Long) -> String = { ts -> "T$ts" }

    /** Convenience: most tests don't care about push state. */
    private fun format(
        refreshedAt: Long = 0,
        errorAt: Long = 0,
        errorCategory: String? = null,
        errorDetail: String? = null,
        pushedAt: Long = 0,
        pushErrorAt: Long = 0,
        pushErrorCategory: String? = null,
        isLoggedIn: Boolean = true,
    ) = WidgetStatusFormatter.format(
        refreshedAt = refreshedAt,
        errorAt = errorAt,
        errorCategory = errorCategory,
        errorDetail = errorDetail,
        pushedAt = pushedAt,
        pushErrorAt = pushErrorAt,
        pushErrorCategory = pushErrorCategory,
        isLoggedIn = isLoggedIn,
        formatTime = fmt,
    )

    // ── Refresh line: empty / success / cold-start ──────────────────────────

    @Test
    fun `never refreshed and logged in shows --`() {
        val d = format(isLoggedIn = true)
        assertEquals("Refreshed: --", d.refreshLine)
        assertEquals("Pushed: --", d.pushLine)
        assertNull(d.errorLine)
        assertFalse(d.isError)
        assertFalse(d.needsSignIn)
        assertFalse(d.isPushError)
    }

    @Test
    fun `never refreshed and logged out shows tap-to-sign-in prompt`() {
        // Cold-start empty state. Quiet, not red — separate axis from the
        // error states. The widget body tap already routes to login.
        val d = format(isLoggedIn = false)
        assertEquals("Tap to sign in", d.refreshLine)
        assertFalse(d.isError)
        assertFalse(d.needsSignIn)
    }

    @Test
    fun `past success shows timestamp`() {
        val d = format(refreshedAt = 1000)
        assertEquals("Refreshed: T1000", d.refreshLine)
        assertNull(d.errorLine)
        assertFalse(d.isError)
    }

    @Test
    fun `stale error older than last success is ignored`() {
        val d = format(
            refreshedAt = 2000,
            errorAt = 1000,
            errorCategory = RefreshErrorCategory.SERVER_DOWN,
            errorDetail = "HTTP 500",
        )
        assertFalse(d.isError)
        assertEquals("Refreshed: T2000", d.refreshLine)
        assertNull(d.errorLine)
    }

    @Test
    fun `error at same timestamp as last success is treated as not-error`() {
        // Tie-break on equal timestamps: success wins.
        val d = format(
            refreshedAt = 5000,
            errorAt = 5000,
            errorCategory = RefreshErrorCategory.SERVER_DOWN,
            errorDetail = "HTTP 500",
        )
        assertFalse(d.isError)
        assertEquals("Refreshed: T5000", d.refreshLine)
    }

    @Test
    fun `null errorCategory ignored even if errorAt is newer`() {
        // Defensive: if errorAt is set but the category isn't, fall back to
        // success display instead of a blank red state.
        val d = format(refreshedAt = 1000, errorAt = 2000, errorCategory = null)
        assertFalse(d.isError)
        assertEquals("Refreshed: T1000", d.refreshLine)
    }

    // ── Refresh-error category mapping (the agreed taxonomy) ────────────────

    @Test
    fun `SERVER_DOWN maps to Hevy is down`() {
        val d = format(
            refreshedAt = 1000,
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.SERVER_DOWN,
            errorDetail = "HTTP 502",
        )
        assertTrue(d.isError)
        assertEquals("Hevy is down · T2000", d.refreshLine)
        assertFalse("server-down does not require sign-in", d.needsSignIn)
        assertEquals("HTTP 502 (last OK: T1000)", d.errorLine)
    }

    @Test
    fun `AUTH_EXPIRED maps to Sign in again and flips needsSignIn`() {
        // After a 401 the interactor wipes credentials → isLoggedIn = false.
        val d = format(
            refreshedAt = 1000,
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.AUTH_EXPIRED,
            errorDetail = "HTTP 401",
            isLoggedIn = false,
        )
        assertTrue(d.isError)
        assertTrue(d.needsSignIn)
        assertEquals("Sign in again · T2000", d.refreshLine)
    }

    @Test
    fun `FORBIDDEN maps to Account blocked`() {
        val d = format(
            refreshedAt = 1000,
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.FORBIDDEN,
            errorDetail = "HTTP 403",
        )
        assertTrue(d.isError)
        assertEquals("Account blocked · T2000", d.refreshLine)
        // Credentials may still be on file; the user doesn't need to sign in,
        // they need to contact Hevy. So no glyph-swap on the icon.
        assertFalse(d.needsSignIn)
    }

    @Test
    fun `OTHER_HTTP includes the raw detail in the user-facing string`() {
        // 4XX outside 401/403/429 → developer signal; surfacing the code helps
        // the user report the issue.
        val d = format(
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.OTHER_HTTP,
            errorDetail = "HTTP 422",
        )
        assertTrue(d.isError)
        assertEquals("Refresh rejected (HTTP 422) · T2000", d.refreshLine)
    }

    @Test
    fun `CONTRACT maps to Unexpected response`() {
        val d = format(
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.CONTRACT,
            errorDetail = "200 with missing fields",
        )
        assertTrue(d.isError)
        assertEquals("Unexpected response · T2000", d.refreshLine)
    }

    @Test
    fun `NETWORK maps to Can't reach Hevy`() {
        val d = format(
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.NETWORK,
            errorDetail = "Unable to resolve host api.hevyapp.com",
        )
        assertTrue(d.isError)
        assertEquals("Can't reach Hevy · T2000", d.refreshLine)
        // The raw exception text stays available on the second line as a
        // debugging surface.
        assertEquals("Unable to resolve host api.hevyapp.com", d.errorLine)
    }

    @Test
    fun `PERSISTENCE maps to Can't save tokens`() {
        val d = format(
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.PERSISTENCE,
            errorDetail = "keystore unavailable",
        )
        assertTrue(d.isError)
        assertEquals("Can't save tokens · T2000", d.refreshLine)
    }

    @Test
    fun `unknown refresh category falls back to generic Refresh failed`() {
        // Forward-compat: if a future build writes a category we don't know
        // about, render a sensible default instead of a blank line.
        val d = format(
            errorAt = 2000,
            errorCategory = "FUTURE_CATEGORY",
            errorDetail = "whatever",
        )
        assertTrue(d.isError)
        assertEquals("Refresh failed · T2000", d.refreshLine)
    }

    @Test
    fun `error with no prior successful refresh omits last-ok suffix`() {
        val d = format(
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.NETWORK,
            errorDetail = "Unable to resolve host",
        )
        assertTrue(d.isError)
        // No "(last OK: …)" — never been successful.
        assertEquals("Unable to resolve host", d.errorLine)
    }

    @Test
    fun `error detail that matches user-facing string collapses errorLine to last-ok suffix`() {
        // If the detail is the same as the friendly text (or null), no point
        // duplicating it on the second line.
        val d = format(
            refreshedAt = 1000,
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.SERVER_DOWN,
            errorDetail = "Hevy is down",
        )
        assertEquals("(last OK: T1000)", d.errorLine)
    }

    // ── Push line ───────────────────────────────────────────────────────────

    @Test
    fun `successful push shows timestamp on push line`() {
        val d = format(refreshedAt = 1000, pushedAt = 1500)
        assertEquals("Pushed: T1500", d.pushLine)
        assertFalse(d.isPushError)
    }

    @Test
    fun `NO_WATCH push error replaces the push line with watch-unreachable`() {
        val d = format(
            refreshedAt = 1000,
            pushedAt = 1500,
            pushErrorAt = 2000,
            pushErrorCategory = PushErrorCategory.NO_WATCH,
        )
        assertTrue(d.isPushError)
        assertEquals("Watch unreachable · T2000 (last OK: T1500)", d.pushLine)
        // Push errors must NOT spill into refresh state.
        assertFalse(d.isError)
        assertEquals("Refreshed: T1000", d.refreshLine)
    }

    @Test
    fun `FAILED push error also surfaces as watch-unreachable`() {
        val d = format(
            pushErrorAt = 2000,
            pushErrorCategory = PushErrorCategory.FAILED,
        )
        assertTrue(d.isPushError)
        assertEquals("Watch unreachable · T2000", d.pushLine)
    }

    @Test
    fun `push error older than last push success is ignored`() {
        val d = format(
            pushedAt = 3000,
            pushErrorAt = 1000,
            pushErrorCategory = PushErrorCategory.NO_WATCH,
        )
        assertFalse(d.isPushError)
        assertEquals("Pushed: T3000", d.pushLine)
    }

    // ── Refresh + push coexist independently ─────────────────────────────────

    @Test
    fun `refresh error and push error coexist on independent lines`() {
        val d = format(
            errorAt = 2000,
            errorCategory = RefreshErrorCategory.NETWORK,
            errorDetail = "DNS failure",
            pushErrorAt = 2500,
            pushErrorCategory = PushErrorCategory.NO_WATCH,
        )
        assertTrue(d.isError)
        assertTrue(d.isPushError)
        assertEquals("Can't reach Hevy · T2000", d.refreshLine)
        assertEquals("Watch unreachable · T2500", d.pushLine)
    }

    @Test
    fun `refresh succeeded but push failed — refresh line stays green`() {
        val d = format(
            refreshedAt = 2000,
            pushErrorAt = 2500,
            pushErrorCategory = PushErrorCategory.NO_WATCH,
        )
        assertFalse(d.isError)
        assertTrue(d.isPushError)
        assertEquals("Refreshed: T2000", d.refreshLine)
        assertEquals("Watch unreachable · T2500", d.pushLine)
    }
}
