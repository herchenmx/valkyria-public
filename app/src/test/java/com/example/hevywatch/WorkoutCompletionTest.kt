package com.example.hevywatch

import com.example.hevywatch.data.api.model.RoutineDetailResponse
import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.api.model.WorkoutExerciseResponse
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.ExerciseCompletionStatus.Status
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.data.model.toActiveWorkout
import com.example.hevywatch.data.model.toDomain
import com.example.hevywatch.presentation.workout.WorkoutDetailViewModel
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the workout completion comparison logic using real API fixtures:
 * - Incomplete workout (5/7 exercises done, Squat only 1/3 normal sets)
 * - Routine prescribing 7 exercises with varying normal set counts
 */
class WorkoutCompletionTest {

    private val gson = Gson()

    private fun loadWorkout(): WorkoutDetailResponse =
        gson.fromJson(
            javaClass.classLoader!!.getResourceAsStream("fixtures/workout_incomplete_real.json")!!
                .bufferedReader().readText(),
            WorkoutDetailResponse::class.java
        )

    private fun loadRoutine(): RoutineDetailResponse =
        gson.fromJson(
            javaClass.classLoader!!.getResourceAsStream("fixtures/routine_lower2_real.json")!!
                .bufferedReader().readText(),
            RoutineDetailResponse::class.java
        )

    @Test
    fun `fixtures deserialize correctly`() {
        val workout = loadWorkout()
        assertEquals(5, workout.exercises.size)
        assertEquals("a1fbe803-74ab-4071-9571-59e27adb43b5", workout.id)

        val routine = loadRoutine()
        assertEquals(7, routine.routine.exercises.size)
    }

    @Test
    fun `buildCompletionStatuses returns 7 entries (one per routine exercise)`() {
        val statuses = WorkoutDetailViewModel.buildCompletionStatuses(loadWorkout(), loadRoutine().routine)
        assertEquals(7, statuses.size)
    }

    @Test
    fun `Squat is INCOMPLETE - 1 normal set recorded vs 3 prescribed`() {
        val statuses = WorkoutDetailViewModel.buildCompletionStatuses(loadWorkout(), loadRoutine().routine)
        val squat = statuses.first { it.exerciseTemplateId == "CC35A01F" }
        assertEquals(Status.INCOMPLETE, squat.status)
        assertEquals(1, squat.recordedNormalSets)
        assertEquals(3, squat.prescribedNormalSets)
    }

    @Test
    fun `Romanian Deadlift is COMPLETE - 3 of 3 normal sets`() {
        val statuses = WorkoutDetailViewModel.buildCompletionStatuses(loadWorkout(), loadRoutine().routine)
        val rdl = statuses.first { it.exerciseTemplateId == "2B4B7310" }
        assertEquals(Status.COMPLETE, rdl.status)
        assertEquals(3, rdl.recordedNormalSets)
        assertEquals(3, rdl.prescribedNormalSets)
    }

    @Test
    fun `Leg Press Horizontal is COMPLETE - 3 of 3`() {
        val statuses = WorkoutDetailViewModel.buildCompletionStatuses(loadWorkout(), loadRoutine().routine)
        val lp = statuses.first { it.exerciseTemplateId == "0EB695C9" }
        assertEquals(Status.COMPLETE, lp.status)
        assertEquals(3, lp.recordedNormalSets)
    }

    @Test
    fun `Single Leg Press is COMPLETE - 6 of 6`() {
        val statuses = WorkoutDetailViewModel.buildCompletionStatuses(loadWorkout(), loadRoutine().routine)
        val slp = statuses.first { it.exerciseTemplateId == "3FD83744" }
        assertEquals(Status.COMPLETE, slp.status)
        assertEquals(6, slp.recordedNormalSets)
        assertEquals(6, slp.prescribedNormalSets)
    }

    @Test
    fun `Good Morning is COMPLETE - 3 of 3`() {
        val statuses = WorkoutDetailViewModel.buildCompletionStatuses(loadWorkout(), loadRoutine().routine)
        val gm = statuses.first { it.exerciseTemplateId == "4180C405" }
        assertEquals(Status.COMPLETE, gm.status)
        assertEquals(3, gm.recordedNormalSets)
    }

    @Test
    fun `Hip Thrust is MISSING - not in workout at all`() {
        val statuses = WorkoutDetailViewModel.buildCompletionStatuses(loadWorkout(), loadRoutine().routine)
        val ht = statuses.first { it.exerciseTemplateId == "68CE0B9B" }
        assertEquals(Status.MISSING, ht.status)
        assertEquals(0, ht.recordedNormalSets)
        assertEquals(3, ht.prescribedNormalSets)
    }

