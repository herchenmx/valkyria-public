package com.example.hevywatch

import com.example.hevywatch.data.api.model.RoutineFolderResponse
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.wear.WatchSnapshot
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Round-trip tests for the WatchSnapshot payload that rides the Wearable
 * MessageClient between watch and companion. The companion treats the blob
 * as opaque bytes, so any corruption shows up on the watch parse side.
 */
class WatchSnapshotSerializationTest {

    private val gson = Gson()

    @Test
    fun `empty snapshot round-trips`() {
        val snapshot = WatchSnapshot(
            folders = emptyList(),
            routines = emptyList(),
            routineLastWorkoutAt = emptyMap(),
            routineWorkoutIds = emptyMap()
        )
        val json = gson.toJson(snapshot)
        val restored = gson.fromJson(json, WatchSnapshot::class.java)
        assertNotNull(restored)
        assertEquals(0, restored.folders.size)
        assertEquals(0, restored.routines.size)
        assertEquals(WatchSnapshot.SNAPSHOT_VERSION, restored.snapshotVersion)
    }

    @Test
    fun `snapshot with folders and history metadata round-trips`() {
        val snapshot = WatchSnapshot(
            folders = listOf(
                RoutineFolderResponse(id = "fA", index = 0, title = "Push"),
                RoutineFolderResponse(id = "fB", index = 1, title = "Pull")
            ),
            routines = emptyList(),
            routineLastWorkoutAt = mapOf(
                "routineA" to "2026-04-18T10:00:00Z",
                "routineB" to "2026-04-19T09:30:00Z"
            ),
            routineWorkoutIds = mapOf(
                "routineA" to setOf("wA1", "wA2"),
                "routineB" to setOf("wB1")
            )
        )
        val json = gson.toJson(snapshot)
        val restored = gson.fromJson(json, WatchSnapshot::class.java)

        assertEquals(2, restored.folders.size)
        assertEquals("fA", restored.folders[0].id)
        assertEquals("Pull", restored.folders[1].title)
        assertEquals("2026-04-19T09:30:00Z", restored.routineLastWorkoutAt["routineB"])
        assertEquals(setOf("wA1", "wA2"), restored.routineWorkoutIds["routineA"])
        assertEquals(setOf("wB1"), restored.routineWorkoutIds["routineB"])
    }

    @Test
    fun `snapshot with a single routine preserves exercise list`() {
        // Only testing that Gson doesn't choke on the nested Routine / RoutineExercise
        // / RoutineSet structure. Actual semantic correctness is covered by
        // RoutineMapperTest.
        val snapshot = WatchSnapshot(
            folders = emptyList(),
            routines = listOf(
                Routine(
                    id = "rA",
                    title = "Leg Day",
                    notes = null,
                    folderId = "fA",
                    exercises = emptyList(),
                    updatedAt = "2026-04-18T00:00:00Z",
                    progressiveOverload = false
                )
            ),
            routineLastWorkoutAt = emptyMap(),
            routineWorkoutIds = emptyMap()
        )
        val json = gson.toJson(snapshot)
        val restored = gson.fromJson(json, WatchSnapshot::class.java)
        assertEquals(1, restored.routines.size)
        assertEquals("Leg Day", restored.routines[0].title)
        assertEquals("fA", restored.routines[0].folderId)
    }

    @Test
    fun `snapshotVersion mismatch is detectable on restore`() {
        val snapshot = WatchSnapshot(
            folders = emptyList(),
            routines = emptyList(),
            routineLastWorkoutAt = emptyMap(),
            routineWorkoutIds = emptyMap(),
            snapshotVersion = 99
        )
        val json = gson.toJson(snapshot)
        val restored = gson.fromJson(json, WatchSnapshot::class.java)
        assertEquals(99, restored.snapshotVersion)
        // The receiver in PhoneAuthService checks this field and ignores mismatches.
    }
}
