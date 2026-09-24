package com.example.hevywatch

import android.content.Intent
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.wear.WatchSnapshot
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class PhoneAuthService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        android.util.Log.d(TAG, "onMessageReceived: path=${messageEvent.path}")
        val app = applicationContext as HevyApp
        val dispatcher = PhoneAuthDispatcher(object : PhoneAuthDispatcher.Handlers {
            override fun onTokensReceived(accessToken: String, refreshToken: String, expiresAt: String) {
                app.onTokensReceived(accessToken, refreshToken, expiresAt)
            }
            override fun requestAuthFromPhone() = app.requestAuthFromPhone()
            override fun isLoggedIn() = app.authStore.isLoggedIn
            override fun trustedNodeId(): String? = app.authStore.trustedPhoneNodeId
            override fun setTrustedNodeId(nodeId: String) {
                app.authStore.trustedPhoneNodeId = nodeId
            }
            override fun applySeed(snapshot: WatchSnapshot) {
                app.applySeed(snapshot)
                android.util.Log.d(
                    TAG,
                    "Applied seed: ${snapshot.folders.size} folders, " +
                        "${snapshot.routines.size} routines, " +
                        "${snapshot.routineLastWorkoutAt.size} last-workout entries"
                )
            }
            override fun setApiVersion(versionName: String, versionCode: String) {
                app.hevyAppVersionStore.update(versionName, versionCode)
            }
            override fun requestResumeWorkout(workoutId: String) {
                // Deep-link the watch UI to the Workout Detail screen for this
                // workout, where the user taps Resume. Reuses the Tile's
                // "navigate_to" extra channel (sanitised by MainActivity via
                // Screen.sanitizeTileRoute). FLAG_ACTIVITY_NEW_TASK is required
                // to start an activity from a service context. Background
                // activity launch is permitted on Wear OS 2 / API 28 (the
                // strict Android 10+ restriction doesn't apply at this target).
                val intent = Intent(applicationContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("navigate_to", Screen.workoutDetail(workoutId))
                applicationContext.startActivity(intent)
            }
            override fun log(tag: String, msg: String) { android.util.Log.d(tag, msg) }
            override fun logError(tag: String, msg: String) { android.util.Log.e(tag, msg) }
        })
        dispatcher.dispatch(messageEvent.path, messageEvent.data, messageEvent.sourceNodeId)
    }

    companion object {
        private const val TAG = "PhoneAuthService"
    }
}
