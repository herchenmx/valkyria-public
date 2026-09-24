package com.example.hevycompanion.recents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pins the proactive-refresh window used before the resume's private v2 calls,
 * ported from the watch's `isTokenExpiringSoon` (60 s lookahead).
 */
class TokenExpiryTest {

    private val now = Instant.parse("2026-06-13T12:00:00Z")
    private fun soon(at: String?) =
        ResumeWorkoutViewModel.isTokenExpiringSoon(at, now)

    @Test fun `null expiry refreshes`() = assertTrue(soon(null))

    @Test fun `unparseable expiry refreshes`() = assertTrue(soon("not-a-date"))

    @Test fun `already-expired token refreshes`() =
        assertTrue(soon("2026-06-13T11:59:00Z"))

    @Test fun `expiry within the 60s window refreshes`() =
        assertTrue(soon("2026-06-13T12:00:30Z"))

    @Test fun `expiry comfortably in the future does not refresh`() =
        assertFalse(soon("2026-06-13T13:00:00Z"))
}
