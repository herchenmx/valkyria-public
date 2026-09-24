package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Companion mirror of the watch's `PoTargetHistoryWindowTest`. Pins
 * `RecentsAdviceRepo.historyBefore` — reconstructing a past workout's PO target
 * from sessions BEFORE it, so later heavier sessions can't inflate the target
 * over a warmup-bucket boundary and read a completed exercise as incomplete.
 */
class PoTargetHistoryWindowTest {

    private fun entry(id: String, startIso: String?) =
        ExerciseHistoryEntry(workoutId = id, workoutStartTime = startIso, weightKg = 70f, reps = 8, setType = "normal")

    private val prior = entry("w-prior", "2026-07-01T10:00:00Z")
    private val viewed = entry("w-viewed", "2026-07-06T10:00:00Z")
    private val laterA = entry("w-later-a", "2026-07-08T10:00:00Z")
    private val laterB = entry("w-later-b", "2026-07-10T10:00:00Z")
    private val all = listOf(prior, viewed, laterA, laterB)

    private fun cutoff(iso: String) = RecentsAdviceRepo.parseInstant(iso)

    @Test
    fun `keeps only sessions strictly before the viewed workout`() {
        val kept = RecentsAdviceRepo.historyBefore(all, excludeWorkoutId = "w-viewed", cutoff = cutoff("2026-07-06T10:00:00Z"))
        assertEquals(listOf("w-prior"), kept.map { it.workoutId })
    }

    @Test
    fun `null cutoff falls back to id-only exclusion`() {
        val kept = RecentsAdviceRepo.historyBefore(all, excludeWorkoutId = "w-viewed", cutoff = null)
        assertEquals(listOf("w-prior", "w-later-a", "w-later-b"), kept.map { it.workoutId })
    }

    @Test
    fun `entries with a missing or unparseable stamp are kept`() {
        val nullStamp = entry("w-null", null)
        val garbled = entry("w-garbled", "not-a-timestamp")
        val kept = RecentsAdviceRepo.historyBefore(
            listOf(prior, nullStamp, garbled, laterA), excludeWorkoutId = "w-viewed",
            cutoff = cutoff("2026-07-06T10:00:00Z")
        )
        assertEquals(listOf("w-prior", "w-null", "w-garbled"), kept.map { it.workoutId })
    }

    @Test
    fun `offset-format timestamps compare chronologically`() {
        val offsetPrior = entry("w-off-prior", "2026-07-01T10:00:00+00:00")
        val offsetLater = entry("w-off-later", "2026-07-09T10:00:00+00:00")
        val kept = RecentsAdviceRepo.historyBefore(
            listOf(offsetPrior, offsetLater), excludeWorkoutId = "w-viewed",
            cutoff = cutoff("2026-07-06T10:00:00+00:00")
        )
        assertEquals(listOf("w-off-prior"), kept.map { it.workoutId })
    }
}
