package com.example.hevywatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TokenRefreshTest {

    // ── isTokenExpiringSoon ────────────────────────────────────────────────────

    @Test
    fun `null expiresAt returns true`() {
        assertTrue(isTokenExpiringSoon(expiresAt = null))
    }

    @Test
    fun `token expiring in 30 seconds returns true`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val expiresAt = "2026-01-01T12:00:30Z"   // 30s from now, within 60s buffer
        assertTrue(isTokenExpiringSoon(expiresAt, now))
    }

    @Test
    fun `token expiring in exactly 60 seconds returns true`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val expiresAt = "2026-01-01T12:01:00Z"   // exactly at the 60s threshold
        assertTrue(isTokenExpiringSoon(expiresAt, now))
    }

    @Test
    fun `token expiring in 61 seconds returns false`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val expiresAt = "2026-01-01T12:01:01Z"   // 61s from now, outside 60s buffer
        assertFalse(isTokenExpiringSoon(expiresAt, now))
    }

    @Test
    fun `already expired token returns true`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val expiresAt = "2025-12-31T12:00:00Z"   // in the past
        assertTrue(isTokenExpiringSoon(expiresAt, now))
    }

    @Test
    fun `unparseable expiresAt string returns true`() {
        assertTrue(isTokenExpiringSoon(expiresAt = "not-a-date"))
    }

    @Test
    fun `blank expiresAt string returns true`() {
        assertTrue(isTokenExpiringSoon(expiresAt = ""))
    }

    @Test
    fun `token expiring in one hour returns false`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val expiresAt = "2026-01-01T13:00:00Z"
        assertFalse(isTokenExpiringSoon(expiresAt, now))
    }
}
