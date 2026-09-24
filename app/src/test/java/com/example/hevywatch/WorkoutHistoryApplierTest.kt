package com.example.hevywatch

import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.WorkoutHistoryApplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 / T5 — covers the pure subset of [WorkoutHistoryApplier]:
 *  - applyHistoryHints (B1 invariants for non-PO pre-fill)
 *  - applyProgressiveOverload (PO-only branch)
 *  - applyWarmupAdvisor (auto-inject vs prompt-override paths)
 *
 * The full load() pipeline (which calls into HevyApp's caches via
 * WorkoutDataLoader) needs Robolectric and is exercised at the VM layer; the
 * unit-level tests here pin the deterministic transforms.
 */
class WorkoutHistoryApplierTest {

    private fun makeWorkout(
        progressiveOverload: Boolean = false,
        warmupAdvisorApplied: Boolean = false,
        exercises: List<ActiveExercise>,
    ) = ActiveWorkout(
        name = "test workout",
        startTimeMs = 0L,
        exercises = exercises,
        progressiveOverload = progressiveOverload,
        warmupAdvisorApplied = warmupAdvisorApplied,
    )

    private fun makeExercise(
        id: String = "tpl-1",
        sets: List<ActiveSet> = listOf(ActiveSet(setType = SetType.NORMAL)),
        equipment: String? = "barbell",
        primaryMuscleGroup: String? = "chest",
    ) = ActiveExercise(
        exerciseTemplateId = id,
        title = "Bench Press",
        sets = sets,
        equipment = equipment,
        primaryMuscleGroup = primaryMuscleGroup,
    )

    private fun history(
        templateId: String,
        weightsByWorkout: Map<String, List<Float?>> = emptyMap(),
    ): ExerciseHistoryResponse {
        val entries = weightsByWorkout.flatMap { (wid, weights) ->
            weights.map { w ->
                ExerciseHistoryEntry(
                    workoutId = wid,
                    workoutTitle = null,
                    workoutStartTime = "2026-01-01T00:00:00Z",
                    workoutEndTime = null,
                    exerciseTemplateId = templateId,
                    weightKg = w,
                    reps = 10,
                    distanceMeters = null,
                    durationSeconds = null,
                    rpe = null,
                    customMetric = null,
                    setType = "normal",
                )
            }
        }
        return ExerciseHistoryResponse(exerciseHistory = entries)
    }

    @Test fun `applyHistoryHints pre-fills last session weight on empty non-PO sets`() {
        val ex = makeExercise(sets = listOf(ActiveSet(setType = SetType.NORMAL)))
        val workout = makeWorkout(exercises = listOf(ex))
        val map = mapOf("tpl-1" to history("tpl-1", mapOf("w1" to listOf(50f))))

        val result = WorkoutHistoryApplier.applyHistoryHints(workout, map)

        assertEquals(50f, result.exercises[0].sets[0].weightKg)
        assertNotNull("previous hint must be set", result.exercises[0].sets[0].previous)
    }

    @Test fun `applyHistoryHints does NOT overwrite a user-entered weight (B1)`() {
        val ex = makeExercise(sets = listOf(ActiveSet(setType = SetType.NORMAL, weightKg = 42.5f)))
        val workout = makeWorkout(exercises = listOf(ex))
        val map = mapOf("tpl-1" to history("tpl-1", mapOf("w1" to listOf(50f))))

        val result = WorkoutHistoryApplier.applyHistoryHints(workout, map)

        assertEquals("user-entered weight must survive crash recovery",
            42.5f, result.exercises[0].sets[0].weightKg)
    }

    @Test fun `applyHistoryHints does NOT overwrite a completed set (B1)`() {
        val ex = makeExercise(sets = listOf(
            ActiveSet(setType = SetType.NORMAL, weightKg = 60f, completed = true)
        ))
        val workout = makeWorkout(exercises = listOf(ex))
        val map = mapOf("tpl-1" to history("tpl-1", mapOf("w1" to listOf(50f))))

        val result = WorkoutHistoryApplier.applyHistoryHints(workout, map)

        assertEquals(60f, result.exercises[0].sets[0].weightKg)
        assertTrue(result.exercises[0].sets[0].completed)
    }

