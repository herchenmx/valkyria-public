package com.example.hevycompanion.trends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Number formatting + the trend window's timestamp handling. */
class TrendFormatTest {

    @Test
    fun `volume is grouped whole kilos`() {
        assertEquals("7,274 kg", TrendFormat.volume(7273.5))
        assertEquals("0 kg", TrendFormat.volume(0.0))
    }

    @Test
    fun `deltas always carry a sign`() {
        assertEquals("+510 kg", TrendFormat.signedVolume(510.0))
        assertEquals("−510 kg", TrendFormat.signedVolume(-510.0))
        assertEquals("±0 kg", TrendFormat.signedVolume(0.0))
        assertEquals("+4", TrendFormat.signedInt(4))
        assertEquals("−2", TrendFormat.signedInt(-2))
        assertEquals("±0", TrendFormat.signedInt(0))
    }

    @Test
    fun `a sub-kilo delta is not rounded away to zero`() {
        assertEquals("−0.5 kg", TrendFormat.signedVolume(-0.5))
    }

    @Test
    fun `metric values read from the right total`() {
        val totals = Totals(volumeKg = 7274.0, sets = 28, reps = 412)
        assertEquals("7,274 kg", TrendFormat.metricValue(TrendMetric.VOLUME, totals))
        assertEquals("28", TrendFormat.metricValue(TrendMetric.SETS, totals))
        assertEquals("412", TrendFormat.metricValue(TrendMetric.REPS, totals))
    }

    @Test
    fun `the window cutoff is one year back`() {
        val now = 1_800_000_000_000L
        assertEquals(now - 365L * 24 * 60 * 60 * 1000, TrendTime.cutoffMs(now))
    }

    @Test
    fun `start times parse in every shape the API emits`() {
        val expected = 1_754_042_400_000L  // 2025-08-01T10:00:00Z
        assertEquals(expected, TrendTime.epochMsOrNull("2025-08-01T10:00:00Z"))
        assertEquals(expected, TrendTime.epochMsOrNull("2025-08-01T10:00:00+00:00"))
        assertEquals(expected, TrendTime.epochMsOrNull("2025-08-01T10:00:00"))
        assertEquals(expected, TrendTime.epochMsOrNull("2025-08-01 10:00:00"))
    }

    @Test
    fun `an unparseable start time is dropped rather than defaulted`() {
        assertNull(TrendTime.epochMsOrNull(null))
        assertNull(TrendTime.epochMsOrNull(""))
        assertNull(TrendTime.epochMsOrNull("last tuesday"))
    }
}
