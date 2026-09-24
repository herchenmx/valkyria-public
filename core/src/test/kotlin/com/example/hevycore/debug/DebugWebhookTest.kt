package com.example.hevycore.debug

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWebhookTest {

    // ── what gets mirrored ───────────────────────────────────────────────────

    @Test fun `workout writes are watched on every path resume can take`() {
        // private create — both the new-workout POST and the resume POST
        assertTrue(DebugWebhookInterceptor.isWatched("POST", "/v2/workout"))
        // public create and public in-place update (the resume fallback)
        assertTrue(DebugWebhookInterceptor.isWatched("POST", "/v1/workouts"))
        assertTrue(DebugWebhookInterceptor.isWatched("PUT", "/v1/workouts/abc-123"))
        // the second half of the resume POST+DELETE flow
        assertTrue(DebugWebhookInterceptor.isWatched("DELETE", "/workout/abc-123"))
    }

    @Test fun `the private workout GET is mirrored, because resume depends on it`() {
        // If this GET fails, continuingWorkoutDetailV2 is null and the private
        // POST never happens — so the failure is invisible from the POST side.
        assertTrue(DebugWebhookInterceptor.isWatched("GET", "/workout/abc-123"))
    }

    @Test fun `other reads are not mirrored`() {
        // Listing workouts is noise, and its response is large.
        assertFalse(DebugWebhookInterceptor.isWatched("GET", "/v1/workouts"))
        assertFalse(DebugWebhookInterceptor.isWatched("GET", "/v1/routines"))
    }

    @Test fun `unrelated endpoints are not mirrored`() {
        // Notably auth: refresh_token's body carries credentials.
        assertFalse(DebugWebhookInterceptor.isWatched("POST", "/auth/refresh_token"))
        assertFalse(DebugWebhookInterceptor.isWatched("POST", "/v1/routines"))
    }

    @Test fun `a trailing slash does not defeat the match`() {
        assertTrue(DebugWebhookInterceptor.isWatched("POST", "/v2/workout/"))
    }

    // ── what the payload may contain ─────────────────────────────────────────

    @Test fun `payload carries the bodies and the status`() {
        DebugWebhook.configure("https://example.invalid/hook", "watch", "5ce93fe")
        val json = JsonParser.parseString(
            DebugWebhook.buildPayload(
                api = "private",
                method = "POST",
                path = "/v2/workout",
                status = 400,
                durationMs = 812,
                requestBody = """{"workout":{"title":"Legs"}}""",
                responseBody = """{"error":"missing field"}""",
                error = null,
            )
        ).asJsonObject

        assertEquals("watch", json["device"].asString)
        assertEquals("5ce93fe", json["commit"].asString)
        assertEquals("private", json["api"].asString)
        assertEquals(400, json["status"].asInt)
        assertEquals("http-400", json["outcome"].asString)
        assertTrue(json["requestBody"].asString.contains("Legs"))
        assertTrue(json["responseBody"].asString.contains("missing field"))
    }

    /**
     * The invariant that matters most: [DebugWebhook.send] has no parameter
     * through which a header could be passed, so the payload cannot carry the
     * bearer token, X-Api-Key or the public api-key. This pins the field set,
     * so adding a `headers` field to the envelope fails here rather than
     * quietly shipping credentials to a third-party URL.
     */
    @Test fun `payload has no field that could carry a credential`() {
        DebugWebhook.configure("https://example.invalid/hook", "phone", "5ce93fe")
        val json = JsonParser.parseString(
            DebugWebhook.buildPayload(
                "public", "PUT", "/v1/workouts/abc", 200, 5, "{}", "{}", null,
            )
        ).asJsonObject

        assertEquals(
            setOf(
                "device", "commit", "at", "api", "method", "path",
                "status", "outcome", "durationMs", "requestBody", "responseBody",
            ),
            json.keySet(),
        )
    }

    @Test fun `a transport failure is reported as such rather than as a status`() {
        DebugWebhook.configure("https://example.invalid/hook", "watch", "5ce93fe")
        val json = JsonParser.parseString(
            DebugWebhook.buildPayload(
                "private", "POST", "/v2/workout", null, 15_000,
                "{}", null, "java.net.SocketTimeoutException",
            )
        ).asJsonObject

        assertTrue(json["status"].isJsonNull)
        assertEquals("transport-error", json["outcome"].asString)
        assertTrue(json["error"].asString.contains("SocketTimeout"))
    }

    @Test fun `oversized bodies are truncated and say so`() {
        DebugWebhook.configure("https://example.invalid/hook", "watch", "5ce93fe")
        val huge = "x".repeat(DebugWebhook.BODY_LIMIT + 500)
        val json = JsonParser.parseString(
            DebugWebhook.buildPayload("private", "POST", "/v2/workout", 201, 1, huge, null, null)
        ).asJsonObject

        val sent = json["requestBody"].asString
        assertTrue(sent.length < huge.length)
        assertTrue(sent.contains("truncated 500 more chars"))
    }

    @Test fun `a blank url disables it entirely`() {
        DebugWebhook.configure("", "watch", "5ce93fe")
        assertFalse(DebugWebhook.isEnabled)
        // Must be a no-op rather than a throw: the debug aid can never be
        // allowed to fail the workout save it is describing.
        DebugWebhook.send("private", "POST", "/v2/workout", 200, 1, "{}", "{}")
    }

    @Test fun `whitespace is not a url`() {
        DebugWebhook.configure("   ", "watch", "5ce93fe")
        assertFalse(DebugWebhook.isEnabled)
    }

    // ── the expiry ───────────────────────────────────────────────────────────

    @Test fun `mirroring stops once the build has aged out`() {
        DebugWebhook.configure(
            "https://example.invalid/hook", "watch", "5ce93fe",
            expiresAtMillis = System.currentTimeMillis() - 1,
        )
        assertFalse(DebugWebhook.isEnabled)
        // And send() must respect it, not merely isEnabled — the expiry is
        // worthless if the caller is the only thing checking it.
        DebugWebhook.send("private", "POST", "/v2/workout", 200, 1, "{}", "{}")
    }

    @Test fun `a build inside its window still mirrors`() {
        DebugWebhook.configure(
            "https://example.invalid/hook", "watch", "5ce93fe",
            expiresAtMillis = System.currentTimeMillis() + 60_000,
        )
        assertTrue(DebugWebhook.isEnabled)
    }

    @Test fun `zero means no expiry`() {
        // What a device reports when its install time can't be read. Losing the
        // timestamp must not silently disable debugging — the URL being set is
        // still a deliberate act.
        DebugWebhook.configure("https://example.invalid/hook", "watch", "5ce93fe", 0L)
        assertTrue(DebugWebhook.isEnabled)
    }

    @Test fun `the window is two weeks`() {
        // Long enough to finish an investigation, short enough that a forgotten
        // secret stops leaking workout data without anyone intervening.
        assertEquals(14L * 24 * 60 * 60 * 1000, DebugWebhook.TTL_MILLIS)
    }

    @Test fun `the request capture limit matches the response one`() {
        assertEquals(
            DebugWebhook.BODY_LIMIT.toLong(),
            DebugWebhookInterceptor.MAX_CAPTURE_BYTES,
        )
    }
}
