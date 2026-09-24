package com.example.hevywatch.data.store

import android.content.Context
import com.example.hevywatch.data.api.model.RoutineFolderResponse
import com.google.gson.reflect.TypeToken

/**
 * Persists the routine-folder list to SharedPreferences so the Folders tab
 * paints instantly on relaunch instead of waiting for the GET /v1/routine_folders
 * round-trip. Folders change rarely — a cold-start cache is a pure win.
 */
class FolderCacheStore(context: Context) : JsonPrefsStore<List<RoutineFolderResponse>>(
    context,
    prefsName = "folder_cache",
    key = "folders_json",
    type = object : TypeToken<List<RoutineFolderResponse>>() {}.type,
) {
    fun load(): List<RoutineFolderResponse> = loadOrNull() ?: emptyList()
}
