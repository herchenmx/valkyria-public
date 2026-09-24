package com.example.hevycompanion.generate.mm

import android.content.Context
import android.content.SharedPreferences

class MmGeneratorPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var splitDay: SplitDay?
        get() = prefs.getString(KEY_SPLIT, null)?.let {
            runCatching { SplitDay.valueOf(it) }.getOrNull()
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SPLIT) else putString(KEY_SPLIT, value.name)
            }.apply()
        }

    var subAreas: Set<String>?
        get() = prefs.getStringSet(KEY_SUBAREAS, null)?.toSet()
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SUBAREAS) else putStringSet(KEY_SUBAREAS, value)
            }.apply()
        }

    var equipment: Set<String>?
        get() = prefs.getStringSet(KEY_EQUIPMENT, null)?.toSet()
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_EQUIPMENT) else putStringSet(KEY_EQUIPMENT, value)
            }.apply()
        }

    var types: Set<ExerciseType>?
        get() = prefs.getStringSet(KEY_TYPES, null)?.mapNotNullTo(mutableSetOf()) { name ->
            runCatching { ExerciseType.valueOf(name) }.getOrNull()
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_TYPES)
                else putStringSet(KEY_TYPES, value.mapTo(mutableSetOf()) { it.name })
            }.apply()
        }

    var addWarmup: Boolean
        get() = prefs.getBoolean(KEY_ADD_WARMUP, false)
        set(value) { prefs.edit().putBoolean(KEY_ADD_WARMUP, value).apply() }

    var addCooldown: Boolean
        get() = prefs.getBoolean(KEY_ADD_COOLDOWN, false)
        set(value) { prefs.edit().putBoolean(KEY_ADD_COOLDOWN, value).apply() }

    /** "split" or "sub_areas" — UI mode, persisted across opens. */
    var modeKind: String
        get() = prefs.getString(KEY_MODE_KIND, MODE_SPLIT) ?: MODE_SPLIT
        set(value) { prefs.edit().putString(KEY_MODE_KIND, value).apply() }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val MODE_SPLIT = "split"
        const val MODE_SUB_AREAS = "sub_areas"
        private const val PREFS_NAME = "mm_generator"
        private const val KEY_MODE_KIND = "mode_kind"
        private const val KEY_SPLIT = "split_day"
        private const val KEY_SUBAREAS = "sub_areas"
        private const val KEY_EQUIPMENT = "equipment"
        private const val KEY_TYPES = "types"
        private const val KEY_ADD_WARMUP = "add_warmup"
        private const val KEY_ADD_COOLDOWN = "add_cooldown"
    }
}
