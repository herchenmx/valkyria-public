package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

data class WorkoutDetailResponse(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("routine_id") val routineId: String?,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String?,
    // ALWAYS null in practice: `is_private` is not part of the API's Workout
    // response schema, so GET /v1/workouts/{id} never sends it. Kept only so
    // the field would populate if that ever changes — do NOT use it to decide
    // what to put in a request body, and never default it to `false`: that is
    // what silently republished private workouts on resume.
    @SerializedName("is_private") val isPrivate: Boolean? = null,
    @SerializedName("exercises") val exercises: List<WorkoutExerciseResponse>
)

data class WorkoutExerciseResponse(
    @SerializedName("index") val index: Int,
    @SerializedName("title") val title: String?,
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: Int? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("sets") val sets: List<WorkoutSetResponse>
)

data class WorkoutSetResponse(
    @SerializedName("index") val index: Int,
    @SerializedName("type") val type: String,
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    @SerializedName("distance_meters") val distanceMeters: Float?,
    @SerializedName("duration_seconds") val durationSeconds: Int?,
    @SerializedName("custom_metric") val customMetric: Float? = null,
    @SerializedName("rpe") val rpe: Float? = null
)
