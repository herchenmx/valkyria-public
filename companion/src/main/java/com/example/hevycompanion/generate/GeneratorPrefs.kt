package com.example.hevycompanion.generate

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed persistence for the workout-generator filter state.
 *
 * Persists everything the user configures in the chips row so the generator
 * opens in the same state they last confirmed: equipment, level multi-select,
 * category multi-select, and the weight-bias (Light/Medium/Heavy). Duration
 * still resets to its default (1h) on every open — it's the one knob that's
 * situational rather than environmental or preference-based.
 *
 * Pattern matches [com.example.hevycompanion.data.AuthPrefs]: property-
 * delegate style, private prefs file named by purpose, one key per field.
 */
class GeneratorPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The user's last-confirmed equipment set, or `null` if they've never
     * touched the equipment picker on this device. Callers should treat
     * `null` as "use the default" ([EquipmentMap.KNOWN_HEVY_TAGS]).
     *
     * Defensive copy on read: SharedPreferences docs warn against mutating
     * the returned Set directly — `toSet()` gives us an immutable snapshot.
     */
    var selectedEquipment: Set<String>?
        get() = prefs.getStringSet(KEY_EQUIPMENT, null)?.toSet()
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_EQUIPMENT)
                else putStringSet(KEY_EQUIPMENT, value)
            }.apply()
        }

    /**
     * Last-confirmed level multi-select. Stored as a set of `Level.name`
     * strings (enum constant names, not display names) so reads are robust
     * to display-label renames. Null = never-touched → caller uses
     * [Level.DEFAULT].
     */
    var selectedLevels: Set<Level>?
        get() = prefs.getStringSet(KEY_LEVELS, null)?.mapNotNullTo(mutableSetOf()) { name ->
            runCatching { Level.valueOf(name) }.getOrNull()
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LEVELS)
                else putStringSet(KEY_LEVELS, value.mapTo(mutableSetOf()) { it.name })
            }.apply()
        }

    /**
     * Last-confirmed category multi-select. Same encoding story as
     * [selectedLevels]: enum constant names, null = never-touched, caller
     * falls back to [Category.DEFAULT].
     */
    var selectedCategories: Set<Category>?
        get() = prefs.getStringSet(KEY_CATEGORIES, null)?.mapNotNullTo(mutableSetOf()) { name ->
            runCatching { Category.valueOf(name) }.getOrNull()
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_CATEGORIES)
                else putStringSet(KEY_CATEGORIES, value.mapTo(mutableSetOf()) { it.name })
            }.apply()
        }

    /**
     * Last-confirmed weight bias (Light / Medium / Heavy). Null = never
     * touched → caller uses [Weights.DEFAULT] (HEAVY).
     */
    var selectedWeights: Weights?
        get() = prefs.getString(KEY_WEIGHTS, null)?.let { name ->
            runCatching { Weights.valueOf(name) }.getOrNull()
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_WEIGHTS)
                else putString(KEY_WEIGHTS, value.name)
            }.apply()
        }

    /** Wipe all generator-related prefs. Not wired into the UI; convenience for tests. */
    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "hevy_generator"
        private const val KEY_EQUIPMENT = "selected_equipment"
        private const val KEY_LEVELS = "selected_levels"
        private const val KEY_CATEGORIES = "selected_categories"
        private const val KEY_WEIGHTS = "selected_weights"
    }
}
