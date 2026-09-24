package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

data class RoutinesResponse(
    @SerializedName("routines") val routines: List<RoutineResponse>,
    @SerializedName("page") val page: Int = 1,
    @SerializedName("page_count") val pageCount: Int = 1
)

/** Wrapper for GET /v1/routines/{routineId} which returns {"routine": {...}} */
data class RoutineDetailResponse(
    @SerializedName("routine") val routine: RoutineResponse
)

data class RoutineResponse(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("notes") val notes: String?,
    @SerializedName("folder_id") val folderId: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("exercises") val exercises: List<RoutineExerciseResponse>
)

data class RoutineExerciseResponse(
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("index") val index: Int = 0,
    @SerializedName("title") val title: String?,
    @SerializedName("notes") val notes: String?,
    @SerializedName("supersets_id") val supersetsId: String?,
    @SerializedName("rest_seconds") val restSeconds: Int?,
    @SerializedName("equipment") val equipment: String? = null,
    @SerializedName("sets") val sets: List<RoutineSetResponse>
)

data class RoutineSetResponse(
    @SerializedName("type") val type: String,
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    // The API returns rep ranges as a nested object: { "rep_range": { "start": 10, "end": 15 } }
    @SerializedName("rep_range") val repRange: RepRangeResponse? = null,
    @SerializedName("distance_meters") val distanceMeters: Float?,
    @SerializedName("duration_seconds") val durationSeconds: Int?,
    @SerializedName("custom_metric") val customMetric: Int?,
    @SerializedName("rpe_rating") val rpeRating: Float?
)

data class RepRangeResponse(
    @SerializedName("start") val start: Int?,
    @SerializedName("end") val end: Int?
)
