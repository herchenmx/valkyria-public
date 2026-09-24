package com.example.hevywatch

import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.api.model.WorkoutExerciseResponse
import com.example.hevywatch.data.api.model.WorkoutSetResponse
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.buildWorkoutPutRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests the PUT builder for resumed / continued workouts.
 *
 * Contract:
 *  - start_time, end_time, title, description, is_private are preserved
 *    verbatim from the original GET response. The resumed workout keeps the
 *    original session's exact window so re-finishing an incomplete workout
 *    days later can't stretch its duration (Hevy's displayed duration is
 *    end_time − start_time). `endTimeMs` / `active.startTimeMs` are NOT used
 *    for the window — the resume timer is display-only. This matches the v2
 *    POST primary (buildResumePostRequestV2) and the companion's buildPutV1.
 *  - Exercises are merged: original sets preserved verbatim, newly-completed
 *    sets appended.
 *
 * (An earlier revision recomputed end_time as
 * `originalStart + (endTimeMs − active.startTimeMs)`. That inflated the
 * duration to wall-clock hours/days on the v1 PUT fallback whenever
 * active.startTimeMs was stale — e.g. a resume finished in a later session —
 * producing 90h workouts. Reverted to preserving the original window.)
 */
class WorkoutPutRequestBuilderTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun getSet(
        index: Int = 0,
        type: String = "normal",
        weightKg: Float? = 60f,
        reps: Int? = 10
    ) = WorkoutSetResponse(
        index = index,
        type = type,
        weightKg = weightKg,
        reps = reps,
        distanceMeters = null,
        durationSeconds = null
    )

    private fun getExercise(
        templateId: String = "ex1",
        title: String = "Bench Press",
        sets: List<WorkoutSetResponse> = listOf(getSet())
    ) = WorkoutExerciseResponse(
        index = 0,
        title = title,
        exerciseTemplateId = templateId,
        sets = sets
    )

    private fun getDetail(
        workoutId: String = "w-1",
        startTime: String = "2026-04-17T10:00:00Z",
        endTime: String? = "2026-04-17T10:45:00Z",
        title: String? = "Push Day",
        description: String? = null,
        isPrivate: Boolean? = false,
        exercises: List<WorkoutExerciseResponse> = listOf(getExercise())
    ) = WorkoutDetailResponse(
        id = workoutId,
        title = title,
        description = description,
        routineId = "r-1",
        startTime = startTime,
        endTime = endTime,
        isPrivate = isPrivate,
        exercises = exercises
    )

    private fun activeSet(completed: Boolean = true, weightKg: Float? = 60f, reps: Int? = 10) =
        ActiveSet(setType = SetType.NORMAL, weightKg = weightKg, reps = reps, completed = completed)

    private fun activeExercise(
        templateId: String = "ex1",
        sets: List<ActiveSet> = listOf(activeSet())
    ) = ActiveExercise(
        exerciseTemplateId = templateId,
        title = "Bench Press",
        sets = sets
    )

    private fun active(
        exercises: List<ActiveExercise> = listOf(activeExercise()),
        continuingId: String? = "w-1"
    ) = ActiveWorkout(
        name = "Push Day",
        startTimeMs = 1_700_000_000_000L,
        routineId = "r-1",
        continuingWorkoutId = continuingId,
        exercises = exercises
    )

    // ── end_time preserves the original window verbatim ─────────────────────

    @Test
    fun `end_time is preserved verbatim from the original workout`() {
        val req = buildWorkoutPutRequest(
            getDetail(startTime = "2026-04-17T10:00:00Z", endTime = "2026-04-17T10:45:00Z"),
            active(),
            endTimeMs = 1_715_000_000_000L
        )
        assertEquals("2026-04-17T10:45:00Z", req.workout.endTime)
    }

    @Test
    fun `stale startTimeMs finished days later does not inflate end_time (90h regression)`() {
        // The bug: original started Jul 6, resume finished Jul 10. The old
        // recompute (endTimeMs − active.startTimeMs) spanned the whole 90h gap
        // and produced end_time = Jul 10, a 90-hour workout. Now the original
        // [start, end] window is preserved regardless of the stale timestamps.
        val originalStartIso = "2026-07-06T18:50:42Z"
        val originalEndIso = "2026-07-06T19:30:42Z" // 40-min session
        val jul6StartMs = java.time.Instant.parse(originalStartIso).toEpochMilli()
        val jul10FinishMs = java.time.Instant.parse("2026-07-10T13:20:09Z").toEpochMilli()

        val req = buildWorkoutPutRequest(
            getDetail(startTime = originalStartIso, endTime = originalEndIso),
            active().copy(startTimeMs = jul6StartMs),
            endTimeMs = jul10FinishMs
        )

        assertEquals(originalStartIso, req.workout.startTime)
        assertEquals(originalEndIso, req.workout.endTime)
    }

    @Test
    fun `end_time falls back to start_time when the original has no end`() {
        val req = buildWorkoutPutRequest(
            getDetail(startTime = "2026-04-17T10:00:00Z", endTime = null),
            active(),
            endTimeMs = 1_715_000_000_000L
        )
        assertEquals("2026-04-17T10:00:00Z", req.workout.endTime)
    }

    @Test
    fun `start_time from the original workout is preserved`() {
        val original = getDetail(startTime = "2026-04-17T10:00:00Z")
        val req = buildWorkoutPutRequest(original, active(), endTimeMs = 1_713_600_000_000L)
        assertEquals("2026-04-17T10:00:00Z", req.workout.startTime)
    }

    @Test
    fun `title from original is preserved when present`() {
        val original = getDetail(title = "Custom Push Title")
        val req = buildWorkoutPutRequest(original, active(), endTimeMs = 0L)
        assertEquals("Custom Push Title", req.workout.title)
    }

    @Test
    fun `title falls back to ActiveWorkout name when original title is null`() {
        val original = getDetail(title = null)
        val req = buildWorkoutPutRequest(original, active(), endTimeMs = 0L)
        assertEquals("Push Day", req.workout.title)
    }

    @Test
    fun `description from original is preserved when non-blank`() {
        val original = getDetail(description = "Felt strong today")
        val req = buildWorkoutPutRequest(original, active(), endTimeMs = 0L)
        assertEquals("Felt strong today", req.workout.description)
    }

    @Test
    fun `blank description collapses to null`() {
        val original = getDetail(description = "   ")
        val req = buildWorkoutPutRequest(original, active(), endTimeMs = 0L)
        assertEquals(null, req.workout.description)
    }

    // ── is_private is never sent on a resume ────────────────────────────────
    // `is_private` is absent from the API's Workout response schema, so a
    // resume can't read back the original's visibility — any value we send is
    // a guess. The old builder guessed `false`, which force-published every
    // private workout that took the v1-PUT resume path. The key must now be
    // absent from the wire entirely so the server keeps what it has stored.

    @Test
    fun `is_private is absent from the serialized PUT body`() {
        val req = buildWorkoutPutRequest(getDetail(), active(), endTimeMs = 0L)
        val json = com.google.gson.Gson().toJson(req)
        assertFalse("is_private must not appear in the body: $json", json.contains("is_private"))
    }

    @Test
    fun `is_private stays absent even when the original reports it private`() {
        // Defensive: if the API ever starts returning is_private on the v1 GET,
        // the PUT body must still not carry a guess unless the builder is
        // deliberately taught to forward it.
        val req = buildWorkoutPutRequest(getDetail(isPrivate = true), active(), endTimeMs = 0L)
        val json = com.google.gson.Gson().toJson(req)
        assertFalse("is_private must not appear in the body: $json", json.contains("is_private"))
    }

    // ── Original sets are preserved as-is when user only "resumed" ──────────

    @Test
    fun `original sets are kept verbatim when user adds no new sets`() {
        val original = getDetail(
            exercises = listOf(
                getExercise(sets = listOf(
                    getSet(index = 0, weightKg = 80f, reps = 5),
                    getSet(index = 1, weightKg = 80f, reps = 5)
                ))
            )
        )
        // ActiveWorkout has only the original 2 sets, still marked completed
        // (locked from the resume flow). No new sets dropped.
        val activeExercises = listOf(activeExercise(sets = listOf(
            activeSet(weightKg = 80f, reps = 5),
            activeSet(weightKg = 80f, reps = 5)
        )))

        val req = buildWorkoutPutRequest(original, active(activeExercises), endTimeMs = 0L)

        assertEquals(1, req.workout.exercises.size)
        val sets = req.workout.exercises[0].sets
        assertEquals(2, sets.size)
        assertEquals(80f, sets[0].weightKg)
        assertEquals(5, sets[0].reps)
    }

    @Test
    fun `newly completed sets are appended after original sets`() {
        // Original had 1 set; user resumed and completed 2 more.
        val original = getDetail(
            exercises = listOf(
                getExercise(sets = listOf(getSet(index = 0, weightKg = 80f, reps = 5)))
            )
        )
        val activeExercises = listOf(activeExercise(sets = listOf(
            activeSet(weightKg = 80f, reps = 5),      // the original set (locked)
            activeSet(weightKg = 82.5f, reps = 5),    // new
            activeSet(weightKg = 82.5f, reps = 4)     // new
        )))

        val req = buildWorkoutPutRequest(original, active(activeExercises), endTimeMs = 0L)

        val sets = req.workout.exercises[0].sets
        assertEquals(3, sets.size)
        assertEquals(80f, sets[0].weightKg)
        assertEquals(82.5f, sets[1].weightKg)
        assertEquals(82.5f, sets[2].weightKg)
        assertEquals(4, sets[2].reps)
    }

    @Test
    fun `exercises missing from the original are appended when completed`() {
        val original = getDetail(
            exercises = listOf(
                getExercise(templateId = "ex1", sets = listOf(getSet()))
            )
        )
        // User resumed and added an entirely different exercise (ex2).
        val activeExercises = listOf(
            activeExercise(templateId = "ex1"),
            activeExercise(templateId = "ex2", sets = listOf(activeSet(weightKg = 30f, reps = 15)))
        )

        val req = buildWorkoutPutRequest(original, active(activeExercises), endTimeMs = 0L)

        assertEquals(2, req.workout.exercises.size)
        val appended = req.workout.exercises.first { it.exerciseTemplateId == "ex2" }
        assertEquals(1, appended.sets.size)
        assertEquals(30f, appended.sets[0].weightKg)
    }

    @Test
    fun `incomplete new sets are dropped when appending to existing exercise`() {
        val original = getDetail(
            exercises = listOf(
                getExercise(sets = listOf(getSet(index = 0, weightKg = 80f, reps = 5)))
            )
        )
        // 1 original + 1 completed new + 1 uncompleted new
        val activeExercises = listOf(activeExercise(sets = listOf(
            activeSet(),                                 // original locked
            activeSet(weightKg = 82.5f, reps = 5),       // new & completed
            activeSet(completed = false)                 // new but not completed → drop
        )))

        val req = buildWorkoutPutRequest(original, active(activeExercises), endTimeMs = 0L)
        val sets = req.workout.exercises[0].sets
        assertEquals(2, sets.size) // 1 original + 1 new completed; uncompleted dropped
    }
}
