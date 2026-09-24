package com.example.hevywatch

import com.example.hevywatch.data.api.model.BiometricsBody
import com.example.hevywatch.data.api.model.HeartRateSampleBody
import com.example.hevywatch.data.api.model.WorkoutDetailExerciseV2
import com.example.hevywatch.data.api.model.WorkoutDetailResponseV2
import com.example.hevywatch.data.api.model.WorkoutDetailSetV2
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.buildResumePostRequestV2
import com.example.hevywatch.presentation.workout.combineHeartRateSamples
import com.example.hevywatch.data.model.HeartRateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pins the resume POST builder. The contract:
 *  - end_time = original.start_time + (endTimeMs − active.startTimeMs) / 1000
 *  - original exercises preserved verbatim, new completed sets appended
 *  - exercises absent from original but present in active are added
 *  - workout_id is a fresh UUID (the new workout is a distinct record)
 *  - wearos_watch = true, is_biometrics_public defaults to original's value
 *    (true if originally true)
 *  - biometrics blob is whatever the caller passes (we don't compute here)
 */
class BuildResumePostRequestV2Test {

    private fun originalSet(idx: Int = 0, indicator: String = "normal", weight: Float? = 60f, reps: Int? = 5) =
        WorkoutDetailSetV2(
            id = "set-$idx", index = idx, indicator = indicator,
            weightKg = weight, reps = reps,
            distanceMeters = null, durationSeconds = null,
            customMetric = null, rpe = null, completedAt = null
        )

    private fun originalEx(templateId: String = "ex1", title: String = "Bench", sets: List<WorkoutDetailSetV2> = listOf(originalSet())) =
        WorkoutDetailExerciseV2(
            id = "orig-ex", title = title, notes = null,
            exerciseTemplateId = templateId, supersetId = null, restSeconds = null,
            sets = sets
        )

    private fun originalDetail(
        startSec: Long = 1_715_000_000L,
        endSec: Long = 1_715_000_000L + 45L * 60L,
        biometrics: BiometricsBody? = null,
        exercises: List<WorkoutDetailExerciseV2> = listOf(originalEx())
    ) = WorkoutDetailResponseV2(
        id = "w-original",
        shortId = "abc",
        name = "Push Day",
        description = null,
        startTime = startSec,
        endTime = endSec,
        isPrivate = false,
        isBiometricsPublic = true,
        wearosWatch = true,
        appleWatch = false,
        biometrics = biometrics,
        exercises = exercises
    )

    private fun activeSet(completed: Boolean = true, weight: Float? = 60f, reps: Int? = 5) =
        ActiveSet(setType = SetType.NORMAL, weightKg = weight, reps = reps, completed = completed)

    private fun activeEx(templateId: String = "ex1", sets: List<ActiveSet> = listOf(activeSet())) =
        ActiveExercise(exerciseTemplateId = templateId, title = "Bench", sets = sets)

    private fun active(
        startMs: Long = 1_715_000_000_000L,
        exercises: List<ActiveExercise> = listOf(activeEx())
    ) = ActiveWorkout(
        name = "Push Day", startTimeMs = startMs,
        routineId = "r-1", continuingWorkoutId = "w-original",
        exercises = exercises
    )

    @Test
    fun `start_time and end_time inherit the original window verbatim`() {
        // Resuming keeps the original session's exact [start, end] so re-finishing
        // an incomplete workout days later can't stretch its duration. The live
        // resume timer (endTimeMs / adjusted start) is display-only now.
        val originalStartSec = 1_715_000_000L
        val originalEndSec = originalStartSec + 45L * 60L
        val endTimeMs = 1_716_000_000_000L // "now" — days after the original
        val adjustedStartMs = endTimeMs - 65L * 60_000L

        val req = buildResumePostRequestV2(
            original = originalDetail(startSec = originalStartSec, endSec = originalEndSec),
            active = active(startMs = adjustedStartMs),
            endTimeMs = endTimeMs,
            combinedBiometrics = null
        )
        assertEquals(originalStartSec, req.workout.startTime)
        assertEquals(originalEndSec, req.workout.endTime)
    }

    @Test
    fun `new-set completed_at is clamped to the original end, never wall-clock now`() {
        // A set logged during the resumed segment carries a real "now" timestamp
        // days after the original session. Hevy derives duration from the latest
        // set's completed_at, so it must be pulled back inside the window.
        val originalStartSec = 1_715_000_000L
        val originalEndSec = originalStartSec + 45L * 60L
        val nowMs = (originalEndSec + 2L * 24 * 3600) * 1000L // 2 days later
        val origSets = listOf(originalSet(0))
        // 1 locked original + 1 brand-new set completed "now".
        val newAct = active(exercises = listOf(activeEx(sets = listOf(
            activeSet(),
            ActiveSet(setType = SetType.NORMAL, weightKg = 65f, reps = 5, completed = true, completedAtMs = nowMs)
        ))))
        val req = buildResumePostRequestV2(
            original = originalDetail(startSec = originalStartSec, endSec = originalEndSec,
                exercises = listOf(originalEx(sets = origSets))),
            active = newAct,
            endTimeMs = nowMs,
            combinedBiometrics = null
        )
        val newSet = req.workout.exercises[0].sets.last()
        val expectedIso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(originalEndSec))
        assertEquals(expectedIso, newSet.completedAt)
    }

    @Test
    fun `original sets preserved verbatim when active has no new sets beyond locked ones`() {
        // Original has 3 sets. Active workout mirrors all 3 as completed-and-
        // locked (the Continue flow does this). The merge should emit exactly
        // 3 sets, not 6.
        val origSets = listOf(originalSet(0), originalSet(1), originalSet(2))
        val lockedActive = active(exercises = listOf(activeEx(sets = listOf(activeSet(), activeSet(), activeSet()))))
        val req = buildResumePostRequestV2(
            original = originalDetail(exercises = listOf(originalEx(sets = origSets))),
            active = lockedActive,
            endTimeMs = 1_716_000_000_000L,
            combinedBiometrics = null
        )
        assertEquals(1, req.workout.exercises.size)
        assertEquals(3, req.workout.exercises[0].sets.size)
    }

    @Test
    fun `new sets appended after locked original sets`() {
        // Original 3 sets, active 5 (3 locked + 2 new completed).
        val origSets = listOf(originalSet(0), originalSet(1), originalSet(2))
        val newAct = active(exercises = listOf(activeEx(sets = listOf(
            activeSet(), activeSet(), activeSet(),
            activeSet(weight = 65f), activeSet(weight = 70f)
        ))))
        val req = buildResumePostRequestV2(
            original = originalDetail(exercises = listOf(originalEx(sets = origSets))),
            active = newAct,
            endTimeMs = 1_716_000_000_000L,
            combinedBiometrics = null
        )
        assertEquals(1, req.workout.exercises.size)
        val sets = req.workout.exercises[0].sets
        assertEquals(5, sets.size)
        // Originals carried their weight (60f); new ones the new weights.
        assertEquals(60f, sets[0].weightKg!!, 0.001f)
        assertEquals(65f, sets[3].weightKg!!, 0.001f)
        assertEquals(70f, sets[4].weightKg!!, 0.001f)
    }

    @Test
    fun `exercises only in active workout get added`() {
        val req = buildResumePostRequestV2(
            original = originalDetail(exercises = listOf(originalEx(templateId = "ex1"))),
            active = active(exercises = listOf(
                activeEx(templateId = "ex1"),
                activeEx(templateId = "ex2-new")
            )),
            endTimeMs = 1_716_000_000_000L,
            combinedBiometrics = null
        )
        assertEquals(2, req.workout.exercises.size)
        assertEquals(setOf("ex1", "ex2-new"),
            req.workout.exercises.map { it.exerciseTemplateId }.toSet())
    }

    @Test
    fun `workout_id is fresh — never equal to the original id`() {
        val req = buildResumePostRequestV2(
            original = originalDetail(),
            active = active(),
            endTimeMs = 1_716_000_000_000L,
            combinedBiometrics = null
        )
        assertNotNull(req.workout.workoutId)
        assertNotEquals("w-original", req.workout.workoutId)
    }

    @Test
    fun `is_private is absent from the resume POST body`() {
        // The resume POST creates a replacement workout record, and the
        // Workout response schema carries no is_private for us to inherit —
        // so the key is omitted and the account's default visibility applies.
        // It used to be hardcoded `false`, publishing private workouts.
        val req = buildResumePostRequestV2(
            original = originalDetail().copy(isPrivate = true),
            active = active(),
            endTimeMs = 1_716_000_000_000L,
            combinedBiometrics = null
        )
        val json = com.google.gson.GsonBuilder().serializeNulls().create().toJson(req)
        assertFalse("is_private must not appear in the body: $json", json.contains("is_private"))
    }

    @Test
    fun `wearos_watch is true and is_biometrics_public inherits original`() {
        val req = buildResumePostRequestV2(
            original = originalDetail().copy(isBiometricsPublic = true),
            active = active(),
            endTimeMs = 1_716_000_000_000L,
            combinedBiometrics = null
        )
        assertTrue(req.workout.wearosWatch)
        assertTrue(req.workout.isBiometricsPublic)
    }

    @Test
    fun `combinedBiometrics is forwarded into the body unchanged`() {
        val bm = BiometricsBody(
            totalCalories = 42,
            heartRateSamples = listOf(HeartRateSampleBody(99.0, 1_000_000L))
        )
        val req = buildResumePostRequestV2(
            original = originalDetail(),
            active = active(),
            endTimeMs = 1_716_000_000_000L,
            combinedBiometrics = bm
        )
        assertEquals(bm, req.workout.biometrics)
    }

    @Test
    fun `combineHeartRateSamples orders chronologically`() {
        val original = listOf(
            HeartRateSampleBody(80.0, 100_000L),
            HeartRateSampleBody(90.0, 200_000L)
        )
        val new = listOf(
            HeartRateSample(bpm = 110.0, timestamp_ms = 50_000L),     // earlier
            HeartRateSample(bpm = 120.0, timestamp_ms = 300_000L)     // later
        )
        val merged = combineHeartRateSamples(original, new)
        assertEquals(listOf(50_000L, 100_000L, 200_000L, 300_000L), merged.map { it.timestampMs })
    }
}
