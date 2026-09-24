package com.example.hevywatch.wear

import com.example.hevywatch.data.api.model.RoutineFolderResponse
import com.example.hevywatch.data.model.Routine

/**
 * Companion-backed snapshot of the watch's cold-start caches. Persisted on the
 * phone's companion app in the Wearable Data Layer so a freshly-reinstalled
 * watch can hydrate folders, routines, and workout-history metadata within a
 * second or two — before the first API pagination round-trip completes.
 *
 * The companion never parses this blob; it just stores raw bytes and returns
 * them on `/request_seed`. All serialization lives on the watch side.
 */
data class WatchSnapshot(
    val folders: List<RoutineFolderResponse>,
    val routines: List<Routine>,
    val routineLastWorkoutAt: Map<String, String>,
    val routineWorkoutIds: Map<String, Set<String>>,
    val snapshotVersion: Int = SNAPSHOT_VERSION,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        /** Bumped when the payload shape changes — older/newer snapshots are ignored. */
        const val SNAPSHOT_VERSION = 1
    }
}
