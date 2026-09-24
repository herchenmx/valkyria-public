package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

// The API returns a flat list of individual set records — one entry per set logged.
// Grouping by workout_id is done in the ViewModel.
data class ExerciseHistoryResponse(
    @SerializedName("exercise_history") val exerciseHistory: List<ExerciseHistoryEntry>? = null
)

data class ExerciseHistoryEntry(
    @SerializedName("workout_id") val workoutId: String,
    @SerializedName("workout_title") val workoutTitle: String?,
    @SerializedName("workout_start_time") val workoutStartTime: String,
    @SerializedName("workout_end_time") val workoutEndTime: String?,
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    @SerializedName("distance_meters") val distanceMeters: Float?,
    @SerializedName("duration_seconds") val durationSeconds: Int?,
    @SerializedName("rpe") val rpe: Float?,
    @SerializedName("custom_metric") val customMetric: Float?,
    @SerializedName("set_type") val setType: String
)
