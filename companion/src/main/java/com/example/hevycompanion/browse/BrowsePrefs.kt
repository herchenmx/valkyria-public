package com.example.hevycompanion.browse

import android.content.Context
import com.example.hevycompanion.util.GsonHolder

/**
 * SharedPreferences-backed persistence for the unified Browser's per-source
 * filter state plus its last-active [Source] and [ViewMode]. Each filter is
 * round-tripped via Gson to keep the prefs schema trivial — adding a new
 * filter dimension only requires adding a field to [HevyExerciseListFilter] /
 * [MmExerciseListFilter].
 *
 * Pattern matches [com.example.hevycompanion.generate.GeneratorPrefs]; same
 * defensive behavior on corrupt JSON (fall back to a fresh empty filter).
 *
 * The two sources keep separate filter slots so flipping `Hevy → M&M → Hevy`
 * restores the user's Hevy chip selection. The last-active source and view
 * mode are persisted so re-opening the Browser lands you exactly where you
 * left off.
 */
class BrowsePrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = GsonHolder.gson

    var hevyFilter: HevyExerciseListFilter
        get() {
            val json = prefs.getString(KEY_HEVY, null) ?: return HevyExerciseListFilter()
            return runCatching {
                gson.fromJson(json, HevyExerciseListFilter::class.java) ?: HevyExerciseListFilter()
            }.getOrDefault(HevyExerciseListFilter())
        }
        set(value) {
            prefs.edit().putString(KEY_HEVY, gson.toJson(value)).apply()
        }

    var mmFilter: MmExerciseListFilter
        get() {
            val json = prefs.getString(KEY_MM, null) ?: return MmExerciseListFilter()
            return runCatching {
                gson.fromJson(json, MmExerciseListFilter::class.java) ?: MmExerciseListFilter()
            }.getOrDefault(MmExerciseListFilter())
        }
        set(value) {
            prefs.edit().putString(KEY_MM, gson.toJson(value)).apply()
        }

    /**
     * Last source the user had active in the Browser. Falls back to
     * [Source.DEFAULT] when missing or when the persisted name doesn't match
     * a known enum constant (forward-compat in case the enum is ever
     * extended).
     */
    var lastSource: Source
        get() {
            val raw = prefs.getString(KEY_LAST_SOURCE, null) ?: return Source.DEFAULT
            return runCatching { Source.valueOf(raw) }.getOrDefault(Source.DEFAULT)
        }
        set(value) {
            prefs.edit().putString(KEY_LAST_SOURCE, value.name).apply()
        }

    /** Last layout mode (List vs. MuscleGrid). Same forward-compat shape. */
    var lastViewMode: ViewMode
        get() {
            val raw = prefs.getString(KEY_LAST_VIEW_MODE, null) ?: return ViewMode.DEFAULT
            return runCatching { ViewMode.valueOf(raw) }.getOrDefault(ViewMode.DEFAULT)
        }
        set(value) {
            prefs.edit().putString(KEY_LAST_VIEW_MODE, value.name).apply()
        }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "hevy_browse_prefs"
        private const val KEY_HEVY = "hevy_filter_json"
        private const val KEY_MM = "mm_filter_json"
        private const val KEY_LAST_SOURCE = "last_source"
        private const val KEY_LAST_VIEW_MODE = "last_view_mode"
    }
}
