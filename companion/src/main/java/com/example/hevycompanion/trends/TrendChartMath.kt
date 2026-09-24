package com.example.hevycompanion.trends

import kotlin.math.abs

/**
 * The chart's pure arithmetic, kept out of the composable so it can be
 * unit-tested (`TrendChartMathTest`).
 */
object TrendChartMath {

    /** How close a tap must land, horizontally, to count as hitting a point. */
    const val TAP_SLOP_PX = 48f

    /**
     * Y-axis bounds for [values]: the data range with 10% headroom, and never
     * a zero-height range (a routine whose totals never moved would otherwise
     * divide by zero and collapse onto one line).
     */
    fun axisBounds(values: List<Double>): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 1.0
        val min = values.minOrNull()!!
        val max = values.maxOrNull()!!
        if (max <= min) {
            // Centre a flat series in a band around its value.
            val pad = if (max == 0.0) 1.0 else abs(max) * 0.1
            return (min - pad) to (max + pad)
        }
        val pad = (max - min) * 0.1
        return (min - pad) to (max + pad)
    }

    /**
     * Index of the datapoint nearest [tapX], or null when the tap landed
     * further than [slopPx] from every point (so a stray tap on empty chart
     * area doesn't yank the selection across the screen). Ties go to the
     * earlier point.
     */
    fun nearestIndex(xs: List<Float>, tapX: Float, slopPx: Float = TAP_SLOP_PX): Int? {
        if (xs.isEmpty()) return null
        var best = 0
        var bestDistance = abs(xs[0] - tapX)
        for (i in 1 until xs.size) {
            val d = abs(xs[i] - tapX)
            if (d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return if (bestDistance <= slopPx) best else null
    }
}
