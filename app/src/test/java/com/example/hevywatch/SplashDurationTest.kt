package com.example.hevywatch

import com.example.hevywatch.ui.components.SPLASH_DURATION_MS
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cold-start splash duration to a sane window so a future tweak
 * can't accidentally flash the logo for a single frame (too short) or hold
 * the user behind the splash on every launch (too long).
 */
class SplashDurationTest {

    @Test fun `splash duration is within sane bounds`() {
        assertTrue(
            "splash too short: $SPLASH_DURATION_MS ms",
            SPLASH_DURATION_MS >= 600L,
        )
        assertTrue(
            "splash too long: $SPLASH_DURATION_MS ms",
            SPLASH_DURATION_MS <= 2500L,
        )
    }
}
