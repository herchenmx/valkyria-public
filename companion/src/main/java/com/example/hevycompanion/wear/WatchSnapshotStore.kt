package com.example.hevycompanion.wear

import android.content.Context
import android.util.Base64

/**
 * Dumb blob store for the watch's cache snapshot. The companion never parses
 * the payload — the watch owns all serialization — so this class just keeps
 * the latest bytes it received and returns them on `/request_seed`.
 *
 * Bytes are Base64-encoded for storage in SharedPreferences; the live
 * Wearable MessageClient transport uses raw bytes on both ends.
 */
class WatchSnapshotStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastSavedAtMs: Long
        get() = prefs.getLong(KEY_SAVED_AT, 0L)
        private set(value) = prefs.edit().putLong(KEY_SAVED_AT, value).apply()

    fun save(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_SNAPSHOT, encoded)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Returns the last saved snapshot bytes, or null if none was ever stored. */
    fun load(): ByteArray? {
        val encoded = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME    = "watch_snapshot"
        private const val KEY_SNAPSHOT  = "snapshot_b64"
        private const val KEY_SAVED_AT  = "saved_at_ms"
    }
}
