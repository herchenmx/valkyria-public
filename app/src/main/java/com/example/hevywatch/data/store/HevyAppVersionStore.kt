package com.example.hevywatch.data.store

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.hevywatch.BuildConfig

/**
 * Holds the (version_name, version_code) pair stamped into the
 * `Hevy-App-Version` / `Hevy-App-Build` headers on every private-API request.
 *
 * The official Hevy Wear OS app ships periodic builds; the private v2 routes
 * compare the spoofed values we send against an allowlist that drifts over
 * time. Hard-coding these in source meant every Hevy release forced a watch-
 * app rebuild + reinstall. Now:
 *
 *  - the build-time defaults live in BuildConfig (matched to whatever apkmirror
 *    showed at the time this APK was assembled), and
 *  - a runtime override sits in sharedPrefs, set by either an ADB broadcast
 *    ([com.example.hevywatch.util.SetApiVersionReceiver]) or a DataClient push
 *    from the companion ([com.example.hevywatch.PhoneAuthDispatcher] handles
 *    the `/api_version` path).
 *
 * Compose state so the Settings screen rerenders when the value changes without
 * a process restart.
 */
class HevyAppVersionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var versionName: String by mutableStateOf(
        prefs.getString(KEY_VERSION_NAME, null) ?: BuildConfig.DEFAULT_HEVY_APP_VERSION
    )
        private set

    var versionCode: String by mutableStateOf(
        prefs.getString(KEY_VERSION_CODE, null) ?: BuildConfig.DEFAULT_HEVY_APP_BUILD
    )
        private set

    /**
     * Atomically updates both values. Blank inputs are rejected per-field so a
     * partial broadcast (one extra missing) can't half-update the pair.
     */
    fun update(name: String?, code: String?) {
        val normalisedName = name?.trim().orEmpty()
        val normalisedCode = code?.trim().orEmpty()
        if (normalisedName.isEmpty() || normalisedCode.isEmpty()) return
        versionName = normalisedName
        versionCode = normalisedCode
        prefs.edit()
            .putString(KEY_VERSION_NAME, normalisedName)
            .putString(KEY_VERSION_CODE, normalisedCode)
            .apply()
    }

    fun asPair(): Pair<String, String> = versionName to versionCode

    companion object {
        private const val PREFS_NAME = "hevy_app_version"
        private const val KEY_VERSION_NAME = "version_name"
        private const val KEY_VERSION_CODE = "version_code"
    }
}
