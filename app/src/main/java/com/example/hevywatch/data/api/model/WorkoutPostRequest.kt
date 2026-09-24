package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

data class WorkoutPostRequest(
    @SerializedName("workout") val workout: WorkoutPostBody
)

data class WorkoutPostBody(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String?,
    @SerializedName("start_time") val startTime: String,   // ISO 8601
    @SerializedName("end_time") val endTime: String,       // ISO 8601
    // NO is_private HERE, DELIBERATELY — the app has no privacy control, so it
    // has nothing to assert. Sending a hardcoded `false` overrode the account's
    // default visibility. Omitted, so that default governs. Mirrors the v2
    // bodies (WorkoutPostBodyV2) and the resume PUT (WorkoutPutBody).
    @SerializedName("exercises") val exercises: List<WorkoutPostExercise>
)

data class WorkoutPostExercise(
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: Int?,
    @SerializedName("notes") val notes: String?,
    @SerializedName("sets") val sets: List<WorkoutPostSet>
)

data class WorkoutPostSet(
    @SerializedName("type") val type: String,
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    @SerializedName("distance_meters") val distanceMeters: Float?,
    @SerializedName("duration_seconds") val durationSeconds: Int?,
    @SerializedName("custom_metric") val customMetric: Int?,
    @SerializedName("rpe") val rpe: Float?
)
