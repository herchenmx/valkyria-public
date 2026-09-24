package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

data class WorkoutsListResponse(
    @SerializedName("workouts") val workouts: List<WorkoutSummaryResponse>,
    @SerializedName("page") val page: Int = 1,
    @SerializedName("page_count") val pageCount: Int = 1
)

data class WorkoutSummaryResponse(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String?,
    @SerializedName("routine_id") val routineId: String?,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("created_at") val createdAt: String? = null
)
