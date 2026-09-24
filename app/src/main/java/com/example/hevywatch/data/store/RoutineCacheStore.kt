package com.example.hevywatch.data.store

import android.content.Context
import com.example.hevywatch.data.model.Routine
import com.google.gson.reflect.TypeToken

/**
 * Persists the routine list to SharedPreferences so the tile can show
 * routines immediately after a reinstall or process restart, without
 * the user needing to open the app first.
 */
class RoutineCacheStore(context: Context) : JsonPrefsStore<List<Routine>>(
    context,
    prefsName = "routine_cache",
    key = "routines_json",
    type = object : TypeToken<List<Routine>>() {}.type,
) {
    fun load(): List<Routine> = loadOrNull() ?: emptyList()
}
