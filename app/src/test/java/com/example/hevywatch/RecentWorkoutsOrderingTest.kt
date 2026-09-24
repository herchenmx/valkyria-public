package com.example.hevywatch

import com.example.hevywatch.data.api.model.WorkoutSummaryResponse
import com.example.hevywatch.presentation.routine.RoutineFolderListViewModel.Companion.sortedRecents
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Recent-workouts page ordering. The page sorts by `start_time`
 * desc so rows appear in chronological order of when each workout actually
 * happened — what the user sees on the row's date label and what they
 * expect from a "recent workouts" list.
 */
class RecentWorkoutsOrderingTest {

    private fun w(id: String, startTime: String, createdAt: String?): WorkoutSummaryResponse =
        WorkoutSummaryResponse(
            id = id,
            title = id,
            routineId = null,
            startTime = startTime,
            createdAt = createdAt,
        )

    @Test
    fun `sorts by start_time desc`() {
        val newest = w("newest",
            startTime = "2026-05-09T10:00:00Z",
            createdAt = "2026-05-09T10:30:00Z")
        val middle = w("middle",
            startTime = "2026-05-08T08:00:00Z",
            createdAt = "2026-05-08T08:30:00Z")
        val oldest = w("oldest",
            startTime = "2026-05-07T07:00:00Z",
            createdAt = "2026-05-07T07:30:00Z")

        val out = sortedRecents(listOf(middle, oldest, newest))

        assertEquals(listOf("newest", "middle", "oldest"), out.map { it.id })
    }

    @Test
    fun `created_at is irrelevant to the order`() {
        // Workout A was logged later (later created_at) but happened earlier
        // (earlier start_time) — must sort below B.
        val a = w("a",
            startTime = "2026-05-08T05:00:00Z",
            createdAt = "2026-05-09T20:00:00Z")
        val b = w("b",
            startTime = "2026-05-09T05:00:00Z",
            createdAt = "2026-05-09T05:30:00Z")

        val out = sortedRecents(listOf(a, b))

        assertEquals(listOf("b", "a"), out.map { it.id })
    }

    @Test
    fun `default cap is RECENT_WORKOUTS_LIMIT (10) — matches one page of v1 workouts`() {
        // /v1/workouts?page=1 returns exactly 10 per page (verified live).
        // Surfacing 10 keeps the page useful without any additional fetch.
        val workouts = (1..15).map {
            // Newer start_times for lower index numbers.
            w("w$it",
                startTime = "2026-05-${"%02d".format(25 - it)}T00:00:00Z",
                createdAt = null)
        }

        val out = sortedRecents(workouts)
        assertEquals(
            com.example.hevywatch.presentation.routine.RoutineFolderListViewModel.RECENT_WORKOUTS_LIMIT,
            out.size
        )
        assertEquals(10, out.size) // sanity-pin the constant's current value
        assertEquals("w1", out.first().id)
    }

    @Test
    fun `explicit limit overrides default`() {
        val workouts = (1..15).map {
            w("w$it",
                startTime = "2026-05-${"%02d".format(25 - it)}T00:00:00Z",
                createdAt = null)
        }
        assertEquals(3, sortedRecents(workouts, limit = 3).size)
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(emptyList<WorkoutSummaryResponse>(), sortedRecents(emptyList()))
    }
}
