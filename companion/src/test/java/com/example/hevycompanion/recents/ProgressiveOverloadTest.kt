package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the companion's progressive-overload port against the watch's documented
 * examples (PRD-WATCH-APP.md §"Progressive Overload"). History is the flat,
 * newest-first list `GET /v1/exercise_history/{id}` returns.
 */
class ProgressiveOverloadTest {

    private fun set(workoutId: String, weight: Float, reps: Int, type: String = "normal") =
        ExerciseHistoryEntry(workoutId = workoutId, weightKg = weight, reps = reps, setType = type)

    @Test fun `rolling-average bump from a single variable-weight workout`() {
        // 100 / 105 / 107 × 15 → avg 104, floored, +1 = 105 (PRD example).
        val history = listOf(
            set("w1", 100f, 15), set("w1", 105f, 15), set("w1", 107f, 15),
        )
        val po = ProgressiveOverload.compute(history, hasEquipment = true, exerciseTemplateId = "LEGPRESS", bodyweightKg = 57f)
        assertTrue(po.increased)
        assertEquals(105f, po.targetKg!!, 0.001f)
        assertEquals(104f, po.baseKg!!, 0.001f)
    }

    @Test fun `all-same shortcut spans the whole pool, preserving half-increments`() {
        // Two qualifying sessions each 22.5 × 15 × 3 → pool all 22.5 → 23.5.
        val history = listOf(
            set("w2", 22.5f, 15), set("w2", 22.5f, 15), set("w2", 22.5f, 15),
            set("w1", 22.5f, 15), set("w1", 22.5f, 15), set("w1", 22.5f, 15),
        )
        val po = ProgressiveOverload.compute(history, hasEquipment = true, exerciseTemplateId = "X", bodyweightKg = 57f)
        assertTrue(po.increased)
        assertEquals(23.5f, po.targetKg!!, 0.001f)
        assertEquals(22.5f, po.baseKg!!, 0.001f)
    }

    @Test fun `recency-weighted average favours the newest qualifying session`() {
        // Real FBB62888 window (newest→oldest, lookback 3): means 45 / 42.5 / 35.
        // Recency weights 9:3:1 → (45·9 + 42.5·3 + 35·1) / 13 ≈ 43.65 → floor
        // 43 → target 44. The old flat pool gave 34 (dragged down by ramp-up).
        val history = listOf(
            set("w1", 42.5f, 15), set("w1", 42.5f, 15), set("w1", 45f, 15),
            set("w1", 45f, 15), set("w1", 47.5f, 15), set("w1", 47.5f, 15),
            set("w2", 42.5f, 15), set("w2", 42.5f, 15), set("w2", 42.5f, 15),
            set("w2", 42.5f, 15), set("w2", 42.5f, 15), set("w2", 42.5f, 15),
            set("w3", 27.5f, 15), set("w3", 27.5f, 15), set("w3", 35f, 15),
            set("w3", 35f, 15), set("w3", 42.5f, 15), set("w3", 42.5f, 15),
        )
        val po = ProgressiveOverload.compute(history, hasEquipment = true, exerciseTemplateId = "FBB62888", bodyweightKg = 57f)
        assertTrue(po.increased)
        assertEquals(44f, po.targetKg!!, 0.001f)
        assertEquals(43f, po.baseKg!!, 0.001f)
    }

    @Test fun `no qualifying workout carries the most recent weight forward, no bump`() {
        // Most recent session is sub-rep-floor (10 reps), so no PO — carry 50kg.
        val history = listOf(
            set("w1", 50f, 10), set("w1", 50f, 10), set("w1", 50f, 10),
        )
        val po = ProgressiveOverload.compute(history, hasEquipment = true, exerciseTemplateId = "X", bodyweightKg = 57f)
        assertFalse(po.increased)
        assertEquals(50f, po.targetKg!!, 0.001f)
        assertNull(po.baseKg)
    }

    @Test fun `assisted exercise progresses by lowering logged kg`() {
        // Assisted chin-up: logged 10kg assist × 15 × 3, bodyweight 57 → effort
        // 47, target effort 48 → target logged 57 − 48 = 9 (less assist = harder).
        val history = listOf(
            set("w1", 10f, 15), set("w1", 10f, 15), set("w1", 10f, 15),
        )
        val po = ProgressiveOverload.compute(history, hasEquipment = true, exerciseTemplateId = "E9E4089F", bodyweightKg = 57f)
        assertTrue(po.increased)
        assertEquals(9f, po.targetKg!!, 0.001f)
        assertEquals(10f, po.baseKg!!, 0.001f)
    }

    @Test fun `bodyweight exercise (no equipment) gets no PO`() {
        val history = listOf(set("w1", 0f, 15), set("w1", 0f, 15), set("w1", 0f, 15))
        val po = ProgressiveOverload.compute(history, hasEquipment = false, exerciseTemplateId = "X", bodyweightKg = 57f)
        assertNull(po.targetKg)
        assertFalse(po.increased)
    }

    @Test fun `empty history yields no target`() {
        val po = ProgressiveOverload.compute(emptyList(), hasEquipment = true, exerciseTemplateId = "X", bodyweightKg = 57f)
        assertEquals(PoOutcome.NONE, po)
    }
}
