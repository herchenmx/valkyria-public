package com.example.hevywatch.presentation.workout

import com.example.hevywatch.data.api.model.BiometricsBody
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.data.api.model.HeartRateSampleBody
import com.example.hevywatch.data.api.model.WorkoutDetailExerciseV2
import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.api.model.WorkoutDetailResponseV2
import com.example.hevywatch.data.api.model.WorkoutDetailSetV2
import com.example.hevywatch.data.api.model.WorkoutExerciseResponse
import com.example.hevywatch.data.api.model.WorkoutPostBody
import com.example.hevywatch.data.api.model.WorkoutPostBodyV2
import com.example.hevywatch.data.api.model.WorkoutPostExercise
import com.example.hevywatch.data.api.model.WorkoutPostExerciseV2
import com.example.hevywatch.data.api.model.WorkoutPostRequest
import com.example.hevywatch.data.api.model.WorkoutPostRequestV2
import com.example.hevywatch.data.api.model.WorkoutPostSet
import com.example.hevywatch.data.api.model.WorkoutPostSetV2
import com.example.hevywatch.data.api.model.WorkoutPutBody
import com.example.hevywatch.data.api.model.WorkoutPutExercise
import com.example.hevywatch.data.api.model.WorkoutPutRequest
import com.example.hevywatch.data.api.model.WorkoutPutSet
import com.example.hevywatch.data.api.model.WorkoutSetResponse
import com.example.hevywatch.data.model.ActiveWorkout
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

// ── Public (v1) workout request builder ──────────────────────────────────────

internal fun buildPublicPostRequest(w: ActiveWorkout, endTimeMs: Long) = WorkoutPostRequest(
    workout = WorkoutPostBody(
        title = w.name,
        description = null,
        startTime = Instant.ofEpochMilli(w.startTimeMs).toString(),
        endTime = Instant.ofEpochMilli(endTimeMs).toString(),
        exercises = w.exercises.mapNotNull { exercise ->
            val completed = exercise.sets.filter { it.completed }
            if (completed.isEmpty()) null
            else WorkoutPostExercise(
                exerciseTemplateId = exercise.exerciseTemplateId,
                supersetId = null,
                notes = null,
                sets = completed.map { set ->
                    WorkoutPostSet(
                        type = set.setType.apiValue,
                        // Force null weight for non-weight exercise types
                        // (REPS_ONLY, DURATION, …) so a stale routine value
                        // doesn't leak into the saved workout.
                        weightKg = if (exercise.exerciseType.usesWeight) set.weightKg else null,
                        reps = if (exercise.exerciseType.usesReps) set.reps else null,
                        distanceMeters = null,
                        durationSeconds = null,
                        customMetric = null,
                        rpe = null
                    )
                }
            )
        }
    )
)

// ── PUT (v1 fallback path for resume) ────────────────────────────────────────
//
// Fallback for when the private v2 GET on Continue failed, so we have no
// biometrics to merge. The primary resume path is buildResumePostRequestV2
// (POST+DELETE on the private v2 API). This builder degrades gracefully: same
// exercise merge, but no biometrics survive the round-trip (the v1 PUT
// whitelist rejects biometrics / wearos_watch / is_biometrics_public).

/**
 * Builds a PUT request body by starting from the original GET /v1/workouts/{id} response
 * and merging in newly completed exercises/sets from the ActiveWorkout.
 *
 * For exercises that existed in the original workout: the original data is preserved as-is
 * (including warmups, notes, rpe, custom_metric, etc.). Any newly completed sets for that
 * exercise are appended.
 *
 * For exercises that are new (were missing from the original workout, user just completed them):
 * they are appended from the ActiveWorkout.
 *
 * This ensures no data from the original workout is lost during the PUT.
 *
 * `is_private` is deliberately not part of the body — see [WorkoutPutBody].
 */
