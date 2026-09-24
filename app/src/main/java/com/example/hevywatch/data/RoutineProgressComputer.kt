package com.example.hevywatch.data

import com.example.hevycore.exercise.AssistedBodyweight
import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.model.RoutineWorkoutVolume
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Pure computation: builds routine-level volume progress from workout detail responses.
 * Stateless and fully testable without Android context.
 */
object RoutineProgressComputer {

    /**
     * Parse an ISO-ish timestamp into an [Instant], tolerating multiple formats:
     * - "2026-04-12T10:00:00Z"          → Instant.parse
     * - "2026-04-12T10:00:00+00:00"     → OffsetDateTime.parse
     * - "2026-04-12T10:00:00"           → LocalDateTime at UTC
     * - "2026-04-12 10:00:00"           → space-separated variant
     */
    internal fun parseInstant(iso: String): Instant? {
        try { return Instant.parse(iso) } catch (_: Exception) {}
        try { return OffsetDateTime.parse(iso).toInstant() } catch (_: Exception) {}
        try { return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) } catch (_: Exception) {}
        val tVariant = iso.replace(' ', 'T')
        if (tVariant != iso) {
            try { return LocalDateTime.parse(tVariant).toInstant(ZoneOffset.UTC) } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Compute total volume for a single workout: sum of (weight_kg × reps) across all sets
     * of all exercises.
     *
     * When [routineId] matches [AssistedBodyweight.ROUTINE_ID] AND a set belongs to one of
     * the [AssistedBodyweight.TEMPLATE_IDS], the contribution becomes
     * `(bodyweightKg − weight_kg) × reps` — i.e. the bodyweight portion the lifter actually
     * shifted, since logged kg on those exercises is the stack assistance, not the load.
     */
    fun workoutVolume(
        workout: WorkoutDetailResponse,
        routineId: String? = null,
        bodyweightKg: Float = 0f
    ): Double {
        val applyAssisted = routineId == AssistedBodyweight.ROUTINE_ID
        return workout.exercises.sumOf { exercise ->
            val isAssistedHere = applyAssisted &&
                AssistedBodyweight.isAssisted(exercise.exerciseTemplateId)
            exercise.sets.sumOf { set ->
                val w = (set.weightKg ?: 0f).toDouble()
                val r = (set.reps ?: 0).toDouble()
                val effective = if (isAssistedHere) {
                    (bodyweightKg.toDouble() - w).coerceAtLeast(0.0)
                } else w
                effective * r
            }
        }
    }

    /**
     * Compute routine-level workout volumes with deltas from full workout detail responses.
     *
     * @param workouts     List of [WorkoutDetailResponse] for this routine (from GET /v1/workouts/{id})
     * @param monthsBack   How many months of data to include (default 3)
     * @param routineId    Routine the workouts belong to — drives assisted-bodyweight inversion
     * @param bodyweightKg User bodyweight in kg, used when [routineId] requires the inversion
     * @return  List of [RoutineWorkoutVolume] sorted newest-first, with delta from preceding workout
     */
    fun compute(
        workouts: List<WorkoutDetailResponse>,
        monthsBack: Long = 3,
        routineId: String? = null,
        bodyweightKg: Float = 0f
    ): List<RoutineWorkoutVolume> {
        if (workouts.isEmpty()) return emptyList()

        val cutoff = Instant.now().minus(monthsBack * 30, ChronoUnit.DAYS)

        // Build volume summaries, filtered by date
        val volumes = workouts.mapNotNull { workout ->
            val instant = parseInstant(workout.startTime)
            if (instant != null && instant.isBefore(cutoff)) return@mapNotNull null

            Triple(workout.id, workout.startTime, workoutVolume(workout, routineId, bodyweightKg))
        }

        // Sort by date ascending for delta computation
        val sorted = volumes.sortedBy { it.second }

        // Compute deltas
        val result = sorted.mapIndexed { index, (workoutId, date, volume) ->
            val delta = if (index > 0) volume - sorted[index - 1].third else null
            RoutineWorkoutVolume(workoutId, date, volume, delta)
        }

        return result.reversed()
    }

    /**
     * Compute the average delta across all entries that have one.
     */
    fun averageDelta(volumes: List<RoutineWorkoutVolume>): Double? {
        val deltas = volumes.mapNotNull { it.deltaKg }
        if (deltas.isEmpty()) return null
        return deltas.sum() / deltas.size
    }
}
