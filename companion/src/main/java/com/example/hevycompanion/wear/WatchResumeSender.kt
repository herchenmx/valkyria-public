package com.example.hevycompanion.wear

import android.content.Context
import android.util.Log
import com.example.hevycompanion.util.GsonHolder
import com.example.hevycore.wear.WearMessagePaths
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends a "resume this incomplete workout on the watch" request over Wearable
 * MessageAPI. The watch's [com.example.hevywatch.PhoneAuthDispatcher] handles
 * `/resume_workout`, TOFU-gates it against the paired companion, and deep-links
 * its UI to the Workout Detail screen for the given workout — where the user
 * taps Resume to continue logging on the watch.
 *
 * Pure transport, mirroring [WatchTokenSender.push]: discover connected nodes,
 * send to each, return a [Result] the caller surfaces to the user.
 */
object WatchResumeSender {

    private const val TAG = "WatchResumeSender"
    private const val NODE_TIMEOUT_SECONDS = 10L
    private const val SEND_TIMEOUT_SECONDS = 10L

    sealed class Result {
        data class Sent(val nodeCount: Int) : Result()
        object NoWatchConnected : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun send(context: Context, workoutId: String): Result = withContext(Dispatchers.IO) {
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
            val json = GsonHolder.gson.toJson(mapOf("workout_id" to workoutId))
                .toByteArray(Charsets.UTF_8)

            nodes.forEach { node ->
                Tasks.await(
                    msgClient.sendMessage(node.id, WearMessagePaths.RESUME_WORKOUT, json),
                    SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS
                )
            }
            Log.d(TAG, "Resume request sent to ${nodes.size} node(s)")
            Result.Sent(nodes.size)
        } catch (e: Exception) {
            Log.w(TAG, "send failed: $e")
            Result.Failed(e.message ?: "send failed")
        }
    }
}
