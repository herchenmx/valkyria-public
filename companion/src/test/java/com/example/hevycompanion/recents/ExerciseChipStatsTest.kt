package com.example.hevycompanion.recents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Companion Workout Detail chip mapping ([chipStatsForStatus]) — the phone
 * counterpart to the watch's `chipStatsForStatus`. Same contract: completion
 * state → tint, swap/extra kind tag, `X/Y W` + `X/Y N`, and the weight (PO
 * target until the first normal set, then the logged working weight).
 */
class ExerciseChipStatsTest {

    private fun status(
        prescribedNormal: Int,
        recordedNormal: Int,
        recordedWarmup: Int,
        status: ExerciseCompletionStatus.Status,
        expectedWarmup: Int = 0,
        loggedWeight: Float? = null,
    ) = ExerciseCompletionStatus(
        title = "Squat",
        exerciseTemplateId = "X",
        prescribedNormalSets = prescribedNormal,
        recordedNormalSets = recordedNormal,
        recordedWarmupSets = recordedWarmup,
        status = status,
        expectedWarmupSets = expectedWarmup,
        loggedWorkingWeightKg = loggedWeight,
    )

    private fun advice(targetKg: Float?, increased: Boolean) =
        ExerciseAdvice(po = PoOutcome(targetKg = targetKg, baseKg = null, increased = increased), warmups = emptyList())

    @Test
    fun `state maps complete, partial, missing`() {
        assertEquals(ChipState.COMPLETE, chipStatsForStatus(status(3, 3, 0, ExerciseCompletionStatus.Status.COMPLETE), null).state)
        assertEquals(ChipState.IN_PROGRESS, chipStatsForStatus(status(3, 1, 0, ExerciseCompletionStatus.Status.INCOMPLETE), null).state)
        assertEquals(ChipState.MISSING, chipStatsForStatus(status(3, 0, 0, ExerciseCompletionStatus.Status.MISSING), null).state)
    }

    @Test
    fun `warmup-short complete-normal reads in-progress`() {
        val s = chipStatsForStatus(status(3, 3, 0, ExerciseCompletionStatus.Status.COMPLETE, expectedWarmup = 2, loggedWeight = 60f), null)
        assertEquals(ChipState.IN_PROGRESS, s.state)
        assertEquals(0, s.warmupDone); assertEquals(2, s.warmupTotal)
    }

    @Test
    fun `swap and extra carry their kind tag, tinted by completion`() {
        val swap = chipStatsForStatus(status(3, 3, 2, ExerciseCompletionStatus.Status.SUBSTITUTED, expectedWarmup = 2, loggedWeight = 45f), null)
        assertEquals(ChipKind.SWAP, swap.kind)
        assertEquals(ChipState.COMPLETE, swap.state)

        val extra = chipStatsForStatus(status(0, 4, 0, ExerciseCompletionStatus.Status.EXTRA, loggedWeight = 40f), null)
        assertEquals(ChipKind.EXTRA, extra.kind)
        assertEquals(ChipState.COMPLETE, extra.state)
    }

    @Test
    fun `missing slot shows PO target, green when bumped`() {
        val bumped = chipStatsForStatus(status(3, 0, 0, ExerciseCompletionStatus.Status.MISSING), advice(50f, increased = true))
        assertEquals("50kg", bumped.weightText)
        assertEquals(WeightKind.PO, bumped.weightKind)

        val flat = chipStatsForStatus(status(3, 0, 0, ExerciseCompletionStatus.Status.MISSING), advice(50f, increased = false))
        assertEquals("50kg", flat.weightText)
        assertEquals(WeightKind.PLAIN, flat.weightKind)
    }

    @Test
    fun `logged weight wins once a normal set exists, ignoring PO target`() {
        val s = chipStatsForStatus(
            status(3, 1, 0, ExerciseCompletionStatus.Status.INCOMPLETE, loggedWeight = 62.5f),
            advice(50f, increased = true),
        )
        assertEquals("62.5kg", s.weightText)
        assertEquals(WeightKind.PLAIN, s.weightKind)
    }

    @Test
    fun `no weight when neither target nor logged weight available`() {
        assertNull(chipStatsForStatus(status(3, 0, 0, ExerciseCompletionStatus.Status.MISSING), null).weightText)
    }
}