    @Test
    fun `Cable Twist is MISSING - not in workout at all`() {
        val statuses = WorkoutDetailViewModel.buildCompletionStatuses(loadWorkout(), loadRoutine().routine)
        val ct = statuses.first { it.exerciseTemplateId == "A2D838BD" }
        assertEquals(Status.MISSING, ct.status)
        assertEquals(0, ct.recordedNormalSets)
        assertEquals(6, ct.prescribedNormalSets)
    }

    @Test
    fun `statuses are sorted - COMPLETE first, then INCOMPLETE, then MISSING`() {
        val statuses = WorkoutDetailViewModel.buildCompletionStatuses(loadWorkout(), loadRoutine().routine)
        val statusOrder = statuses.map { it.status }

        // All COMPLETE should come before INCOMPLETE, which should come before MISSING
        val lastComplete = statusOrder.lastIndexOf(Status.COMPLETE)
        val firstIncomplete = statusOrder.indexOf(Status.INCOMPLETE)
        val firstMissing = statusOrder.indexOf(Status.MISSING)

        if (firstIncomplete >= 0) assert(lastComplete < firstIncomplete) { "COMPLETE should precede INCOMPLETE" }
        if (firstMissing >= 0 && firstIncomplete >= 0) assert(firstIncomplete < firstMissing) { "INCOMPLETE should precede MISSING" }
        if (firstMissing >= 0) assert(lastComplete < firstMissing) { "COMPLETE should precede MISSING" }
    }

    // ── Continue workout: warmup preservation ────────────────────────────────

    /**
     * Simulates the continueWorkout() rebuild logic to verify that warmup sets
     * from already-completed exercises are preserved (not dropped).
     */
    private fun simulateContinueWorkout(
        workout: WorkoutDetailResponse,
        routine: RoutineDetailResponse
    ): ActiveWorkout {
        val domainRoutine = routine.routine.toDomain()
        val activeWorkout = domainRoutine.toActiveWorkout()

        val workoutExercisesByTemplate = workout.exercises.associateBy { it.exerciseTemplateId }
        val preFilledExercises = activeWorkout.exercises.map { activeEx ->
            val workoutEx = workoutExercisesByTemplate[activeEx.exerciseTemplateId]
                ?: return@map activeEx

            val recordedSets = workoutEx.sets.map { ws ->
                ActiveSet(
                    setType = SetType.fromApiValue(ws.type),
                    weightKg = ws.weightKg,
                    reps = ws.reps,
                    distanceMeters = ws.distanceMeters,
                    durationSeconds = ws.durationSeconds,
                    completed = true,
                    locked = true
                )
            }

            val recordedNormalCount = recordedSets.count { it.setType == SetType.NORMAL }
            val prescribedNormalCount = activeEx.sets.count { it.setType == SetType.NORMAL }
            val remainingNormal = prescribedNormalCount - recordedNormalCount

            val remainingSets = if (remainingNormal > 0) {
                activeEx.sets
                    .filter { it.setType == SetType.NORMAL }
                    .takeLast(remainingNormal)
                    .map { it.copy(completed = false, completedAtMs = null) }
            } else emptyList()

            activeEx.copy(sets = recordedSets + remainingSets)
        }

        return activeWorkout.copy(exercises = preFilledExercises, continuingWorkoutId = workout.id)
    }

    @Test
    fun `continued workout preserves warmup sets from recorded exercises`() {
        val continued = simulateContinueWorkout(loadWorkout(), loadRoutine())

        // Romanian Deadlift was fully completed with 3 warmup + 3 normal = 6 sets
        val rdl = continued.exercises.first { it.exerciseTemplateId == "2B4B7310" }
        val warmups = rdl.sets.filter { it.setType == SetType.WARMUP }
        val normals = rdl.sets.filter { it.setType == SetType.NORMAL }
        assertEquals("RDL should have 3 warmup sets preserved", 3, warmups.size)
        assertEquals("RDL should have 3 normal sets", 3, normals.size)
        assertTrue("All RDL sets should be completed", rdl.sets.all { it.completed })
        // Verify warmup weights match the original workout
        assertEquals(30f, warmups[0].weightKg)
        assertEquals(32.5f, warmups[1].weightKg)
        assertEquals(42.5f, warmups[2].weightKg)
    }

