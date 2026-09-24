package com.example.hevywatch

import com.example.hevycore.wear.WearMessagePaths
import com.example.hevywatch.data.api.model.RefreshTokenResponse
import com.example.hevywatch.util.GsonHolder
import com.example.hevywatch.wear.WatchSnapshot
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Pure path/bytes dispatcher extracted from [PhoneAuthService] so the
 * companion-→-watch message flow can be unit-tested without instantiating a
 * WearableListenerService or constructing a MessageEvent. The Wearable
 * framework code in [PhoneAuthService] hands every received message to
 * [PhoneAuthDispatcher.dispatch]; tests do the same with synthetic bytes.
 */
class PhoneAuthDispatcher(
    private val handlers: Handlers,
    private val gson: Gson = GsonHolder.gson,
) {

    /** Side-effect surface needed by the dispatcher. Tests stub these. */
    interface Handlers {
        fun onTokensReceived(accessToken: String, refreshToken: String, expiresAt: String)
        fun requestAuthFromPhone()
        fun isLoggedIn(): Boolean
        fun applySeed(snapshot: WatchSnapshot)
        /** Companion-pushed override for the spoofed Hevy-App-Version /
         *  Hevy-App-Build headers. Default no-op so existing tests don't
         *  need to opt in. */
        fun setApiVersion(versionName: String, versionCode: String) {}
        /** Companion-initiated "resume this incomplete workout on the watch".
         *  The watch deep-links to the Workout Detail screen for [workoutId]
         *  where the user taps Resume. Default no-op so existing tests don't
         *  need to opt in. */
        fun requestResumeWorkout(workoutId: String) {}
        /** Currently trusted phone nodeId, or null if no companion has been
         *  paired yet (fresh install or post-logout). */
        fun trustedNodeId(): String? = null
        /** Pin the trusted nodeId on first successful token receipt
         *  (trust-on-first-use). Subsequent senders are rejected unless they
         *  match this id. */
        fun setTrustedNodeId(nodeId: String) {}
        fun log(tag: String, msg: String) {}
        fun logError(tag: String, msg: String) {}
    }

    /** [sourceNodeId] is the Wearable peer that delivered the message. Sensitive
     *  paths (`/auth_tokens`, `/watch_seed`) are gated on the trusted-node-id
     *  invariant: the first /auth_tokens after install pins the sender; any
     *  subsequent message from a different node is rejected. */
    fun dispatch(path: String, data: ByteArray, sourceNodeId: String? = null) {
        when (path) {
            WearMessagePaths.AUTH_TOKENS -> handleTokens(data, sourceNodeId)
            WearMessagePaths.ON_PHONE_AUTHENTICATED -> handlePhoneAuthenticated()
            WearMessagePaths.WATCH_SEED -> handleSeed(data, sourceNodeId)
            WearMessagePaths.API_VERSION -> handleApiVersion(data, sourceNodeId)
            WearMessagePaths.RESUME_WORKOUT -> handleResumeWorkout(data, sourceNodeId)
            // Unknown paths are ignored — Wearable delivers all messages on
            // the listener, so we silently no-op rather than logging noise.
        }
    }

    private fun handleTokens(data: ByteArray, sourceNodeId: String?) {
        val trusted = handlers.trustedNodeId()
        if (trusted != null && sourceNodeId != trusted) {
            // After pinning, require an exact node-id match — null included.
            // The Wearable framework always populates sourceNodeId on real
            // messages (see PhoneAuthService), so a null here means the
            // packet came from somewhere that bypassed Wearable's delivery.
            handlers.logError(
                TAG,
                "Rejected /auth_tokens from untrusted node ${sourceNodeId ?: "(null)"}"
            )
            return
        }
        val json = data.decodeToString()
        try {
            val token = gson.fromJson(json, RefreshTokenResponse::class.java)
            // Reject malformed payloads early — a partial token is worse than
            // none, since it would put AuthStore into an inconsistent state.
            val at = token?.accessToken
            val rt = token?.refreshToken
            val exp = token?.expiresAt
            if (at.isNullOrBlank() || rt.isNullOrBlank() || exp.isNullOrBlank()) {
                handlers.logError(TAG, "Rejected /auth_tokens with empty fields")
                return
            }
            handlers.onTokensReceived(at, rt, exp)
            // Trust-on-first-use: pin the sender so a second phone (or a
            // sideloaded app) can't later overwrite tokens.
            if (trusted == null && sourceNodeId != null) {
                handlers.setTrustedNodeId(sourceNodeId)
            }
            handlers.log(TAG, "Tokens stored OK")
        } catch (e: Exception) {
            handlers.logError(TAG, "Failed to parse /auth_tokens: $e")
        }
    }

    private fun handlePhoneAuthenticated() {
        // No node-id check — this path only triggers a /request_auth send,
        // which has no privileged side-effect on its own. The reply
        // /auth_tokens still goes through the trust check above.
        if (!handlers.isLoggedIn()) handlers.requestAuthFromPhone()
    }

    private fun handleSeed(data: ByteArray, sourceNodeId: String?) {
        val trusted = handlers.trustedNodeId()
        if (trusted != null && sourceNodeId != trusted) {
            handlers.logError(
                TAG,
                "Rejected /watch_seed from untrusted node ${sourceNodeId ?: "(null)"}"
            )
            return
        }
        if (data.isEmpty()) {
            handlers.log(TAG, "Received empty /watch_seed")
            return
        }
        try {
            val json = data.decodeToString()
            val snapshot = gson.fromJson(json, WatchSnapshot::class.java)
            if (snapshot == null) {
                handlers.logError(TAG, "Parsed /watch_seed was null")
                return
            }
            if (snapshot.snapshotVersion != WatchSnapshot.SNAPSHOT_VERSION) {
                handlers.logError(TAG, "Ignoring seed with version ${snapshot.snapshotVersion}")
                return
            }
            handlers.applySeed(snapshot)
        } catch (e: Exception) {
            handlers.logError(TAG, "Failed to apply /watch_seed: $e")
        }
    }

    private fun handleApiVersion(data: ByteArray, sourceNodeId: String?) {
        // Gate on the same trust check as /auth_tokens and /watch_seed: the
        // companion is the only legitimate sender, and a hostile broadcast
        // (e.g. another sideloaded app on the watch) could otherwise downgrade
        // the spoofed version to one the private API rejects. The ADB
        // broadcast SET_API_VERSION is the manual override that bypasses this
        // check — that's by design and acceptable because adb shell access is
        // already privileged.
        val trusted = handlers.trustedNodeId()
        if (trusted != null && sourceNodeId != trusted) {
            handlers.logError(
                TAG,
                "Rejected /api_version from untrusted node ${sourceNodeId ?: "(null)"}"
            )
            return
        }
        if (data.isEmpty()) {
            handlers.log(TAG, "Received empty /api_version")
            return
        }
        try {
            val msg = gson.fromJson(data.decodeToString(), ApiVersionMessage::class.java)
            if (msg == null ||
                msg.versionName.isNullOrBlank() ||
                msg.versionCode.isNullOrBlank()
            ) {
                handlers.logError(TAG, "Rejected /api_version with empty fields")
                return
            }
            handlers.setApiVersion(msg.versionName, msg.versionCode)
            handlers.log(TAG, "API version updated: ${msg.versionName} (${msg.versionCode})")
        } catch (e: Exception) {
            handlers.logError(TAG, "Failed to parse /api_version: $e")
        }
    }

    private fun handleResumeWorkout(data: ByteArray, sourceNodeId: String?) {
        // Same TOFU gate as /auth_tokens: only the paired companion may steer
        // the watch into a workout. A hostile peer could otherwise pop the
        // user into an arbitrary workout-detail screen.
        val trusted = handlers.trustedNodeId()
        if (trusted != null && sourceNodeId != trusted) {
            handlers.logError(
                TAG,
                "Rejected /resume_workout from untrusted node ${sourceNodeId ?: "(null)"}"
            )
            return
        }
        if (data.isEmpty()) {
            handlers.log(TAG, "Received empty /resume_workout")
            return
        }
        try {
            val msg = gson.fromJson(data.decodeToString(), ResumeWorkoutMessage::class.java)
            val id = msg?.workoutId
            // Validate the id charset — it's interpolated into a nav route, so
            // restrict it the same way Screen.sanitizeTileRoute does.
            if (id.isNullOrBlank() || !id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                handlers.logError(TAG, "Rejected /resume_workout with bad workout_id")
                return
            }
            handlers.requestResumeWorkout(id)
            handlers.log(TAG, "Resume requested for workout $id")
        } catch (e: Exception) {
            handlers.logError(TAG, "Failed to parse /resume_workout: $e")
        }
    }

    /** Wire format for the /api_version DataClient push from the companion.
     *  Matches the api-versions/active.json shape so the companion can forward
     *  the file contents almost as-is. */
    private data class ApiVersionMessage(
        @SerializedName("version_name") val versionName: String?,
        @SerializedName("version_code") val versionCode: String?,
    )

    /** Wire format for the companion's /resume_workout request. */
    private data class ResumeWorkoutMessage(
        @SerializedName("workout_id") val workoutId: String?,
    )

    companion object {
        private const val TAG = "PhoneAuthDispatcher"
    }
}
