package com.example.hevywatch.data

/**
 * Equipment values (lowercased, as the Hevy catalog returns them) that mean the
 * exercise carries no external load — so there is no "working weight" to seed a
 * suggestion from, and no similar-exercise weight to scale.
 *
 * Single source of truth for the "is this bodyweight?" test. Both
 * [WorkoutDataLoader.prefetchSimilarExerciseHistory] and
 * [SimilarExerciseSuggestion.find] gate on this — comparing on
 * `equipment.lowercase()` so a non-lowercase value from the API can't make the
 * two paths disagree (the prefetch skip and the suggestion skip must line up,
 * else we'd fetch history for a bodyweight exercise we'd never suggest for, or
 * vice-versa).
 */
internal val BODYWEIGHT_EQUIPMENT: Set<String> =
    setOf("none", "resistance_band", "suspension", "other")
