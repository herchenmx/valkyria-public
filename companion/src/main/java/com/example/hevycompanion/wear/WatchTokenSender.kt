package com.example.hevycompanion.wear

import android.content.Context
import android.util.Log
import com.example.hevycompanion.data.AuthPrefs
import com.example.hevycompanion.util.GsonHolder
import com.example.hevycore.wear.WearMessagePaths
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for pushing tokens to the watch via Wearable MessageAPI.
 *
 * Replaces three near-identical copies in [MainViewModel.notifyWatchAuthenticated],
 * [TokenRefreshWorker.pushTokensToWatch], and [TokenWidgetProvider.pushToWatch].
 * Each call-site previously handled its own payload assembly, node lookup, send
 * loop, and timestamp stamping — consolidating them kills a drift surface.
 */
object WatchTokenSender {

    private const val TAG = "WatchTokenSender"
    private const val NODE_TIMEOUT_SECONDS = 10L
    private const val SEND_TIMEOUT_SECONDS = 10L

    sealed class Result {
        /** Message delivered to at least one watch node; [lastTokenPushedAt] was stamped. */
        data class Pushed(val nodeCount: Int) : Result()

        /** No connected watch nodes; caller should retry (worker) or ignore (widget/UI). */
        object NoWatchConnected : Result()

        /** Send threw — transport or Tasks.await timeout. */
        data class Failed(val message: String) : Result()
    }

    /**
     * Sends the current tokens from [prefs] to every connected node. Pure
     * transport: callers are responsible for stamping success/failure into
     * prefs via [AuthPrefs.markPushSuccess] / [AuthPrefs.markPushError], so
     * the success and error paths share one atomic write surface and the
     * widget always sees consistent state. Always dispatched on Dispatchers.IO.
     */
    suspend fun push(context: Context, prefs: AuthPrefs): Result = withContext(Dispatchers.IO) {
        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                NODE_TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            if (nodes.isEmpty()) {
                Log.d(TAG, "No watch connected")
                return@withContext Result.NoWatchConnected
            }
            val msgClient = Wearable.getMessageClient(context)
            val json = GsonHolder.gson.toJson(
                mapOf(
                    "access_token"  to prefs.accessToken,
                    "refresh_token" to prefs.refreshToken,
                    "expires_at"    to prefs.expiresAt
                )
            ).toByteArray(Charsets.UTF_8)

            nodes.forEach { node ->
                Tasks.await(
                    msgClient.sendMessage(node.id, WearMessagePaths.AUTH_TOKENS, json),
                    SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS
                )
                Tasks.await(
                    msgClient.sendMessage(node.id, WearMessagePaths.ON_PHONE_AUTHENTICATED, ByteArray(0)),
                    SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS
                )
            }
            Log.d(TAG, "Tokens pushed to ${nodes.size} node(s)")
            Result.Pushed(nodes.size)
        } catch (e: Exception) {
            Log.w(TAG, "push failed: $e")
            Result.Failed(e.message ?: "push failed")
        }
    }
}
