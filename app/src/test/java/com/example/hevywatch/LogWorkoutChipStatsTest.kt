package com.example.hevywatch

import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.ChipKind
import com.example.hevywatch.presentation.workout.ChipState
import com.example.hevywatch.presentation.workout.WeightKind
import com.example.hevywatch.presentation.workout.logWorkoutChipStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LogWorkoutScreen's chip-stats mapping ([logWorkoutChipStats]) — the live /
 * resume counterpart to WorkoutDetailScreen's `chipStatsForStatus`. Done counts
 * are *completed* sets; totals are prescribed sets; a swapped-in exercise shows
 * the `swap` tag.
 */
class LogWorkoutChipStatsTest {

    private fun set(type: SetType, completed: Boolean) =
        ActiveSet(setType = type, completed = completed)

    private fun exercise(sets: List<ActiveSet>, wasSwapped: Boolean = false) = ActiveExercise(
        exerciseTemplateId = "X",
        title = "Squat",
        sets = sets,
        equipment = "barbell",
        wasSwapped = wasSwapped
    )

    @Test
    fun `nothing completed reads missing`() {
        val ex = exercise(listOf(set(SetType.NORMAL, false), set(SetType.NORMAL, false)))
        assertEquals(ChipState.MISSING, logWorkoutChipStats(ex, 60f, null, false).state)
    }

    @Test
    fun `partial completion reads in-progress with done-over-total counts`() {
        val ex = exercise(
            listOf(
                set(SetType.WARMUP, true),
                set(SetType.NORMAL, true),
                set(SetType.NORMAL, false),
                set(SetType.NORMAL, false)
            )
        )
        val s = logWorkoutChipStats(ex, 60f, null, false)
        assertEquals(ChipState.IN_PROGRESS, s.state)
        assertEquals(1, s.warmupDone); assertEquals(1, s.warmupTotal)
        assertEquals(1, s.normalDone); assertEquals(3, s.normalTotal)
    }

    @Test
    fun `all sets completed reads complete`() {
        val ex = exercise(listOf(set(SetType.NORMAL, true), set(SetType.NORMAL, true)))
        assertEquals(ChipState.COMPLETE, logWorkoutChipStats(ex, 60f, null, false).state)
    }

    @Test
    fun `swapped-in exercise carries the swap tag`() {
        val ex = exercise(listOf(set(SetType.NORMAL, false)), wasSwapped = true)
        assertEquals(ChipKind.SWAP, logWorkoutChipStats(ex, 60f, null, false).kind)
    }

    @Test
    fun `non-swapped exercise has no tag`() {
        val ex = exercise(listOf(set(SetType.NORMAL, false)))
        assertNull(logWorkoutChipStats(ex, 60f, null, false).kind)
    }

    @Test
    fun `PO bump paints the target weight green`() {
        val ex = exercise(listOf(set(SetType.NORMAL, false)))
        val s = logWorkoutChipStats(ex, 60f, null, poWillIncrease = true)
        assertEquals("60kg", s.weightText)
        assertEquals(WeightKind.PO, s.weightKind)
    }
}
