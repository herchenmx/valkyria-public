package com.example.hevycompanion.browse

import com.example.hevycompanion.data.ExerciseTemplate

/**
 * Mirrors the watch app's [com.example.hevywatch.data.SimilarExerciseSuggestion]
 * matching rule — "same equipment AND same primary muscle group" — for the
 * companion's exercise detail screen. Returns every other catalog row that
 * shares both axes with the target, alphabetised by title.
 *
 * The watch additionally skips bodyweight-style equipment ("none",
 * "resistance_band", "suspension", "other") because its caller picks the
 * lowest historical weight and there's nothing to weight when there's no
 * weight. Browsing similar exercises has no such concern, so those rows are
 * still grouped here — a user looking at "Pull Up" benefits from seeing
 * "Chin Up" as a sibling.
 *
 * Comparison is case-insensitive on both axes because the public Hevy REST
 * catalog mixes casings ("Barbell" vs "barbell") across rows and the watch
 * already normalises this way.
 *
 * Returns an empty list — never throws — when the target's equipment or
 * primaryMuscleGroup is null/blank, since "missing axis" can't sensibly match
 * anything.
 */
object HevySimilarExercises {

    fun find(
        target: ExerciseTemplate,
        catalog: List<ExerciseTemplate>,
    ): List<ExerciseTemplate> {
        val equipment = target.equipment?.takeIf { it.isNotBlank() } ?: return emptyList()
        val muscle = target.primaryMuscleGroup?.takeIf { it.isNotBlank() } ?: return emptyList()
        return catalog.asSequence()
            .filter { it.id != target.id }
            .filter { it.equipment.equals(equipment, ignoreCase = true) }
            .filter { it.primaryMuscleGroup.equals(muscle, ignoreCase = true) }
            .sortedBy { it.title.lowercase() }
            .toList()
    }
}
