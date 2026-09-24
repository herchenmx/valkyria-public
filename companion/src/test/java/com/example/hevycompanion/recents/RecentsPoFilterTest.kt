package com.example.hevycompanion.recents

import com.example.hevycompanion.recents.RecentsViewModel.Companion.filterToPoRoutines
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Recents-list scoping rule: only workouts whose routine is in a
 * Progressive-Overload folder are shown. Mirrors the watch app's
 * `RecentWorkoutsPoFilterTest`.
 */
class RecentsPoFilterTest {

    private fun workout(id: String, routineId: String?): RecentWorkout =
        RecentWorkout(id = id, title = id, startTime = "2026-05-09T10:00:00Z", routineId = routineId)

    @Test
    fun `keeps only workouts from PO routines`() {
        val workouts = listOf(
            workout("w-po-a", routineId = "po-a"),
            workout("w-plain", routineId = "plain"),
            workout("w-po-b", routineId = "po-b"),
        )
        val out = filterToPoRoutines(workouts, setOf("po-a", "po-b"))
        assertEquals(listOf("w-po-a", "w-po-b"), out.map { it.id })
    }

    @Test
    fun `hides workouts with no routine`() {
        val workouts = listOf(
            workout("freestyle", routineId = null),
            workout("w-po", routineId = "po-a"),
        )
        val out = filterToPoRoutines(workouts, setOf("po-a"))
        assertEquals(listOf("w-po"), out.map { it.id })
    }

    @Test
    fun `preserves input order`() {
        val workouts = listOf(
            workout("third", routineId = "po"),
            workout("first", routineId = "po"),
            workout("second", routineId = "po"),
        )
        val out = filterToPoRoutines(workouts, setOf("po"))
        assertEquals(listOf("third", "first", "second"), out.map { it.id })
    }

    @Test
    fun `empty PO set hides everything`() {
        val workouts = listOf(workout("w", routineId = "po-a"))
        assertEquals(emptyList<RecentWorkout>(), filterToPoRoutines(workouts, emptySet()))
    }
}
