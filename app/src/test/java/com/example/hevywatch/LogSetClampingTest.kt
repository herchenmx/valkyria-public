package com.example.hevywatch

import com.example.hevywatch.presentation.workout.MAX_REPS
import com.example.hevywatch.presentation.workout.MAX_WEIGHT_KG
import com.example.hevywatch.presentation.workout.clampReps
import com.example.hevywatch.presentation.workout.clampWeightKg
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S4 — every weight/reps input path on LogSetScreen runs through [clampReps]
 * and [clampWeightKg]. The clamps prevent stuck-button counter overflow and
 * NaN propagation from a malformed manual entry, both of which would otherwise
 * end up serialized into a workout POST body.
 */
class LogSetClampingTest {

    @Test fun `reps stay in 0 to MAX_REPS`() {
        assertEquals(0, clampReps(-5))
        assertEquals(0, clampReps(0))
        assertEquals(42, clampReps(42))
        assertEquals(MAX_REPS, clampReps(MAX_REPS))
        assertEquals(MAX_REPS, clampReps(MAX_REPS + 1))
        assertEquals(MAX_REPS, clampReps(Int.MAX_VALUE))
    }

    @Test fun `weight stays in 0 to MAX_WEIGHT_KG`() {
        assertEquals(0f, clampWeightKg(-12.5f), 0.0001f)
        assertEquals(0f, clampWeightKg(0f), 0.0001f)
        assertEquals(72.5f, clampWeightKg(72.5f), 0.0001f)
        assertEquals(MAX_WEIGHT_KG, clampWeightKg(MAX_WEIGHT_KG), 0.0001f)
        assertEquals(MAX_WEIGHT_KG, clampWeightKg(MAX_WEIGHT_KG + 1f), 0.0001f)
        assertEquals(MAX_WEIGHT_KG, clampWeightKg(Float.MAX_VALUE), 0.0001f)
    }

    @Test fun `weight NaN clamps to zero`() {
        // Prevents a malformed manual numpad entry from poisoning the workout
        // POST body downstream.
        assertEquals(0f, clampWeightKg(Float.NaN), 0.0001f)
    }

    @Test fun `weight Infinity clamps to zero`() {
        assertEquals(0f, clampWeightKg(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(0f, clampWeightKg(Float.NEGATIVE_INFINITY), 0.0001f)
    }
}
