package com.example.hevywatch

import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.util.DateFormatUtils
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins the per-routine "last workout" subtitle on RoutineListScreen to the
 * same `"MMM d, ''yy"` form that the Recent page (RoutineFolderListScreen)
 * and the Progress page (RoutineDetailScreen) already use. The earlier
 * version fell back to a raw `yyyy-MM-dd` string whenever the helper
 * couldn't parse the source timestamp; that fallback then leaked through
 * to every card on the screen because of a parse-path mismatch.
 *
 * Locale/zone are forced to en-US / UTC so a CI box in a different
 * locale doesn't render "3 May, '26" or shift the day across the date
 * line.
 */
class RoutineListDateFormatTest {

    private val savedLocale = Locale.getDefault()
    private val savedZone = TimeZone.getDefault()

    @Before fun pinEnvironment() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreEnvironment() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    @Test fun `formats ISO-Z workout timestamp like the Recent and Progress pages`() {
        val instant = RoutineProgressComputer.parseInstant("2026-05-03T12:34:56Z")
        assertEquals(
            "May 3, '26",
            DateFormatUtils.formatListDate(instant, fallback = "fallback"),
        )
    }

    @Test fun `formats offset-timestamp variant the API sometimes returns`() {
        val instant = RoutineProgressComputer.parseInstant("2026-05-03T14:34:56+02:00")
        assertEquals(
            "May 3, '26",
            DateFormatUtils.formatListDate(instant, fallback = "fallback"),
        )
    }

    @Test fun `does not silently drop to the yyyy-MM-dd fallback on the common shape`() {
        val instant = RoutineProgressComputer.parseInstant("2026-05-03T12:34:56Z")
        val rendered = DateFormatUtils.formatListDate(instant, fallback = "2026-05-03")
        assertNotEquals("2026-05-03", rendered)
    }
}
