package com.example.hevycompanion.wear

import android.content.Context

/**
 * Caches the active Hevy Wear OS version pulled from the repo's
 * api-versions/active.json. The companion reads this on launch, compares to a
 * fresh fetch, and pushes any change to the watch via the `/api_version`
 * MessageAPI path. Surfaced in the main UI as a "API spoof: X.Y.Z (build)"
 * line so the user can see what the watch is currently advertising.
 *
 * Source of truth lives in
 *   https://raw.githubusercontent.com/herchenmx/hevy-for-wearos-2.45/main/api-versions/active.json
 * which is rotated by the .github/workflows/check-hevy-version.yml workflow
 * after a manual probe + promotion.
 */
class HevyApiVersionPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val versionName: String? get() = prefs.getString(KEY_VERSION_NAME, null)
    val versionCode: String? get() = prefs.getString(KEY_VERSION_CODE, null)
    val lastSyncedAt: Long get() = prefs.getLong(KEY_LAST_SYNCED_AT, 0L)
    val lastPushedAt: Long get() = prefs.getLong(KEY_LAST_PUSHED_AT, 0L)

    /** Epoch-ms of the last sync ATTEMPT, whatever its outcome (0 if never).
     *  [lastSyncedAt] only moves on success, so on its own it cannot tell
     *  "checked, unchanged" apart from "has not managed to check since". */
    val lastSyncAttemptAt: Long get() = prefs.getLong(KEY_LAST_SYNC_ATTEMPT_AT, 0L)

    /** Why the last attempt failed, or null if it succeeded. */
    val lastSyncError: String? get() = prefs.getString(KEY_LAST_SYNC_ERROR, null)

    fun saveSync(name: String, code: String, syncedAtMs: Long) {
        prefs.edit()
            .putString(KEY_VERSION_NAME, name)
            .putString(KEY_VERSION_CODE, code)
            .putLong(KEY_LAST_SYNCED_AT, syncedAtMs)
            .putLong(KEY_LAST_SYNC_ATTEMPT_AT, syncedAtMs)
            // Clear any prior failure in the same edit, so the UI can never
            // show a stale error next to a fresh success.
            .remove(KEY_LAST_SYNC_ERROR)
            .apply()
    }

    /**
     * Records a failed fetch. Deliberately leaves [versionName] / [versionCode]
     * and [lastSyncedAt] untouched: the cached pair is still what the watch is
     * advertising, and the last good sync really did happen. Only the attempt
     * stamp and the reason move.
     */
    fun markSyncFailed(reason: String, attemptedAtMs: Long) {
        prefs.edit()
            .putLong(KEY_LAST_SYNC_ATTEMPT_AT, attemptedAtMs)
            .putString(KEY_LAST_SYNC_ERROR, reason.ifBlank { "unknown error" })
            .apply()
    }

    fun markPushed(pushedAtMs: Long) {
        prefs.edit().putLong(KEY_LAST_PUSHED_AT, pushedAtMs).apply()
    }

    /** Observe writes so the UI can react instead of polling. Callers must
     *  hold a strong reference to [listener] — SharedPreferences keeps only a
     *  weak one — and pair this with [unregisterListener]. */
    fun registerListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "hevy_api_version"
        private const val KEY_VERSION_NAME = "version_name"
        private const val KEY_VERSION_CODE = "version_code"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
        private const val KEY_LAST_PUSHED_AT = "last_pushed_at"
        private const val KEY_LAST_SYNC_ATTEMPT_AT = "last_sync_attempt_at"
        private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
    }
}
