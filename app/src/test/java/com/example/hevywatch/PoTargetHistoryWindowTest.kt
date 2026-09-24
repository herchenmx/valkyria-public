package com.example.hevywatch

import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.presentation.workout.WorkoutDetailViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins `WorkoutDetailViewModel.historyBefore` — the window that reconstructs the
 * PO target a past workout should be judged against.
 *
 * The bug: viewing an OLD workout (e.g. "POP 2: LOWER" on 6 Jul), the completion
 * recompute excluded only that workout by id and kept LATER, heavier sessions in
 * the recency-weighted PO base. The target then drifted above the warmup-bucket
 * boundary (Leg Press: LARGE group, 80kg step) so an exercise that was advised 3
 * warmups at the time (and did all 3) was re-judged as needing 4 → false
 * incomplete. The fix drops sessions logged at/after the viewed workout's start.
 */
class PoTargetHistoryWindowTest {

    private fun entry(id: String, startIso: String) = ExerciseHistoryEntry(
        workoutId = id, workoutTitle = null, workoutStartTime = startIso,
        workoutEndTime = null, exerciseTemplateId = "LEGPRESS", weightKg = 70f,
        reps = 8, distanceMeters = null, durationSeconds = null, rpe = null,
        customMetric = null, setType = "normal"
    )

    // Prior session, the viewed workout, and TWO later (heavier) sessions.
    private val prior = entry("w-prior", "2026-07-01T10:00:00Z")
    private val viewed = entry("w-viewed", "2026-07-06T10:00:00Z")
    private val laterA = entry("w-later-a", "2026-07-08T10:00:00Z")
    private val laterB = entry("w-later-b", "2026-07-10T10:00:00Z")
    private val all = listOf(prior, viewed, laterA, laterB)

    @Test
    fun `keeps only sessions strictly before the viewed workout`() {
        val kept = WorkoutDetailViewModel.historyBefore(
            all, excludeWorkoutId = "w-viewed", cutoffIso = "2026-07-06T10:00:00Z"
        )
        assertEquals(listOf("w-prior"), kept.map { it.workoutId })
    }

    @Test
    fun `the viewed workout is dropped even if the cutoff would keep an equal-time twin`() {
        // Strict `isBefore` already drops the viewed workout (same instant as the
        // cutoff); the id-exclude is belt-and-suspenders for equal-time siblings.
        val twinAtSameInstant = entry("w-twin", "2026-07-06T10:00:00Z")
        val kept = WorkoutDetailViewModel.historyBefore(
            all + twinAtSameInstant, excludeWorkoutId = "w-viewed", cutoffIso = "2026-07-06T10:00:00Z"
        )
        assertEquals(listOf("w-prior"), kept.map { it.workoutId })
    }

    @Test
    fun `null cutoff falls back to id-only exclusion (old behaviour)`() {
        val kept = WorkoutDetailViewModel.historyBefore(all, excludeWorkoutId = "w-viewed", cutoffIso = null)
        assertEquals(listOf("w-prior", "w-later-a", "w-later-b"), kept.map { it.workoutId })
    }

    @Test
    fun `an unparseable entry stamp is kept rather than dropped`() {
        val garbled = entry("w-garbled", "not-a-timestamp")
        val kept = WorkoutDetailViewModel.historyBefore(
            listOf(prior, garbled, laterA), excludeWorkoutId = "w-viewed", cutoffIso = "2026-07-06T10:00:00Z"
        )
        // prior (before) kept, garbled (unparseable) kept, laterA (after) dropped.
        assertEquals(listOf("w-prior", "w-garbled"), kept.map { it.workoutId })
    }

    @Test
    fun `offset-format timestamps compare chronologically`() {
        val offsetPrior = entry("w-off-prior", "2026-07-01T10:00:00+00:00")
        val offsetLater = entry("w-off-later", "2026-07-09T10:00:00+00:00")
        val kept = WorkoutDetailViewModel.historyBefore(
            listOf(offsetPrior, offsetLater), excludeWorkoutId = "w-viewed",
            cutoffIso = "2026-07-06T10:00:00+00:00"
        )
        assertEquals(listOf("w-off-prior"), kept.map { it.workoutId })
    }
}
