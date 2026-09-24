package com.example.hevywatch

import com.example.hevycore.wear.WearMessagePaths
import com.example.hevywatch.wear.WatchSnapshot
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the watch's cross-app message contract with the companion phone.
 * The flow is fragile (Wearable MessageAPI delivers raw bytes; one fumbled
 * JSON decode hands the user a logged-out watch), so every path the watch
 * accepts gets a positive AND a negative case.
 */
class PhoneAuthDispatcherTest {

    private class FakeHandlers : PhoneAuthDispatcher.Handlers {
        var lastTokens: Triple<String, String, String>? = null
        var requestAuthCalls = 0
        var loggedIn = false
        var lastSeed: WatchSnapshot? = null
        var lastApiVersion: Pair<String, String>? = null
        var lastResumeWorkoutId: String? = null
        var trusted: String? = null
        val errors = mutableListOf<String>()

        override fun onTokensReceived(accessToken: String, refreshToken: String, expiresAt: String) {
            lastTokens = Triple(accessToken, refreshToken, expiresAt)
        }
        override fun requestAuthFromPhone() { requestAuthCalls++ }
        override fun isLoggedIn(): Boolean = loggedIn
        override fun applySeed(snapshot: WatchSnapshot) { lastSeed = snapshot }
        override fun setApiVersion(versionName: String, versionCode: String) {
            lastApiVersion = versionName to versionCode
        }
        override fun requestResumeWorkout(workoutId: String) { lastResumeWorkoutId = workoutId }
        override fun trustedNodeId(): String? = trusted
        override fun logError(tag: String, msg: String) { errors += msg }
    }

    private val gson = Gson()

    // ── /auth_tokens ───────────────────────────────────────────────────────

