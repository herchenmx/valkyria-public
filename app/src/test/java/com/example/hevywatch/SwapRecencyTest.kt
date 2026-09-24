package com.example.hevywatch

import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.presentation.workout.WorkoutHistoryApplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Recency sort key for swap candidates ([WorkoutHistoryApplier.lastUsedEpochMsOf]):
 * the most-recently-used substitute should sort first, never-used ones last.
 */
class SwapRecencyTest {

    private fun entry(start: String) = ExerciseHistoryEntry(
        workoutId = "w", workoutTitle = null, workoutStartTime = start,
        workoutEndTime = null, exerciseTemplateId = "x", weightKg = null,
        reps = null, distanceMeters = null, durationSeconds = null,
        rpe = null, customMetric = null, setType = "normal"
    )

    @Test
    fun `never-used exercise sorts last via MIN_VALUE`() {
        assertEquals(Long.MIN_VALUE, WorkoutHistoryApplier.lastUsedEpochMsOf(emptyList()))
    }

    @Test
    fun `picks the most recent session across entries, regardless of order`() {
        val recent = "2026-06-26T11:54:03+00:00"
        val older = "2026-06-20T08:00:00+00:00"
        val key = WorkoutHistoryApplier.lastUsedEpochMsOf(listOf(entry(older), entry(recent), entry(older)))
        assertEquals(Instant.parse("2026-06-26T11:54:03Z").toEpochMilli(), key)
    }

    @Test
    fun `a more recently used exercise outranks an older one`() {
        val a = WorkoutHistoryApplier.lastUsedEpochMsOf(listOf(entry("2026-06-26T11:54:03+00:00")))
        val b = WorkoutHistoryApplier.lastUsedEpochMsOf(listOf(entry("2026-01-02T09:00:00+00:00")))
        assertTrue("recent must sort ahead (larger key) than old", a > b)
    }

    @Test
    fun `unparseable timestamps are ignored, not crashing`() {
        assertEquals(Long.MIN_VALUE, WorkoutHistoryApplier.lastUsedEpochMsOf(listOf(entry("not-a-date"))))
    }
}