internal fun buildWorkoutPutRequest(
    original: WorkoutDetailResponse,
    activeWorkout: ActiveWorkout,
    endTimeMs: Long
): WorkoutPutRequest {
    val originalExercisesByTemplate = original.exercises.associateBy { it.exerciseTemplateId }

    // Build the merged exercise list
    val mergedExercises = mutableListOf<WorkoutPutExercise>()

    // 1. Start with all original exercises, preserving their exact data.
    //    For exercises that the user continued (added new sets), append the new sets.
    val handledTemplateIds = mutableSetOf<String>()
    for (origEx in original.exercises) {
        handledTemplateIds.add(origEx.exerciseTemplateId)

        // Check if ActiveWorkout has new (uncompleted-then-completed) sets for this exercise
        val activeEx = activeWorkout.exercises.firstOrNull {
            it.exerciseTemplateId == origEx.exerciseTemplateId
        }

        // Convert original sets to PUT format (preserves all fields from the GET response)
        val originalPutSets = origEx.sets.map { it.toPutSet() }

        // Find newly completed sets that weren't in the original workout
        val newSets = if (activeEx != null) {
            // The ActiveWorkout has all original sets marked as completed + any new sets.
            // New sets = completed sets in ActiveWorkout beyond the count of original sets.
            val originalSetCount = origEx.sets.size
            activeEx.sets.drop(originalSetCount)
                .filter { it.completed }
                .map { set ->
                    WorkoutPutSet(
                        type = set.setType.apiValue,
                        weightKg = if (activeEx.exerciseType.usesWeight) set.weightKg else null,
                        reps = if (activeEx.exerciseType.usesReps) set.reps else null,
                        distanceMeters = set.distanceMeters?.toFloat(),
                        durationSeconds = set.durationSeconds,
                        customMetric = null,
                        rpe = null
                    )
                }
        } else emptyList()

        mergedExercises.add(WorkoutPutExercise(
            exerciseTemplateId = origEx.exerciseTemplateId,
            supersetId = origEx.supersetId?.toString(),
            notes = origEx.notes?.takeIf { it.isNotBlank() },
            sets = originalPutSets + newSets
        ))
    }

    // 2. Append exercises that are new (weren't in the original workout at all)
    for (activeEx in activeWorkout.exercises) {
        if (activeEx.exerciseTemplateId in handledTemplateIds) continue
        val completed = activeEx.sets.filter { it.completed }
        if (completed.isEmpty()) continue

        mergedExercises.add(WorkoutPutExercise(
            exerciseTemplateId = activeEx.exerciseTemplateId,
            notes = activeEx.notes?.takeIf { it.isNotBlank() },
            sets = completed.map { set ->
                WorkoutPutSet(
                    type = set.setType.apiValue,
                    weightKg = if (activeEx.exerciseType.usesWeight) set.weightKg else null,
                    reps = if (activeEx.exerciseType.usesReps) set.reps else null,
                    distanceMeters = set.distanceMeters?.toFloat(),
                    durationSeconds = set.durationSeconds,
                    customMetric = null,
                    rpe = null
                )
            }
        ))
    }

    // Keep the original session's [start_time, end_time] window verbatim —
    // re-finishing an incomplete workout days later must NOT stretch its
    // duration. Hevy's displayed duration is end_time − start_time, so the old
    // recompute (originalStart + (endTimeMs − active.startTimeMs)) inflated the
    // workout to wall-clock hours/days whenever active.startTimeMs was stale
    // (e.g. the resume was finished in a later session, so `now − startTimeMs`
    // spanned the whole gap). This mirrors buildResumePostRequestV2 (the v2
    // POST+DELETE primary) and the companion's buildPutV1; the resume timer is
    // display-only. `endTimeMs` is no longer used for the window. Fallback to
    // start_time only if the original somehow lacks an end (PUT schema requires
    // a value); a saved workout always carries both.
    val endTimeIso = original.endTime?.takeIf { it.isNotBlank() } ?: original.startTime

    return WorkoutPutRequest(
        workout = WorkoutPutBody(
            title = original.title ?: activeWorkout.name,
            description = original.description?.takeIf { it.isNotBlank() },
            startTime = original.startTime,
            endTime = endTimeIso,
            exercises = mergedExercises
        )
    )
}

/** Convert a GET response set to PUT format, preserving all fields exactly. */
private fun WorkoutSetResponse.toPutSet() = WorkoutPutSet(
    type = type,
    weightKg = weightKg,
    reps = reps,
    distanceMeters = distanceMeters,
    durationSeconds = durationSeconds,
    customMetric = customMetric?.toInt(),
    rpe = rpe
)

// ── V2 workout request builder ────────────────────────────────────────────────

