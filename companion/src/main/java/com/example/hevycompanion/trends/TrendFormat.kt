package com.example.hevycompanion.trends

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Number + date formatting for Routine Trends. Deltas always carry an explicit
 * sign (including `±0`) so a row never reads as an absolute figure by mistake.
 */
object TrendFormat {

    private val SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("MMM d, ''yy", Locale.US)

    /** Unicode minus, matching the typography the rest of the app uses. */
    private const val MINUS = "−"

    fun volume(kg: Double): String = "${number(kg)} kg"

    fun signedVolume(kg: Double): String = "${sign(kg)}${number(abs(kg))} kg"

    fun signedInt(value: Int): String = "${sign(value.toDouble())}${abs(value)}"

    /** The metric's value as shown in the totals panel. */
    fun metricValue(metric: TrendMetric, totals: Totals): String = when (metric) {
        TrendMetric.VOLUME -> volume(totals.volumeKg)
        TrendMetric.SETS -> totals.sets.toString()
        TrendMetric.REPS -> totals.reps.toString()
    }

    /** Compact form for the chart's Y-axis labels. */
    fun axisValue(metric: TrendMetric, value: Double): String = when (metric) {
        TrendMetric.VOLUME -> number(value)
        else -> Math.round(value).toString()
    }

    fun shortDate(epochMs: Long): String = SHORT_DATE.format(zoned(epochMs))

    fun date(epochMs: Long): String = DATE.format(zoned(epochMs))

    private fun zoned(epochMs: Long) =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())

    private fun sign(value: Double): String = when {
        value > 0.0 -> "+"
        value < 0.0 -> MINUS
        else -> "±"   // ±
    }

    /**
     * Whole kilos with thousands grouping ("7,274"), except below 1 kg where
     * rounding would swallow the whole figure — a 0.5 kg delta reads as "0.5",
     * not the "1" that half-up rounding would give it. Anything under 0.05 kg
     * is float noise and reads as "0".
     */
    private fun number(value: Double): String {
        if (value == 0.0) return "0"
        if (abs(value) < 1.0) {
            val oneDecimal = String.format(Locale.US, "%.1f", value)
            return if (oneDecimal == "0.0" || oneDecimal == "-0.0") "0" else oneDecimal
        }
        return String.format(Locale.US, "%,d", Math.round(value))
    }
}
