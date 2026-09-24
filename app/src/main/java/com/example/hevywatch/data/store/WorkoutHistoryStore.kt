package com.example.hevywatch.data.store

import android.content.Context
import com.example.hevywatch.util.GsonHolder
import com.google.gson.reflect.TypeToken

class WorkoutHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = GsonHolder.gson

    /** Epoch-ms of the last full workout history fetch; 0 if never fetched. */
    var lastFullFetchAtMs: Long
        get() = prefs.getLong(KEY_LAST_FULL_FETCH, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_FULL_FETCH, v).apply()

    /** Persisted map of routineId → most recent workout start_time (ISO string). */
    var routineLastWorkoutAt: Map<String, String>
        get() {
            val json = prefs.getString(KEY_ROUTINE_DATES, null) ?: return emptyMap()
            return try {
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        }
        set(v) = prefs.edit().putString(KEY_ROUTINE_DATES, gson.toJson(v)).apply()

    /** Persisted map of routineId → set of workoutIds that belong to that routine.
     *  Populated during the full/incremental workout history fetch. */
    var routineWorkoutIds: Map<String, Set<String>>
        get() {
            val json = prefs.getString(KEY_ROUTINE_WORKOUT_IDS, null) ?: return emptyMap()
            return try {
                val type = object : TypeToken<Map<String, Set<String>>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        }
        set(v) = prefs.edit().putString(KEY_ROUTINE_WORKOUT_IDS, gson.toJson(v)).apply()

    /** Returns true if a full re-fetch of all workout pages is required. */
    fun needsFullFetch(): Boolean {
        val last = lastFullFetchAtMs
        if (last == 0L) return true
        return System.currentTimeMillis() - last > THIRTY_DAYS_MS
    }

    companion object {
        private const val PREFS_NAME      = "workout_history"
        private const val KEY_LAST_FULL_FETCH = "last_full_fetch_ms"
        private const val KEY_ROUTINE_DATES   = "routine_last_workout_at"
        private const val KEY_ROUTINE_WORKOUT_IDS = "routine_workout_ids"
        private const val THIRTY_DAYS_MS  = 30L * 24 * 60 * 60 * 1000
    }
}
