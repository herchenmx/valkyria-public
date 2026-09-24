package com.example.hevywatch.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.hevywatch.HevyApp

/**
 * Phase G — ADB-driven knob for the post-workout Wi-Fi priority list.
 * Receives an explicit broadcast targeted at this component:
 *
 *   adb shell am broadcast \
 *     -n com.example.hevywatch/.util.WifiPriorityReceiver \
 *     -a com.example.hevywatch.SET_WIFI_PRIORITY \
 *     --es priority "FRITZ!Box 7520 DU,WLAN-077848"
 *
 * To clear:
 *   adb shell am broadcast \
 *     -n com.example.hevywatch/.util.WifiPriorityReceiver \
 *     -a com.example.hevywatch.SET_WIFI_PRIORITY \
 *     --es priority ""
 *
 * The receiver is exported because `adb shell` runs as a non-privileged
 * shell user and broadcasts from there can only reach exported components.
 * Writing here can only change *which already-saved Wi-Fi network the watch
 * prefers post-workout* — no credential exposure, no network join — so the
 * blast radius of a hostile broadcast is bounded.
 */
class WifiPriorityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val raw = intent.getStringExtra(EXTRA_PRIORITY) ?: ""
        val app = context.applicationContext as? HevyApp ?: return
        app.wifiPriorityStore.update(raw)
        Log.i(TAG, "Wi-Fi priority updated: \"$raw\"")
    }

    companion object {
        const val ACTION = "com.example.hevywatch.SET_WIFI_PRIORITY"
        const val EXTRA_PRIORITY = "priority"
        private const val TAG = "WifiPriorityReceiver"
    }
}
