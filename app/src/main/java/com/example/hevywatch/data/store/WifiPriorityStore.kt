package com.example.hevywatch.data.store

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Phase G — persists the user's Wi-Fi SSID priority list at runtime, so the
 * list can be changed via ADB (`am broadcast`) without rebuilding the APK.
 *
 * Stored as a single comma-separated string because SharedPreferences
 * doesn't natively store list types, and our consumers (HevyApp +
 * WifiSuppressor) want a List<String> anyway. Round-trips through
 * [parsePriority] / [joinPriority].
 */
class WifiPriorityStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Raw comma-separated priority list. Compose state so a write from the
     *  BroadcastReceiver is observed by the HevyApp / coordinator on its
     *  next read without restart. */
    var priorityCsv: String by mutableStateOf(prefs.getString(KEY_PRIORITY, "") ?: "")
        private set

    val priority: List<String>
        get() = parsePriority(priorityCsv)

    fun update(csv: String) {
        val normalised = csv.trim()
        priorityCsv = normalised
        prefs.edit().putString(KEY_PRIORITY, normalised).apply()
    }

    companion object {
        private const val PREFS_NAME = "wifi_priority"
        private const val KEY_PRIORITY = "priority_csv"

        fun parsePriority(csv: String): List<String> =
            csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
