package com.example.hevywatch.data.api.model

import com.google.gson.annotations.SerializedName

data class RoutineFoldersResponse(
    @SerializedName("routine_folders") val routineFolders: List<RoutineFolderResponse>,
    @SerializedName("page") val page: Int = 1,
    @SerializedName("page_count") val pageCount: Int = 1
)

data class RoutineFolderResponse(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("index") val index: Int
)
