package com.example.hevywatch.data.store

import android.content.Context

/**
 * Persists the user's per-watch tile preferences. Today only the "which
 * folder feeds the tile" choice; future expansion (dark/light, content
 * variants) can sit in the same prefs file.
 *
 * Falls back to [DEFAULT_TILE_FOLDER_ID] when unset, which preserves the
 * historical behavior — fresh installs see exactly what they used to.
 */
class TilePreferenceStore(context: Context) {

    private val prefs = context.getSharedPreferences("tile_prefs", Context.MODE_PRIVATE)

    /** Folder id whose routines populate the idle tile. Null/blank = use default. */
    var tileFolderId: String
        get() {
            val v = prefs.getString(KEY_FOLDER_ID, null)
            return if (v.isNullOrBlank()) DEFAULT_TILE_FOLDER_ID else v
        }
        set(value) {
            prefs.edit().putString(KEY_FOLDER_ID, value).apply()
        }

    fun resetToDefault() {
        prefs.edit().remove(KEY_FOLDER_ID).apply()
    }

    companion object {
        const val DEFAULT_TILE_FOLDER_ID = "2525049"
        private const val KEY_FOLDER_ID = "tile_folder_id"
    }
}
