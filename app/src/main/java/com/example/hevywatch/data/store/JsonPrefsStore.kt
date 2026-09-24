package com.example.hevywatch.data.store

import android.content.Context
import com.example.hevywatch.util.GsonHolder
import java.lang.reflect.Type

/**
 * R2 — generic JSON-in-SharedPreferences store. The watch has half a dozen
 * tiny stores that all do "gson.toJson on save, fromJson with a try/catch on
 * load, remove on clear"; this base collapses that boilerplate. Subclasses
 * only need to declare their prefs file, JSON key, and TypeToken.
 *
 * `apply()` is used for [save] — every write is fine to be async + write-
 * coalesced. Stores that need a synchronous flush (e.g. ActiveWorkoutStore's
 * `saveBlocking`) override or wrap [save].
 */
open class JsonPrefsStore<T>(
    context: Context,
    prefsName: String,
    private val key: String,
    private val type: Type,
) {

    protected val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    protected val gson = GsonHolder.gson

    fun save(value: T) {
        prefs.edit().putString(key, gson.toJson(value)).apply()
    }

    /** Synchronous flush — use sparingly (durable checkpoints only). */
    fun saveBlocking(value: T) {
        prefs.edit().putString(key, gson.toJson(value)).commit()
    }

    @Suppress("UNCHECKED_CAST")
    fun loadOrNull(): T? {
        val json = prefs.getString(key, null) ?: return null
        return try {
            gson.fromJson(json, type) as? T
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }
}
