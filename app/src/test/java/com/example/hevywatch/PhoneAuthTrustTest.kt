package com.example.hevywatch

import com.example.hevycore.wear.WearMessagePaths
import com.example.hevywatch.wear.WatchSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trust-on-first-use semantics for the phone bridge. The first /auth_tokens
 * after install pins the sender as the trusted phone; later messages from a
 * different sourceNodeId are rejected for sensitive paths so a sideloaded
 * app on the watch (or a second misconfigured phone) can't overwrite stored
 * credentials. Companion to PhoneAuthDispatcherTest's path-shape coverage.
 */
class PhoneAuthTrustTest {

    private class FakeHandlers(initialTrusted: String? = null) : PhoneAuthDispatcher.Handlers {
        var lastTokens: Triple<String, String, String>? = null
        var lastSeed: WatchSnapshot? = null
        var lastApiVersion: Pair<String, String>? = null
        var trusted: String? = initialTrusted
        val errors = mutableListOf<String>()

        override fun onTokensReceived(accessToken: String, refreshToken: String, expiresAt: String) {
            lastTokens = Triple(accessToken, refreshToken, expiresAt)
        }
        override fun requestAuthFromPhone() = Unit
        override fun isLoggedIn(): Boolean = lastTokens != null
        override fun applySeed(snapshot: WatchSnapshot) { lastSeed = snapshot }
        override fun setApiVersion(versionName: String, versionCode: String) {
            lastApiVersion = versionName to versionCode
        }
        override fun trustedNodeId(): String? = trusted
        override fun setTrustedNodeId(nodeId: String) { trusted = nodeId }
        override fun logError(tag: String, msg: String) { errors += msg }
    }

    private val validTokens =
        """{"access_token":"AT","refresh_token":"RT","expires_at":"2030-01-01T00:00:00Z"}"""

    @Test fun `first auth_tokens pins sender as trusted`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(
            WearMessagePaths.AUTH_TOKENS,
            validTokens.toByteArray(),
            sourceNodeId = "phone-A"
        )

        assertEquals(Triple("AT", "RT", "2030-01-01T00:00:00Z"), handlers.lastTokens)
        assertEquals("phone-A", handlers.trusted)
    }

    @Test fun `second auth_tokens from same node is accepted`() {
        val handlers = FakeHandlers(initialTrusted = "phone-A")
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(
            WearMessagePaths.AUTH_TOKENS,
            validTokens.toByteArray(),
            sourceNodeId = "phone-A"
        )

        assertEquals(Triple("AT", "RT", "2030-01-01T00:00:00Z"), handlers.lastTokens)
    }

    @Test fun `auth_tokens from a different node after pinning is rejected`() {
        val handlers = FakeHandlers(initialTrusted = "phone-A")
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(
            WearMessagePaths.AUTH_TOKENS,
            validTokens.toByteArray(),
            sourceNodeId = "phone-B"
        )

        assertNull("must NOT update tokens from untrusted node", handlers.lastTokens)
        assertTrue(handlers.errors.any { it.contains("untrusted node phone-B") })
    }

    @Test fun `watch_seed from untrusted node is rejected`() {
        val handlers = FakeHandlers(initialTrusted = "phone-A")
        val dispatcher = PhoneAuthDispatcher(handlers)
        val snapshot = WatchSnapshot(
            folders = emptyList(),
            routines = emptyList(),
            routineLastWorkoutAt = emptyMap(),
            routineWorkoutIds = emptyMap()
        )
        val bytes = com.example.hevywatch.util.GsonHolder.gson
            .toJson(snapshot).toByteArray()

        dispatcher.dispatch(WearMessagePaths.WATCH_SEED, bytes, sourceNodeId = "phone-B")

        assertNull(handlers.lastSeed)
        assertTrue(handlers.errors.any { it.contains("untrusted") })
    }

    @Test fun `watch_seed from trusted node is applied`() {
        val handlers = FakeHandlers(initialTrusted = "phone-A")
        val dispatcher = PhoneAuthDispatcher(handlers)
        val snapshot = WatchSnapshot(
            folders = emptyList(),
            routines = emptyList(),
            routineLastWorkoutAt = emptyMap(),
            routineWorkoutIds = emptyMap()
        )
        val bytes = com.example.hevywatch.util.GsonHolder.gson
            .toJson(snapshot).toByteArray()

        dispatcher.dispatch(WearMessagePaths.WATCH_SEED, bytes, sourceNodeId = "phone-A")

        assertEquals(snapshot.snapshotVersion, handlers.lastSeed?.snapshotVersion)
    }

    @Test fun `dispatch with null sourceNodeId after pinning is rejected`() {
        // S2: tightening — once a trusted node is pinned, any later message
        // (including one with a null source) must be rejected. Wearable's
        // production code path always populates sourceNodeId, so a null after
        // pinning means the packet bypassed the framework. Accepting it would
        // let a same-package sender forge a token rotation.
        val handlers = FakeHandlers(initialTrusted = "phone-A")
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(WearMessagePaths.AUTH_TOKENS, validTokens.toByteArray())

        assertNull("must NOT update tokens from a null-id source after pinning", handlers.lastTokens)
        assertTrue(handlers.errors.any { it.contains("(null)") })
    }

    @Test fun `api_version from untrusted node is rejected`() {
        val handlers = FakeHandlers(initialTrusted = "phone-A")
        val dispatcher = PhoneAuthDispatcher(handlers)
        val payload = """{"version_name":"3.0.99","version_code":"9999999"}"""

        dispatcher.dispatch(
            WearMessagePaths.API_VERSION,
            payload.toByteArray(),
            sourceNodeId = "phone-B"
        )

        assertNull("must NOT update API version from untrusted node", handlers.lastApiVersion)
        assertTrue(handlers.errors.any { it.contains("untrusted") })
    }

    @Test fun `api_version from trusted node is applied`() {
        val handlers = FakeHandlers(initialTrusted = "phone-A")
        val dispatcher = PhoneAuthDispatcher(handlers)
        val payload = """{"version_name":"3.0.13","version_code":"2033100"}"""

        dispatcher.dispatch(
            WearMessagePaths.API_VERSION,
            payload.toByteArray(),
            sourceNodeId = "phone-A"
        )

        assertEquals("3.0.13" to "2033100", handlers.lastApiVersion)
    }

    @Test fun `dispatch with null sourceNodeId on first install pins it as trusted`() {
        // Edge case: a fresh install before pinning — `trustedNodeId()` returns
        // null. If the framework somehow delivers a packet with a null source
        // id (it shouldn't, but we don't want to brick first-time auth), we
        // accept the tokens but DO NOT pin a null id (setTrustedNodeId is
        // guarded by the non-null check below).
        val handlers = FakeHandlers(initialTrusted = null)
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(WearMessagePaths.AUTH_TOKENS, validTokens.toByteArray())

        assertEquals(Triple("AT", "RT", "2030-01-01T00:00:00Z"), handlers.lastTokens)
        assertNull("must not pin a null nodeId as trusted", handlers.trusted)
    }
}