    @Test
    fun `continued workout preserves warmup sets for incomplete exercises`() {
        val continued = simulateContinueWorkout(loadWorkout(), loadRoutine())

        // Squat was incomplete: 2 warmup + 1 normal recorded, 3 normal prescribed
        val squat = continued.exercises.first { it.exerciseTemplateId == "CC35A01F" }
        val warmups = squat.sets.filter { it.setType == SetType.WARMUP }
        val normals = squat.sets.filter { it.setType == SetType.NORMAL }
        assertEquals("Squat should have 2 warmup sets preserved", 2, warmups.size)
        assertEquals("Squat should have 3 normal sets (1 done + 2 remaining)", 3, normals.size)
        // The 2 warmups + 1 normal should be completed
        assertTrue("Warmups should be completed", warmups.all { it.completed })
        assertEquals("1 normal should be completed", 1, normals.count { it.completed })
        assertEquals("2 normals should be uncompleted", 2, normals.count { !it.completed })
    }

    @Test
    fun `continued workout leaves missing exercises empty for warmup advisor`() {
        val continued = simulateContinueWorkout(loadWorkout(), loadRoutine())

        // Hip Thrust was completely missing from the workout
        val ht = continued.exercises.first { it.exerciseTemplateId == "68CE0B9B" }
        assertTrue("Missing exercise should have no completed sets", ht.sets.none { it.completed })
        assertEquals("Missing exercise should have routine-prescribed sets only", 3,
            ht.sets.count { it.setType == SetType.NORMAL })
    }

    @Test
    fun `continued workout has continuingWorkoutId set`() {
        val continued = simulateContinueWorkout(loadWorkout(), loadRoutine())
        assertEquals("a1fbe803-74ab-4071-9571-59e27adb43b5", continued.continuingWorkoutId)
    }

    @Test
    fun `PUT request body would include warmup sets from completed exercises`() {
        val continued = simulateContinueWorkout(loadWorkout(), loadRoutine())

        // Count total completed sets that would be sent in PUT
        val totalCompletedSets = continued.exercises.sumOf { ex ->
            ex.sets.count { it.completed }
        }
        // Original workout had: Squat(3) + RDL(6) + LegPress(7) + SingleLeg(10) + GoodMorning(5) = 31
        assertEquals("All original sets (including warmups) should be preserved as completed", 31, totalCompletedSets)
    }

    // ── Locked set behavior ──────────────────────────────────────────────────

    @Test
    fun `recorded sets from existing workout are locked`() {
        val continued = simulateContinueWorkout(loadWorkout(), loadRoutine())

        // RDL: all 6 sets came from the workout → all locked
        val rdl = continued.exercises.first { it.exerciseTemplateId == "2B4B7310" }
        assertTrue("All recorded sets should be locked", rdl.sets.all { it.locked })
    }

    @Test
    fun `remaining prescribed sets are NOT locked`() {
        val continued = simulateContinueWorkout(loadWorkout(), loadRoutine())

        // Squat: 3 recorded (locked) + 2 remaining (not locked)
        val squat = continued.exercises.first { it.exerciseTemplateId == "CC35A01F" }
        val lockedSets = squat.sets.filter { it.locked }
        val unlockedSets = squat.sets.filter { !it.locked }
        assertEquals("3 recorded sets should be locked", 3, lockedSets.size)
        assertEquals("2 remaining normal sets should not be locked", 2, unlockedSets.size)
        assertTrue("Unlocked sets should not be completed", unlockedSets.none { it.completed })
    }

    @Test
    fun `missing exercises have zero locked sets`() {
        val continued = simulateContinueWorkout(loadWorkout(), loadRoutine())

        // Hip Thrust was completely missing → no locked sets
        val ht = continued.exercises.first { it.exerciseTemplateId == "68CE0B9B" }
        assertTrue("Missing exercise should have no locked sets", ht.sets.none { it.locked })
        assertTrue("Missing exercise should have no completed sets", ht.sets.none { it.completed })
    }

    @Test
    fun `normal new workout has zero locked sets`() {
        // A fresh workout from a routine (not continuing) should never have locked sets
        val routine = loadRoutine().routine.toDomain()
        val fresh = routine.toActiveWorkout()
        val allSets = fresh.exercises.flatMap { it.sets }
        assertTrue("Fresh workout should have no locked sets", allSets.none { it.locked })
        assertTrue("Fresh workout should have no completed sets", allSets.none { it.completed })
    }

    @Test
    fun `completing a set in a fresh workout does not lock it`() {
        // Simulate what LogWorkoutViewModel.completeCurrentSet() does
        val routine = loadRoutine().routine.toDomain()
        val fresh = routine.toActiveWorkout()
        val firstEx = fresh.exercises.first()
        val firstSet = firstEx.sets.first()

        // completeCurrentSet copies with completed=true but never sets locked
        val completed = firstSet.copy(completed = true, completedAtMs = System.currentTimeMillis())
        assertFalse("Completed set in fresh workout must NOT be locked", completed.locked)
    }
}
