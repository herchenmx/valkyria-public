package com.example.hevywatch

import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.computeProgressiveOverload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveOverloadTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun historyEntry(
        workoutId: String,
        setType: String = "normal",
        weightKg: Float?,
        reps: Int?
    ) = ExerciseHistoryEntry(
        workoutId = workoutId,
        workoutTitle = null,
        workoutStartTime = "2026-01-01T00:00:00Z",
        workoutEndTime = null,
        exerciseTemplateId = "t1",
        weightKg = weightKg,
        reps = reps,
        distanceMeters = null,
        durationSeconds = null,
        rpe = null,
        customMetric = null,
        setType = setType
    )

    private fun normalSet(weightKg: Float? = 80f) =
        ActiveSet(setType = SetType.NORMAL, weightKg = weightKg)

    private fun warmupSet(weightKg: Float? = 60f) =
        ActiveSet(setType = SetType.WARMUP, weightKg = weightKg)

    private fun exercise(
        id: String = "t1",
        equipment: String? = "barbell",
        sets: List<ActiveSet> = listOf(normalSet(), normalSet(), normalSet()),
        primaryMuscleGroup: String? = null
    ) = ActiveExercise(
        exerciseTemplateId = id,
        title = "Squat",
        equipment = equipment,
        sets = sets,
        primaryMuscleGroup = primaryMuscleGroup
    )

    // ── Scenario 1: all sets hit top of range ─────────────────────────────────

    @Test
    fun `scenario 1 - uniform weight - barbell uses 1kg universal increment`() {
        val ex = exercise(equipment = "barbell")
        // avg(80,80,80) = 80; all-same shortcut → 80 + 1 = 81.
        val history = listOf(
            historyEntry("w1", weightKg = 80f, reps = 15),
            historyEntry("w1", weightKg = 80f, reps = 15),
            historyEntry("w1", weightKg = 80f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(81f, result[0].sets[0].weightKg)
        assertEquals(81f, result[0].sets[1].weightKg)
        assertTrue("t1 should be in increased set", "t1" in increased)
    }

    @Test
    fun `scenario 1 - ascending sets - uses average floored to increment`() {
        // Leg press (machine): sets at 100, 105, 107
        // avg = 104, floor(104/1)*1 = 104, +1 = 105
        val ex = exercise(equipment = "machine", sets = listOf(normalSet(), normalSet(), normalSet()))
        val history = listOf(
            historyEntry("w1", weightKg = 100f, reps = 15),
            historyEntry("w1", weightKg = 105f, reps = 15),
            historyEntry("w1", weightKg = 107f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(105f, result[0].sets[0].weightKg)
        assertEquals(105f, result[0].sets[1].weightKg)
        assertEquals(105f, result[0].sets[2].weightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `scenario 1 - ascending barbell sets - floors to 1kg`() {
        // Barbell: sets at 30, 35, 37.5
        // avg = 34.17, floor(34.17/1)*1 = 34, +1 = 35
        val ex = exercise(equipment = "barbell", sets = listOf(normalSet(), normalSet(), normalSet()))
        val history = listOf(
            historyEntry("w1", weightKg = 30f, reps = 15),
            historyEntry("w1", weightKg = 35f, reps = 15),
            historyEntry("w1", weightKg = 37.5f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(35f, result[0].sets[0].weightKg)
    }

    @Test
    fun `scenario 1 - ascending barbell sets over 40kg - 1kg increment`() {
        // Barbell: sets at 40, 45, 50
        // avg = 45, floor(45/1)*1 = 45, +1 = 46
        val ex = exercise(equipment = "barbell", sets = listOf(normalSet(), normalSet(), normalSet()))
        val history = listOf(
            historyEntry("w1", weightKg = 40f, reps = 15),
            historyEntry("w1", weightKg = 45f, reps = 15),
            historyEntry("w1", weightKg = 50f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(46f, result[0].sets[0].weightKg)
    }

    @Test
    fun `scenario 1 - dumbbell increment is 1_0kg`() {
        val ex = exercise(equipment = "dumbbell")
        val history = listOf(historyEntry("w1", weightKg = 20f, reps = 15))
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)
        assertEquals(21f, result[0].sets[0].weightKg)
    }

    @Test
    fun `scenario 1 - kettlebell increment is 1_0kg`() {
        val ex = exercise(equipment = "kettlebell")
        val history = listOf(historyEntry("w1", weightKg = 16f, reps = 15))
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)
        assertEquals(17f, result[0].sets[0].weightKg)
    }

    @Test
    fun `scenario 1 - machine increment is 1_0kg`() {
        val ex = exercise(equipment = "machine")
        val history = listOf(historyEntry("w1", weightKg = 50f, reps = 15))
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)
        assertEquals(51f, result[0].sets[0].weightKg)
    }

    @Test
    fun `scenario 1 - poBaseWeightKg is set to floored average`() {
        val ex = exercise(equipment = "machine", sets = listOf(normalSet(), normalSet(), normalSet()))
        val history = listOf(
            historyEntry("w1", weightKg = 100f, reps = 15),
            historyEntry("w1", weightKg = 105f, reps = 15),
            historyEntry("w1", weightKg = 107f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)

        // avg = 104, floored to 1kg = 104
        assertEquals(104f, result[0].sets[0].poBaseWeightKg)
        assertEquals(104f, result[0].sets[1].poBaseWeightKg)
    }

    @Test
    fun `scenario 1 - warmup sets are not increased`() {
        val ex = exercise(
            equipment = "machine",
            sets = listOf(warmupSet(30f), normalSet(50f), normalSet(50f))
        )
        val history = listOf(
            historyEntry("w1", weightKg = 50f, reps = 15),
            historyEntry("w1", weightKg = 50f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)
        assertEquals(30f, result[0].sets[0].weightKg)  // warmup unchanged
        assertEquals(51f, result[0].sets[1].weightKg)   // normal increased
    }

    // ── Scenario 2: most recent failed but older workout succeeded ─────────

    @Test
    fun `scenario 2 - PO from last successful workout avg even if most recent failed`() {
        val ex = exercise(equipment = "barbell")
        // w1 = most recent, failed (12 reps at 80kg)
        // w2 = previous, succeeded (15 reps at 75kg)
        // PO should apply from w2: avg(75,75)=75, all-same shortcut → 75+1=76.
        val history = listOf(
            historyEntry("w1", weightKg = 80f, reps = 12),
            historyEntry("w1", weightKg = 80f, reps = 12),
            historyEntry("w2", weightKg = 75f, reps = 15),
            historyEntry("w2", weightKg = 75f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(76f, result[0].sets[0].weightKg)
        assertEquals(76f, result[0].sets[1].weightKg)
        assertTrue("t1 should be in increased set", "t1" in increased)
        assertEquals(75f, result[0].sets[0].poBaseWeightKg)
    }

    @Test
    fun `scenario 2 - PO from successful workout with ascending weights uses avg`() {
        val ex = exercise(equipment = "machine")
        // w1 = most recent, failed
        // w2 = previous, succeeded with ascending weights: 40, 45, 50
        // avg(40,45,50) = 45, floor(45/1)*1 = 45, +1 = 46
        val history = listOf(
            historyEntry("w1", weightKg = 46f, reps = 10),
            historyEntry("w1", weightKg = 46f, reps = 10),
            historyEntry("w1", weightKg = 46f, reps = 10),
            historyEntry("w2", weightKg = 40f, reps = 15),
            historyEntry("w2", weightKg = 45f, reps = 15),
            historyEntry("w2", weightKg = 50f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(46f, result[0].sets[0].weightKg)
        assertTrue("t1" in increased)
    }

    // ── Scenario 3: drop-then-recover regresses under rolling average ─────

    @Test
    fun `scenario 3 - rolling avg regresses target after a forced drop`() {
        // User did 22.5kg x 3 x 15 reps on a machine in workout w2.
        // Next time they tried to push, the next stack pin (25kg) was too heavy,
        // so they dropped to 21kg x 4 x 15 (qualifying but lower) in workout w1
        // (most recent). The previous heaviest-qualifying rule protected the
        // 22.5kg achievement; the recency-weighted rule does NOT — and because
        // the *drop* is the most recent session, it's weighted heavily.
        //
        // Per-workout means [21 (w1), 22.5 (w2)], recency weights 1:⅓ →
        // (21·1 + 22.5·⅓) / (1 + ⅓) = 28.5/1.333 ≈ 21.375. Floor (1kg) = 21.
        // Target = 22.
        val ex = exercise(equipment = "machine")
        val history = listOf(
            historyEntry("w1", weightKg = 21f, reps = 15),
            historyEntry("w1", weightKg = 21f, reps = 15),
            historyEntry("w1", weightKg = 21f, reps = 15),
            historyEntry("w1", weightKg = 21f, reps = 15),
            historyEntry("w2", weightKg = 22.5f, reps = 15),
            historyEntry("w2", weightKg = 22.5f, reps = 15),
            historyEntry("w2", weightKg = 22.5f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(22f, result[0].sets[0].weightKg)
        assertEquals(21f, result[0].sets[0].poBaseWeightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `scenario 3 - half-kg uniform weight is preserved (not floored down)`() {
        // 12.5kg dumbbell x 15, all sets identical.
        // Old behaviour: floor(12.5/1)*1 = 12, +1 = 13 — drops the half-kg.
        // New behaviour: all-same shortcut → 12.5 + 1 = 13.5kg.
        val ex = exercise(equipment = "dumbbell")
        val history = listOf(historyEntry("w1", weightKg = 12.5f, reps = 15))
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)
        assertEquals(13.5f, result[0].sets[0].weightKg)
        assertEquals(12.5f, result[0].sets[0].poBaseWeightKg)
    }

    @Test
    fun `no successful workout in lookback window - uses most recent weights without PO`() {
        val ex = exercise(equipment = "barbell")
        val history = listOf(
            historyEntry("w1", weightKg = 80f, reps = 10),
            historyEntry("w1", weightKg = 80f, reps = 10)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)
        assertEquals(80f, result[0].sets[0].weightKg)
        assertFalse("t1" in increased)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `exercise with no history is returned unchanged`() {
        val ex = exercise()
        val historyMap = emptyMap<String, ExerciseHistoryResponse>()

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(ex, result[0])
        assertTrue(increased.isEmpty())
    }

    @Test
    fun `exercise with null equipment is skipped`() {
        val ex = exercise(equipment = null)
        val history = listOf(historyEntry("w1", weightKg = 80f, reps = 15))
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(ex, result[0])
        assertTrue(increased.isEmpty())
    }

    @Test
    fun `exercise with no normal sets in history is skipped`() {
        val ex = exercise()
        val history = listOf(historyEntry("w1", setType = "warmup", weightKg = 60f, reps = 5))
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)
        assertEquals(ex, result[0])
    }

    // ── Single-qualifying-workout trigger (no per-muscle threshold) ──────────
    //
    // Under the rolling-average algorithm there is no per-muscle threshold:
    // any one qualifying workout in the lookback window fires PO, and the base
    // is the rolling average of qualifying sets pooled across every qualifying
    // workout. The pre-rolling code gated small muscles on 2-of-5 qualifying
    // workouts to slow their cadence; the rolling average smooths breakthrough
    // sessions naturally, so the gate isn't needed.

    @Test
    fun `biceps with a single qualifying workout triggers PO (no small-muscle gate)`() {
        val ex = exercise(equipment = "dumbbell", primaryMuscleGroup = "biceps")
        val history = listOf(
            historyEntry("w1", weightKg = 12f, reps = 10),  // failed
            historyEntry("w1", weightKg = 12f, reps = 10),
            historyEntry("w1", weightKg = 12f, reps = 10),
            historyEntry("w2", weightKg = 12f, reps = 10),  // failed
            historyEntry("w2", weightKg = 12f, reps = 10),
            historyEntry("w2", weightKg = 12f, reps = 10),
            historyEntry("w3", weightKg = 12f, reps = 15),  // qualifying
            historyEntry("w3", weightKg = 12f, reps = 15),
            historyEntry("w3", weightKg = 12f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        // Pool = 3 qualifying sets at 12kg, all-same → base 12, target 13.
        assertEquals(13f, result[0].sets[0].weightKg)
        assertEquals(12f, result[0].sets[0].poBaseWeightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `triceps two qualifying workouts at different weights - newer session leads`() {
        val ex = exercise(equipment = "dumbbell", primaryMuscleGroup = "triceps")
        // w1 (most recent) failed; w2 (newer qualifying) at 12kg, w3 (older) at 14kg.
        // Per-workout means [12 (w2), 14 (w3)], recency-weighted (newest ×1,
        // older ×1/3): (12·1 + 14·⅓) / (1 + ⅓) = 12.5 → floor 12 → target 13.
        // The *newer* qualifying session (12kg) leads even though it's lighter,
        // so the target tracks recency, not the heaviest week.
        val history = listOf(
            historyEntry("w1", weightKg = 14f, reps = 8),
            historyEntry("w1", weightKg = 14f, reps = 8),
            historyEntry("w1", weightKg = 14f, reps = 8),
            historyEntry("w2", weightKg = 12f, reps = 15),
            historyEntry("w2", weightKg = 12f, reps = 15),
            historyEntry("w2", weightKg = 12f, reps = 15),
            historyEntry("w3", weightKg = 14f, reps = 15),
            historyEntry("w3", weightKg = 14f, reps = 15),
            historyEntry("w3", weightKg = 14f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(13f, result[0].sets[0].weightKg)
        assertEquals(12f, result[0].sets[0].poBaseWeightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `three qualifying workouts at uniform weight triggers all-same shortcut`() {
        // Calves, 3 workouts all at 20kg×15×3. Pool = 9 sets all at 20kg →
        // all-same shortcut keeps the base at 20 exactly, target = 21.
        val ex = exercise(equipment = "dumbbell", primaryMuscleGroup = "calves")
        val history = listOf(
            historyEntry("w1", weightKg = 20f, reps = 15),
            historyEntry("w1", weightKg = 20f, reps = 15),
            historyEntry("w1", weightKg = 20f, reps = 15),
            historyEntry("w2", weightKg = 20f, reps = 15),
            historyEntry("w2", weightKg = 20f, reps = 15),
            historyEntry("w2", weightKg = 20f, reps = 15),
            historyEntry("w3", weightKg = 20f, reps = 15),
            historyEntry("w3", weightKg = 20f, reps = 15),
            historyEntry("w3", weightKg = 20f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(21f, result[0].sets[0].weightKg)
        assertEquals(20f, result[0].sets[0].poBaseWeightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `quadriceps single qualifying workout still triggers PO`() {
        val ex = exercise(equipment = "machine", primaryMuscleGroup = "quadriceps")
        val history = listOf(
            historyEntry("w1", weightKg = 100f, reps = 10),  // failed
            historyEntry("w1", weightKg = 100f, reps = 10),
            historyEntry("w1", weightKg = 100f, reps = 10),
            historyEntry("w2", weightKg = 100f, reps = 10),  // failed
            historyEntry("w2", weightKg = 100f, reps = 10),
            historyEntry("w2", weightKg = 100f, reps = 10),
            historyEntry("w3", weightKg = 100f, reps = 15),  // qualifying
            historyEntry("w3", weightKg = 100f, reps = 15),
            historyEntry("w3", weightKg = 100f, reps = 15)
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(101f, result[0].sets[0].weightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `null muscle group still triggers PO (algorithm is muscle-agnostic)`() {
        val ex = exercise(equipment = "machine")  // primaryMuscleGroup defaults to null
        val history = listOf(
            historyEntry("w1", weightKg = 50f, reps = 10),
            historyEntry("w2", weightKg = 50f, reps = 15)  // single qualifying set
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(51f, result[0].sets[0].weightKg)
        assertTrue("t1" in increased)
    }

    // ── Lookback window (PO_LOOKBACK_WORKOUTS = 3) ───────────────────────────

    @Test
    fun `success at lookback-edge (3rd most recent workout) still triggers PO`() {
        // 3 historical workouts; only the OLDEST one (w3) succeeded. With
        // lookback=3 it's still in the window and qualifies.
        val ex = exercise(equipment = "machine")  // null muscle group → non-small
        val history = listOf(
            historyEntry("w1", weightKg = 50f, reps = 10),
            historyEntry("w2", weightKg = 50f, reps = 10),
            historyEntry("w3", weightKg = 50f, reps = 15)  // qualifying, oldest in window
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        // w3 qualifies, so PO applies. Only qualifying workout → mean 50, +1.
        assertEquals(51f, result[0].sets[0].weightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `success beyond the lookback (4th most recent) does NOT trigger PO`() {
        // 4 historical workouts; the OLDEST (w4) is past the lookback edge
        // and must be ignored. None of w1..w3 qualify → no PO.
        val ex = exercise(equipment = "machine")
        val history = listOf(
            historyEntry("w1", weightKg = 50f, reps = 10),
            historyEntry("w2", weightKg = 50f, reps = 10),
            historyEntry("w3", weightKg = 50f, reps = 10),
            historyEntry("w4", weightKg = 50f, reps = 15)  // qualifying but out of window
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        // w4 doesn't enter the calculation; w1..w3 are all failures → no PO.
        assertEquals(50f, result[0].sets[0].weightKg)
        assertFalse("t1" in increased)
    }

    // ── Muscle-group independence (rolling-avg algorithm has no per-muscle gate) ──
    //
    // Every muscle group runs through the same rolling-average path. These
    // tests pin that the four muscles previously affected by the small-muscle
    // gate (shoulders + abdominals — now in SMALL_GROUPS for warmup purposes;
    // abductors + adductors — formerly in SMALL_GROUPS) all behave identically
    // to any other muscle: a single qualifying workout fires PO.

    @Test
    fun `shoulders with 1 qualifying workout triggers PO`() {
        val ex = exercise(equipment = "machine", primaryMuscleGroup = "shoulders")
        val history = listOf(
            historyEntry("w1", weightKg = 20f, reps = 15),
            historyEntry("w1", weightKg = 20f, reps = 15),
            historyEntry("w1", weightKg = 20f, reps = 15),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(21f, result[0].sets[0].weightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `abdominals with 1 qualifying workout triggers PO`() {
        val ex = exercise(equipment = "machine", primaryMuscleGroup = "abdominals")
        val history = listOf(
            historyEntry("w1", weightKg = 40f, reps = 15),
            historyEntry("w1", weightKg = 40f, reps = 15),
            historyEntry("w1", weightKg = 40f, reps = 15),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(41f, result[0].sets[0].weightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `abductors with 1 qualifying workout triggers PO`() {
        val ex = exercise(equipment = "machine", primaryMuscleGroup = "abductors")
        val history = listOf(
            historyEntry("w1", weightKg = 50f, reps = 15),
            historyEntry("w1", weightKg = 50f, reps = 15),
            historyEntry("w1", weightKg = 50f, reps = 15),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(51f, result[0].sets[0].weightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `adductors with 1 qualifying workout triggers PO`() {
        val ex = exercise(equipment = "machine", primaryMuscleGroup = "adductors")
        val history = listOf(
            historyEntry("w1", weightKg = 45f, reps = 15),
            historyEntry("w1", weightKg = 45f, reps = 15),
            historyEntry("w1", weightKg = 45f, reps = 15),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(46f, result[0].sets[0].weightKg)
        assertTrue("t1" in increased)
    }

    // ── Crash-recovery (B9) ────────────────────────────────────────────────
    //
    // The pipeline reruns on VM init for crash-recovered workouts. Before B9
    // the PO step rewrote *every* normal set with one broadcast target value,
    // which silently overwrote the per-set weights the user had logged before
    // the crash (e.g. "rear delts reverse fly (machine)" — user dropped 21 →
    // 19 → 17.5 as the body refused, then the watch died; on resume + Finish,
    // the API received 21kg for every set). Reps survived because PO never
    // touches them. These tests pin the new behaviour: completed sets are
    // sacred, non-completed sets still pick up the PO target.

    private fun completedNormalSet(weightKg: Float) =
        ActiveSet(setType = SetType.NORMAL, weightKg = weightKg, reps = 15, completed = true)

    @Test
    fun `B9 - PO does not overwrite completed sets on recovery - machine drop pattern`() {
        // Reproduction of the user-reported bug:
        //   - "rear delts reverse fly (machine)" — equipment increment = 1kg
        //   - History: heaviest qualifying = 3×20kg×15 → PO target = 21kg
        //   - Live workout: user did 21, dropped to 19, dropped to 17.5 (all completed)
        //   - Crash → resume → applyProgressiveOverload runs again
        //   - Expected: completed sets keep their actual logged weights.
        val ex = exercise(
            equipment = "machine",
            sets = listOf(
                completedNormalSet(21f),
                completedNormalSet(19f),
                completedNormalSet(17.5f),
            )
        )
        val history = listOf(
            historyEntry("w1", weightKg = 20f, reps = 15),
            historyEntry("w1", weightKg = 20f, reps = 15),
            historyEntry("w1", weightKg = 20f, reps = 15),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals("set 1 weight preserved", 21f, result[0].sets[0].weightKg)
        assertEquals("set 2 weight preserved", 19f, result[0].sets[1].weightKg)
        assertEquals("set 3 weight preserved", 17.5f, result[0].sets[2].weightKg)
        // All sets stay completed too — the copy mustn't unset that flag.
        assertTrue(result[0].sets.all { it.completed })
    }

    @Test
    fun `B9 - PO target still applied to non-completed sets after recovery`() {
        // Partial-progress recovery: set 1 done at user-adjusted weight, sets
        // 2 and 3 untouched. PO target must still fill the pending sets so the
        // user sees a sensible suggestion when they resume.
        val ex = exercise(
            equipment = "machine",
            sets = listOf(
                completedNormalSet(19f),
                normalSet(weightKg = null),
                normalSet(weightKg = null),
            )
        )
        val history = listOf(
            historyEntry("w1", weightKg = 20f, reps = 15),
            historyEntry("w1", weightKg = 20f, reps = 15),
            historyEntry("w1", weightKg = 20f, reps = 15),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals("completed set preserved", 19f, result[0].sets[0].weightKg)
        assertEquals("pending set 2 gets PO target", 21f, result[0].sets[1].weightKg)
        assertEquals("pending set 3 gets PO target", 21f, result[0].sets[2].weightKg)
        assertTrue("template id still flagged as increased", "t1" in increased)
    }

    @Test
    fun `B9 - no-PO branch also preserves completed sets`() {
        // When history has no qualifying workout, the else branch falls back
        // to per-set history weights. That path must also respect completed sets.
        val ex = exercise(
            id = "t1",
            equipment = "machine",
            primaryMuscleGroup = "biceps",
            sets = listOf(
                completedNormalSet(15f),
                completedNormalSet(13f),
            )
        )
        // History has only failed (sub-15-rep) sets — no qualifying workout,
        // so the else branch runs (history per-set fill, no PO target).
        val history = listOf(
            historyEntry("w1", weightKg = 12f, reps = 10),
            historyEntry("w1", weightKg = 12f, reps = 10),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertFalse("t1 should NOT be in increased set", "t1" in increased)
        assertEquals("set 1 weight preserved", 15f, result[0].sets[0].weightKg)
        assertEquals("set 2 weight preserved", 13f, result[0].sets[1].weightKg)
    }

    // ── Rolling-average pooling (cross-workout behaviour) ─────────────────────
    //
    // Tests that explicitly pin the rolling-average semantics: pooling across
    // qualifying workouts, the all-same shortcut spanning the full pool, and
    // the (intentional) loss of drop-protection.

    @Test
    fun `recency-weighted avg across multiple qualifying workouts favours the newest`() {
        // Three qualifying workouts (newest→oldest): w1=24, w2=22, w3=20 (3 sets
        // each). Per-workout means [24, 22, 20], recency weights 9:3:1 →
        // (24·9 + 22·3 + 20·1) / 13 = 302/13 ≈ 23.23. Floor 1kg = 23. Target 24.
        // A flat pool would have averaged 22 → target 23; recency weighting
        // pulls the target toward the newest (heaviest) session.
        val ex = exercise(equipment = "machine")
        val history = listOf(
            historyEntry("w1", weightKg = 24f, reps = 15),
            historyEntry("w1", weightKg = 24f, reps = 15),
            historyEntry("w1", weightKg = 24f, reps = 15),
            historyEntry("w2", weightKg = 22f, reps = 15),
            historyEntry("w2", weightKg = 22f, reps = 15),
            historyEntry("w2", weightKg = 22f, reps = 15),
            historyEntry("w3", weightKg = 20f, reps = 15),
            historyEntry("w3", weightKg = 20f, reps = 15),
            historyEntry("w3", weightKg = 20f, reps = 15),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(24f, result[0].sets[0].weightKg)
        assertEquals(23f, result[0].sets[0].poBaseWeightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `ramp-up sessions no longer drag a rising target down (torso rotation regression)`() {
        // Real FBB62888 history that produced a too-low 34kg under the old flat
        // 5-workout pool. Window (newest→oldest, lookback 3):
        //   w1 (Jun 29): 42.5,42.5,45,45,47.5,47.5  → mean 45
        //   w2 (Jun 23): 42.5 ×6                      → mean 42.5
        //   w3 (Jun 15): 27.5,27.5,35,35,42.5,42.5   → mean 35
        // Recency weights 9:3:1 → (45·9 + 42.5·3 + 35·1) / 13 = 567.5/13 ≈
        // 43.65. Floor 1kg = 43. Target 44 — the older, lighter ramp-up weeks
        // no longer sink the target the way the flat pool did (which gave 34).
        val ex = exercise(equipment = "machine")
        val history = listOf(
            historyEntry("w1", weightKg = 42.5f, reps = 15),
            historyEntry("w1", weightKg = 42.5f, reps = 15),
            historyEntry("w1", weightKg = 45f, reps = 15),
            historyEntry("w1", weightKg = 45f, reps = 15),
            historyEntry("w1", weightKg = 47.5f, reps = 15),
            historyEntry("w1", weightKg = 47.5f, reps = 15),
            historyEntry("w2", weightKg = 42.5f, reps = 15),
            historyEntry("w2", weightKg = 42.5f, reps = 15),
            historyEntry("w2", weightKg = 42.5f, reps = 15),
            historyEntry("w2", weightKg = 42.5f, reps = 15),
            historyEntry("w2", weightKg = 42.5f, reps = 15),
            historyEntry("w2", weightKg = 42.5f, reps = 15),
            historyEntry("w3", weightKg = 27.5f, reps = 15),
            historyEntry("w3", weightKg = 27.5f, reps = 15),
            historyEntry("w3", weightKg = 35f, reps = 15),
            historyEntry("w3", weightKg = 35f, reps = 15),
            historyEntry("w3", weightKg = 42.5f, reps = 15),
            historyEntry("w3", weightKg = 42.5f, reps = 15),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(44f, result[0].sets[0].weightKg)
        assertEquals(43f, result[0].sets[0].poBaseWeightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `rolling avg preserves half-kg when every qualifying set across all workouts matches`() {
        // Two qualifying workouts at 22.5kg×3 each → pool of 6 sets all at
        // 22.5. All-same shortcut applies across the entire pool → base 22.5,
        // target 23.5 (not floored down to 23).
        val ex = exercise(equipment = "machine")
        val history = listOf(
            historyEntry("w1", weightKg = 22.5f, reps = 15),
            historyEntry("w1", weightKg = 22.5f, reps = 15),
            historyEntry("w1", weightKg = 22.5f, reps = 15),
            historyEntry("w2", weightKg = 22.5f, reps = 15),
            historyEntry("w2", weightKg = 22.5f, reps = 15),
            historyEntry("w2", weightKg = 22.5f, reps = 15),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(23.5f, result[0].sets[0].weightKg)
        assertEquals(22.5f, result[0].sets[0].poBaseWeightKg)
    }

    @Test
    fun `recent breakthrough leads but older workouts still damp it`() {
        // Most recent workout was a 30kg PR×3×15. Older qualifying workouts
        // (w2..w5) were all at 20kg×3×15, but only w2 and w3 fall inside the
        // 3-workout lookback (w4, w5 are dropped). Per-workout means [30, 20,
        // 20], recency weights 9:3:1 → (30·9 + 20·3 + 20·1) / 13 = 350/13 ≈
        // 26.92. Floor 1kg = 26. Target 27. The recent PR leads (well above a
        // flat pool's 23) but the two older 20kg weeks still hold it below the
        // pure "heaviest + 1" = 31.
        val ex = exercise(equipment = "machine")
        val history = listOf(
            historyEntry("w1", weightKg = 30f, reps = 15),
            historyEntry("w1", weightKg = 30f, reps = 15),
            historyEntry("w1", weightKg = 30f, reps = 15),
        ) + (2..5).flatMap { wid ->
            listOf(
                historyEntry("w$wid", weightKg = 20f, reps = 15),
                historyEntry("w$wid", weightKg = 20f, reps = 15),
                historyEntry("w$wid", weightKg = 20f, reps = 15),
            )
        }
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, increased) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(27f, result[0].sets[0].weightKg)
        assertTrue("t1" in increased)
    }

    @Test
    fun `failed sets in a qualifying workout are excluded from the rolling avg`() {
        // One workout: 3 qualifying sets at 25 kg + 1 breakthrough set at 30 kg×8
        // (failed — below the 15-rep bar). The 30 kg×8 must NOT enter the
        // average — qualifying-set filter pre-pools.
        // Pool = 3 sets at 25 → all-same → base 25, target 26.
        val ex = exercise(equipment = "machine")
        val history = listOf(
            historyEntry("w1", weightKg = 25f, reps = 15),
            historyEntry("w1", weightKg = 25f, reps = 15),
            historyEntry("w1", weightKg = 25f, reps = 15),
            historyEntry("w1", weightKg = 30f, reps = 8),
        )
        val historyMap = mapOf("t1" to ExerciseHistoryResponse(history))

        val (result, _) = computeProgressiveOverload(listOf(ex), historyMap)

        assertEquals(26f, result[0].sets[0].weightKg)
        assertEquals(25f, result[0].sets[0].poBaseWeightKg)
    }

    @Test
    fun `multiple exercises each get independent overload decisions`() {
        val ex1 = exercise(id = "t1", equipment = "machine")
        val ex2 = ActiveExercise(
            exerciseTemplateId = "t2", title = "Deadlift", equipment = "barbell",
            sets = listOf(normalSet(100f))
        )
        val historyMap = mapOf(
            "t1" to ExerciseHistoryResponse(listOf(historyEntry("w1", weightKg = 80f, reps = 15))),
            "t2" to ExerciseHistoryResponse(listOf(historyEntry("w1", weightKg = 100f, reps = 10)))
        )

        val (result, increased) = computeProgressiveOverload(listOf(ex1, ex2), historyMap)

        assertTrue("t1 should be increased", "t1" in increased)
        assertFalse("t2 should NOT be increased", "t2" in increased)
        assertEquals(81f, result[0].sets[0].weightKg)   // machine: 80+1
        assertEquals(100f, result[1].sets[0].weightKg)   // t2 unchanged
    }
}
