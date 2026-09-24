package com.example.hevycompanion.recents

import com.example.hevycompanion.data.WorkoutDetail
import com.example.hevycompanion.data.WorkoutDetailResponseV2
import com.example.hevycompanion.data.WorkoutPostBodyV2
import com.example.hevycompanion.data.WorkoutPostExerciseV2
import com.example.hevycompanion.data.WorkoutPostRequestV2
import com.example.hevycompanion.data.WorkoutPostSetV2
import com.example.hevycompanion.data.WorkoutPutBody
import com.example.hevycompanion.data.WorkoutPutExercise
import com.example.hevycompanion.data.WorkoutPutRequest
import com.example.hevycompanion.data.WorkoutPutSet
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A set the user logged on the phone during a resume (warmup or normal). */
data class ResumeNewSet(val type: String, val weightKg: Float?, val reps: Int?)

/** The newly logged sets for one exercise, with the title to use if it's an
 *  exercise that wasn't in the original workout (a filled MISSING slot). */
data class ResumeNewExercise(val templateId: String, val title: String, val sets: List<ResumeNewSet>)

/**
 * Builds the resume submission body, mirroring the watch's `WorkoutRequestBuilder`:
 *
 *  - [buildPostV2] — the **primary** path: a merged `POST /v2/workout` that
 *    replaces the original (the original is DELETEd after a successful POST).
 *    Every original exercise/set is preserved verbatim (incl. the original
 *    biometrics, passed straight through), new sets are appended, and filled
 *    MISSING slots are added as new exercises. This is what the watch switched
 *    to in `d12a5f2` so HR survives a resume.
 *  - [buildPutV1] — the **fallback**: an in-place `PUT /v1/workouts/{id}`, used
 *    only when the private v2 detail isn't available (not logged in, or the v2
 *    GET failed). Biometrics are lost on this path, exactly as on the watch.
 *
 * Both share the same `original verbatim + new sets appended + new exercises`
 * merge; only the wire schema differs.
 */
object ResumeRequestBuilder {

    // ── primary: merged POST /v2/workout ─────────────────────────────────────

    /**
     * @param original   the workout from the private `GET /workout/{id}` (v2).
     * @param routineId  the routine this workout was logged from (the v2 GET
     *   doesn't echo it; the companion already has it from the v1 detail).
     * @param newExercises newly logged sets per exercise.
     * @param nowIso     ISO-8601 `completed_at` stamp for the new sets.
     */
    fun buildPostV2(
        original: WorkoutDetailResponseV2,
        routineId: String?,
        newExercises: List<ResumeNewExercise>,
        nowIso: String,
    ): WorkoutPostRequestV2 {
        val newByTemplate = newExercises.associateBy { it.templateId }
        val handled = mutableSetOf<String>()
        val merged = mutableListOf<WorkoutPostExerciseV2>()

        // completed_at for phone-logged sets: clamped to the original workout's
        // end so it stays inside the [start_time, end_time] window. Hevy derives
        // the displayed duration from the latest set's completed_at (not the
        // end_time we send), so stamping a resume-days-later at wall-clock `now`
        // made the workout read as hours/days long. Back-dating to the window
        // end keeps the original duration.
        val windowEndIso = ISO_MILLIS_UTC.format(Instant.ofEpochSecond(original.endTime))
        val newSetIso = earlierIso(nowIso, windowEndIso)

        // 1. Original exercises verbatim, new sets appended after them.
        for (origEx in original.exercises) {
            handled += origEx.exerciseTemplateId
            val originalSets = origEx.sets.mapIndexed { idx, s ->
                WorkoutPostSetV2(
                    index = s.index ?: idx,
                    // Sanitised, not echoed -- see the watch's toPostSetV2. The
                    // companion has no SetType enum, so the accepted vocabulary
                    // is spelled out here.
                    type = sanitizeSetType(s.indicator),
                    weightKg = s.weightKg,
                    reps = s.reps,
                    distanceMeters = s.distanceMeters,
                    durationSeconds = s.durationSeconds,
                    customMetric = s.customMetric,
                    rpe = s.rpe,
                    completedAt = s.completedAt,
                )
            }
            val newSets = newByTemplate[origEx.exerciseTemplateId]?.sets.orEmpty()
                .mapIndexed { i, ns -> ns.toPostSetV2(origEx.sets.size + i, newSetIso) }
            merged += WorkoutPostExerciseV2(
                title = origEx.title.orEmpty(),
                exerciseTemplateId = origEx.exerciseTemplateId,
                supersetId = origEx.supersetId,
                restTimerSeconds = origEx.restSeconds,
                notes = origEx.notes.orEmpty(),
                sets = originalSets + newSets,
            )
        }

        // 2. Brand-new exercises (filled MISSING slots).
        for (ex in newExercises) {
            if (ex.templateId in handled || ex.sets.isEmpty()) continue
            merged += WorkoutPostExerciseV2(
                title = ex.title,
                exerciseTemplateId = ex.templateId,
                supersetId = null,
                restTimerSeconds = null,
                notes = "",
                sets = ex.sets.mapIndexed { i, ns -> ns.toPostSetV2(i, newSetIso) },
            )
        }

        return WorkoutPostRequestV2(
            workout = WorkoutPostBodyV2(
                title = original.name ?: "Workout",
                description = original.description.orEmpty(),
                startTime = original.startTime,
                // No live timer on the phone, so preserve the recorded window.
                endTime = original.endTime,
                routineId = routineId,
                workoutId = UUID.randomUUID().toString(),
                isBiometricsPublic = original.isBiometricsPublic ?: true,
                // Pass the original session's biometrics straight through so the
                // HR chart survives the replace (the phone adds none of its own).
                biometrics = original.biometrics,
                exercises = merged,
            )
        )
    }

