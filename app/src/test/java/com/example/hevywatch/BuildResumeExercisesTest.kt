package com.example.hevywatch

import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.api.model.WorkoutExerciseResponse
import com.example.hevywatch.data.api.model.WorkoutSetResponse
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.WorkoutDetailViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resume reconstruction must match what was actually logged — including a
 * swapped-in substitute — against the routine, so resuming an incomplete
 * workout continues the *swapped* exercise rather than re-prescribing the
 * original (the bug). Mirrors the substitution passes in
 * `buildCompletionStatuses` that already drive Workout Detail.
 */
class BuildResumeExercisesTest {

    // Real SubstitutionMap members of the "Glutes" group:
    private val DEADLIFT = "C6272009" // Deadlift (Barbell) — prescribed slot
    private val RDL = "2B4B7310"      // Romanian Deadlift (Barbell) — swapped in
    private val BENCH = "BENCH_NOGRP" // not in any substitution group

    private val PO_FOLDER = "po-folder"
    private val NOW = 1_700_000_000_000L

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun routineSet(weight: Float = 100f, reps: Int = 5) =
        ActiveSet(setType = SetType.NORMAL, weightKg = weight, reps = reps, completed = false)

    private fun routineSlot(templateId: String, title: String, normalSets: Int = 3) =
        ActiveExercise(
            exerciseTemplateId = templateId,
            title = title,
            sets = List(normalSets) { routineSet() },
        )

    private fun activeWorkout(vararg slots: ActiveExercise) =
        ActiveWorkout(name = "Leg Day", startTimeMs = NOW, routineId = "r-1", exercises = slots.toList())

    private fun loggedSet(weight: Float = 80f, reps: Int = 8, type: String = "normal") =
        WorkoutSetResponse(index = 0, type = type, weightKg = weight, reps = reps, distanceMeters = null, durationSeconds = null)

    private fun loggedExercise(templateId: String, title: String, sets: List<WorkoutSetResponse>) =
        WorkoutExerciseResponse(index = 0, title = title, exerciseTemplateId = templateId, sets = sets)

    private fun detail(vararg exercises: WorkoutExerciseResponse) =
        WorkoutDetailResponse(
            id = "w-1",
            title = "Leg Day",
            description = null,
            routineId = "r-1",
            startTime = "2026-04-17T10:00:00Z",
            endTime = "2026-04-17T10:30:00Z",
            isPrivate = false,
            exercises = exercises.toList(),
        )

    private fun build(
        detail: WorkoutDetailResponse,
        active: ActiveWorkout,
        poFolderIds: Set<String> = setOf(PO_FOLDER),
        routineFolderId: String? = PO_FOLDER,
    ) = WorkoutDetailViewModel.buildResumeExercises(detail, active, poFolderIds, routineFolderId, NOW)

    // ── the bug: a partially-done swap resumes as the swapped exercise ────────

    @Test
    fun `partially-logged swap resumes as the swapped exercise, not the routine one`() {
        // Routine prescribes Deadlift; user swapped to RDL and logged 1 of 3 sets.
        val active = activeWorkout(
            routineSlot(DEADLIFT, "Deadlift (Barbell)", normalSets = 3),
            routineSlot(BENCH, "Bench Press", normalSets = 3),
        )
        val logged = detail(
            loggedExercise(RDL, "Romanian Deadlift (Barbell)", listOf(loggedSet(weight = 90f, reps = 6))),
        )

        val result = build(logged, active)

        // Slot 0 resumes RDL, not Deadlift.
        val first = result[0]
        assertEquals(RDL, first.exerciseTemplateId)
        assertEquals("Romanian Deadlift (Barbell)", first.title)
        // 1 recorded (locked/completed) + 2 remaining (toward the prescribed 3).
        assertEquals(3, first.sets.size)
        assertEquals(1, first.sets.count { it.completed })
        assertTrue(first.sets[0].locked)
        assertEquals(90f, first.sets[0].weightKg)
        assertEquals(2, first.sets.count { !it.completed })
        assertFalse(first.sets.last().locked)
    }

