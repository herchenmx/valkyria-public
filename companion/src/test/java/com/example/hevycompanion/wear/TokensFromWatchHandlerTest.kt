package com.example.hevycompanion.wear

import androidx.test.core.app.ApplicationProvider
import com.example.hevycompanion.data.AuthPrefs
import com.example.hevycompanion.data.RefreshErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks in the parse-and-store contract for `/tokens_from_watch`: a
 * well-formed payload FROM AN ALREADY-PINNED SENDER must persist all three
 * fields AND clear any prior refresh-error stamp; a malformed payload OR an
 * un-pinned sender must persist nothing.
 *
 * The un-pinned rejection is deliberate: the pin is only set through the
 * explicit user-approval flow on `/request_auth`. Without this, the first
 * Wearable peer to send `/tokens_from_watch` after a fresh install could
 * poison the trusted-node pin with a valid-looking payload and lock out
 * the real watch.
 *
 * The on-the-wire JSON shape is fixed by the watch-side
 * `CompanionTokenSender` — if either side drifts, this test catches it.
 */
@RunWith(RobolectricTestRunner::class)
class TokensFromWatchHandlerTest {

    private lateinit var prefs: AuthPrefs

    @Before
    fun setUp() {
        prefs = AuthPrefs(ApplicationProvider.getApplicationContext())
        prefs.clear()
        // Every parse/store test needs an already-pinned sender — pinning
        // only happens through the approval flow, not through this handler.
        prefs.addTrustedWatch(TRUSTED_NODE)
    }

    @Test
    fun `well-formed payload from trusted node stores all three fields and stamps success`() {
        val json = """{"access_token":"at_new","refresh_token":"rt_new","expires_at":"2030-01-01T00:00:00Z"}"""

        val outcome = TokensFromWatchHandler.handle(
            data = json.toByteArray(),
            prefs = prefs,
            sourceNodeId = TRUSTED_NODE,
            clock = { 1_700_000_000_000L }
        )

        assertEquals(TokensFromWatchHandler.Outcome.Stored, outcome)
        assertEquals("at_new", prefs.accessToken)
        assertEquals("rt_new", prefs.refreshToken)
        assertEquals("2030-01-01T00:00:00Z", prefs.expiresAt)
        assertEquals(1_700_000_000_000L, prefs.lastTokenRefreshedAt)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
        assertNull(prefs.lastTokenRefreshError)
        assertNull(prefs.lastTokenRefreshErrorCategory)
    }

    @Test
    fun `well-formed payload clears prior refresh error atomically`() {
        // The companion's last refresh attempt failed (e.g. 401 because its
        // RT was retired by the very rotation we're about to recover from).
        // The watch's push must clear that error so the widget doesn't keep
        // showing "Sign in again" when we now have valid tokens.
        prefs.save("at_old", "rt_old", "2020-01-01T00:00:00Z")
        prefs.markRefreshError(RefreshErrorCategory.AUTH_EXPIRED, "HTTP 401", 1_000L)
        assertEquals(RefreshErrorCategory.AUTH_EXPIRED, prefs.lastTokenRefreshErrorCategory)

        val json = """{"access_token":"at_new","refresh_token":"rt_new","expires_at":"2030-01-01T00:00:00Z"}"""
        TokensFromWatchHandler.handle(
            json.toByteArray(),
            prefs,
            sourceNodeId = TRUSTED_NODE,
            clock = { 2_000L }
        )

        assertNull(prefs.lastTokenRefreshErrorCategory)
        assertNull(prefs.lastTokenRefreshError)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
        assertEquals(2_000L, prefs.lastTokenRefreshedAt)
    }

    @Test
    fun `empty bytes are rejected with no prefs writes`() {
        prefs.save("at_old", "rt_old", "2020-01-01T00:00:00Z")

        val outcome = TokensFromWatchHandler.handle(
            ByteArray(0), prefs, sourceNodeId = TRUSTED_NODE
        )

        assertEquals(TokensFromWatchHandler.Outcome.Rejected, outcome)
        assertEquals("at_old", prefs.accessToken)
        assertEquals(0L, prefs.lastTokenRefreshedAt)
    }

    @Test
    fun `malformed JSON is rejected`() {
        prefs.save("at_old", "rt_old", "2020-01-01T00:00:00Z")

        val outcome = TokensFromWatchHandler.handle(
            data = "not actually json".toByteArray(),
            prefs = prefs,
            sourceNodeId = TRUSTED_NODE,
        )

        assertEquals(TokensFromWatchHandler.Outcome.Rejected, outcome)
        assertEquals("at_old", prefs.accessToken)
    }

