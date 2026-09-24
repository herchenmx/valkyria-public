package com.example.hevywatch

import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.ExerciseBest
import com.example.hevywatch.presentation.workout.PrType
import com.example.hevywatch.presentation.workout.computeOneRepMax
import com.example.hevywatch.presentation.workout.detectPrType
import com.example.hevywatch.presentation.workout.findBestPrInWorkout
import com.example.hevywatch.presentation.workout.historyToBest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PrDetectionTest {

    // ── computeOneRepMax ───────────────────────────────────────────────────────

    @Test
    fun `1 rep at bodyweight returns the weight itself`() {
        assertEquals(100f, computeOneRepMax(100f, 1))
    }

    @Test
    fun `null weight returns null`() {
        assertNull(computeOneRepMax(null, 10))
    }

    @Test
    fun `null reps returns null`() {
        assertNull(computeOneRepMax(60f, null))
    }

    @Test
    fun `zero reps returns null`() {
        assertNull(computeOneRepMax(60f, 0))
    }

    @Test
    fun `zero weight returns null`() {
        assertNull(computeOneRepMax(0f, 10))
    }

    @Test
    fun `reps beyond table size returns null`() {
        assertNull(computeOneRepMax(60f, 37))   // table only goes to 36
    }

    @Test
    fun `10 reps at 60kg gives reasonable 1RM estimate`() {
        // Table[9] = 0.730, so 1RM = 60 / 0.730 ≈ 82.2
        val result = computeOneRepMax(60f, 10)!!
        assertTrue(result > 80f && result < 85f, "Expected ~82kg, got $result")
    }

    // ── detectPrType ──────────────────────────────────────────────────────────

    @Test
    fun `ExerciseBest with all nulls treats every positive value as a PR`() {
        // When the ViewModel has never seen this exercise before, it creates ExerciseBest()
        // with all-null fields. The null-safe fallback to 0f means any positive result is a PR.
        // (The real "no history" guard is one level up: checkForPr returns null if the key
        // is absent from exerciseBests, so detectPrType is never called in that case.)
        val best = ExerciseBest()
        assertEquals(PrType.ONE_REP_MAX, detectPrType(best, weightKg = 100f, reps = 10))
    }

    @Test
    fun `new 1RM is detected`() {
        val best = ExerciseBest(bestOneRepMax = 100f, bestWeightKg = 80f, bestReps = 10)
        // 1 rep at 110kg → 1RM = 110, beats previous 100
        val result = detectPrType(best, weightKg = 110f, reps = 1)
        assertEquals(PrType.ONE_REP_MAX, result)
    }

    @Test
    fun `weight PR detected when no 1RM PR`() {
        val best = ExerciseBest(bestOneRepMax = 120f, bestWeightKg = 80f, bestReps = 10)
        // 85kg × 1 → 1RM = 85, does NOT beat 120. But 85 > 80 → weight PR
        val result = detectPrType(best, weightKg = 85f, reps = 1)
        assertEquals(PrType.WEIGHT, result)
    }

    @Test
    fun `reps PR detected when no 1RM or weight PR`() {
        val best = ExerciseBest(bestOneRepMax = null, bestWeightKg = 0f, bestReps = 10)
        // Bodyweight (null weight), 15 reps > 10
        val result = detectPrType(best, weightKg = null, reps = 15)
        assertEquals(PrType.REPS, result)
    }

    @Test
    fun `lower weight and reps returns null`() {
        val best = ExerciseBest(bestOneRepMax = 150f, bestWeightKg = 100f, bestReps = 12)
        val result = detectPrType(best, weightKg = 80f, reps = 8)
        assertNull(result)
    }

    @Test
    fun `same weight as best weight is not a PR`() {
        // Set bestOneRepMax high enough that the computed 1RM won't beat it, then check
        // that matching the weight exactly is also not a weight PR.
        // computeOneRepMax(100f, 5) ≈ 113.6, so bestOneRepMax must exceed that.
        val best = ExerciseBest(bestOneRepMax = 150f, bestWeightKg = 100f, bestReps = 10)
        val result = detectPrType(best, weightKg = 100f, reps = 5)
        assertNull(result)
    }

    @Test
    fun `1RM PR takes priority over weight PR`() {
        // Both could fire, but 1RM should be returned first
        val best = ExerciseBest(bestOneRepMax = 50f, bestWeightKg = 50f, bestReps = 5)
        val result = detectPrType(best, weightKg = 60f, reps = 10)
        assertEquals(PrType.ONE_REP_MAX, result)
    }

    // ── historyToBest ─────────────────────────────────────────────────────────

    @Test
    fun `historyToBest returns null for null history`() {
        assertNull(historyToBest(null))
    }

    @Test
    fun `historyToBest returns null when history is empty`() {
        assertNull(historyToBest(ExerciseHistoryResponse(emptyList())))
    }

    @Test
    fun `historyToBest ignores warmup and dropset entries`() {
        // Warmup at heavier weight than the only normal set — must not crown the warmup.
        val best = historyToBest(ExerciseHistoryResponse(listOf(
            entry(weightKg = 100f, reps = 3, setType = "warmup"),
            entry(weightKg = 60f,  reps = 10, setType = "normal"),
            entry(weightKg = 120f, reps = 2, setType = "dropset")
        )))
        assertNotNull(best)
        assertEquals(60f, best!!.bestWeightKg)
        assertEquals(10, best.bestReps)
    }

    @Test
    fun `historyToBest picks the heaviest weight, most reps, and best 1RM independently`() {
        val best = historyToBest(ExerciseHistoryResponse(listOf(
            entry(weightKg = 80f, reps = 5,  setType = "normal"),   // 1RM ≈ 94.1
            entry(weightKg = 60f, reps = 12, setType = "normal"),   // 1RM ≈ 89.6
            entry(weightKg = 90f, reps = 3,  setType = "normal")    // 1RM ≈ 95.7
        )))!!
        assertEquals(90f, best.bestWeightKg)
        assertEquals(12, best.bestReps)
        assertEquals(true, (best.bestOneRepMax ?: 0f) > 95f)
    }

    // ── findBestPrInWorkout ───────────────────────────────────────────────────

    @Test
    fun `findBestPrInWorkout returns null when prior history is null`() {
        // No prior history → first-ever session; not eligible for a PR.
        val sets = listOf(set(100f, 10, completed = true))
        assertNull(findBestPrInWorkout(best = null, sets = sets))
    }

    @Test
    fun `findBestPrInWorkout returns null when no set beats the snapshot`() {
        val best = ExerciseBest(bestOneRepMax = 150f, bestWeightKg = 100f, bestReps = 15)
        val sets = listOf(
            set(80f, 10, completed = true),
            set(90f, 5,  completed = true)
        )
        assertNull(findBestPrInWorkout(best, sets))
    }

    @Test
    fun `findBestPrInWorkout returns the strongest PR across all sets`() {
        val best = ExerciseBest(bestOneRepMax = 100f, bestWeightKg = 80f, bestReps = 10)
        // First set is a weight PR only (81 > 80), second set hits a 1RM PR.
        val sets = listOf(
            set(81f, 5,  completed = true),   // weight PR: 81 > 80
            set(90f, 3,  completed = true)    // 1RM ≈ 98.9 — no. bump reps to push past 100.
        )
        // Bump reps on the second set so 1RM clears 100.
        val sets2 = listOf(
            set(81f, 5,  completed = true),
            set(95f, 5,  completed = true)    // 1RM ≈ 111.8 → beats 100
        )
        assertEquals(PrType.ONE_REP_MAX, findBestPrInWorkout(best, sets2))
        // Sanity: if we ONLY have the weight-PR set, we should get WEIGHT.
        assertEquals(PrType.WEIGHT, findBestPrInWorkout(best, listOf(set(81f, 5, completed = true))))
    }

    @Test
    fun `findBestPrInWorkout ignores incomplete and non-normal sets`() {
        val best = ExerciseBest(bestOneRepMax = 100f, bestWeightKg = 80f, bestReps = 10)
        val sets = listOf(
            set(200f, 10, completed = false),                     // incomplete — must ignore
            set(200f, 10, completed = true,  type = SetType.WARMUP),  // warmup — must ignore
            set(200f, 10, completed = true,  type = SetType.DROPSET), // dropset — must ignore
            set(85f,  5,  completed = true)                       // only legit set — weight PR
        )
        assertEquals(PrType.WEIGHT, findBestPrInWorkout(best, sets))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertTrue(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private fun entry(weightKg: Float?, reps: Int?, setType: String) = ExerciseHistoryEntry(
        workoutId = "w", workoutTitle = null, workoutStartTime = "t", workoutEndTime = null,
        exerciseTemplateId = "x", weightKg = weightKg, reps = reps,
        distanceMeters = null, durationSeconds = null, rpe = null, customMetric = null,
        setType = setType
    )

    private fun set(
        weightKg: Float?, reps: Int?, completed: Boolean, type: SetType = SetType.NORMAL
    ) = ActiveSet(setType = type, weightKg = weightKg, reps = reps, completed = completed)
}
