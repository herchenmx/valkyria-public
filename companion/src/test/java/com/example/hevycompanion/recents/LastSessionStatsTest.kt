package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the companion's last-session normal-set average against the same rules
 * the watch's `LastSessionStats` follows: most-recent session only, normal sets
 * only, positive weights only.
 */
class LastSessionStatsTest {

    private fun set(workoutId: String, weight: Float?, type: String = "normal") =
        ExerciseHistoryEntry(workoutId = workoutId, weightKg = weight, reps = 10, setType = type)

    @Test fun `averages only the most recent session's normal sets`() {
        // Newest-first: w2 is the last session (60/70), w1 is older (100) and ignored.
        val history = listOf(
            set("w2", 60f), set("w2", 70f),
            set("w1", 100f),
        )
        assertEquals(65f, LastSessionStats.avgLastSessionNormalKg(history)!!, 0.001f)
    }

    @Test fun `excludes warmup, dropset and failure sets`() {
        val history = listOf(
            set("w1", 20f, type = "warmup"),
            set("w1", 80f, type = "normal"),
            set("w1", 82f, type = "normal"),
            set("w1", 60f, type = "dropset"),
            set("w1", 84f, type = "failure"),
        )
        assertEquals(81f, LastSessionStats.avgLastSessionNormalKg(history)!!, 0.001f)
    }

    @Test fun `set-type match is case-insensitive`() {
        val history = listOf(set("w1", 40f, type = "Normal"), set("w1", 50f, type = "NORMAL"))
        assertEquals(45f, LastSessionStats.avgLastSessionNormalKg(history)!!, 0.001f)
    }

    @Test fun `null and non-positive weights are ignored`() {
        val history = listOf(
            set("w1", null),
            set("w1", 0f),
            set("w1", 90f),
        )
        assertEquals(90f, LastSessionStats.avgLastSessionNormalKg(history)!!, 0.001f)
    }

    @Test fun `returns null when the last session has no weighted normal sets`() {
        // Bodyweight-only last session (all zero weight).
        val history = listOf(set("w1", 0f), set("w1", null))
        assertNull(LastSessionStats.avgLastSessionNormalKg(history))
    }

    @Test fun `returns null for empty history`() {
        assertNull(LastSessionStats.avgLastSessionNormalKg(emptyList()))
    }

    @Test fun `latestSessionEntries keeps only the newest workout's entries`() {
        val history = listOf(
            set("w2", 60f), set("w2", 70f),
            set("w1", 100f),
        )
        val latest = LastSessionStats.latestSessionEntries(history)
        assertEquals(2, latest.size)
        assertEquals("w2", latest.first().workoutId)
    }
}
