package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

/** Request body for PUT /v1/workouts/{workoutId} (public API). */
data class WorkoutPutRequest(
    @SerializedName("workout") val workout: WorkoutPutBody
)

data class WorkoutPutBody(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String,
    // NO is_private HERE, DELIBERATELY.
    // `is_private` is absent from the API's Workout response schema, so a
    // resume can never learn the original workout's visibility — GET
    // /v1/workouts/{id} simply doesn't report it. Sending the field anyway
    // meant sending a guess, and the guess was a hardcoded `false`, which
    // silently reverted every private workout to public on a v1-PUT resume.
    // Omitting the key entirely leaves the stored visibility untouched.
    @SerializedName("exercises") val exercises: List<WorkoutPutExercise>
)

data class WorkoutPutExercise(
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: String? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("sets") val sets: List<WorkoutPutSet>
)

data class WorkoutPutSet(
    @SerializedName("type") val type: String,
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    @SerializedName("distance_meters") val distanceMeters: Float?,
    @SerializedName("duration_seconds") val durationSeconds: Int?,
    @SerializedName("custom_metric") val customMetric: Int?,
    @SerializedName("rpe") val rpe: Float?
)
