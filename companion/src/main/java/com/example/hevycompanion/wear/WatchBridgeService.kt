package com.example.hevycompanion.wear

import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import com.example.hevycompanion.TokenWidgetProvider
import com.example.hevycompanion.data.AuthPrefs
import com.example.hevycompanion.util.GsonHolder
import com.example.hevycore.wear.WearMessagePaths
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives messages from the paired Wear OS watch via the Wearable MessageAPI.
 * Started automatically by the Android framework even when the app is backgrounded.
 *
 * Protocol:
 *   watch → phone  /request_auth              (watch wants tokens; un-pinned → TOFU approval)
 *   phone → watch  /auth_tokens                JSON: {access_token, refresh_token, expires_at}
 *   phone → watch  /on_phone_authenticated     (signal: phone is logged in, watch may request auth)
 *
 *   watch → phone  /tokens_from_watch          JSON: {access_token, refresh_token, expires_at}
 *     Watch pushes back here after a watch-initiated refresh rotates the
 *     server-side refresh token. Without this the companion's stored RT
 *     becomes retired silently and the next companion refresh fails 401.
 *
 *   watch → phone  /watch_snapshot / /request_seed  (cache backup/hydration; pinned only)
 */
class WatchBridgeService : WearableListenerService() {

    private val gson = GsonHolder.gson

    /** Opening EncryptedSharedPreferences costs a KeyStore round-trip on
     *  API 26+, so build it once per service rather than per message. */
    private val authPrefs: AuthPrefs by lazy { AuthPrefs(this) }

    companion object {
        private const val TAG = "WatchBridgeService"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val nodeId = messageEvent.sourceNodeId
        android.util.Log.d(TAG, "onMessageReceived: path=${messageEvent.path} from=$nodeId")
        when (messageEvent.path) {
            WearMessagePaths.REQUEST_AUTH       -> handleRequestAuth(nodeId)
            WearMessagePaths.WATCH_SNAPSHOT     -> handleWatchSnapshot(messageEvent.data, nodeId)
            WearMessagePaths.REQUEST_SEED       -> handleRequestSeed(nodeId)
            WearMessagePaths.TOKENS_FROM_WATCH  -> handleTokensFromWatch(messageEvent.data, nodeId)
        }
    }

    /**
     * Senders outside the allowlist are silently dropped on the
     * non-`/request_auth` paths. Nodes join the allowlist only through the
     * explicit user-approval flow on `/request_auth` — see
     * [notifyPendingApproval] + [TrustWatchReceiver] — or via the one-time
     * upgrade migration in [HevyCompanionApp].
     */
    private fun requirePinnedSender(prefs: AuthPrefs, sourceNodeId: String?, path: String): Boolean {
        if (prefs.isTrustedWatch(sourceNodeId)) return true
        android.util.Log.w(
            TAG,
            "Dropped $path from untrusted node ${sourceNodeId ?: "(null)"} " +
                "(trusted=${prefs.trustedWatchNodeIds})"
        )
        return false
    }

