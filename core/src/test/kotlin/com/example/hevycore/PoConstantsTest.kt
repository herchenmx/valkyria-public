package com.example.hevycore

import com.example.hevycore.workout.PoConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class PoConstantsTest {

    @Test
    fun `single data point returns itself`() {
        assertEquals(42.5f, PoConstants.recencyWeightedMean(listOf(42.5f)), 0.001f)
    }

    @Test
    fun `empty input returns zero`() {
        assertEquals(0f, PoConstants.recencyWeightedMean(emptyList()), 0.001f)
    }

    @Test
    fun `newest data point dominates via geometric decay`() {
        // Weights 9:3:1 over three workouts (newest first): the torso-rotation
        // window that motivated the recency weighting.
        // (45·9 + 42.5·3 + 35·1) / 13 = 567.5 / 13 ≈ 43.654.
        val avg = PoConstants.recencyWeightedMean(listOf(45f, 42.5f, 35f))
        assertEquals(43.654f, avg, 0.01f)
    }

    @Test
    fun `all-equal points collapse to that value regardless of count`() {
        assertEquals(20f, PoConstants.recencyWeightedMean(listOf(20f, 20f, 20f)), 0.001f)
    }

    @Test
    fun `increment kg is 1_0 across every equipment cohort`() {
        // Was `PoIncrement.forEquipment(equipment)` on the watch side before
        // the constant was collapsed to `INCREMENT_KG`. Kept as an explicit
        // pin so a future re-introduction of per-equipment steps has to
        // consciously delete this test.
        assertEquals(1.0f, PoConstants.INCREMENT_KG, 0f)
    }

    @Test
    fun `decay factor gives the documented 9 to 3 to 1 weighting`() {
        // Pin the ratio: with points [1, 0, 0] the result is the newest weight
        // share = 9/13; with [0, 1, 0] it is 3/13; with [0, 0, 1] it is 1/13.
        assertEquals(9f / 13f, PoConstants.recencyWeightedMean(listOf(1f, 0f, 0f)), 0.001f)
        assertEquals(3f / 13f, PoConstants.recencyWeightedMean(listOf(0f, 1f, 0f)), 0.001f)
        assertEquals(1f / 13f, PoConstants.recencyWeightedMean(listOf(0f, 0f, 1f)), 0.001f)
    }
}
