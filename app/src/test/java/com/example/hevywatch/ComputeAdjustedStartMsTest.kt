package com.example.hevywatch

import com.example.hevywatch.presentation.workout.WorkoutDetailViewModel.Companion.computeAdjustedStartMs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the resume-timer math. The fix: when the user taps "Continue" on a
 * workout that was originally 45 minutes long, the on-watch timer must read
 * 45:00 immediately and tick up from there — not start at 0:00 (the old
 * default) and not read time-since-original-start (which would be days).
 *
 * The mechanism is virtual: we slide `active.startTimeMs` back by the
 * original duration so `now − active.startTimeMs == originalDuration`, and
 * everything else (WorkoutAwareTimeText, the PUT builder) just reads that
 * field. So this test only needs to pin the arithmetic on the helper.
 */
class ComputeAdjustedStartMsTest {

    @Test
    fun `adjusts start back by the original duration`() {
        val now = 1_715_000_000_000L
        val adjusted = computeAdjustedStartMs(
            originalStartIso = "2026-04-17T10:00:00Z",   // 1_713_348_000_000
            originalEndIso   = "2026-04-17T10:45:00Z",   // +45min
            nowMs = now
        )
        // 45 minutes = 2_700_000 ms back from now
        assertEquals(now - 2_700_000L, adjusted)
    }

    @Test
    fun `returns now when original endTime is missing`() {
        val now = 1_715_000_000_000L
        val adjusted = computeAdjustedStartMs(
            originalStartIso = "2026-04-17T10:00:00Z",
            originalEndIso = null,
            nowMs = now
        )
        assertEquals(now, adjusted)
    }

    @Test
    fun `returns now when original startTime is missing`() {
        val now = 1_715_000_000_000L
        val adjusted = computeAdjustedStartMs(
            originalStartIso = null,
            originalEndIso = "2026-04-17T10:45:00Z",
            nowMs = now
        )
        assertEquals(now, adjusted)
    }

    @Test
    fun `returns now when either timestamp is unparseable`() {
        val now = 1_715_000_000_000L
        val adjusted = computeAdjustedStartMs(
            originalStartIso = "garbage",
            originalEndIso = "also-garbage",
            nowMs = now
        )
        assertEquals(now, adjusted)
    }

    @Test
    fun `coerces negative duration to zero (end before start)`() {
        // Defensive: if server returned end < start, don't slide into the future.
        val now = 1_715_000_000_000L
        val adjusted = computeAdjustedStartMs(
            originalStartIso = "2026-04-17T10:45:00Z",
            originalEndIso   = "2026-04-17T10:00:00Z",   // earlier than start
            nowMs = now
        )
        assertEquals(now, adjusted)
    }
}
