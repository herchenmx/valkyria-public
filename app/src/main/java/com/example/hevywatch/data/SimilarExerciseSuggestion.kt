package com.example.hevywatch.data

import com.example.hevywatch.HevyApp
import kotlin.math.roundToInt

/**
 * A weight suggestion derived from a similar exercise (same equipment + primary muscle group).
 */
data class SuggestedWeight(
    val weightKg: Float,
    val sourceExerciseName: String
)

private const val SIMILAR_EXERCISE_FRACTION = 0.60f
private const val SUGGESTION_ROUND_KG = 2.5f
private const val BARBELL_MIN_KG = 20f

/**
 * Conservative starting weight for a new exercise: 60% of the matched similar exercise,
 * rounded to the nearest 2.5kg multiple. Barbell exercises are floored at 20kg (Olympic
 * bar) — the suggestion can't go below the bar itself. Exposed `internal` so it can be
 * unit-tested without constructing a HevyApp instance.
 */
internal fun scaleSimilarExerciseWeight(similarWeightKg: Float, equipment: String? = null): Float {
    val scaled = ((similarWeightKg * SIMILAR_EXERCISE_FRACTION) / SUGGESTION_ROUND_KG)
        .roundToInt() * SUGGESTION_ROUND_KG
    return if (equipment?.lowercase() == "barbell") scaled.coerceAtLeast(BARBELL_MIN_KG) else scaled
}

/**
 * Finds the lowest last-session working weight among exercises that share the same
 * equipment AND primary muscle group as the given exercise. Returns null if no
 * similar exercise with history exists.
 */
object SimilarExerciseSuggestion {

    fun find(
        exerciseTemplateId: String,
        hevyApp: HevyApp
    ): SuggestedWeight? {
        val equipment = hevyApp.exerciseEquipment[exerciseTemplateId] ?: return null
        val muscleGroup = hevyApp.exerciseMuscleGroup[exerciseTemplateId] ?: return null

        // Skip bodyweight / no-weight equipment. Compare on the lowercased value
        // via the shared [BODYWEIGHT_EQUIPMENT] set so this stays in lockstep
        // with WorkoutDataLoader.prefetchSimilarExerciseHistory (which already
        // lowercased) — a non-lowercase API value must skip both or neither.
        if (equipment.lowercase() in BODYWEIGHT_EQUIPMENT) return null

        var lowestWeight: Float? = null
        var lowestName: String? = null

        for ((templateId, eq) in hevyApp.exerciseEquipment) {
            if (templateId == exerciseTemplateId) continue
            if (!eq.equals(equipment, ignoreCase = true)) continue
            val mg = hevyApp.exerciseMuscleGroup[templateId] ?: continue
            if (!mg.equals(muscleGroup, ignoreCase = true)) continue

            // This exercise matches — check if it has history
            val history = hevyApp.exerciseHistoryCache[templateId]
                ?.exerciseHistory.orEmpty()
            val latestWorkoutId = history.firstOrNull()?.workoutId ?: continue
            val latestNormal = history.filter {
                it.workoutId == latestWorkoutId &&
                    it.setType.equals("normal", ignoreCase = true)
            }
            val lastWeight = latestNormal.firstOrNull()?.weightKg ?: continue
            if (lastWeight <= 0f) continue

            if (lowestWeight == null || lastWeight < lowestWeight) {
                lowestWeight = lastWeight
                // Look up exercise name from cached routines or fall back to template ID
                lowestName = findExerciseName(templateId, hevyApp)
            }
        }

        if (lowestWeight == null || lowestName == null) return null
        val scaled = scaleSimilarExerciseWeight(lowestWeight, equipment)
        if (scaled <= 0f) return null
        return SuggestedWeight(scaled, lowestName)
    }

    private fun findExerciseName(templateId: String, hevyApp: HevyApp): String {
        // Search cached routines for a matching exercise name
        for (routine in hevyApp.cachedRoutines) {
            for (ex in routine.exercises) {
                if (ex.exerciseTemplateId == templateId) return ex.title
            }
        }
        return templateId
    }
}