    @Test fun `valid auth tokens payload is forwarded to onTokensReceived`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)
        val payload = """{"access_token":"AT","refresh_token":"RT","expires_at":"2030-01-01T00:00:00Z"}"""

        dispatcher.dispatch(WearMessagePaths.AUTH_TOKENS, payload.toByteArray())

        assertEquals(Triple("AT", "RT", "2030-01-01T00:00:00Z"), handlers.lastTokens)
        assertTrue("no errors expected", handlers.errors.isEmpty())
    }

    @Test fun `auth tokens with empty access_token is rejected`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)
        val payload = """{"access_token":"","refresh_token":"RT","expires_at":"2030"}"""

        dispatcher.dispatch(WearMessagePaths.AUTH_TOKENS, payload.toByteArray())

        assertNull("must NOT update tokens with blank access", handlers.lastTokens)
        assertTrue(handlers.errors.any { it.contains("empty fields") })
    }

    @Test fun `auth tokens malformed JSON is swallowed, not crashed`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)
        val payload = """not json at all""".toByteArray()

        dispatcher.dispatch(WearMessagePaths.AUTH_TOKENS, payload)

        assertNull(handlers.lastTokens)
        assertTrue(handlers.errors.any { it.contains("Failed to parse") })
    }

    // ── /on_phone_authenticated ───────────────────────────────────────────

    @Test fun `on phone authenticated requests auth when watch not logged in`() {
        val handlers = FakeHandlers().also { it.loggedIn = false }
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(WearMessagePaths.ON_PHONE_AUTHENTICATED, ByteArray(0))

        assertEquals(1, handlers.requestAuthCalls)
    }

    @Test fun `on phone authenticated does NOT request auth if already logged in`() {
        val handlers = FakeHandlers().also { it.loggedIn = true }
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(WearMessagePaths.ON_PHONE_AUTHENTICATED, ByteArray(0))

        assertEquals(0, handlers.requestAuthCalls)
    }

    // ── /watch_seed ────────────────────────────────────────────────────────

    @Test fun `watch seed empty bytes is no-op without error`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(WearMessagePaths.WATCH_SEED, ByteArray(0))

        assertNull(handlers.lastSeed)
        assertTrue("empty seed must not log error", handlers.errors.isEmpty())
    }

    @Test fun `watch seed with current version is applied`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)
        val snapshot = WatchSnapshot(
            folders = emptyList(),
            routines = emptyList(),
            routineLastWorkoutAt = emptyMap(),
            routineWorkoutIds = emptyMap()
        )
        val bytes = gson.toJson(snapshot).toByteArray()

        dispatcher.dispatch(WearMessagePaths.WATCH_SEED, bytes)

        assertEquals(snapshot.snapshotVersion, handlers.lastSeed?.snapshotVersion)
    }

    @Test fun `watch seed with mismatched version is ignored`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)
        // Build JSON manually so we can inject a future snapshot_version
        val futureJson = """{"folders":[],"routines":[],"routineLastWorkoutAt":{},"routineWorkoutIds":{},"snapshotVersion":99,"createdAtMs":0}"""

        dispatcher.dispatch(WearMessagePaths.WATCH_SEED, futureJson.toByteArray())

        assertNull(handlers.lastSeed)
        assertTrue(handlers.errors.any { it.contains("version 99") })
    }

    // ── /api_version ───────────────────────────────────────────────────────

    @Test fun `valid api_version payload is forwarded to setApiVersion`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)
        val payload = """{"version_name":"3.0.13","version_code":"2033100"}"""

        dispatcher.dispatch(WearMessagePaths.API_VERSION, payload.toByteArray())

        assertEquals("3.0.13" to "2033100", handlers.lastApiVersion)
        assertTrue("no errors expected", handlers.errors.isEmpty())
    }

    @Test fun `api_version with empty version_name is rejected`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)
        val payload = """{"version_name":"","version_code":"2033100"}"""

        dispatcher.dispatch(WearMessagePaths.API_VERSION, payload.toByteArray())

        assertNull(handlers.lastApiVersion)
        assertTrue(handlers.errors.any { it.contains("empty fields") })
    }

    @Test fun `api_version empty bytes is no-op without error`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(WearMessagePaths.API_VERSION, ByteArray(0))

        assertNull(handlers.lastApiVersion)
        assertTrue("empty payload must not log error", handlers.errors.isEmpty())
    }

    @Test fun `api_version malformed JSON is swallowed, not crashed`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(WearMessagePaths.API_VERSION, "not json".toByteArray())

        assertNull(handlers.lastApiVersion)
        assertTrue(handlers.errors.any { it.contains("Failed to parse") })
    }

    // ── /resume_workout ──────────────────────────────────────────────────────

    @Test fun `valid resume_workout payload is forwarded to requestResumeWorkout`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)
        val payload = """{"workout_id":"abc-123_DEF"}"""

        dispatcher.dispatch(WearMessagePaths.RESUME_WORKOUT, payload.toByteArray(), "node-1")

        assertEquals("abc-123_DEF", handlers.lastResumeWorkoutId)
        assertTrue("no errors expected", handlers.errors.isEmpty())
    }

    @Test fun `resume_workout with an unsafe workout_id is rejected`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)
        // A slash would let a forged id smuggle extra nav path segments.
        val payload = """{"workout_id":"../../log_workout"}"""

        dispatcher.dispatch(WearMessagePaths.RESUME_WORKOUT, payload.toByteArray(), "node-1")

        assertNull(handlers.lastResumeWorkoutId)
        assertTrue(handlers.errors.any { it.contains("bad workout_id") })
    }

    @Test fun `resume_workout from an untrusted node is rejected`() {
        val handlers = FakeHandlers().also { it.trusted = "good-node" }
        val dispatcher = PhoneAuthDispatcher(handlers)
        val payload = """{"workout_id":"abc"}"""

        dispatcher.dispatch(WearMessagePaths.RESUME_WORKOUT, payload.toByteArray(), "evil-node")

        assertNull(handlers.lastResumeWorkoutId)
        assertTrue(handlers.errors.any { it.contains("untrusted node") })
    }

    @Test fun `resume_workout malformed JSON is swallowed, not crashed`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch(WearMessagePaths.RESUME_WORKOUT, "not json".toByteArray(), "node-1")

        assertNull(handlers.lastResumeWorkoutId)
        assertTrue(handlers.errors.any { it.contains("Failed to parse") })
    }

    // ── unknown path ───────────────────────────────────────────────────────

    @Test fun `unknown path is silently ignored`() {
        val handlers = FakeHandlers()
        val dispatcher = PhoneAuthDispatcher(handlers)

        dispatcher.dispatch("/no_such_path", "anything".toByteArray())

        assertNull(handlers.lastTokens)
        assertNull(handlers.lastSeed)
        assertEquals(0, handlers.requestAuthCalls)
        assertTrue("unknown path should NOT log an error", handlers.errors.isEmpty())
    }
}
