package com.example.hevywatch.data.store

import android.content.Context

/**
 * Persists the user's bodyweight in kg. Used by computations that need to convert
 * between logged stack-assist weight and effective work for assisted-bodyweight
 * exercises (see [com.example.hevycore.exercise.AssistedBodyweight]).
 */
class BodyweightStore(context: Context) {

    private val prefs = context.getSharedPreferences("bodyweight_prefs", Context.MODE_PRIVATE)

    var bodyweightKg: Float
        get() = prefs.getFloat(KEY_BODYWEIGHT_KG, DEFAULT_BODYWEIGHT_KG)
        set(value) {
            val clamped = value.coerceIn(MIN_BODYWEIGHT_KG, MAX_BODYWEIGHT_KG)
            prefs.edit().putFloat(KEY_BODYWEIGHT_KG, clamped).apply()
        }

    companion object {
        const val DEFAULT_BODYWEIGHT_KG = 57.0f
        const val MIN_BODYWEIGHT_KG = 30.0f
        const val MAX_BODYWEIGHT_KG = 200.0f
        const val STEP_KG = 0.5f
        private const val KEY_BODYWEIGHT_KG = "bodyweight_kg"
    }
}
