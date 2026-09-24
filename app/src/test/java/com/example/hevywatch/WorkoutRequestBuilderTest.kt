package com.example.hevywatch

import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.buildWorkoutPostRequestV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WorkoutRequestBuilderTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun completedSet(
        weightKg: Float? = 60f,
        reps: Int? = 10,
        type: SetType = SetType.NORMAL
    ) = ActiveSet(setType = type, weightKg = weightKg, reps = reps, completed = true)

    private fun incompleteSet() = ActiveSet(completed = false)

    private fun exercise(
        id: String = "ex1",
        title: String = "Bench Press",
        sets: List<ActiveSet> = listOf(completedSet()),
        restTimerSeconds: Int? = 90
    ) = ActiveExercise(
        exerciseTemplateId = id,
        title = title,
        sets = sets,
        restTimerSeconds = restTimerSeconds
    )

    private fun workout(
        name: String = "Push Day",
        startTimeMs: Long = 1_000_000L,
        routineId: String? = "routine-123",
        exercises: List<ActiveExercise> = listOf(exercise())
    ) = ActiveWorkout(
        name = name,
        startTimeMs = startTimeMs,
        routineId = routineId,
        exercises = exercises
    )

    // ── Timestamps ────────────────────────────────────────────────────────────

    @Test
    fun `startTime is unix seconds not milliseconds`() {
        val w = workout(startTimeMs = 1_000_000L)   // 1 000 000 ms = 1 000 s
        val req = buildWorkoutPostRequestV2(w, endTimeMs = 2_000_000L)
        assertEquals(1_000L, req.workout.startTime)
    }

    @Test
    fun `endTime is unix seconds not milliseconds`() {
        val w = workout(startTimeMs = 0L)
        val req = buildWorkoutPostRequestV2(w, endTimeMs = 3_600_000L)   // 1 hour in ms
        assertEquals(3_600L, req.workout.endTime)
    }

    // ── Metadata flags ────────────────────────────────────────────────────────

    @Test
    fun `wearos_watch is true`() {
        val req = buildWorkoutPostRequestV2(workout(), 2_000_000L)
        assertTrue(req.workout.wearosWatch)
    }

    @Test
    fun `apple_watch is false`() {
        val req = buildWorkoutPostRequestV2(workout(), 2_000_000L)
        assertEquals(false, req.workout.appleWatch)
    }

    // ── routineId ─────────────────────────────────────────────────────────────

    @Test
    fun `routineId is passed through`() {
        val req = buildWorkoutPostRequestV2(workout(routineId = "r-abc"), 2_000_000L)
        assertEquals("r-abc", req.workout.routineId)
    }

    @Test
    fun `routineId is null when workout has none`() {
        val req = buildWorkoutPostRequestV2(workout(routineId = null), 2_000_000L)
        assertNull(req.workout.routineId)
    }

    // ── workoutId ─────────────────────────────────────────────────────────────

    @Test
    fun `workoutId is a valid UUID`() {
        val req = buildWorkoutPostRequestV2(workout(), 2_000_000L)
        assertNotNull(UUID.fromString(req.workout.workoutId))   // throws if invalid
    }

    @Test
    fun `each call produces a different workoutId`() {
        val w = workout()
        val id1 = buildWorkoutPostRequestV2(w, 2_000_000L).workout.workoutId
        val id2 = buildWorkoutPostRequestV2(w, 2_000_000L).workout.workoutId
        assertNotEquals(id1, id2)
    }

    // ── Exercise filtering ────────────────────────────────────────────────────

    @Test
    fun `exercises with no completed sets are omitted`() {
        val w = workout(exercises = listOf(
            exercise(id = "ex1", sets = listOf(incompleteSet())),
            exercise(id = "ex2", sets = listOf(completedSet()))
        ))
        val req = buildWorkoutPostRequestV2(w, 2_000_000L)
        assertEquals(1, req.workout.exercises.size)
        assertEquals("ex2", req.workout.exercises[0].exerciseTemplateId)
    }

    @Test
    fun `only completed sets are included within an exercise`() {
        val w = workout(exercises = listOf(
            exercise(sets = listOf(completedSet(), incompleteSet(), completedSet()))
        ))
        val req = buildWorkoutPostRequestV2(w, 2_000_000L)
        assertEquals(2, req.workout.exercises[0].sets.size)
    }

    @Test
    fun `workout with all incomplete sets produces empty exercise list`() {
        val w = workout(exercises = listOf(
            exercise(sets = listOf(incompleteSet(), incompleteSet()))
        ))
        val req = buildWorkoutPostRequestV2(w, 2_000_000L)
        assertTrue(req.workout.exercises.isEmpty())
    }

    // ── Exercise fields ───────────────────────────────────────────────────────

    @Test
    fun `exercise title is included`() {
        val w = workout(exercises = listOf(exercise(title = "Squat")))
        val req = buildWorkoutPostRequestV2(w, 2_000_000L)
        assertEquals("Squat", req.workout.exercises[0].title)
    }

    @Test
    fun `exercise restTimerSeconds is included`() {
        val w = workout(exercises = listOf(exercise(restTimerSeconds = 120)))
        val req = buildWorkoutPostRequestV2(w, 2_000_000L)
        assertEquals(120, req.workout.exercises[0].restTimerSeconds)
    }

    @Test
    fun `null restTimerSeconds is preserved`() {
        val w = workout(exercises = listOf(exercise(restTimerSeconds = null)))
        val req = buildWorkoutPostRequestV2(w, 2_000_000L)
        assertNull(req.workout.exercises[0].restTimerSeconds)
    }

    // ── Set fields ────────────────────────────────────────────────────────────

    @Test
    fun `set type apiValue is used`() {
        val w = workout(exercises = listOf(
            exercise(sets = listOf(completedSet(type = SetType.WARMUP)))
        ))
        val req = buildWorkoutPostRequestV2(w, 2_000_000L)
        assertEquals("warmup", req.workout.exercises[0].sets[0].type)
    }

    @Test
    fun `set weightKg and reps are passed through`() {
        val w = workout(exercises = listOf(
            exercise(sets = listOf(completedSet(weightKg = 100f, reps = 5)))
        ))
        val req = buildWorkoutPostRequestV2(w, 2_000_000L)
        val set = req.workout.exercises[0].sets[0]
        assertEquals(100f, set.weightKg)
        assertEquals(5, set.reps)
    }

    @Test
    fun `workout title is used`() {
        val req = buildWorkoutPostRequestV2(workout(name = "Leg Day"), 2_000_000L)
        assertEquals("Leg Day", req.workout.title)
    }

    // ── is_private regression pin ─────────────────────────────────────────────

    @Test
    fun `is_private is absent from the new-workout POST body`() {
        // Burn scar: a hardcoded `is_private = false` on the save path silently
        // re-published workouts the user had made private (38aa9b4 removed the
        // field entirely; b4b4019's earlier "fix" was inert). The Workout
        // response schema carries no is_private for us to read back, so there
        // is no safe value to send — the field must simply not appear.
        // BuildResumePostRequestV2Test pins the resume path; this pins the
        // new-workout path, which had no equivalent guard.
        val req = buildWorkoutPostRequestV2(workout(), 2_000_000L)
        val json = com.example.hevywatch.util.GsonHolder.gson.toJson(req)
        assertTrue("is_private must not appear in the body: $json", !json.contains("is_private"))
    }

    @Test
    fun `is_biometrics_public defaults to true`() {
        val req = buildWorkoutPostRequestV2(workout(), 2_000_000L)
        assertTrue(req.workout.isBiometricsPublic)
    }

    // ── Biometrics pass-through ───────────────────────────────────────────────

    @Test
    fun `biometrics blob is passed through verbatim`() {
        val bio = com.example.hevywatch.data.api.model.BiometricsBody(
            totalCalories = 412,
            heartRateSamples = listOf(
                com.example.hevywatch.data.api.model.HeartRateSampleBody(
                    bpm = 142.0, timestampMs = 1_700_000_000_000L
                )
            )
        )
        val req = buildWorkoutPostRequestV2(workout(), 2_000_000L, biometrics = bio)
        assertNotNull(req.workout.biometrics)
        assertEquals(412, req.workout.biometrics!!.totalCalories)
        assertEquals(1, req.workout.biometrics!!.heartRateSamples.size)
        assertEquals(142.0, req.workout.biometrics!!.heartRateSamples[0].bpm, 0.001)
    }

    @Test
    fun `biometrics is null when not supplied`() {
        val req = buildWorkoutPostRequestV2(workout(), 2_000_000L)
        assertNull(req.workout.biometrics)
    }

    // ── Exercise-type gating on weight / reps ─────────────────────────────────

    @Test
    fun `reps-only exercise nulls weight but keeps reps`() {
        val ex = ActiveExercise(
            exerciseTemplateId = "ex-bw",
            title = "Push Up",
            exerciseType = com.example.hevywatch.data.model.ExerciseType.REPS_ONLY,
            sets = listOf(completedSet(weightKg = 60f, reps = 12)),
        )
        val req = buildWorkoutPostRequestV2(workout(exercises = listOf(ex)), 2_000_000L)
        val set = req.workout.exercises[0].sets[0]
        assertNull("reps_only must not send a weight", set.weightKg)
        assertEquals(12, set.reps)
    }

    @Test
    fun `duration exercise nulls both weight and reps`() {
        val ex = ActiveExercise(
            exerciseTemplateId = "ex-plank",
            title = "Plank",
            exerciseType = com.example.hevywatch.data.model.ExerciseType.DURATION,
            sets = listOf(completedSet(weightKg = 20f, reps = 5)),
        )
        val req = buildWorkoutPostRequestV2(workout(exercises = listOf(ex)), 2_000_000L)
        val set = req.workout.exercises[0].sets[0]
        assertNull(set.weightKg)
        assertNull(set.reps)
    }

    // ── completedAt formatting ────────────────────────────────────────────────

    @Test
    fun `completedAt is ISO-8601 when the set carries a timestamp`() {
        val ex = exercise(sets = listOf(
            ActiveSet(
                setType = SetType.NORMAL, weightKg = 60f, reps = 10,
                completed = true, completedAtMs = 1_700_000_000_000L,
            )
        ))
        val req = buildWorkoutPostRequestV2(workout(exercises = listOf(ex)), 2_000_000L)
        val completedAt = req.workout.exercises[0].sets[0].completedAt
        assertNotNull("completedAtMs must surface as completed_at", completedAt)
        assertTrue(
            "expected an ISO-8601 instant, got $completedAt",
            completedAt!!.startsWith("2023-11-14T") && completedAt.endsWith("Z")
        )
    }

    @Test
    fun `completedAt is null when the set has no timestamp`() {
        val req = buildWorkoutPostRequestV2(workout(), 2_000_000L)
        assertNull(req.workout.exercises[0].sets[0].completedAt)
    }
}
