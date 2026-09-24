package com.example.hevywatch.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.hevywatch.HevyApp

/**
 * ADB-driven knob for overriding the spoofed Hevy app version/build headers
 * without rebuilding the watch APK. Receives an explicit broadcast:
 *
 *   adb shell am broadcast \
 *     -n com.example.hevywatch/.util.SetApiVersionReceiver \
 *     -a com.example.hevywatch.SET_API_VERSION \
 *     --es name "3.0.13" \
 *     --es code "2033100"
 *
 * Source of truth is [HevyAppVersionStore]; the OkHttp interceptor in
 * [com.example.hevywatch.data.api.HevyApiClient] reads from it on every
 * request, so a successful broadcast takes effect on the next API call with
 * no app restart.
 *
 * Exported because `adb shell` runs as a non-privileged shell user; broadcasts
 * from there only reach exported components. Blast radius is bounded: a
 * hostile broadcast can only set the two header values the server gates
 * private routes on. The companion-pushed DataClient path is the same
 * underlying store and is gated by trusted-node-id.
 */
class SetApiVersionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val name = intent.getStringExtra(EXTRA_NAME)
        val code = intent.getStringExtra(EXTRA_CODE)
        val app = context.applicationContext as? HevyApp ?: return
        app.hevyAppVersionStore.update(name, code)
        Log.i(TAG, "API version override: name=\"$name\" code=\"$code\"")
    }

    companion object {
        const val ACTION = "com.example.hevywatch.SET_API_VERSION"
        const val EXTRA_NAME = "name"
        const val EXTRA_CODE = "code"
        private const val TAG = "SetApiVersionReceiver"
    }
}
