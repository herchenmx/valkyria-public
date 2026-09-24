package com.example.hevycore.chip

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the [ExerciseChipStats.state] state machine that the watch's
 * WorkoutDetail chip and the companion's Recents chip both read from. Any
 * change to these transitions changes the meaning of every green / blue /
 * red chip in both apps.
 */
class ExerciseChipDataTest {

    @Test fun `fully-recorded chip is COMPLETE`() {
        val stats = stats(warmupDone = 2, warmupTotal = 2, normalDone = 4, normalTotal = 4)
        assertEquals(ChipState.COMPLETE, stats.state)
    }

    @Test fun `extra warmups beyond expected still complete`() {
        val stats = stats(warmupDone = 3, warmupTotal = 2, normalDone = 4, normalTotal = 4)
        assertEquals(ChipState.COMPLETE, stats.state)
    }

    @Test fun `partial normal sets is IN_PROGRESS`() {
        val stats = stats(warmupDone = 2, warmupTotal = 2, normalDone = 2, normalTotal = 4)
        assertEquals(ChipState.IN_PROGRESS, stats.state)
    }

    @Test fun `warmup done normal not started is IN_PROGRESS`() {
        val stats = stats(warmupDone = 2, warmupTotal = 2, normalDone = 0, normalTotal = 4)
        assertEquals(ChipState.IN_PROGRESS, stats.state)
    }

    @Test fun `warmup partial normal not started is IN_PROGRESS`() {
        val stats = stats(warmupDone = 1, warmupTotal = 2, normalDone = 0, normalTotal = 4)
        assertEquals(ChipState.IN_PROGRESS, stats.state)
    }

    @Test fun `absolutely nothing logged is MISSING`() {
        val stats = stats(warmupDone = 0, warmupTotal = 2, normalDone = 0, normalTotal = 4)
        assertEquals(ChipState.MISSING, stats.state)
    }

    @Test fun `no warmups expected and normal sets complete is COMPLETE`() {
        val stats = stats(warmupDone = 0, warmupTotal = 0, normalDone = 3, normalTotal = 3)
        assertEquals(ChipState.COMPLETE, stats.state)
    }

    @Test fun `label maps as expected`() {
        assertEquals("swap", ChipKind.SWAP.label())
        assertEquals("extra", ChipKind.EXTRA.label())
    }

    private fun stats(
        warmupDone: Int,
        warmupTotal: Int,
        normalDone: Int,
        normalTotal: Int,
    ) = ExerciseChipStats(
        warmupDone = warmupDone,
        warmupTotal = warmupTotal,
        normalDone = normalDone,
        normalTotal = normalTotal,
        weightText = null,
        weightKind = WeightKind.PLAIN,
        kind = null,
    )
}
