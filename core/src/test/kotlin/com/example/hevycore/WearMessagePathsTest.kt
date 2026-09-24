package com.example.hevycore

import com.example.hevycore.wear.WearMessagePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire-level strings for the Wearable message paths. If any of these
 * change, the two apps stop understanding each other silently — the framework
 * simply drops unknown paths. This test acts as the "if you change a path,
 * update both apps AND the tests" tripwire.
 */
class WearMessagePathsTest {

    @Test
    fun `each path is a slash-prefixed lowercase token`() {
        val allPaths = listOf(
            WearMessagePaths.REQUEST_AUTH,
            WearMessagePaths.AUTH_TOKENS,
            WearMessagePaths.ON_PHONE_AUTHENTICATED,
            WearMessagePaths.WATCH_SNAPSHOT,
            WearMessagePaths.REQUEST_SEED,
            WearMessagePaths.WATCH_SEED,
            WearMessagePaths.TOKENS_FROM_WATCH,
            WearMessagePaths.API_VERSION,
            WearMessagePaths.RESUME_WORKOUT,
        )
        allPaths.forEach { path ->
            assertTrue("Path $path must start with /", path.startsWith("/"))
            assertTrue(
                "Path $path must be lowercase snake_case",
                path.substring(1).all { it.isLowerCase() || it == '_' }
            )
        }
    }

    @Test
    fun `pinned string values`() {
        // These are the wire-format strings the peer app relies on. Changing
        // any string here is a coordinated wire-format change and requires
        // updating both apps and the ADB-side scripts that speak the protocol.
        assertEquals("/request_auth", WearMessagePaths.REQUEST_AUTH)
        assertEquals("/auth_tokens", WearMessagePaths.AUTH_TOKENS)
        assertEquals("/on_phone_authenticated", WearMessagePaths.ON_PHONE_AUTHENTICATED)
        assertEquals("/watch_snapshot", WearMessagePaths.WATCH_SNAPSHOT)
        assertEquals("/request_seed", WearMessagePaths.REQUEST_SEED)
        assertEquals("/watch_seed", WearMessagePaths.WATCH_SEED)
        assertEquals("/tokens_from_watch", WearMessagePaths.TOKENS_FROM_WATCH)
        assertEquals("/api_version", WearMessagePaths.API_VERSION)
        assertEquals("/resume_workout", WearMessagePaths.RESUME_WORKOUT)
    }
}
