package com.example.hevywatch

import com.example.hevywatch.data.LastSessionStats
import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the "average normal-set weight" computation used as the second line
 * on each exercise chip in RoutineDetailScreen.
 */
class LastSessionStatsTest {

    private fun entry(
        setType: String = "normal",
        weightKg: Float? = null,
        reps: Int? = null,
        workoutId: String = "w-1"
    ) = ExerciseHistoryEntry(
        workoutId = workoutId,
        workoutTitle = "t",
        workoutStartTime = "2026-04-10T10:00:00Z",
        workoutEndTime = null,
        exerciseTemplateId = "abc",
        weightKg = weightKg,
        reps = reps,
        distanceMeters = null,
        durationSeconds = null,
        rpe = null,
        customMetric = null,
        setType = setType
    )

    // ── Empty / no-data cases ─────────────────────────────────────────────────

    @Test
    fun `empty entry list returns null`() {
        assertNull(LastSessionStats.avgNormalWeightKg(emptyList()))
    }

    @Test
    fun `no normal sets returns null`() {
        val entries = listOf(
            entry(setType = "warmup", weightKg = 40f),
            entry(setType = "warmup", weightKg = 50f)
        )
        assertNull(LastSessionStats.avgNormalWeightKg(entries))
    }

    @Test
    fun `normal sets with null weight return null`() {
        val entries = listOf(
            entry(setType = "normal", weightKg = null, reps = 10),
            entry(setType = "normal", weightKg = null, reps = 10)
        )
        assertNull(LastSessionStats.avgNormalWeightKg(entries))
    }

    @Test
    fun `normal sets with zero weight are excluded`() {
        // Bodyweight exercise logged with weight 0 — no meaningful avg.
        val entries = listOf(
            entry(setType = "normal", weightKg = 0f, reps = 12),
            entry(setType = "normal", weightKg = 0f, reps = 10)
        )
        assertNull(LastSessionStats.avgNormalWeightKg(entries))
    }

    // ── Happy paths ───────────────────────────────────────────────────────────

    @Test
    fun `single normal set returns its weight`() {
        val entries = listOf(entry(setType = "normal", weightKg = 100f))
        assertEquals(100f, LastSessionStats.avgNormalWeightKg(entries)!!, 0.0001f)
    }

    @Test
    fun `average of three equal normal sets is that weight`() {
        val entries = listOf(
            entry(setType = "normal", weightKg = 100f),
            entry(setType = "normal", weightKg = 100f),
            entry(setType = "normal", weightKg = 100f)
        )
        assertEquals(100f, LastSessionStats.avgNormalWeightKg(entries)!!, 0.0001f)
    }

    @Test
    fun `average of mixed-weight normal sets is arithmetic mean`() {
        val entries = listOf(
            entry(setType = "normal", weightKg = 100f),
            entry(setType = "normal", weightKg = 105f),
            entry(setType = "normal", weightKg = 107f)
        )
        assertEquals(104f, LastSessionStats.avgNormalWeightKg(entries)!!, 0.0001f)
    }

    // ── Set-type filtering ────────────────────────────────────────────────────

    @Test
    fun `warmup and dropset and failure are excluded from the average`() {
        val entries = listOf(
            entry(setType = "warmup",  weightKg = 40f),   // excluded
            entry(setType = "warmup",  weightKg = 60f),   // excluded
            entry(setType = "normal",  weightKg = 100f),
            entry(setType = "normal",  weightKg = 100f),
            entry(setType = "dropset", weightKg = 80f),   // excluded
            entry(setType = "failure", weightKg = 90f)    // excluded
        )
        assertEquals(100f, LastSessionStats.avgNormalWeightKg(entries)!!, 0.0001f)
    }

    @Test
    fun `setType matching is case-insensitive`() {
        val entries = listOf(
            entry(setType = "Normal", weightKg = 100f),
            entry(setType = "NORMAL", weightKg = 110f),
            entry(setType = "normal", weightKg = 120f)
        )
        assertEquals(110f, LastSessionStats.avgNormalWeightKg(entries)!!, 0.0001f)
    }

    // ── Partial-weight normal sets ────────────────────────────────────────────

    @Test
    fun `normal sets with some missing weights average only those with weight`() {
        val entries = listOf(
            entry(setType = "normal", weightKg = 50f),
            entry(setType = "normal", weightKg = null), // excluded
            entry(setType = "normal", weightKg = 70f),
            entry(setType = "normal", weightKg = 0f)    // excluded (non-positive)
        )
        // Avg of 50 and 70 = 60
        assertEquals(60f, LastSessionStats.avgNormalWeightKg(entries)!!, 0.0001f)
    }

    @Test
    fun `decimal weights round-trip as expected`() {
        // 22.5 + 25 + 27.5 = 75 / 3 = 25.0
        val entries = listOf(
            entry(setType = "normal", weightKg = 22.5f),
            entry(setType = "normal", weightKg = 25f),
            entry(setType = "normal", weightKg = 27.5f)
        )
        assertEquals(25f, LastSessionStats.avgNormalWeightKg(entries)!!, 0.0001f)
    }

    // ── latestSessionEntries — partitions a multi-session history list ────────

    @Test
    fun `latestSessionEntries returns empty for empty input`() {
        assertEquals(emptyList<ExerciseHistoryEntry>(), LastSessionStats.latestSessionEntries(emptyList()))
    }

    @Test
    fun `latestSessionEntries keeps only the first workoutId's entries`() {
        // API returns newest workout first; later workouts come after.
        val entries = listOf(
            entry(workoutId = "w-newest", weightKg = 100f),
            entry(workoutId = "w-newest", weightKg = 105f),
            entry(workoutId = "w-newest", weightKg = 110f),
            entry(workoutId = "w-older",  weightKg = 95f),
            entry(workoutId = "w-older",  weightKg = 100f),
            entry(workoutId = "w-oldest", weightKg = 90f)
        )
        val latest = LastSessionStats.latestSessionEntries(entries)
        assertEquals(3, latest.size)
        assertEquals(listOf("w-newest", "w-newest", "w-newest"), latest.map { it.workoutId })
        assertEquals(listOf(100f, 105f, 110f), latest.mapNotNull { it.weightKg })
    }

    @Test
    fun `latestSessionEntries with a single session returns all entries`() {
        val entries = listOf(
            entry(workoutId = "w-1", weightKg = 80f),
            entry(workoutId = "w-1", weightKg = 80f)
        )
        assertEquals(entries, LastSessionStats.latestSessionEntries(entries))
    }

    @Test
    fun `latestSessionEntries does not reorder within the latest session`() {
        // Mixed set types within the latest workout — order must be preserved
        // since the UI numbers them by index ("1", "2W", "3D", etc).
        val entries = listOf(
            entry(workoutId = "w-latest", setType = "warmup", weightKg = 40f),
            entry(workoutId = "w-latest", setType = "normal", weightKg = 100f),
            entry(workoutId = "w-latest", setType = "dropset", weightKg = 80f),
            entry(workoutId = "w-prev",   setType = "normal", weightKg = 95f)
        )
        val latest = LastSessionStats.latestSessionEntries(entries)
        assertEquals(listOf("warmup", "normal", "dropset"), latest.map { it.setType })
    }
}
