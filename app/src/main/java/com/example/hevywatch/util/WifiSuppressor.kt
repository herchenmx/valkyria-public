package com.example.hevywatch.util

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager

/**
 * Phase E — turn Wi-Fi off for the duration of a workout. The May 14
 * forensics showed Wi-Fi associated through the whole 58-min session was a
 * non-trivial fraction of drain; Bluetooth-tether to the paired phone keeps
 * `NET_CAPABILITY_INTERNET` available so set-completion webhook pings + the
 * finish POST still work, and BT-tether is much cheaper than associated Wi-Fi.
 *
 * State machine:
 *  - **idle**: app started, no workout running → Wi-Fi untouched.
 *  - **suppressed**: workout active and we previously turned Wi-Fi off. The
 *    "we did this" bit is persisted to SharedPreferences so a crash + reboot
 *    can restore Wi-Fi to its prior state next launch (otherwise Wi-Fi would
 *    stay off forever after a mid-workout crash).
 *  - **restored**: workout ended (Finish, Discard, or end-keeping-recovery)
 *    → re-enable Wi-Fi if and only if we previously turned it off. Don't
 *    flip it for users who already had Wi-Fi off before starting.
 *
 * API quirk: `WifiManager.setWifiEnabled(false)` is a no-op for apps with
 * `targetSdk >= Q`. The watch app sticks at targetSdk 28 specifically so this
 * call still works.
 */
object WifiSuppressor {

    private const val PREFS_NAME = "wifi_suppressor"
    private const val KEY_SUPPRESSED = "suppressed"
    private const val KEY_PRIOR_ENABLED = "prior_enabled"

    /** Idempotent. If Wi-Fi is currently enabled, remember that and disable it.
     *  If we already suppressed earlier (workout still running, repeated call),
     *  do nothing. Returns true if a state change happened. */
    fun suppress(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_SUPPRESSED, false)) return false
        val wifi = wifiManager(context) ?: return false
        val prior = wifi.isWifiEnabled
        prefs.edit()
            .putBoolean(KEY_SUPPRESSED, true)
            .putBoolean(KEY_PRIOR_ENABLED, prior)
            .commit()
        return if (prior) wifi.setWifiEnabled(false) else false
    }

    /** Restore prior state. Idempotent: if we never suppressed, no-op. */
    fun restore(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SUPPRESSED, false)) return false
        val priorEnabled = prefs.getBoolean(KEY_PRIOR_ENABLED, true)
        prefs.edit()
            .putBoolean(KEY_SUPPRESSED, false)
            .remove(KEY_PRIOR_ENABLED)
            .commit()
        if (!priorEnabled) return false   // user already had Wi-Fi off — leave it
        val wifi = wifiManager(context) ?: return false
        return wifi.setWifiEnabled(true)
    }

    /** Called at app startup. If the persisted suppression bit is set but the
     *  caller reports no active workout, we crashed mid-workout — restore
     *  Wi-Fi so the user isn't stuck offline outside the workout flow. */
    fun restoreIfStaleFromCrash(context: Context, hasActiveWorkout: Boolean): Boolean {
        if (!isSuppressed(context)) return false
        if (hasActiveWorkout) return false
        return restore(context)
    }

    fun isSuppressed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUPPRESSED, false)

    /**
     * Phase G — best-effort steering after Wi-Fi is re-enabled. Walks
     * `configuredNetworks` and explicitly enables the first saved network
     * matching the priority list (in order). The OS would auto-pick a saved
     * network on its own; this just disambiguates when multiple saved
     * networks are in range and the user has a specific preference (home
     * Wi-Fi over phone hotspot, for instance).
     *
     * Requires nothing beyond `ACCESS_WIFI_STATE` + `CHANGE_WIFI_STATE` —
     * deliberately avoids `connectionInfo.SSID` (location-gated) and reads
     * SSIDs from the saved configurations instead.
     *
     * Returns the SSID we steered to, or null if no match was found / the
     * priority list was empty / the WifiManager call failed.
     */
    fun steerToPriorityNetwork(context: Context, prioritySsids: List<String>): String? {
        if (prioritySsids.isEmpty()) return null
        val wifi = wifiManager(context) ?: return null
        @Suppress("DEPRECATION", "MissingPermission")
        val saved: List<android.net.wifi.WifiConfiguration> =
            wifi.configuredNetworks ?: return null
        val savedSsids = saved.mapNotNull { it.SSID }
        val matchedSavedSsid = pickPrioritySsid(prioritySsids, savedSsids) ?: return null
        val targetId = saved.firstOrNull { it.SSID == matchedSavedSsid }?.networkId
            ?: return null
        @Suppress("DEPRECATION")
        wifi.enableNetwork(targetId, /* attemptConnect = disableOthers */ true)
        // Re-enable the other saved networks (with attemptConnect=false) so
        // the disable-others side effect of the call above doesn't persist
        // beyond this single steering decision — the user still wants
        // auto-roam to those networks the next time they're in range.
        saved.filter { it.networkId != targetId }.forEach {
            @Suppress("DEPRECATION")
            wifi.enableNetwork(it.networkId, false)
        }
        return matchedSavedSsid
    }

    /**
     * Pure: pick the first priority SSID that has a saved-networks match.
     * Returns the saved-network form (typically `"MyNetwork"` with quotes —
     * Android's `WifiConfiguration.SSID` is wrapped in literal quotes); the
     * caller looks it up in `configuredNetworks` directly.
     *
     * Priority list entries may or may not be quoted — we normalise both
     * sides before comparing.
     */
    internal fun pickPrioritySsid(
        prioritySsids: List<String>,
        savedSsids: List<String>,
    ): String? {
        if (prioritySsids.isEmpty() || savedSsids.isEmpty()) return null
        val savedByStripped: Map<String, String> = savedSsids.associateBy { stripQuotes(it) }
        for (preferred in prioritySsids) {
            val key = stripQuotes(preferred)
            if (key.isBlank()) continue
            val match = savedByStripped[key]
            if (match != null) return match
        }
        return null
    }

    private fun stripQuotes(s: String): String =
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s.substring(1, s.length - 1)
        else s

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
}
