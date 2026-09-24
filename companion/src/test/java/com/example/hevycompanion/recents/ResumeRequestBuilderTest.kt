package com.example.hevycompanion.recents

import com.example.hevycompanion.data.BiometricsBody
import com.example.hevycompanion.data.HeartRateSampleBody
import com.example.hevycompanion.data.WorkoutDetail
import com.example.hevycompanion.data.WorkoutDetailExercise
import com.example.hevycompanion.data.WorkoutDetailExerciseV2
import com.example.hevycompanion.data.WorkoutDetailResponseV2
import com.example.hevycompanion.data.WorkoutDetailSet
import com.example.hevycompanion.data.WorkoutDetailSetV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pins the resume merge for both submission paths: original sets preserved
 * verbatim, newly logged sets appended, brand-new exercises appended. Mirrors
 * the watch's `buildResumePostRequestV2` (primary) + `buildWorkoutPutRequest`
 * (fallback).
 */
class ResumeRequestBuilderTest {

    private fun newEx(templateId: String, vararg sets: ResumeNewSet) =
        ResumeNewExercise(templateId, templateId, sets.toList())

    // ── primary: POST /v2/workout ────────────────────────────────────────────

    private fun setV2(indicator: String, weight: Float?, reps: Int?) =
        WorkoutDetailSetV2(
            index = 0, indicator = indicator, weightKg = weight, reps = reps,
            distanceMeters = null, durationSeconds = null, customMetric = null, rpe = null,
            completedAt = "2026-05-09T10:30:00.000Z",
        )

    private fun exV2(templateId: String, vararg sets: WorkoutDetailSetV2) =
        WorkoutDetailExerciseV2(
            title = templateId, notes = null, exerciseTemplateId = templateId,
            supersetId = null, restSeconds = 90, sets = sets.toList(),
        )

    private fun workoutV2(biometrics: BiometricsBody?, vararg exercises: WorkoutDetailExerciseV2) =
        WorkoutDetailResponseV2(
            id = "w1", name = "Lower A", description = "desc",
            startTime = 1_700_000_000L, endTime = 1_700_002_700L,
            isPrivate = false, isBiometricsPublic = true, wearosWatch = true,
            biometrics = biometrics, exercises = exercises.toList(),
        )

    @Test fun `v2 merge appends new sets, keeps originals, passes biometrics through`() {
        val bio = BiometricsBody(totalCalories = 320, heartRateSamples = listOf(HeartRateSampleBody(120.0, 1_700_000_100L)))
        val original = workoutV2(bio, exV2("X", setV2("warmup", 10f, 10), setV2("normal", 20f, 15)))

        val body = ResumeRequestBuilder.buildPostV2(
            original = original,
            routineId = "r1",
            newExercises = listOf(newEx("X", ResumeNewSet("normal", 30f, 15))),
            nowIso = "2026-05-09T11:00:00.000Z",
        ).workout

        val x = body.exercises.single { it.exerciseTemplateId == "X" }
        assertEquals(3, x.sets.size)                 // 2 original + 1 new
        assertEquals(20f, x.sets[1].weightKg)        // original preserved
        assertEquals(30f, x.sets[2].weightKg)        // new appended
        assertEquals(2, x.sets[2].index)             // index continues after originals
        assertSame(bio, body.biometrics)             // original biometrics passed through
        assertEquals(1_700_000_000L, body.startTime) // recorded window preserved
        assertEquals("r1", body.routineId)
        assertNotNull(body.workoutId)                // fresh UUID minted
    }

