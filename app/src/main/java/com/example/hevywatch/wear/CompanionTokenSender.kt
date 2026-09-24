package com.example.hevywatch.wear

import android.content.Context
import com.example.hevycore.wear.WearMessagePaths
import com.example.hevywatch.util.GsonHolder
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pushes a freshly-refreshed `(access_token, refresh_token, expires_at)` triple
 * from the watch to the paired companion phone via the Wearable MessageAPI.
 *
 * Exists to keep the companion's stored credentials in sync with the watch's
 * after a watch-initiated `refreshTokenIfNeeded()` (Hevy's server rotates the
 * refresh token on every successful call, so a watch-driven refresh leaves the
 * companion holding a now-retired token and the next companion refresh would
 * fail with 401 — surfacing a misleading "Sign in again" on the home-screen
 * widget even though the watch's tokens are perfectly valid).
 *
 * Counterpart to the companion's `WatchTokenSender` (companion-→-watch). The
 * companion's [WatchBridgeService] listens on the same `/tokens_from_watch`
 * path defined here.
 *
 * Pure transport — callers handle the [Result] and update UI state (banner,
 * mutable error string). Always dispatched on `Dispatchers.IO`.
 */
object CompanionTokenSender {

    private const val TAG = "CompanionTokenSender"
    private const val NODE_TIMEOUT_SECONDS = 10L
    private const val SEND_TIMEOUT_SECONDS = 10L

    sealed class Result {
        /** Delivered to at least one node. */
        data class Pushed(val nodeCount: Int) : Result()

        /** No reachable companion node — phone is off, Bluetooth is off, or
         *  the user has never paired a phone. The watch should keep working
         *  with its fresh tokens; the companion will simply diverge until
         *  its own next refresh fails with 401. */
        object NoCompanionConnected : Result()

        /** Send threw — MessageAPI timeout, transport error, etc. */
        data class Failed(val message: String) : Result()
    }

    suspend fun push(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresAt: String,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                NODE_TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            if (nodes.isEmpty()) {
                android.util.Log.d(TAG, "No connected companion node")
                return@withContext Result.NoCompanionConnected
            }
            val json = GsonHolder.gson.toJson(
                mapOf(
                    "access_token" to accessToken,
                    "refresh_token" to refreshToken,
                    "expires_at" to expiresAt,
                )
            ).toByteArray(Charsets.UTF_8)
            val msgClient = Wearable.getMessageClient(context)
            nodes.forEach { node ->
                Tasks.await(
                    msgClient.sendMessage(node.id, WearMessagePaths.TOKENS_FROM_WATCH, json),
                    SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                )
            }
            android.util.Log.d(TAG, "Pushed fresh tokens to ${nodes.size} node(s)")
            Result.Pushed(nodes.size)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "push failed: $e")
            Result.Failed(e.message ?: "push failed")
        }
    }
}