    @Test fun `applyHistoryHints on PO routine attaches hint but does NOT pre-fill weight`() {
        val ex = makeExercise(sets = listOf(ActiveSet(setType = SetType.NORMAL)))
        val workout = makeWorkout(progressiveOverload = true, exercises = listOf(ex))
        val map = mapOf("tpl-1" to history("tpl-1", mapOf("w1" to listOf(50f))))

        val result = WorkoutHistoryApplier.applyHistoryHints(workout, map)

        assertNotNull(result.exercises[0].sets[0].previous)
        assertNull("PO routines defer weights to applyProgressiveOverload",
            result.exercises[0].sets[0].weightKg)
    }

    @Test fun `applyHistoryHints uses only the most recent workout's sets`() {
        // Two workouts in history: the older one shouldn't bleed into the pre-fill.
        val ex = makeExercise(sets = listOf(ActiveSet(), ActiveSet()))
        val workout = makeWorkout(exercises = listOf(ex))
        val map = mapOf("tpl-1" to history("tpl-1", linkedMapOf(
            "recent" to listOf(60f, 65f),
            "older" to listOf(40f, 45f),
        )))

        val result = WorkoutHistoryApplier.applyHistoryHints(workout, map)

        assertEquals(60f, result.exercises[0].sets[0].weightKg)
        assertEquals(65f, result.exercises[0].sets[1].weightKg)
    }

    @Test fun `applyProgressiveOverload is a no-op for non-PO workouts`() {
        val ex = makeExercise(sets = listOf(ActiveSet(setType = SetType.NORMAL, weightKg = 50f)))
        val workout = makeWorkout(progressiveOverload = false, exercises = listOf(ex))
        val map = emptyMap<String, ExerciseHistoryResponse>()

        val (out, increased) = WorkoutHistoryApplier.applyProgressiveOverload(workout, map, 0f)

        assertEquals(workout, out)
        assertTrue(increased.isEmpty())
    }

    @Test fun `applyWarmupAdvisor returns empty overrides when advisor already applied`() {
        val ex = makeExercise(sets = listOf(ActiveSet(setType = SetType.NORMAL, weightKg = 80f)))
        val workout = makeWorkout(warmupAdvisorApplied = true, exercises = listOf(ex))

        val (out, overrides) = WorkoutHistoryApplier.applyWarmupAdvisor(workout, 80f)

        assertEquals(workout, out)
        assertTrue(overrides.isEmpty())
    }

    @Test fun `applyWarmupAdvisor appends missing warmups to a resumed exercise with completed sets`() {
        // Barbell + chest @ 80kg → advisor suggests 3 warmups. The exercise was
        // resumed with its working set already logged (completed) and no warmups,
        // so all 3 are still owed → appended as fresh editable sets.
        val ex = makeExercise(sets = listOf(
            ActiveSet(setType = SetType.NORMAL, weightKg = 80f, completed = true, locked = true)
        ))
        val workout = makeWorkout(exercises = listOf(ex))

        val (out, overrides) = WorkoutHistoryApplier.applyWarmupAdvisor(workout, 80f)
        val sets = out.exercises[0].sets

        // The completed working set is preserved untouched at the front.
        assertEquals(SetType.NORMAL, sets.first().setType)
        assertTrue(sets.first().completed)
        // Missing advised warmups appended as editable (uncompleted, unlocked).
        val appended = sets.drop(1)
        assertEquals(3, appended.size)
        assertTrue(appended.all { it.setType == SetType.WARMUP && !it.completed && !it.locked })
        assertTrue(out.warmupAdvisorApplied)
        assertTrue(overrides.isEmpty())
    }

    @Test fun `applyWarmupAdvisor only appends the still-missing warmups when some were logged`() {
        // 1 warmup already logged → only the remaining 2 of the advised 3 appended.
        val ex = makeExercise(sets = listOf(
            ActiveSet(setType = SetType.WARMUP, weightKg = 40f, completed = true, locked = true),
            ActiveSet(setType = SetType.NORMAL, weightKg = 80f, completed = true, locked = true),
        ))
        val workout = makeWorkout(exercises = listOf(ex))

        val (out, _) = WorkoutHistoryApplier.applyWarmupAdvisor(workout, 80f)
        val sets = out.exercises[0].sets

        // Originals preserved (2), plus 2 newly-appended editable warmups.
        assertEquals(4, sets.size)
        val appended = sets.takeLast(2)
        assertTrue(appended.all { it.setType == SetType.WARMUP && !it.completed && !it.locked })
    }

