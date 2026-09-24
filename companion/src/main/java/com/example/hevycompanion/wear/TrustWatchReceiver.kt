package com.example.hevycompanion.wear

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.hevycompanion.R
import com.example.hevycompanion.TokenWidgetProvider
import com.example.hevycompanion.data.AuthPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the user's response to the "New watch wants to sign in — trust it?"
 * TOFU-approval notification posted by [WatchBridgeService.notifyPendingApproval].
 *
 * Two actions:
 *   - [ACTION_TRUST] adds the pending nodeId to [AuthPrefs.trustedWatchNodeIds]
 *     and immediately pushes the current tokens to the watch so `/request_auth`
 *     resolves in the same session — the user doesn't have to wait for the
 *     watch's next request cycle.
 *   - [ACTION_REJECT] clears the pending slot and cancels the notification;
 *     any subsequent `/request_auth` from that node has to re-request.
 *
 * Both actions cancel the notification so it doesn't linger.
 */
class TrustWatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
        if (nodeId.isNullOrBlank()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(notificationIdForNode(nodeId))

        val prefs = AuthPrefs(context)

        when (intent.action) {
            ACTION_TRUST -> {
                // Only promote if the pending slot still names this node —
                // guards against a stale notification being resurrected after
                // the pending slot moved on to a different requester.
                if (prefs.pendingWatchNodeId != nodeId) {
                    Log.d(TAG, "Trust for $nodeId ignored; pending is now ${prefs.pendingWatchNodeId}")
                    return
                }
                // Additive: trusting a second watch never evicts the first,
                // so both watches stay usable without a logout to swap.
                prefs.addTrustedWatch(nodeId)
                Log.i(TAG, "TOFU: user approved watch node $nodeId; allowlist=${prefs.trustedWatchNodeIds}")
                // Fire-and-forget token push so the requesting watch actually
                // receives credentials without waiting for the next
                // `/request_auth` cycle.
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    val result = WatchTokenSender.push(context.applicationContext, prefs)
                    Log.d(TAG, "post-approval push result: $result")
                }
                TokenWidgetProvider.refreshAllWidgets(context)
            }

            ACTION_REJECT -> {
                if (prefs.pendingWatchNodeId == nodeId) prefs.pendingWatchNodeId = null
                Log.i(TAG, "TOFU: user rejected watch node $nodeId")
            }
        }
    }

    companion object {
        private const val TAG = "TrustWatchReceiver"

        const val ACTION_TRUST  = "com.example.hevycompanion.ACTION_TRUST_WATCH"
        const val ACTION_REJECT = "com.example.hevycompanion.ACTION_REJECT_WATCH"
        const val EXTRA_NODE_ID = "node_id"

        /** Notification channel id for TOFU approval prompts. Created up-front
         *  by `HevyCompanionApp.onCreate`. */
        const val CHANNEL_ID = "watch_trust"

        /** Stable per-node notification id so a re-request from the same
         *  node replaces its previous notification instead of stacking. */
        fun notificationIdForNode(nodeId: String): Int = ("trust:$nodeId").hashCode()

        /**
         * Build the "New watch wants to sign in?" notification. Kept static
         * so [WatchBridgeService] can post it without a receiver instance.
         */
        fun buildNotification(context: Context, nodeId: String): NotificationCompat.Builder {
            val trustPending = PendingIntent.getBroadcast(
                context,
                (nodeId + ":trust").hashCode(),
                Intent(context, TrustWatchReceiver::class.java).apply {
                    action = ACTION_TRUST
                    putExtra(EXTRA_NODE_ID, nodeId)
                    setPackage(context.packageName)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val rejectPending = PendingIntent.getBroadcast(
                context,
                (nodeId + ":reject").hashCode(),
                Intent(context, TrustWatchReceiver::class.java).apply {
                    action = ACTION_REJECT
                    putExtra(EXTRA_NODE_ID, nodeId)
                    setPackage(context.packageName)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val short = nodeId.takeLast(6)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("New watch wants to sign in")
                .setContentText("Trust watch …$short?")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "A Wear OS device (nodeId ends in $short) is requesting your " +
                    "Hevy tokens. Trust only if you just installed the app on your own watch."
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .setOngoing(false)
                .addAction(0, "Trust", trustPending)
                .addAction(0, "Reject", rejectPending)
        }
    }
}
