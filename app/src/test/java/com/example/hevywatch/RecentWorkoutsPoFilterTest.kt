package com.example.hevywatch

import com.example.hevywatch.data.api.model.WorkoutSummaryResponse
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.presentation.routine.RoutineFolderListViewModel.Companion.poRecents
import com.example.hevywatch.presentation.routine.RoutineFolderListViewModel.Companion.poRoutineIds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Recents-page scoping rule: only workouts logged from a routine that
 * lives in a Progressive-Overload folder are shown. Workouts with no routine,
 * or from a non-PO routine, are hidden.
 */
class RecentWorkoutsPoFilterTest {

    private fun workout(id: String, routineId: String?): WorkoutSummaryResponse =
        WorkoutSummaryResponse(
            id = id,
            title = id,
            routineId = routineId,
            startTime = "2026-05-09T10:00:00Z",
            createdAt = null,
        )

    private fun routine(id: String, po: Boolean): Routine =
        Routine(
            id = id,
            title = id,
            notes = null,
            folderId = if (po) "2525049" else "999",
            exercises = emptyList(),
            updatedAt = "2026-05-09T10:00:00Z",
            progressiveOverload = po,
        )

    @Test
    fun `poRoutineIds keeps only PO-flagged routines`() {
        val routines = listOf(
            routine("po-a", po = true),
            routine("plain", po = false),
            routine("po-b", po = true),
        )
        assertEquals(setOf("po-a", "po-b"), poRoutineIds(routines))
    }

    @Test
    fun `poRecents keeps only workouts from PO routines`() {
        val recents = listOf(
            workout("w-po-a", routineId = "po-a"),
            workout("w-plain", routineId = "plain"),
            workout("w-po-b", routineId = "po-b"),
        )
        val out = poRecents(recents, setOf("po-a", "po-b"))
        assertEquals(listOf("w-po-a", "w-po-b"), out.map { it.id })
    }

    @Test
    fun `poRecents hides workouts with no routine`() {
        val recents = listOf(
            workout("freestyle", routineId = null),
            workout("w-po", routineId = "po-a"),
        )
        val out = poRecents(recents, setOf("po-a"))
        assertEquals(listOf("w-po"), out.map { it.id })
    }

    @Test
    fun `poRecents preserves input order`() {
        val recents = listOf(
            workout("third", routineId = "po"),
            workout("first", routineId = "po"),
            workout("second", routineId = "po"),
        )
        val out = poRecents(recents, setOf("po"))
        assertEquals(listOf("third", "first", "second"), out.map { it.id })
    }

    @Test
    fun `empty PO set hides everything`() {
        val recents = listOf(workout("w", routineId = "po-a"))
        assertEquals(emptyList<WorkoutSummaryResponse>(), poRecents(recents, emptySet()))
    }
}
