package com.example.hevywatch.data.store

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Persists the git commit hash of the currently installed APK at runtime.
 *
 * The hash is not known at build time — the flow is (1) build APK, (2) commit
 * & push, (3) install APK and stamp the freshly-pushed hash via ADB broadcast.
 * Stamping after install means the hash on the watch always matches the
 * commit that produced the running APK, with no `BuildConfig` regeneration
 * cycle needed.
 *
 * Compose state so a write from the BroadcastReceiver is observed by the
 * Settings screen on its next recomposition without restart.
 */
class CommitInfoStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var commitHash: String by mutableStateOf(prefs.getString(KEY_COMMIT, "") ?: "")
        private set

    fun update(hash: String) {
        val normalised = hash.trim()
        commitHash = normalised
        prefs.edit().putString(KEY_COMMIT, normalised).apply()
    }

    companion object {
        private const val PREFS_NAME = "commit_info"
        private const val KEY_COMMIT = "commit_hash"
    }
}
