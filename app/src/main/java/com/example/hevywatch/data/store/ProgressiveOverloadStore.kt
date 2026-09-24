package com.example.hevywatch.data.store

import android.content.Context

class ProgressiveOverloadStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val enabledFolderIds: Set<String>
        get() = prefs.getStringSet(KEY_FOLDER_IDS, DEFAULT_FOLDER_IDS) ?: DEFAULT_FOLDER_IDS

    companion object {
        private const val PREFS_NAME = "progressive_overload"
        private const val KEY_FOLDER_IDS = "enabled_folder_ids"
        val DEFAULT_FOLDER_IDS: Set<String> = setOf("2525049")
    }
}
