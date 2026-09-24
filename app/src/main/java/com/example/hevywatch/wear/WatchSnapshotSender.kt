package com.example.hevywatch.wear

import android.content.Context
import com.example.hevycore.wear.WearMessagePaths
import com.example.hevywatch.HevyApp
import com.example.hevywatch.util.GsonHolder
import com.google.android.gms.wearable.Wearable

/**
 * Pushes a [WatchSnapshot] of the watch's caches to the paired phone via the
 * Wearable MessageClient, and requests a stored snapshot from the phone on
 * fresh install.
 *
 * Best-effort: any failure is logged and swallowed. The watch falls back to
 * normal API fetches on the next screen open if the companion has nothing
 * useful to return.
 */
object WatchSnapshotSender {

    private const val TAG = "WatchSnapshotSender"
    private val gson = GsonHolder.gson

    /** Serialize the current in-memory caches and push to the phone. */
    fun pushSnapshot(hevyApp: HevyApp) {
        val snapshot = WatchSnapshot(
            folders = hevyApp.cachedFolders,
            routines = hevyApp.cachedRoutines,
            routineLastWorkoutAt = hevyApp.routineLastWorkoutAt,
            routineWorkoutIds = hevyApp.routineWorkoutIds
        )
        if (!isWorthPushing(snapshot)) return
        sendToPhone(hevyApp, WearMessagePaths.WATCH_SNAPSHOT, gson.toJson(snapshot).toByteArray(Charsets.UTF_8))
    }

    /**
     * True when [snapshot] carries enough to be worth backing up. An empty
     * snapshot must never be pushed: the companion stores whatever arrives, so
     * a push made before the caches hydrate (fresh install, mid-wipe, a
     * failed fetch) would overwrite a rich stored snapshot with nothing —
     * and the watch would then seed from that empty blob on its next cold
     * start. Extracted as a pure predicate so the guard is unit-testable.
     */
    internal fun isWorthPushing(snapshot: WatchSnapshot): Boolean =
        snapshot.folders.isNotEmpty() || snapshot.routines.isNotEmpty()

    /** Ask the phone for the most recent snapshot it has stored. */
    fun requestSeed(context: Context) {
        sendToPhone(context, WearMessagePaths.REQUEST_SEED, ByteArray(0))
    }

    private fun sendToPhone(context: Context, path: String, bytes: ByteArray) {
        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    android.util.Log.d(TAG, "No connected node for $path")
                    return@addOnSuccessListener
                }
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, path, bytes)
                    .addOnFailureListener { e ->
                        android.util.Log.w(TAG, "sendMessage($path) failed: $e")
                    }
            }
            .addOnFailureListener { e ->
                android.util.Log.w(TAG, "connectedNodes() failed for $path: $e")
            }
    }
}
