package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

data class ExerciseTemplatesResponse(
    @SerializedName("exercise_templates") val exerciseTemplates: List<ExerciseTemplateResponse>,
    @SerializedName("page") val page: Int = 1,
    @SerializedName("page_count") val pageCount: Int = 1
)

data class ExerciseTemplateResponse(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("type") val type: String,
    @SerializedName("primary_muscle_group") val primaryMuscleGroup: String?,
    @SerializedName("secondary_muscle_groups") val secondaryMuscleGroups: List<String>?,
    @SerializedName("equipment") val equipment: String?,
    @SerializedName("is_custom") val isCustom: Boolean
)
