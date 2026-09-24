package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

/**
 * Response shape of `GET /workout/{id}` (private v2). Distinct from
 * [WorkoutDetailResponse] (v1 GET) on two axes:
 *   - times are unix-seconds (Long), not ISO strings
 *   - biometrics + wearos_watch / is_biometrics_public flags are included
 *
 * Schema inferred from a live probe round-trip. Only fields the watch
 * actually uses on the resume merge path are modelled — the response carries
 * many more (i18n titles, exercise media URLs, social fields like
 * like_count/comments, server-derived nth_workout / estimated_volume_kg, …)
 * that are safe to ignore for our purpose.
 */
data class WorkoutDetailResponseV2(
    @SerializedName("id") val id: String,
    @SerializedName("short_id") val shortId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("start_time") val startTime: Long,
    @SerializedName("end_time") val endTime: Long,
    @SerializedName("is_private") val isPrivate: Boolean?,
    @SerializedName("is_biometrics_public") val isBiometricsPublic: Boolean?,
    @SerializedName("wearos_watch") val wearosWatch: Boolean?,
    @SerializedName("apple_watch") val appleWatch: Boolean?,
    @SerializedName("biometrics") val biometrics: BiometricsBody?,
    @SerializedName("exercises") val exercises: List<WorkoutDetailExerciseV2>
)

data class WorkoutDetailExerciseV2(
    @SerializedName("id") val id: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("notes") val notes: String?,
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: String?,
    @SerializedName("rest_seconds") val restSeconds: Int?,
    @SerializedName("sets") val sets: List<WorkoutDetailSetV2>
)

data class WorkoutDetailSetV2(
    @SerializedName("id") val id: String?,
    @SerializedName("index") val index: Int?,
    @SerializedName("indicator") val indicator: String?,   // "normal", "warmup", "dropset", "failure"
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    @SerializedName("distance_meters") val distanceMeters: Float?,
    @SerializedName("duration_seconds") val durationSeconds: Int?,
    @SerializedName("custom_metric") val customMetric: Float?,
    @SerializedName("rpe") val rpe: Float?,
    @SerializedName("completed_at") val completedAt: String?
)