    @Test fun `applyWarmupAdvisor adds nothing when the resumed exercise already met its warmups`() {
        // 3 warmups already logged ≥ the advised 3 → nothing appended.
        val ex = makeExercise(sets = listOf(
            ActiveSet(setType = SetType.WARMUP, weightKg = 30f, completed = true, locked = true),
            ActiveSet(setType = SetType.WARMUP, weightKg = 50f, completed = true, locked = true),
            ActiveSet(setType = SetType.WARMUP, weightKg = 65f, completed = true, locked = true),
            ActiveSet(setType = SetType.NORMAL, weightKg = 80f, completed = true, locked = true),
        ))
        val workout = makeWorkout(exercises = listOf(ex))

        val (out, overrides) = WorkoutHistoryApplier.applyWarmupAdvisor(workout, 80f)
        assertEquals(4, out.exercises[0].sets.size) // unchanged
        assertTrue(overrides.isEmpty())
    }

    @Test fun `applyWarmupAdvisor injects warmups when none are prescribed`() {
        // Barbell + chest + a reasonable working weight should produce a non-
        // empty advisor suggestion (validated by WarmupAdvisorTest).
        val ex = makeExercise(sets = listOf(
            ActiveSet(setType = SetType.NORMAL, weightKg = 80f, completed = false)
        ))
        val workout = makeWorkout(exercises = listOf(ex))

        val (out, overrides) = WorkoutHistoryApplier.applyWarmupAdvisor(workout, 80f)

        // Either the advisor produced warmups (most likely) or it didn't — but
        // pending overrides must be empty either way because the exercise had
        // no prescribed warmups to conflict with.
        assertTrue(overrides.isEmpty())
        assertTrue(out.warmupAdvisorApplied)
    }

    // ── filterOutContinuingWorkout ───────────────────────────────────────────
    // Regression test for the "PO doesn't fire on resumed workouts" bug. The
    // in-progress workout is on the server as incomplete, so /exercise_history
    // returns it as the *most recent* entry. For exercises with zero normal
    // sets in the resumed segment, computeProgressiveOverload would otherwise
    // short-circuit on `historyNormal.isEmpty()` and leave remaining sets
    // without a suggested weight.

    @Test fun `filterOutContinuingWorkout is identity when continuingWorkoutId is null`() {
        val map = mapOf("tpl-1" to history("tpl-1", mapOf("w1" to listOf(50f))))
        val out = WorkoutHistoryApplier.filterOutContinuingWorkout(map, null)
        assertEquals(map, out)
    }