private val ISO_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * Builds a v2 POST body that merges an original workout (from `GET /workout/{id}`)
 * with newly completed exercises + sets + HR samples from the resumed session.
 *
 * Used in the resume POST+DELETE flow: after this body POSTs successfully,
 * the caller deletes the original by `original.id`.
 *
 * Behavior:
 *  - title / description / start_time / end_time / is_private inherited from
 *    original — the resumed workout keeps the original session's exact window
 *    so re-finishing an incomplete workout days later can't stretch its
 *    duration. The resumed-segment time the user spent is deliberately not
 *    added (see the completed_at clamp below).
 *  - new sets' completed_at is clamped to `original.endTime` so it stays inside
 *    the [start_time, end_time] window. Hevy derives the displayed duration
 *    from the latest set's completed_at (NOT the end_time we send), so a raw
 *    wall-clock "now" stamp on a resume days later made the workout read as
 *    hours/days long. Clamping keeps every set inside the original window.
 *  - workout_id is a fresh UUID (the new workout is a distinct server record)
 *  - exercises merged with the same "originals verbatim + new completed sets
 *    appended" rule the public PUT path used to use
 *  - biometrics = original samples ++ new samples (chronological); the merged
 *    [combinedBiometrics] is computed by the caller (it needs demographics
 *    we don't pass here)
 *  - wearos_watch=true, is_biometrics_public defaults to true via the model
 */
internal fun buildResumePostRequestV2(
    original: WorkoutDetailResponseV2,
    active: ActiveWorkout,
    endTimeMs: Long,
    combinedBiometrics: BiometricsBody?
): WorkoutPostRequestV2 {
    // Latest instant a resumed set may claim: the original workout's end. Any
    // set logged during the resumed segment (its completedAtMs is real "now",
    // days after the original session) is pulled back to here so the server
    // can't derive a multi-hour duration from it.
    val windowEndMs = original.endTime * 1000L
    // ── exercise merge (mirrors buildWorkoutPutRequest's logic, adapted for
    // the v2 schema) ─────────────────────────────────────────────────────────
    val originalByTemplate = original.exercises.associateBy { it.exerciseTemplateId }
    val merged = mutableListOf<WorkoutPostExerciseV2>()
    val handledTemplateIds = mutableSetOf<String>()

    for (origEx in original.exercises) {
        handledTemplateIds.add(origEx.exerciseTemplateId)
        val activeEx = active.exercises.firstOrNull {
            it.exerciseTemplateId == origEx.exerciseTemplateId
        }
        val originalSets = origEx.sets.mapIndexed { idx, s -> s.toPostSetV2(idx) }
        val newSets: List<WorkoutPostSetV2> = if (activeEx != null) {
            // Active workout has all original sets marked as completed+locked
            // followed by any newly completed sets. Skip the locked-original
            // prefix to avoid double-counting.
            val originalSetCount = origEx.sets.size
            activeEx.sets.drop(originalSetCount)
                .filter { it.completed }
                .mapIndexed { idx, set ->
                    WorkoutPostSetV2(
                        index = originalSetCount + idx,
                        type = set.setType.apiValue,
                        weightKg = if (activeEx.exerciseType.usesWeight) set.weightKg else null,
                        reps = if (activeEx.exerciseType.usesReps) set.reps else null,
                        distanceMeters = null,
                        durationSeconds = null,
                        customMetric = null,
                        rpe = null,
                        completedAt = set.completedAtMs
                            ?.let { ISO_FORMATTER.format(Instant.ofEpochMilli(minOf(it, windowEndMs))) }
                    )
                }
        } else emptyList()
        merged.add(
            WorkoutPostExerciseV2(
                title = origEx.title.orEmpty(),
                exerciseTemplateId = origEx.exerciseTemplateId,
                supersetId = origEx.supersetId,
                restTimerSeconds = origEx.restSeconds,
                notes = origEx.notes.orEmpty(),
                sets = originalSets + newSets
            )
        )
    }

    // Exercises in the active workout that weren't in the original (user
    // added during the resumed session).
    for (activeEx in active.exercises) {
        if (activeEx.exerciseTemplateId in handledTemplateIds) continue
        val completed = activeEx.sets.filter { it.completed }
        if (completed.isEmpty()) continue
        merged.add(
            WorkoutPostExerciseV2(
                title = activeEx.title,
                exerciseTemplateId = activeEx.exerciseTemplateId,
                supersetId = null,
                restTimerSeconds = activeEx.restTimerSeconds,
                notes = activeEx.notes?.takeIf { it.isNotBlank() }.orEmpty(),
                sets = completed.mapIndexed { idx, set ->
                    WorkoutPostSetV2(
                        index = idx,
                        type = set.setType.apiValue,
                        weightKg = if (activeEx.exerciseType.usesWeight) set.weightKg else null,
                        reps = if (activeEx.exerciseType.usesReps) set.reps else null,
                        distanceMeters = null,
                        durationSeconds = null,
                        customMetric = null,
                        rpe = null,
                        completedAt = set.completedAtMs
                            ?.let { ISO_FORMATTER.format(Instant.ofEpochMilli(minOf(it, windowEndMs))) }
                    )
                }
            )
        )
    }

    // ── timing: keep the original session window verbatim ───────────────────
    // The resumed workout inherits the original [start_time, end_time] so
    // re-finishing an incomplete workout days later can't stretch its duration.
    // (endTimeMs / active.startTimeMs — the live resume-timer offset — are no
    // longer used for the saved window; the timer is display-only now.)
    return WorkoutPostRequestV2(
        workout = WorkoutPostBodyV2(
            title = original.name ?: active.name,
            description = original.description.orEmpty(),
            startTime = original.startTime,
            endTime = original.endTime,
            routineId = active.routineId,
            workoutId = UUID.randomUUID().toString(),
            isBiometricsPublic = original.isBiometricsPublic ?: true,
            biometrics = combinedBiometrics,
            exercises = merged
        )
    )
}

/** Combine the original biometrics blob with the resumed segment's HR samples
 *  into a single chronological list. Sample dedup is not necessary because the
 *  watch only samples during the resumed-segment timeline, which is strictly
 *  after `original.heart_rate_samples`. */
internal fun combineHeartRateSamples(
    originalSamples: List<HeartRateSampleBody>,
    newSamples: List<com.example.hevywatch.data.model.HeartRateSample>
): List<HeartRateSampleBody> {
    val mappedNew = newSamples.map { HeartRateSampleBody(bpm = it.bpm, timestampMs = it.timestamp_ms) }
    return (originalSamples + mappedNew).sortedBy { it.timestampMs }
}

private fun WorkoutDetailSetV2.toPostSetV2(fallbackIndex: Int) = WorkoutPostSetV2(
    index = index ?: fallbackIndex,
    // Sanitised rather than echoed. This is the one place a set type comes back
    // from the server instead of from our own SetType enum: every other builder
    // sends set.setType.apiValue, which cannot be anything but the four values
    // the API accepts. Echoing `indicator` raw means a value Hevy adds later
    // goes straight back out in the next request and is rejected -- and because
    // resume is the only path that echoes, it breaks while a normal POST keeps
    // working. fromApiValue already maps anything unrecognised to NORMAL.
    type = SetType.fromApiValue(indicator ?: "normal").apiValue,
    weightKg = weightKg,
    reps = reps,
    distanceMeters = distanceMeters,
    durationSeconds = durationSeconds,
    customMetric = customMetric?.toInt(),
    rpe = rpe,
    completedAt = completedAt
)

internal fun buildWorkoutPostRequestV2(
    w: ActiveWorkout,
    endTimeMs: Long,
    biometrics: BiometricsBody? = null
) = WorkoutPostRequestV2(
    workout = WorkoutPostBodyV2(
        title = w.name,
        startTime = w.startTimeMs / 1000L,
        endTime = endTimeMs / 1000L,
        routineId = w.routineId,
        workoutId = UUID.randomUUID().toString(),
        biometrics = biometrics,
        exercises = w.exercises.mapNotNull { exercise ->
            val completed = exercise.sets.filter { it.completed }
            if (completed.isEmpty()) null
            else WorkoutPostExerciseV2(
                title = exercise.title,
                exerciseTemplateId = exercise.exerciseTemplateId,
                restTimerSeconds = exercise.restTimerSeconds,
                sets = completed.mapIndexed { idx, set ->
                    WorkoutPostSetV2(
                        index = idx,
                        type = set.setType.apiValue,
                        weightKg = if (exercise.exerciseType.usesWeight) set.weightKg else null,
                        reps = if (exercise.exerciseType.usesReps) set.reps else null,
                        distanceMeters = null,
                        durationSeconds = null,
                        customMetric = null,
                        rpe = null,
                        completedAt = set.completedAtMs?.let { ISO_FORMATTER.format(Instant.ofEpochMilli(it)) }
                    )
                }
            )
        }
    )
)