    @Test fun `v2 new-set completed_at is clamped to the original end, not wall-clock now`() {
        // Resuming days after the original session: the phone stamps new sets at
        // `now`, but Hevy derives the workout duration from the latest set's
        // completed_at. Clamping to the recorded end keeps the duration sane.
        val original = workoutV2(null, exV2("X", setV2("normal", 20f, 15))) // endTime 1_700_002_700
        val body = ResumeRequestBuilder.buildPostV2(
            original = original,
            routineId = "r1",
            newExercises = listOf(newEx("X", ResumeNewSet("normal", 30f, 15))),
            nowIso = "2026-05-09T11:00:00.000Z", // long after the original window
        ).workout

        val expectedIso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(1_700_002_700L))
        val newSet = body.exercises.single { it.exerciseTemplateId == "X" }.sets.last()
        assertEquals(expectedIso, newSet.completedAt)
        assertEquals(1_700_002_700L, body.endTime) // window preserved
    }

    @Test fun `v2 merge appends a brand-new exercise`() {
        val original = workoutV2(null, exV2("X", setV2("normal", 20f, 15)))
        val body = ResumeRequestBuilder.buildPostV2(
            original = original,
            routineId = "r1",
            newExercises = listOf(newEx("Y", ResumeNewSet("normal", 40f, 12))),
            nowIso = "2026-05-09T11:00:00.000Z",
        ).workout

        assertEquals(listOf("X", "Y"), body.exercises.map { it.exerciseTemplateId })
        assertEquals(40f, body.exercises.single { it.exerciseTemplateId == "Y" }.sets[0].weightKg)
    }

    // ── fallback: PUT /v1/workouts/{id} ──────────────────────────────────────

    private fun loggedSet(type: String, weight: Float?, reps: Int?) =
        WorkoutDetailSet(type = type, weightKg = weight, reps = reps)

    private fun ex(templateId: String, vararg sets: WorkoutDetailSet) =
        WorkoutDetailExercise(exerciseTemplateId = templateId, title = templateId, sets = sets.toList())

    private fun workout(vararg exercises: WorkoutDetailExercise) = WorkoutDetail(
        id = "w1", title = "Lower A", description = "desc",
        routineId = "r1", startTime = "2026-05-09T10:00:00Z", endTime = "2026-05-09T10:45:00Z",
        exercises = exercises.toList(),
    )

    @Test fun `v1 PUT merge appends new sets and preserves originals + window`() {
        val original = workout(ex("X", loggedSet("warmup", 10f, 10), loggedSet("normal", 20f, 15)))
        val body = ResumeRequestBuilder.buildPutV1(
            original = original,
            newExercises = listOf(newEx("X", ResumeNewSet("normal", 30f, 15))),
            nowIso = "2026-05-09T11:00:00Z",
        ).workout

        val x = body.exercises.single { it.exerciseTemplateId == "X" }
        assertEquals(3, x.sets.size)
        assertEquals(30f, x.sets[2].weightKg)
        assertEquals("2026-05-09T10:00:00Z", body.startTime)
        assertEquals("2026-05-09T10:45:00Z", body.endTime)
    }

    @Test fun `v1 PUT appends a brand-new exercise and ignores empty ones`() {
        val original = workout(ex("X", loggedSet("normal", 20f, 15)))
        val body = ResumeRequestBuilder.buildPutV1(
            original = original,
            newExercises = listOf(newEx("Y", ResumeNewSet("normal", 40f, 12)), newEx("Z")),
            nowIso = "2026-05-09T11:00:00Z",
        ).workout

        assertEquals(listOf("X", "Y"), body.exercises.map { it.exerciseTemplateId })
    }

    // ── is_private preservation (a workout marked private must stay private
    //    across a resume, on both submission paths) ────────────────────────────

    @Test fun `v1 PUT omits is_private entirely`() {
        val req = ResumeRequestBuilder.buildPutV1(
            original = workout(ex("X", loggedSet("normal", 20f, 15))),
            newExercises = listOf(newEx("X", ResumeNewSet("normal", 30f, 15))),
            nowIso = "2026-05-09T11:00:00Z",
        )
        // serializeNulls = false mirrors the resume PUT's real Gson config.
        val json = com.google.gson.Gson().toJson(req)
        assertFalse("is_private must not appear in the body: $json", json.contains("is_private"))
    }

    @Test fun `v1 PUT omits is_private even when the original reports it private`() {
        val original = WorkoutDetail(
            id = "w1", title = "Lower A", description = "desc", routineId = "r1",
            startTime = "2026-05-09T10:00:00Z", endTime = "2026-05-09T10:45:00Z",
            isPrivate = true,
            exercises = listOf(ex("X", loggedSet("normal", 20f, 15))),
        )
        val req = ResumeRequestBuilder.buildPutV1(
            original = original,
            newExercises = listOf(newEx("X", ResumeNewSet("normal", 30f, 15))),
            nowIso = "2026-05-09T11:00:00Z",
        )
        val json = com.google.gson.Gson().toJson(req)
        assertFalse("is_private must not appear in the body: $json", json.contains("is_private"))
    }

    @Test fun `v2 POST omits is_private entirely`() {
        val original = WorkoutDetailResponseV2(
            id = "w1", name = "Lower A", description = "desc",
            startTime = 1_700_000_000L, endTime = 1_700_002_700L,
            isPrivate = true, isBiometricsPublic = true, wearosWatch = true,
            biometrics = null, exercises = listOf(exV2("X", setV2("normal", 20f, 15))),
        )
        val req = ResumeRequestBuilder.buildPostV2(
            original = original,
            routineId = "r1",
            newExercises = listOf(newEx("X", ResumeNewSet("normal", 30f, 15))),
            nowIso = "2026-05-09T11:00:00.000Z",
        )
        // serializeNulls = true mirrors the private client's real Gson config;
        // the key must be gone from the model, not merely null.
        val json = com.google.gson.GsonBuilder().serializeNulls().create().toJson(req)
        assertFalse("is_private must not appear in the body: $json", json.contains("is_private"))
    }
}
