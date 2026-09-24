package com.example.hevywatch

import com.example.hevywatch.data.api.model.RoutineFolderResponse
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.wear.WatchSnapshot
import com.example.hevywatch.wear.WatchSnapshotSender
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "never push an empty snapshot" guard.
 *
 * The companion stores whatever arrives on `/watch_snapshot` without parsing
 * it. If the watch pushes before its caches hydrate — fresh install, mid-wipe,
 * a failed fetch — an unguarded push would overwrite a rich stored snapshot
 * with nothing, and the watch would then seed from that empty blob on its next
 * cold start. Silent, self-inflicted, and only visible as "why is my watch
 * re-fetching everything again".
 */
class WatchSnapshotSenderGuardTest {

    private fun snapshot(
        folders: List<RoutineFolderResponse> = emptyList(),
        routines: List<Routine> = emptyList(),
        lastAt: Map<String, String> = emptyMap(),
        workoutIds: Map<String, Set<String>> = emptyMap(),
    ) = WatchSnapshot(
        folders = folders,
        routines = routines,
        routineLastWorkoutAt = lastAt,
        routineWorkoutIds = workoutIds,
    )

    private fun folder(id: String = "f1") =
        RoutineFolderResponse(id = id, title = "PO", index = 0)

    private fun routine(id: String = "r1") = Routine(
        id = id,
        title = "Push",
        notes = null,
        folderId = "f1",
        exercises = emptyList(),
        updatedAt = "2026-07-31T00:00:00Z",
    )

    @Test fun `completely empty snapshot is not pushed`() {
        assertFalse(WatchSnapshotSender.isWorthPushing(snapshot()))
    }

    @Test fun `folders alone are worth pushing`() {
        assertTrue(WatchSnapshotSender.isWorthPushing(snapshot(folders = listOf(folder()))))
    }

    @Test fun `routines alone are worth pushing`() {
        assertTrue(WatchSnapshotSender.isWorthPushing(snapshot(routines = listOf(routine()))))
    }

    @Test fun `both populated is worth pushing`() {
        assertTrue(
            WatchSnapshotSender.isWorthPushing(
                snapshot(folders = listOf(folder()), routines = listOf(routine()))
            )
        )
    }

    @Test fun `history metadata alone does NOT justify a push`() {
        // routineLastWorkoutAt / routineWorkoutIds without any folders or
        // routines means the caches haven't hydrated — pushing would replace
        // a good stored snapshot with a near-useless one.
        assertFalse(
            WatchSnapshotSender.isWorthPushing(
                snapshot(
                    lastAt = mapOf("r1" to "2026-07-31T00:00:00Z"),
                    workoutIds = mapOf("r1" to setOf("w1")),
                )
            )
        )
    }
}
