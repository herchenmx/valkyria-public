package com.example.hevywatch.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.hevywatch.HevyApp

/**
 * ADB-driven knob for stamping the installed APK's commit hash into shared
 * prefs. Receives an explicit broadcast targeted at this component:
 *
 *   adb shell am broadcast \
 *     -n com.example.hevywatch/.util.CommitInfoReceiver \
 *     -a com.example.hevywatch.SET_COMMIT_HASH \
 *     --es hash "$(git rev-parse --short HEAD)"
 *
 * Workflow: build APK → commit & push → install APK + run the broadcast.
 * Settings shows the stamped value at the bottom so the watch advertises
 * which commit it's actually running.
 *
 * Exported because `adb shell` runs as a non-privileged shell user;
 * broadcasts from there only reach exported components. Blast radius is
 * cosmetic — a hostile broadcast can only change a label in Settings.
 */
class CommitInfoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val raw = intent.getStringExtra(EXTRA_HASH) ?: ""
        val app = context.applicationContext as? HevyApp ?: return
        app.commitInfoStore.update(raw)
        // Keep the webhook's build tag in step with the stamp. Without this a
        // re-stamped install keeps reporting the commit it had at process
        // start, which is exactly when you are least able to notice.
        app.configureDebugWebhook()
        Log.i(TAG, "Commit hash updated: \"$raw\"")
    }

    companion object {
        const val ACTION = "com.example.hevywatch.SET_COMMIT_HASH"
        const val EXTRA_HASH = "hash"
        private const val TAG = "CommitInfoReceiver"
    }
}
