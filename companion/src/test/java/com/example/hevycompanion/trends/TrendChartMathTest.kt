package com.example.hevycompanion.trends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chart's pure arithmetic: axis bounds and tap hit-testing. */
class TrendChartMathTest {

    @Test
    fun `axis bounds pad the data range`() {
        val (min, max) = TrendChartMath.axisBounds(listOf(100.0, 200.0))
        assertEquals(90.0, min, 0.001)
        assertEquals(210.0, max, 0.001)
    }

    @Test
    fun `a flat series still gets a non-zero range`() {
        val (min, max) = TrendChartMath.axisBounds(listOf(500.0, 500.0, 500.0))
        assertTrue("a flat series would divide by zero", max > min)
    }

    @Test
    fun `an all-zero series gets a non-zero range`() {
        val (min, max) = TrendChartMath.axisBounds(listOf(0.0, 0.0))
        assertTrue(max > min)
    }

    @Test
    fun `empty values fall back to a unit range`() {
        assertEquals(0.0 to 1.0, TrendChartMath.axisBounds(emptyList()))
    }

    @Test
    fun `a tap picks the nearest point`() {
        val xs = listOf(0f, 100f, 200f)
        assertEquals(1, TrendChartMath.nearestIndex(xs, tapX = 90f))
        assertEquals(2, TrendChartMath.nearestIndex(xs, tapX = 205f))
    }

    @Test
    fun `a tap far from every point selects nothing`() {
        val xs = listOf(0f, 400f)
        assertNull(TrendChartMath.nearestIndex(xs, tapX = 200f))
    }

    @Test
    fun `ties go to the earlier point`() {
        val xs = listOf(0f, 100f)
        assertEquals(0, TrendChartMath.nearestIndex(xs, tapX = 50f, slopPx = 100f))
    }

    @Test
    fun `an empty series selects nothing`() {
        assertNull(TrendChartMath.nearestIndex(emptyList(), tapX = 0f))
    }
}
