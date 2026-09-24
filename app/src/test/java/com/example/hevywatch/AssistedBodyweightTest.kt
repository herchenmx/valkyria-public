package com.example.hevywatch

import com.example.hevycore.exercise.AssistedBodyweight
import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.api.model.WorkoutExerciseResponse
import com.example.hevywatch.data.api.model.WorkoutSetResponse
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.ExerciseBest
import com.example.hevywatch.presentation.workout.PrType
import com.example.hevywatch.presentation.workout.computeProgressiveOverload
import com.example.hevywatch.presentation.workout.findBestPrInWorkout
import com.example.hevywatch.presentation.workout.historyToBest
import com.example.hevywatch.presentation.workout.suggestWarmupSets
import com.example.hevywatch.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistedBodyweightTest {

    private val ASSISTED_ID = "2C37EC5E"   // chest dip (assisted)
    private val OTHER_ID = "ZZZZZZZZ"
    private val ASSISTED_ROUTINE = AssistedBodyweight.ROUTINE_ID
    private val BW = 57f

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test
    fun `four template ids are recognised as assisted`() {
        assertTrue(AssistedBodyweight.isAssisted("2C37EC5E"))
        assertTrue(AssistedBodyweight.isAssisted("4B4BF8C2"))
        assertTrue(AssistedBodyweight.isAssisted("D23C609B"))
        assertTrue(AssistedBodyweight.isAssisted("E9E4089F"))
    }

    @Test
    fun `non-assisted ids and null are not assisted`() {
        assertTrue(!AssistedBodyweight.isAssisted("ABCDEF12"))
        assertTrue(!AssistedBodyweight.isAssisted(null))
    }

    @Test
    fun `effective conversion uses bodyweight minus logged kg, floored at 0`() {
        assertEquals(30f, AssistedBodyweight.toEffective(27f, 57f))
        assertEquals(0f, AssistedBodyweight.toEffective(60f, 57f))   // floor at 0
        assertEquals(57f, AssistedBodyweight.toEffective(0f, 57f))   // full bodyweight
    }

    // ── WarmupAdvisor inversion ───────────────────────────────────────────────

    @Test
    fun `warmup for assisted exercise produces logged kg HIGHER than target`() {
        // target=27kg logged, bw=57 → effective=30. Lats (large) + machine + effort 30
        // → 2 warmup sets via the 30-50 bracket: 50%/70% of 30 = 15/21 effort,
        // converted back to logged: 57-15=42, 57-21=36.
        val sets = suggestWarmupSets(
            primaryMuscleGroup = "lats",
            equipment = "machine",
            workingWeightKg = 27f,
            normalSetCount = 3,
            exerciseTemplateId = ASSISTED_ID,
            bodyweightKg = BW
        )
        assertEquals(2, sets.size)
        assertEquals(42f, sets[0].weightKg)
        assertEquals(36f, sets[1].weightKg)
        // All warmups must be strictly higher than target (more assistance = easier).
        sets.forEach { assertTrue("$it should be > 27kg", (it.weightKg ?: 0f) > 27f) }
        // And strictly lower than full bodyweight.
        sets.forEach { assertTrue("$it should be < 57kg", (it.weightKg ?: 0f) < BW) }
    }

    @Test
    fun `warmup for non-assisted exercise is unchanged by bodyweight`() {
        // Lats / machine / 60kg working = 3 warmup sets at 40%/60%/80% = 24/36/48.
        val sets = suggestWarmupSets(
            primaryMuscleGroup = "lats",
            equipment = "machine",
            workingWeightKg = 60f,
            normalSetCount = 3,
            exerciseTemplateId = OTHER_ID,
            bodyweightKg = BW
        )
        assertEquals(3, sets.size)
        assertEquals(24f, sets[0].weightKg)
        assertEquals(36f, sets[1].weightKg)
        assertEquals(48f, sets[2].weightKg)
    }

    // ── ProgressiveOverload inversion ─────────────────────────────────────────

    @Test
    fun `PO trigger fires from session with 3+ sets at 15 reps, ignoring breakthrough set`() {
        // User did 3 sets @ 27kg/15 + 1 breakthrough set @ 23kg/4 reps yesterday.
        // For non-assisted, qualifying avg = 27, +1 increment (machine) = 28kg.
        val ex = ActiveExercise(
            exerciseTemplateId = OTHER_ID, title = "X", equipment = "machine",
            sets = listOf(ActiveSet(setType = SetType.NORMAL, weightKg = 0f))
        )
        val history = listOf(
            entry("w1", 27f, 15), entry("w1", 27f, 15), entry("w1", 27f, 15),
            entry("w1", 23f, 4)   // breakthrough — must not pollute
        )
        val (result, increased) = computeProgressiveOverload(
            listOf(ex), mapOf(OTHER_ID to ExerciseHistoryResponse(history))
        )
        assertEquals(28f, result[0].sets[0].weightKg)
        assertTrue(OTHER_ID in increased)
    }

    @Test
    fun `PO for assisted exercise progresses by SUBTRACTING increment in logged space`() {
        // Yesterday's exact scenario: target 27kg, 3 sets at 27/15 + 1 set at 23/4.
        // Avg of qualifying (27,27,27) → effective 30,30,30 → avg 30. floor(30/1)=30. +1=31 effective.
        // Convert back: bw - 31 = 26kg logged. So next time PO should suggest 26kg.
        val ex = ActiveExercise(
            exerciseTemplateId = ASSISTED_ID, title = "Chest Dip", equipment = "machine",
            sets = listOf(ActiveSet(setType = SetType.NORMAL, weightKg = 0f))
        )
        val history = listOf(
            entry("w1", 27f, 15), entry("w1", 27f, 15), entry("w1", 27f, 15),
            entry("w1", 23f, 4)
        )
        val (result, increased) = computeProgressiveOverload(
            listOf(ex), mapOf(ASSISTED_ID to ExerciseHistoryResponse(history)),
            bodyweightKg = BW
        )
        assertEquals(26f, result[0].sets[0].weightKg)
        assertTrue(ASSISTED_ID in increased)
    }

    @Test
    fun `PO for assisted exercise floors at 0 logged kg`() {
        // 3 qualifying sets at 0kg logged (= effective 57 = full bodyweight). PO would
        // suggest negative if unclamped; coerce to 0kg.
        val ex = ActiveExercise(
            exerciseTemplateId = ASSISTED_ID, title = "Pull-up", equipment = "machine",
            sets = listOf(ActiveSet(setType = SetType.NORMAL, weightKg = 0f))
        )
        val history = listOf(entry("w1", 0f, 15), entry("w1", 0f, 15), entry("w1", 0f, 15))
        val (result, _) = computeProgressiveOverload(
            listOf(ex), mapOf(ASSISTED_ID to ExerciseHistoryResponse(history)),
            bodyweightKg = BW
        )
        assertTrue("logged kg should be ≥ 0", (result[0].sets[0].weightKg ?: -1f) >= 0f)
    }

    // ── RoutineProgressComputer inversion ─────────────────────────────────────

    @Test
    fun `routine progress volume for assisted routine sums effective work`() {
        // Yesterday: warmup 36kg×15×1 + normal 27kg×15×3 + extra 23kg×4×1.
        // Effective volume: 21*15 + 30*15*3 + 34*4 = 315 + 1350 + 136 = 1801.
        val workout = workoutOf(
            ASSISTED_ID,
            setOf(36f, 15, "warmup"),
            setOf(27f, 15, "normal"), setOf(27f, 15, "normal"), setOf(27f, 15, "normal"),
            setOf(23f, 4,  "normal")
        )
        val volEffective = RoutineProgressComputer.workoutVolume(
            workout, routineId = ASSISTED_ROUTINE, bodyweightKg = BW
        )
        assertEquals(1801.0, volEffective, 0.5)

        // Same workout under a non-assisted routine: raw sum = 540 + 1215 + 92 = 1847.
        val volLogged = RoutineProgressComputer.workoutVolume(
            workout, routineId = "some-other-routine", bodyweightKg = BW
        )
        assertEquals(1847.0, volLogged, 0.5)
    }

    @Test
    fun `routine progress in assisted routine ignores non-assisted exercises`() {
        // Same routine but the only exercise is non-assisted — must NOT invert.
        val workout = workoutOf(OTHER_ID, setOf(60f, 10, "normal"))
        val v = RoutineProgressComputer.workoutVolume(
            workout, routineId = ASSISTED_ROUTINE, bodyweightKg = BW
        )
        assertEquals(600.0, v, 0.5)
    }

    // ── FormatUtils.formatVolume inversion ────────────────────────────────────

    @Test
    fun `formatVolume in assisted routine uses effective work for the 4 exercises`() {
        val ex = ActiveExercise(
            exerciseTemplateId = ASSISTED_ID, title = "Chest Dip", equipment = "machine",
            sets = listOf(
                ActiveSet(setType = SetType.NORMAL, weightKg = 27f, reps = 15, completed = true),
                ActiveSet(setType = SetType.NORMAL, weightKg = 27f, reps = 15, completed = true),
                ActiveSet(setType = SetType.NORMAL, weightKg = 27f, reps = 15, completed = true)
            )
        )
        // effective per set: (57-27)*15 = 450; total = 1350.
        assertEquals(
            "1350 kg",
            FormatUtils.formatVolume(listOf(ex), routineId = ASSISTED_ROUTINE, bodyweightKg = BW)
        )
        // In a non-assisted routine, falls back to the raw sum: 27*15*3 = 1215.
        assertEquals(
            "1215 kg",
            FormatUtils.formatVolume(listOf(ex), routineId = "other", bodyweightKg = BW)
        )
    }

    // ── PrDetector inversion ─────────────────────────────────────────────────

    @Test
    fun `historyToBest for assisted exercise tracks lowest logged as best effective`() {
        val history = ExerciseHistoryResponse(listOf(
            entry("w1", 30f, 12),  // effective 27
            entry("w1", 27f, 12),  // effective 30
            entry("w2", 23f, 10)   // effective 34 ← best
        ))
        val best = historyToBest(history, exerciseTemplateId = ASSISTED_ID, bodyweightKg = BW)
        assertNotNull(best)
        assertEquals(34f, best!!.bestWeightKg)
    }

    @Test
    fun `findBestPrInWorkout in assisted exercise crowns the lower-logged set`() {
        // Prior best effective = 30 (i.e. 27kg logged). Current set 24kg logged → effective
        // 33 → that's a Weight PR.
        val best = ExerciseBest(bestOneRepMax = 1000f, bestWeightKg = 30f, bestReps = 99)
        val sets = listOf(
            ActiveSet(setType = SetType.NORMAL, weightKg = 24f, reps = 10, completed = true)
        )
        val pr = findBestPrInWorkout(best, sets, exerciseTemplateId = ASSISTED_ID, bodyweightKg = BW)
        assertEquals(PrType.WEIGHT, pr)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun entry(workoutId: String, weightKg: Float, reps: Int) = ExerciseHistoryEntry(
        workoutId = workoutId,
        workoutTitle = null, workoutStartTime = "2026-01-01T00:00:00Z", workoutEndTime = null,
        exerciseTemplateId = "x", weightKg = weightKg, reps = reps,
        distanceMeters = null, durationSeconds = null, rpe = null, customMetric = null,
        setType = "normal"
    )

    /** Triple of (weight, reps, type) for a single set when building a workout fixture. */
    private fun setOf(weight: Float?, reps: Int?, type: String) = Triple(weight, reps, type)

    private fun workoutOf(
        templateId: String,
        vararg sets: Triple<Float?, Int?, String>
    ): WorkoutDetailResponse {
        val setResponses = sets.mapIndexed { i, s ->
            WorkoutSetResponse(
                index = i, type = s.third, weightKg = s.first, reps = s.second,
                distanceMeters = null, durationSeconds = null
            )
        }
        return WorkoutDetailResponse(
            id = "w1", title = "T", description = null, routineId = null,
            startTime = "2026-04-27T10:00:00Z", endTime = null,
            exercises = listOf(
                WorkoutExerciseResponse(
                    index = 0, title = "X", exerciseTemplateId = templateId,
                    sets = setResponses
                )
            )
        )
    }
}