    @Test
    fun `warmup-only swap resumes as the swapped exercise with its warmups preserved`() {
        // Routine prescribes Deadlift; user swapped to RDL and logged only the 3
        // warmups (no normal sets yet). Resume must continue RDL — keeping the 3
        // locked warmups — not re-prescribe Deadlift and drop the logged warmups.
        val active = activeWorkout(routineSlot(DEADLIFT, "Deadlift (Barbell)", normalSets = 3))
        val logged = detail(
            loggedExercise(RDL, "Romanian Deadlift (Barbell)", List(3) { loggedSet(weight = 40f, reps = 10, type = "warmup") }),
        )

        val ex = build(logged, active)[0]
        assertEquals(RDL, ex.exerciseTemplateId)
        assertEquals("Romanian Deadlift (Barbell)", ex.title)
        assertTrue(ex.wasSwapped)
        // 3 locked warmups preserved + 3 fresh normal sets toward the prescription.
        assertEquals(3, ex.sets.count { it.completed && it.locked && it.setType == SetType.WARMUP })
        assertEquals(3, ex.sets.count { !it.completed && it.setType == SetType.NORMAL })
    }

    @Test
    fun `not-started routine slot stays the prescribed exercise`() {
        val active = activeWorkout(
            routineSlot(DEADLIFT, "Deadlift (Barbell)"),
            routineSlot(BENCH, "Bench Press"),
        )
        // Only RDL logged → Bench slot has nothing.
        val logged = detail(loggedExercise(RDL, "Romanian Deadlift (Barbell)", listOf(loggedSet())))

        val bench = build(logged, active)[1]
        assertEquals(BENCH, bench.exerciseTemplateId)
        assertEquals("Bench Press", bench.title)
        assertTrue("nothing logged → all sets still to do", bench.sets.none { it.completed })
    }

    @Test
    fun `exact template match still resumes that exercise (no swap)`() {
        val active = activeWorkout(routineSlot(DEADLIFT, "Deadlift (Barbell)", normalSets = 3))
        val logged = detail(
            loggedExercise(DEADLIFT, "Deadlift (Barbell)", listOf(loggedSet(weight = 100f, reps = 5), loggedSet(weight = 100f, reps = 5))),
        )

        val ex = build(logged, active)[0]
        assertEquals(DEADLIFT, ex.exerciseTemplateId)
        assertEquals(2, ex.sets.count { it.completed && it.locked })
        assertEquals(1, ex.sets.count { !it.completed }) // 3 prescribed − 2 done
    }

    @Test
    fun `substitution is disabled outside PO folders`() {
        val active = activeWorkout(routineSlot(DEADLIFT, "Deadlift (Barbell)"))
        val logged = detail(loggedExercise(RDL, "Romanian Deadlift (Barbell)", listOf(loggedSet())))

        // Routine not in a PO folder → no substitution; slot stays prescribed.
        val ex = build(logged, active, poFolderIds = emptySet(), routineFolderId = "other")[0]
        assertEquals(DEADLIFT, ex.exerciseTemplateId)
        assertTrue(ex.sets.none { it.completed })
    }

    @Test
    fun `exact match wins over a substitute for the same slot`() {
        // Both Deadlift (exact) and RDL (group) logged. The slot must take its
        // exact match; the substitute is left for extras (not surfaced here).
        val active = activeWorkout(routineSlot(DEADLIFT, "Deadlift (Barbell)"))
        val logged = detail(
            loggedExercise(RDL, "Romanian Deadlift (Barbell)", listOf(loggedSet())),
            loggedExercise(DEADLIFT, "Deadlift (Barbell)", listOf(loggedSet(weight = 120f, reps = 5))),
        )

        val ex = build(logged, active)[0]
        assertEquals(DEADLIFT, ex.exerciseTemplateId)
        assertEquals(120f, ex.sets.first().weightKg)
    }

    @Test
    fun `completed swap with all sets done has no remaining sets`() {
        val active = activeWorkout(routineSlot(DEADLIFT, "Deadlift (Barbell)", normalSets = 3))
        val logged = detail(
            loggedExercise(RDL, "Romanian Deadlift (Barbell)", List(3) { loggedSet() }),
        )

        val ex = build(logged, active)[0]
        assertEquals(RDL, ex.exerciseTemplateId)
        assertEquals(3, ex.sets.size)
        assertTrue(ex.sets.all { it.completed && it.locked })
    }
}
