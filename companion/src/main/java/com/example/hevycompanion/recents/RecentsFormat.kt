package com.example.hevycompanion.recents

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date / set-count formatting for the Recents list and Workout Detail. */
object RecentsFormat {

    // "Mar 28, '26  14:30" — matches the watch's LIST_DATE_TIME pattern.
    private val DATE_TIME = DateTimeFormatter.ofPattern("MMM d, ''yy  HH:mm", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("MMM d, ''yy", Locale.US)

    /** ISO-8601 → "Mar 28, '26  14:30"; null / unparseable → fallback. */
    fun dateTime(iso: String?, fallback: String = ""): String = format(iso, DATE_TIME, fallback)

    /** ISO-8601 → "Mar 28, '26"; null / unparseable → fallback. */
    fun date(iso: String?, fallback: String = ""): String = format(iso, DATE, fallback)

    private fun format(iso: String?, fmt: DateTimeFormatter, fallback: String): String {
        if (iso.isNullOrBlank()) return fallback
        return try {
            OffsetDateTime.parse(iso).format(fmt)
        } catch (_: Exception) {
            fallback
        }
    }

    /** Weight as a compact string: "22.5 kg", "40 kg" (trailing ".0" dropped). */
    fun kg(weightKg: Float): String {
        val n = if (weightKg == weightKg.toLong().toFloat()) {
            weightKg.toLong().toString()
        } else {
            weightKg.toString()
        }
        return "$n kg"
    }
}
