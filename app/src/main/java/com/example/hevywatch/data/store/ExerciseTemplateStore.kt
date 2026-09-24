package com.example.hevywatch.data.store

import android.content.Context
import com.example.hevywatch.util.GsonHolder
import com.google.gson.reflect.TypeToken

/**
 * Persists exercise-template metadata (equipment + primary muscle group per
 * template id) to SharedPreferences with a TTL. Templates are essentially
 * static reference data — they only change when Hevy publishes a new exercise
 * — so a 7-day TTL eliminates the paginated `/v1/exercise_templates` fetch
 * on almost every cold start.
 *
 * Missing entries are filled on-demand by
 * [com.example.hevywatch.data.WorkoutDataLoader.fetchExerciseTemplates], so a
 * stale disk cache never blocks a workout: it just means the first workout
 * after the cache expires pays the fetch cost.
 */
class ExerciseTemplateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = GsonHolder.gson

    data class Snapshot(
        val equipment: Map<String, String?>,
        val muscleGroup: Map<String, String?>
    )

    fun save(equipment: Map<String, String?>, muscleGroup: Map<String, String?>) {
        prefs.edit()
            .putString(KEY_EQUIPMENT, gson.toJson(equipment))
            .putString(KEY_MUSCLE_GROUP, gson.toJson(muscleGroup))
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Returns the persisted snapshot if it was saved within the TTL, else null. */
    fun loadFresh(): Snapshot? {
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        if (savedAt == 0L) return null
        if (System.currentTimeMillis() - savedAt > TTL_MS) return null
        val eqJson = prefs.getString(KEY_EQUIPMENT, null) ?: return null
        val mgJson = prefs.getString(KEY_MUSCLE_GROUP, null) ?: return null
        return try {
            val type = object : TypeToken<Map<String, String?>>() {}.type
            Snapshot(
                equipment = gson.fromJson(eqJson, type) ?: emptyMap(),
                muscleGroup = gson.fromJson(mgJson, type) ?: emptyMap()
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME        = "exercise_templates"
        private const val KEY_EQUIPMENT     = "equipment_json"
        private const val KEY_MUSCLE_GROUP  = "muscle_group_json"
        private const val KEY_SAVED_AT      = "saved_at_ms"
        private const val TTL_MS            = 7L * 24 * 60 * 60 * 1000
    }
}
