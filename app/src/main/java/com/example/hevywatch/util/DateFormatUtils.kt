package com.example.hevywatch.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * R3 — single source for the watch's user-facing date formatters. Each screen
 * used to declare its own private `DATE_FORMATTER` (sometimes with the same
 * pattern, sometimes drifting by a leading zero or a separator), and one of
 * them was still on legacy [java.text.SimpleDateFormat]. Consolidating here:
 *
 *  - All formatters use [java.time] (thread-safe, locale-correct).
 *  - All bind to the system default zone so the displayed date matches the
 *    user's wall clock (the API returns UTC ISO timestamps).
 *
 * Pattern conventions:
 *  - [LIST_DATE] — compact "MMM d, 'yy" for chip subtitles
 *  - [LIST_DATE_TIME] — "MMM d, 'yy  HH:mm" for the recent-workout list
 */
object DateFormatUtils {

    private val systemZone: ZoneId = ZoneId.systemDefault()

    val LIST_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, ''yy").withZone(systemZone)

    val LIST_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, ''yy  HH:mm").withZone(systemZone)

    /** Format an [Instant] with [LIST_DATE], or return [fallback] when the
     *  instant is null. */
    fun formatListDate(instant: Instant?, fallback: String = ""): String =
        if (instant != null) LIST_DATE.format(instant) else fallback

    fun formatListDateTime(instant: Instant?, fallback: String = ""): String =
        if (instant != null) LIST_DATE_TIME.format(instant) else fallback
}