    @Test
    fun `payload with blank access_token is rejected`() {
        // Defensive: partial tokens are worse than none because the
        // companion would mint a half-valid state.
        prefs.save("at_old", "rt_old", "2020-01-01T00:00:00Z")
        val json = """{"access_token":"","refresh_token":"rt_new","expires_at":"2030"}"""

        val outcome = TokensFromWatchHandler.handle(json.toByteArray(), prefs, sourceNodeId = TRUSTED_NODE)

        assertEquals(TokensFromWatchHandler.Outcome.Rejected, outcome)
        assertEquals("at_old", prefs.accessToken)
    }

    @Test
    fun `payload missing fields entirely is rejected`() {
        prefs.save("at_old", "rt_old", "2020-01-01T00:00:00Z")
        // Only access_token; refresh_token and expires_at missing.
        val json = """{"access_token":"at_new"}"""

        val outcome = TokensFromWatchHandler.handle(json.toByteArray(), prefs, sourceNodeId = TRUSTED_NODE)

        assertEquals(TokensFromWatchHandler.Outcome.Rejected, outcome)
        assertEquals("at_old", prefs.accessToken)
    }

    @Test
    fun `payload with whitespace-only fields is rejected`() {
        prefs.save("at_old", "rt_old", "2020-01-01T00:00:00Z")
        val json = """{"access_token":"at_new","refresh_token":"   ","expires_at":"2030"}"""

        val outcome = TokensFromWatchHandler.handle(json.toByteArray(), prefs, sourceNodeId = TRUSTED_NODE)

        assertTrue(outcome is TokensFromWatchHandler.Outcome.Rejected)
        assertEquals("at_old", prefs.accessToken)
    }

    // ── TOFU node-id gating ────────────────────────────────────────────────

    @Test
    fun `un-pinned handler rejects even a well-formed payload`() {
        prefs.trustedWatchNodeIds = emptySet()
        val json = """{"access_token":"at","refresh_token":"rt","expires_at":"2030"}"""

        val outcome = TokensFromWatchHandler.handle(
            data = json.toByteArray(),
            prefs = prefs,
            sourceNodeId = "watch-node-1",
        )

        assertEquals(TokensFromWatchHandler.Outcome.Rejected, outcome)
        assertEquals("un-trusted sender must not be auto-added", emptySet<String>(), prefs.trustedWatchNodeIds)
        assertNull("no tokens must have landed", prefs.accessToken)
    }

    @Test
    fun `same node id after pin is accepted`() {
        val json = """{"access_token":"at","refresh_token":"rt","expires_at":"2030"}"""

        val outcome = TokensFromWatchHandler.handle(
            json.toByteArray(),
            prefs,
            sourceNodeId = TRUSTED_NODE,
        )

        assertEquals(TokensFromWatchHandler.Outcome.Stored, outcome)
        assertEquals("at", prefs.accessToken)
    }

    @Test
    fun `different node id after pin is rejected`() {
        // A second Wearable peer (other phone, sideloaded app on watch) MUST
        // NOT be able to overwrite tokens once a trusted node is pinned.
        prefs.save("at_real", "rt_real", "2030")
        val malicious = """{"access_token":"at_evil","refresh_token":"rt_evil","expires_at":"2030"}"""

        val outcome = TokensFromWatchHandler.handle(
            malicious.toByteArray(),
            prefs,
            sourceNodeId = "watch-node-2-evil",
        )

        assertEquals(TokensFromWatchHandler.Outcome.Rejected, outcome)
        assertEquals("real tokens must be preserved", "at_real", prefs.accessToken)
        assertEquals(setOf(TRUSTED_NODE), prefs.trustedWatchNodeIds)
    }

    @Test
    fun `null source node id after pin is rejected`() {
        // Wearable framework always populates sourceNodeId on real messages
        // — null implies a code path that bypassed the framework.
        val json = """{"access_token":"at","refresh_token":"rt","expires_at":"2030"}"""

        val outcome = TokensFromWatchHandler.handle(
            json.toByteArray(),
            prefs,
            sourceNodeId = null,
        )

        assertEquals(TokensFromWatchHandler.Outcome.Rejected, outcome)
    }

    @Test
    fun `clear() wipes the pin so the next session requires re-approval`() {
        prefs.clear()
        assertEquals(emptySet<String>(), prefs.trustedWatchNodeIds)

        // A subsequent receive is now rejected — a new pair must go through
        // the `/request_auth` approval flow to re-arm the pin.
        val json = """{"access_token":"at","refresh_token":"rt","expires_at":"2030"}"""
        val outcome = TokensFromWatchHandler.handle(
            json.toByteArray(),
            prefs,
            sourceNodeId = "watch-node-2-newdevice",
        )

        assertEquals(TokensFromWatchHandler.Outcome.Rejected, outcome)
        assertEquals(emptySet<String>(), prefs.trustedWatchNodeIds)
    }

    companion object {
        private const val TRUSTED_NODE = "watch-node-1"
    }
}
