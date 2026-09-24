package com.example.hevycore.auth

import com.example.hevycore.util.GsonHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These models live in `:core` for exactly one reason: the `@SerializedName`
 * bindings for the refresh-token exchange must NOT drift between the watch and
 * the companion. A cheap Gson round-trip pins the wire keys so a rename on one
 * side can't silently break the other (same guard style as `DebugWebhookTest`
 * / `ResumeBodyMergeTest`). Uses the shared `GsonHolder.gson` both apps use.
 */
class RefreshTokenModelsTest {

    private val gson = GsonHolder.gson

    @Test fun `request serializes the refresh token under refresh_token`() {
        val json = gson.toJson(RefreshTokenRequest(refreshToken = "rt-123"))
        // The server authenticates the rotation off this exact key.
        assertTrue(json, json.contains("\"refresh_token\":\"rt-123\""))
        assertTrue("only the wire key must appear", !json.contains("refreshToken"))
    }

    @Test fun `response binds all three wire keys`() {
        val resp = gson.fromJson(
            """{"access_token":"a","refresh_token":"b","expires_at":"c"}""",
            RefreshTokenResponse::class.java,
        )
        assertEquals("a", resp.accessToken)
        assertEquals("b", resp.refreshToken)
        assertEquals("c", resp.expiresAt)
    }

    @Test fun `response tolerates a missing-field body as the nullable superset`() {
        // The doc-contract: absent fields deserialize to null (consumers reject
        // blanks before use) rather than throwing.
        val resp = gson.fromJson("{}", RefreshTokenResponse::class.java)
        assertNull(resp.accessToken)
        assertNull(resp.refreshToken)
        assertNull(resp.expiresAt)
    }

    @Test fun `response round-trips through camelCase construction to snake_case json`() {
        val json = gson.toJson(RefreshTokenResponse("a", "b", "c"))
        assertTrue(json, json.contains("\"access_token\":\"a\""))
        assertTrue(json, json.contains("\"refresh_token\":\"b\""))
        assertTrue(json, json.contains("\"expires_at\":\"c\""))
    }
}
