package com.example.hevywatch.presentation.workout

import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.util.FormatUtils
import com.example.hevywatch.util.Haptics
import java.time.Instant

/**
 * R1 — post-save bookkeeping extracted from LogWorkoutViewModel. The VM used
 * to inline ~80 lines of: compute PR badges, build summary, update routine
 * last-worked metadata, append workout id to routine history, invalidate
 * recent-workouts cache, clear active workout. All of it pure orchestration
 * over [HevyApp] state — no Compose, no coroutines, no Android dependencies
 * beyond [Haptics], so it can be unit-tested directly with a fake HevyApp.
 *
 * Side effects on the caller VM (showing the Congrats screen, clearing VM-
 * local state) stay in the VM — this helper only touches HevyApp.
 */
object WorkoutCompletionRecorder {

    /**
     * Run after the network save succeeds. Returns the [HevyApp.WorkoutSummary]
     * that will drive the Congrats screen and mutates [HevyApp] state
     * accordingly. The VM is responsible for navigating once this returns.
     */
    fun record(
        hevyApp: HevyApp,
        workout: ActiveWorkout,
        endTimeMs: Long,
    ): HevyApp.WorkoutSummary {
        val duration = FormatUtils.formatDuration(workout.startTimeMs, endTimeMs)
        val bw = hevyApp.bodyweightKg
        val volume = FormatUtils.formatVolume(workout.exercises, workout.routineId, bw)
        val numSets = workout.exercises.sumOf { ex -> ex.sets.count { it.completed } }

        // Compute PRs *before* we bust the history cache below — otherwise
        // historyToBest would see the empty cache and decide every set is a PR.
        val exerciseSummaries = workout.exercises.mapNotNull { ex ->
            val completed = ex.sets.count { it.completed }
            if (completed == 0) return@mapNotNull null
            val best = historyToBest(
                hevyApp.exerciseHistoryCache[ex.exerciseTemplateId],
                exerciseTemplateId = ex.exerciseTemplateId,
                bodyweightKg = bw,
            )
            HevyApp.ExerciseSummary(
                title = ex.title,
                completedSets = completed,
                pr = findBestPrInWorkout(
                    best,
                    ex.sets,
                    exerciseTemplateId = ex.exerciseTemplateId,
                    bodyweightKg = bw,
                ),
            )
        }
        val summary = HevyApp.WorkoutSummary(
            title = workout.name,
            durationFormatted = duration,
            volumeKg = volume,
            totalSets = numSets,
            exercises = exerciseSummaries,
        )
        hevyApp.lastWorkoutSummary = summary

        if (exerciseSummaries.any { it.pr != null }) Haptics.celebrate(hevyApp)

        updateRoutineHistory(hevyApp, workout)

        // After a save the Recent list and last-workout-dates map may no
        // longer reflect reality (new POST-created workouts aren't yet in
        // local state). Drop the memoised page-1 and clear the populate-
        // throttle so the next navigation triggers a fresh fetch.
        hevyApp.invalidateWorkoutsPage1()
        hevyApp.workoutHistoryPopulatedAtMs = 0L

        // Invalidate history cache entries for the exercises we just logged,
        // so the next routine visit reflects this workout instead of the
        // pre-workout snapshot.
        workout.exercises.forEach { ex ->
            hevyApp.exerciseHistoryCache.remove(ex.exerciseTemplateId)
            hevyApp.exerciseBestWeights.remove(ex.exerciseTemplateId)
        }
        hevyApp.clearActiveWorkout()

        return summary
    }

    /** Update [HevyApp.routineLastWorkoutAt] and [HevyApp.routineWorkoutIds]
     *  for the saved workout's routine. Exposed for unit tests. */
    internal fun updateRoutineHistory(hevyApp: HevyApp, workout: ActiveWorkout) {
        val routineId = workout.routineId ?: return
        // Use the workout's actual start_time (ISO) to match what
        // populateLastWorkoutDates reads from the API. For continuing
        // workouts the original workout's start_time is preserved on the
        // merged POST (`buildResumePostRequestV2` uses `original.startTime`
        // verbatim) — and on the v1 PUT fallback too — so the routine's
        // last-worked timestamp should be the original session's, NOT now.
        // Stamping "now" would make the routine appear as most-recently-
        // worked on Routine List when the real last session was days ago.
        // Only advance the map forward, never backward, so resuming an old
        // incomplete workout can't push a more recent completion for the
        // same routine out of the way.
        val newStartIso = if (workout.continuingWorkoutId != null) {
            hevyApp.continuingWorkoutDetail?.startTime
                ?: Instant.ofEpochMilli(workout.startTimeMs).toString()
        } else {
            Instant.ofEpochMilli(workout.startTimeMs).toString()
        }
        val existing = hevyApp.routineLastWorkoutAt[routineId]
        if (existing == null || newStartIso > existing) {
            hevyApp.routineLastWorkoutAt = hevyApp.routineLastWorkoutAt +
                mapOf(routineId to newStartIso)
            hevyApp.workoutHistoryStore.routineLastWorkoutAt = hevyApp.routineLastWorkoutAt
        }
        // Continuing workouts: the original server-side id is DELETED by the
        // POST+DELETE resume flow, and the new (merged) workout has a fresh
        // id that we don't track locally. Strip the dead id from
        // routineWorkoutIds so the Progress tab doesn't waste a 404 GET on
        // it; the next workouts page-1 refresh (invalidated above) repopulates
        // the set with the new id.
        workout.continuingWorkoutId?.let { staleId ->
            hevyApp.removeWorkoutFromRoutineHistory(routineId, staleId)
        }
    }
}
