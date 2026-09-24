package com.example.hevywatch.data.model

/**
 * A single routine workout's volume summary, used for routine-level progress tracking.
 *
 * @param workoutId  The Hevy workout ID
 * @param date       ISO 8601 start time of the workout
 * @param totalVolumeKg  Sum of (weight_kg x reps) for all sets in the workout
 * @param deltaKg    Difference from the preceding workout's totalVolumeKg (null for the oldest entry)
 */
data class RoutineWorkoutVolume(
    val workoutId: String,
    val date: String,
    val totalVolumeKg: Double,
    val deltaKg: Double?
)
