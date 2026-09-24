package com.example.hevywatch

import com.example.hevywatch.presentation.workout.ABANDONMENT_THRESHOLD_MS
import com.example.hevywatch.presentation.workout.shouldNudgeAbandonment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the post-pause abandonment nudge threshold.
 *
 * The prompt only exists to catch a genuinely-forgotten session before it
 * becomes a stale incomplete workout (which then needs the resume
 * POST+DELETE cleanup). It must never fire during a normal rest period, so
 * the boundary behaviour matters more than the exact constant.
 */
class AbandonmentNudgeTest {

    private val now = 1_700_000_000_000L

    @Test fun `threshold is 30 minutes`() {
        assertEquals(30L * 60 * 1000, ABANDONMENT_THRESHOLD_MS)
    }

    @Test fun `running workout never nudges`() {
        assertFalse(
            shouldNudgeAbandonment(
                isPaused = false,
                pausedAtMs = now - ABANDONMENT_THRESHOLD_MS * 10,
                nowMs = now,
            )
        )
    }

    @Test fun `paused but well inside the threshold does not nudge`() {
        // A long rest period — 5 minutes — must not trigger the prompt.
        assertFalse(
            shouldNudgeAbandonment(
                isPaused = true,
                pausedAtMs = now - 5L * 60 * 1000,
                nowMs = now,
            )
        )
    }

    @Test fun `exactly at the threshold does not nudge`() {
        assertFalse(
            shouldNudgeAbandonment(
                isPaused = true,
                pausedAtMs = now - ABANDONMENT_THRESHOLD_MS,
                nowMs = now,
            )
        )
    }

    @Test fun `one millisecond past the threshold nudges`() {
        assertTrue(
            shouldNudgeAbandonment(
                isPaused = true,
                pausedAtMs = now - ABANDONMENT_THRESHOLD_MS - 1,
                nowMs = now,
            )
        )
    }

    @Test fun `hours-long pause nudges`() {
        assertTrue(
            shouldNudgeAbandonment(
                isPaused = true,
                pausedAtMs = now - 4L * 60 * 60 * 1000,
                nowMs = now,
            )
        )
    }

    @Test fun `null pause timestamp does not nudge`() {
        // Shouldn't happen while isPaused, but guessing a pause duration is
        // worse than staying quiet.
        assertFalse(shouldNudgeAbandonment(isPaused = true, pausedAtMs = null, nowMs = now))
    }

    @Test fun `clock skew backwards does not nudge`() {
        // pausedAt in the future (NTP correction, manual clock change) must
        // not produce a negative elapsed that somehow reads as stale.
        assertFalse(
            shouldNudgeAbandonment(
                isPaused = true,
                pausedAtMs = now + 60_000,
                nowMs = now,
            )
        )
    }
}
