package com.example.hevywatch

import com.example.hevywatch.data.model.ExerciseCompletionStatus
import com.example.hevywatch.data.model.ExerciseCompletionStatus.Status
import com.example.hevywatch.presentation.workout.ChipKind
import com.example.hevywatch.presentation.workout.ChipState
import com.example.hevywatch.presentation.workout.WeightKind
import com.example.hevywatch.presentation.workout.backgroundForChipState
import com.example.hevywatch.presentation.workout.chipStatsForStatus
import com.example.hevywatch.ui.theme.ChipPalette
import com.example.hevywatch.ui.theme.DarkExtendedColors
import com.example.hevywatch.ui.theme.LightExtendedColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic helpers for WorkoutDetailScreen's unified chip (shared with
 * LogWorkoutScreen via ExerciseChipUi). `chipStatsForStatus` maps a completion
 * status to the chip's counts / state / kind tag / weight; `backgroundForChipState`
 * turns the state into a tint. Both are independently testable.
 */
class WorkoutDetailScreenLogicTest {

    private fun status(
        prescribedNormal: Int,
        recordedNormal: Int,
        recordedWarmup: Int,
        status: Status,
        expectedWarmup: Int = 0,
        loggedWeight: Float? = null,
        poTarget: Float? = null,
        poIncreased: Boolean = false
    ) = ExerciseCompletionStatus(
        title = "Squat",
        exerciseTemplateId = "X",
        prescribedNormalSets = prescribedNormal,
        recordedNormalSets = recordedNormal,
        recordedWarmupSets = recordedWarmup,
        status = status,
        expectedWarmupSets = expectedWarmup,
        loggedWorkingWeightKg = loggedWeight,
        poTargetKg = poTarget,
        poIncreased = poIncreased
    )

    // ── state → background tint ───────────────────────────────────────────────

    @Test
    fun `dark tint is green when complete, blue when partial, red when nothing logged`() {
        val complete = chipStatsForStatus(status(3, 3, 0, Status.COMPLETE)).state
        val partial = chipStatsForStatus(status(3, 1, 0, Status.INCOMPLETE)).state
        val missing = chipStatsForStatus(status(3, 0, 0, Status.MISSING)).state
        assertEquals(ChipState.COMPLETE, complete)
        assertEquals(ChipState.IN_PROGRESS, partial)
        assertEquals(ChipState.MISSING, missing)
        assertEquals(ChipPalette.StatusCompleteBg, backgroundForChipState(complete, DarkExtendedColors))
        assertEquals(ChipPalette.StatusInProgressBg, backgroundForChipState(partial, DarkExtendedColors))
        assertEquals(ChipPalette.StatusMissingBg, backgroundForChipState(missing, DarkExtendedColors))
    }

    @Test
    fun `light tint mirrors the completion mapping`() {
        assertEquals(ChipPalette.StatusCompleteBgLight, backgroundForChipState(ChipState.COMPLETE, LightExtendedColors))
        assertEquals(ChipPalette.StatusInProgressBgLight, backgroundForChipState(ChipState.IN_PROGRESS, LightExtendedColors))
        assertEquals(ChipPalette.StatusMissingBgLight, backgroundForChipState(ChipState.MISSING, LightExtendedColors))
    }

    // ── counts ────────────────────────────────────────────────────────────────

    @Test
    fun `counts expose warmup and normal done-over-total`() {
        val s = chipStatsForStatus(status(3, 1, 1, Status.INCOMPLETE, expectedWarmup = 2))
        assertEquals(1, s.warmupDone); assertEquals(2, s.warmupTotal)
        assertEquals(1, s.normalDone); assertEquals(3, s.normalTotal)
    }

    // ── kind tag (replaces the old subtitle "↔ slot" / "extra" text + grey) ────

    @Test
    fun `a fully-logged swap is green with a swap tag - no separate colour`() {
        val s = chipStatsForStatus(status(3, 3, 2, Status.SUBSTITUTED, expectedWarmup = 2, loggedWeight = 60f))
        assertEquals(ChipState.COMPLETE, s.state)
        assertEquals(ChipKind.SWAP, s.kind)
    }

    @Test
    fun `a partial swap is blue`() {
        val s = chipStatsForStatus(status(3, 1, 0, Status.SUBSTITUTED, loggedWeight = 60f))
        assertEquals(ChipState.IN_PROGRESS, s.state)
        assertEquals(ChipKind.SWAP, s.kind)
    }

    @Test
    fun `all normal sets done but advised warmups missing reads blue`() {
        val s = chipStatsForStatus(status(3, 3, 0, Status.COMPLETE, expectedWarmup = 2, loggedWeight = 60f))
        assertEquals(ChipState.IN_PROGRESS, s.state)
    }

    @Test
    fun `extra is tinted by completion and carries the extra tag - no grey`() {
        val s = chipStatsForStatus(status(0, 4, 0, Status.EXTRA, loggedWeight = 40f))
        assertEquals(ChipState.COMPLETE, s.state) // bonus work, all "done"
        assertEquals(ChipKind.EXTRA, s.kind)
    }

    // ── weight rule: PO target until first normal set, then logged weight ──────

    @Test
    fun `missing slot shows PO target, green when bumped`() {
        val bumped = chipStatsForStatus(status(3, 0, 0, Status.MISSING, poTarget = 50f, poIncreased = true))
        assertEquals("50kg", bumped.weightText)
        assertEquals(WeightKind.PO, bumped.weightKind)

        val flat = chipStatsForStatus(status(3, 0, 0, Status.MISSING, poTarget = 50f, poIncreased = false))
        assertEquals("50kg", flat.weightText)
        assertEquals(WeightKind.PLAIN, flat.weightKind)
    }

    @Test
    fun `once a normal set is logged the chip shows the logged working weight`() {
        // poTarget is ignored once recordedNormal >= 1.
        val s = chipStatsForStatus(status(3, 1, 0, Status.INCOMPLETE, loggedWeight = 62.5f, poTarget = 50f, poIncreased = true))
        assertEquals("62.5kg", s.weightText)
        assertEquals(WeightKind.PLAIN, s.weightKind)
    }

    @Test
    fun `no weight when neither target nor logged weight is available`() {
        val s = chipStatsForStatus(status(3, 0, 0, Status.MISSING))
        assertNull(s.weightText)
    }

    // ── isComplete (unchanged contract) ───────────────────────────────────────

    @Test
    fun `isComplete requires both normal sets and advised warmups`() {
        assertEquals(true, status(3, 3, 2, Status.COMPLETE, expectedWarmup = 2).isComplete)
        assertEquals(false, status(3, 3, 1, Status.COMPLETE, expectedWarmup = 2).isComplete)
        assertEquals(false, status(3, 2, 2, Status.INCOMPLETE, expectedWarmup = 2).isComplete)
        assertEquals(true, status(3, 3, 0, Status.COMPLETE, expectedWarmup = 0).isComplete)
    }
}
