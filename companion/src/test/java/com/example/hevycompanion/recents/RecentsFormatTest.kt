package com.example.hevycompanion.recents

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Recents/Workout-Detail display formatters. These strings are what
 * the user reads on every recent-workout row and detail header, so the date
 * pattern, the offset handling, and the integer-weight collapse are the
 * contract — a drift here is user-visible. Pattern is `Locale.US`-fixed in the
 * formatter itself, so the assertions are locale-independent.
 */
class RecentsFormatTest {

    // --- dateTime ---------------------------------------------------------

    @Test fun `dateTime formats a UTC ISO timestamp with time`() {
        assertEquals("Mar 28, '26  14:30", RecentsFormat.dateTime("2026-03-28T14:30:00Z"))
    }

    @Test fun `dateTime honours the timestamp's own offset (no zone conversion)`() {
        // OffsetDateTime keeps the wall-clock of the offset it was given.
        assertEquals("Mar 28, '26  14:30", RecentsFormat.dateTime("2026-03-28T14:30:00+02:00"))
    }

    @Test fun `dateTime returns the fallback for null, blank and unparseable input`() {
        assertEquals("", RecentsFormat.dateTime(null))
        assertEquals("", RecentsFormat.dateTime(""))
        assertEquals("", RecentsFormat.dateTime("   "))
        assertEquals("—", RecentsFormat.dateTime("not-a-date", fallback = "—"))
        // A date-only slice has no offset → OffsetDateTime.parse rejects it.
        assertEquals("", RecentsFormat.dateTime("2026-03-28"))
    }

    // --- date -------------------------------------------------------------

    @Test fun `date drops the time component`() {
        assertEquals("Mar 28, '26", RecentsFormat.date("2026-03-28T00:00:00Z"))
        assertEquals("Dec 1, '25", RecentsFormat.date("2025-12-01T09:15:00Z"))
    }

    @Test fun `date returns the fallback for bad input`() {
        assertEquals("", RecentsFormat.date(null))
        assertEquals("?", RecentsFormat.date("garbage", fallback = "?"))
    }

    // --- kg ---------------------------------------------------------------

    @Test fun `kg drops a trailing point-zero on whole numbers`() {
        assertEquals("40 kg", RecentsFormat.kg(40f))
        assertEquals("0 kg", RecentsFormat.kg(0f))
        assertEquals("100 kg", RecentsFormat.kg(100f))
    }

    @Test fun `kg keeps a real fractional weight`() {
        assertEquals("22.5 kg", RecentsFormat.kg(22.5f))
        assertEquals("2.5 kg", RecentsFormat.kg(2.5f))
    }
}