    /** The set types the Hevy API accepts. Anything else -- including a value
     *  Hevy introduces after this build ships -- degrades to "normal" rather
     *  than being echoed back into a request that would then be rejected.
     *  Mirrors SetType.fromApiValue on the watch. */
    private val ACCEPTED_SET_TYPES = setOf("normal", "warmup", "dropset", "failure")

    internal fun sanitizeSetType(indicator: String?): String =
        if (indicator in ACCEPTED_SET_TYPES) indicator!! else "normal"

    private val ISO_MILLIS_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** The earlier of two ISO-8601 instants (lexical compare is chronological
     *  for this fixed UTC millis format). Used to clamp a phone-logged set's
     *  `completed_at` to the original workout's end so a resume days later can't
     *  push the workout's derived duration past its recorded window. Falls back
     *  to [a] if either fails to parse. */
    private fun earlierIso(a: String, b: String): String =
        try {
            if (Instant.parse(b).isBefore(Instant.parse(a))) b else a
        } catch (_: Exception) {
            a
        }

    private fun ResumeNewSet.toPostSetV2(index: Int, nowIso: String) = WorkoutPostSetV2(
        index = index,
        type = type,
        weightKg = weightKg,
        reps = reps,
        distanceMeters = null,
        durationSeconds = null,
        customMetric = null,
        rpe = null,
        completedAt = nowIso,
    )

    // ── fallback: in-place PUT /v1/workouts/{id} ─────────────────────────────

    /**
     * @param original   the workout from the public `GET /v1/workouts/{id}`.
     * @param nowIso     ISO-8601 fallback if the original has no start/end time.
     *
     * `is_private` is deliberately not part of the body — see [WorkoutPutBody].
     */
    fun buildPutV1(
        original: WorkoutDetail,
        newExercises: List<ResumeNewExercise>,
        nowIso: String,
    ): WorkoutPutRequest {
        val newByTemplate = newExercises.associateBy { it.templateId }
        val handled = mutableSetOf<String>()
        val merged = mutableListOf<WorkoutPutExercise>()

        for (origEx in original.exercises) {
            handled += origEx.exerciseTemplateId
            val originalSets = origEx.sets.map {
                WorkoutPutSet(
                    type = it.type,
                    weightKg = it.weightKg,
                    reps = it.reps,
                    distanceMeters = it.distanceMeters,
                    durationSeconds = it.durationSeconds,
                    customMetric = it.customMetric,
                    rpe = it.rpe,
                )
            }
            val newSets = newByTemplate[origEx.exerciseTemplateId]?.sets.orEmpty()
                .map { WorkoutPutSet(type = it.type, weightKg = it.weightKg, reps = it.reps) }
            merged += WorkoutPutExercise(
                exerciseTemplateId = origEx.exerciseTemplateId,
                supersetId = origEx.supersetId?.toString(),
                notes = origEx.notes?.takeIf { it.isNotBlank() },
                sets = originalSets + newSets,
            )
        }

        for (ex in newExercises) {
            if (ex.templateId in handled || ex.sets.isEmpty()) continue
            merged += WorkoutPutExercise(
                exerciseTemplateId = ex.templateId,
                supersetId = null,
                notes = null,
                sets = ex.sets.map { WorkoutPutSet(type = it.type, weightKg = it.weightKg, reps = it.reps) },
            )
        }

        return WorkoutPutRequest(
            workout = WorkoutPutBody(
                title = original.title ?: "Workout",
                description = original.description?.takeIf { it.isNotBlank() },
                startTime = original.startTime ?: nowIso,
                endTime = original.endTime ?: original.startTime ?: nowIso,
                exercises = merged,
            )
        )
    }
}