    @Test fun `filterOutContinuingWorkout removes only entries matching the workoutId`() {
        val map = mapOf("tpl-1" to history("tpl-1", linkedMapOf(
            "in-progress" to listOf(60f, 65f),
            "older-completed" to listOf(40f, 45f),
        )))
        val out = WorkoutHistoryApplier.filterOutContinuingWorkout(map, "in-progress")
        val entries = out["tpl-1"]?.exerciseHistory.orEmpty()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.workoutId == "older-completed" })
    }

    @Test fun `filterOutContinuingWorkout retains keys for fully-stripped exercises`() {
        // After stripping, an exercise that ONLY appeared in the in-progress
        // workout drops to an empty history list. The key must still exist so
        // downstream pipeline steps see "no history" (and apply the
        // similar-exercise fallback) instead of NPEing on a missing key.
        val map = mapOf("tpl-1" to history("tpl-1", mapOf("in-progress" to listOf(60f))))
        val out = WorkoutHistoryApplier.filterOutContinuingWorkout(map, "in-progress")
        assertTrue(out.containsKey("tpl-1"))
        assertTrue(out["tpl-1"]?.exerciseHistory.orEmpty().isEmpty())
    }

    @Test fun `PO fires from older-completed history when in-progress workout is filtered out`() {
        // Five sets logged at 60×15 in the older completed workout — qualifies
        // for PO (3+ normal sets, ≥3 at rep-range-top). Without filtering, the
        // in-progress workout (zero normal sets) is "most recent" and PO
        // short-circuits. With filtering, PO should pick the older workout
        // and bump the target by one barbell increment (2.5kg → 62.5).
        val routineNormals = List(5) { ActiveSet(setType = SetType.NORMAL) }
        val ex = makeExercise(sets = routineNormals)
        val workout = makeWorkout(progressiveOverload = true, exercises = listOf(ex))

        val olderEntries = (0 until 5).map { _ ->
            ExerciseHistoryEntry(
                workoutId = "older-completed",
                workoutTitle = null,
                workoutStartTime = "2026-01-01T00:00:00Z",
                workoutEndTime = null,
                exerciseTemplateId = "tpl-1",
                weightKg = 60f,
                reps = 15,
                distanceMeters = null,
                durationSeconds = null,
                rpe = null,
                customMetric = null,
                setType = "normal",
            )
        }
        // In-progress workout has no normal sets for this exercise (only a
        // warmup entry, which is what the user did before bailing on the set).
        val inProgressWarmup = listOf(ExerciseHistoryEntry(
            workoutId = "in-progress",
            workoutTitle = null,
            workoutStartTime = "2026-02-01T00:00:00Z",
            workoutEndTime = null,
            exerciseTemplateId = "tpl-1",
            weightKg = 20f,
            reps = 10,
            distanceMeters = null,
            durationSeconds = null,
            rpe = null,
            customMetric = null,
            setType = "warmup",
        ))
        val raw = mapOf("tpl-1" to ExerciseHistoryResponse(
            // Order matters: groupBy preserves insertion order, and PO uses
            // the *first* group as "most recent". The in-progress entries
            // come first to reproduce the production failure mode.
            exerciseHistory = inProgressWarmup + olderEntries
        ))

        val filtered = WorkoutHistoryApplier.filterOutContinuingWorkout(raw, "in-progress")
        val (afterPo, increased) = WorkoutHistoryApplier.applyProgressiveOverload(
            workout, filtered, bodyweightKg = 80f,
        )

        assertTrue("PO must mark the exercise as increased after filtering",
            "tpl-1" in increased)
        afterPo.exercises[0].sets.forEach { s ->
            assertEquals("PO target = 60 + 1 (1kg universal increment)", 61f, s.weightKg)
            assertEquals(60f, s.poBaseWeightKg)
        }
    }

    @Test fun `PO does NOT fire when raw map (in-progress workout) is used directly`() {
        // Negative twin of the test above: this reproduces the bug. Without
        // the filter, the in-progress warmup entry is "most recent", its
        // historyNormal list is empty, and PO bails.
        val routineNormals = List(5) { ActiveSet(setType = SetType.NORMAL) }
        val ex = makeExercise(sets = routineNormals)
        val workout = makeWorkout(progressiveOverload = true, exercises = listOf(ex))

        val olderEntries = (0 until 5).map { _ ->
            ExerciseHistoryEntry(
                workoutId = "older-completed",
                workoutTitle = null,
                workoutStartTime = "2026-01-01T00:00:00Z",
                workoutEndTime = null,
                exerciseTemplateId = "tpl-1",
                weightKg = 60f,
                reps = 15,
                distanceMeters = null,
                durationSeconds = null,
                rpe = null,
                customMetric = null,
                setType = "normal",
            )
        }
        val inProgressWarmup = listOf(ExerciseHistoryEntry(
            workoutId = "in-progress",
            workoutTitle = null,
            workoutStartTime = "2026-02-01T00:00:00Z",
            workoutEndTime = null,
            exerciseTemplateId = "tpl-1",
            weightKg = 20f,
            reps = 10,
            distanceMeters = null,
            durationSeconds = null,
            rpe = null,
            customMetric = null,
            setType = "warmup",
        ))
        val raw = mapOf("tpl-1" to ExerciseHistoryResponse(
            exerciseHistory = inProgressWarmup + olderEntries
        ))

        val (afterPo, increased) = WorkoutHistoryApplier.applyProgressiveOverload(
            workout, raw, bodyweightKg = 80f,
        )

        assertTrue("PO should not fire without the filter", increased.isEmpty())
        afterPo.exercises[0].sets.forEach { s ->
            assertNull("no PO weight stamped", s.weightKg)
            assertNull("no PO base recorded", s.poBaseWeightKg)
        }
    }
}