    /**
     * Records [sourceNodeId] as pending user approval and posts the TOFU
     * notification. Idempotent per node: re-requesting from an already-pending
     * node just refreshes the notification. A new node overwrites the slot.
     */
    private fun notifyPendingApproval(prefs: AuthPrefs, sourceNodeId: String) {
        val currentPending = prefs.pendingWatchNodeId
        if (currentPending != sourceNodeId) {
            // Cancel the stale notification for the previous pending node
            // so the tray doesn't stack.
            if (currentPending != null) {
                (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.cancel(TrustWatchReceiver.notificationIdForNode(currentPending))
            }
            prefs.pendingWatchNodeId = sourceNodeId
        }
        val nm = NotificationManagerCompat.from(this)
        // POST_NOTIFICATIONS runtime permission (API 33+) can't be requested
        // from a background service; if the user hasn't granted it the post
        // is silently skipped. The pending slot is still set, so the user
        // will see the pending state next time they open the companion.
        if (!nm.areNotificationsEnabled()) {
            android.util.Log.w(TAG, "Notifications disabled — pending approval stored but not surfaced")
            return
        }
        val notification = TrustWatchReceiver
            .buildNotification(this, sourceNodeId)
            .build()
        try {
            nm.notify(TrustWatchReceiver.notificationIdForNode(sourceNodeId), notification)
        } catch (se: SecurityException) {
            android.util.Log.w(TAG, "notify() SecurityException: ${se.message}")
        }
    }

    private fun handleTokensFromWatch(data: ByteArray, sourceNodeId: String?) {
        val prefs = authPrefs
        // The handler does its own TOFU enforcement so the test surface keeps
        // a single source of truth. Pass sourceNodeId through verbatim.
        val outcome = TokensFromWatchHandler.handle(data, prefs, sourceNodeId)
        when (outcome) {
            TokensFromWatchHandler.Outcome.Stored -> {
                android.util.Log.d(TAG, "Stored fresh tokens from watch; refreshing widgets + worker timer")
                TokenWidgetProvider.refreshAllWidgets(this)
                // Reset the periodic timer: this push counts as a successful
                // refresh from "any path", so the next periodic should fire
                // 1h from now rather than on its old KEEP-policy clock.
                com.example.hevycompanion.TokenRefreshWorker.rescheduleAfterSuccess(this)
            }
            TokensFromWatchHandler.Outcome.Rejected -> {
                android.util.Log.w(TAG, "Rejected /tokens_from_watch payload")
            }
        }
    }

    private fun handleWatchSnapshot(data: ByteArray, sourceNodeId: String?) {
        val prefs = authPrefs
        if (!requirePinnedSender(prefs, sourceNodeId, WearMessagePaths.WATCH_SNAPSHOT)) return
        if (data.isEmpty()) return
        WatchSnapshotStore(this).save(data)
        android.util.Log.d(TAG, "Stored watch snapshot (${data.size} bytes)")
    }

    private fun handleRequestSeed(nodeId: String) {
        val prefs = authPrefs
        if (!requirePinnedSender(prefs, nodeId, WearMessagePaths.REQUEST_SEED)) return
        val bytes = WatchSnapshotStore(this).load()
        if (bytes == null) {
            android.util.Log.d(TAG, "Seed requested but no snapshot stored; sending empty reply")
            send(nodeId, WearMessagePaths.WATCH_SEED, null)
            return
        }
        android.util.Log.d(TAG, "Sending seed to $nodeId (${bytes.size} bytes)")
        sendBytes(nodeId, WearMessagePaths.WATCH_SEED, bytes)
    }

    private fun handleRequestAuth(nodeId: String) {
        val prefs = authPrefs
        // Critical: this is the most security-sensitive path on the
        // companion — replying sends the user's tokens to the requester.
        // Senders outside the allowlist must be user-approved first: the
        // request goes into the pending slot and the user gets a "trust this
        // watch?" notification (plus an in-app card). Only after approval do
        // tokens flow. Approving is additive — trusting a second watch never
        // evicts the first, so ray and shiner both work interchangeably.
        if (!prefs.isTrustedWatch(nodeId)) {
            android.util.Log.i(TAG, "Untrusted /request_auth from $nodeId — awaiting user approval")
            notifyPendingApproval(prefs, nodeId)
            return
        }
        android.util.Log.d(TAG, "handleRequestAuth: isLoggedIn=${prefs.isLoggedIn}")
        if (!prefs.isLoggedIn) return
        val json = gson.toJson(
            mapOf(
                "access_token"  to prefs.accessToken,
                "refresh_token" to prefs.refreshToken,
                "expires_at"    to prefs.expiresAt
            )
        )
        send(nodeId, WearMessagePaths.AUTH_TOKENS, json)
    }

    private fun send(nodeId: String, path: String, data: String?) {
        sendBytes(nodeId, path, data?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
    }

    private fun sendBytes(nodeId: String, path: String, bytes: ByteArray) {
        Wearable.getMessageClient(applicationContext)
            .sendMessage(nodeId, path, bytes)
            .addOnFailureListener { e ->
                android.util.Log.w("WatchBridgeService", "Failed to send $path: $e")
            }
    }
}
