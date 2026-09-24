package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

data class WorkoutPostRequestV2(
    @SerializedName("workout") val workout: WorkoutPostBodyV2
)

data class WorkoutPostBodyV2(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String = "",
    @SerializedName("media") val media: List<Any> = emptyList(),
    @SerializedName("start_time") val startTime: Long,
    @SerializedName("end_time") val endTime: Long,
    @SerializedName("routine_id") val routineId: String?,
    @SerializedName("wearos_watch") val wearosWatch: Boolean = true,
    @SerializedName("apple_watch") val appleWatch: Boolean = false,
    @SerializedName("workout_id") val workoutId: String,
    // NO is_private HERE, DELIBERATELY.
    // `is_private` is absent from the Workout response schema, so a resume can
    // never read back the original workout's visibility — every value we could
    // put here is a guess, and the guess used to be a hardcoded `false`, which
    // published workouts the user had marked private. Omitting the key lets
    // the account's default visibility govern instead.
    //
    // This body is shared by the resume POST (buildResumePostRequestV2) and the
    // new-workout POST (buildPostRequest); neither has ever had a privacy
    // control to feed it, so neither should assert one.
    // Default true so the official Hevy app surfaces the HR chart on the
    // workout detail screen. The v1 PUT path can't change this later (it's
    // whitelisted out), so the choice we make here is sticky for the workout.
    @SerializedName("is_biometrics_public") val isBiometricsPublic: Boolean = true,
    @SerializedName("biometrics") val biometrics: BiometricsBody? = null,
    @SerializedName("trainer_program_id") val trainerProgramId: Any? = null,
    @SerializedName("exercises") val exercises: List<WorkoutPostExerciseV2>,
    @SerializedName("share_to_strava") val shareToStrava: Any? = null,
    @SerializedName("strava_activity_local_time") val stravaActivityLocalTime: Any? = null
)

/** Biometrics blob on POST /v2/workout. Server persists this and also returns
 *  a server-computed `average_heart_rate` derived from the samples — we don't
 *  send it. Discovered from decompiled Hevy Wear OS smali, verified by a live
 *  probe round-trip (`reference_biometrics_schema.md`). */
data class BiometricsBody(
    @SerializedName("total_calories") val totalCalories: Int?,
    @SerializedName("heart_rate_samples") val heartRateSamples: List<HeartRateSampleBody>
)

data class HeartRateSampleBody(
    @SerializedName("bpm") val bpm: Double,
    @SerializedName("timestamp_ms") val timestampMs: Long
)

data class WorkoutPostExerciseV2(
    @SerializedName("title") val title: String,
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: Any? = null,
    @SerializedName("rest_timer_seconds") val restTimerSeconds: Int?,
    @SerializedName("notes") val notes: String = "",
    @SerializedName("volume_doubling_enabled") val volumeDoublingEnabled: Boolean = false,
    @SerializedName("sets") val sets: List<WorkoutPostSetV2>
)

data class WorkoutPostSetV2(
    @SerializedName("index") val index: Int,
    @SerializedName("type") val type: String,
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    @SerializedName("distance_meters") val distanceMeters: Float?,
    @SerializedName("duration_seconds") val durationSeconds: Int?,
    @SerializedName("custom_metric") val customMetric: Int?,
    @SerializedName("rpe") val rpe: Float?,
    @SerializedName("completed_at") val completedAt: String?
)
