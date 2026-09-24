package com.example.hevywatch.data

import com.example.hevywatch.data.api.model.ExerciseHistoryEntry

/**
 * Pure helpers that summarise an exercise's last-session entries for
 * display on RoutineDetailScreen's chips.
 *
 * Extracted as stateless functions so the aggregation rules can be unit-tested
 * without Compose or the ViewModel.
 */
object LastSessionStats {

    /**
     * Average weight (kg) across the "normal" sets from a last-session entry list.
     *
     * - Warmup, dropset, and failure sets are excluded — the number is meant to
     *   summarise the working weight, not the cumulative session load.
     * - Entries with null or non-positive weight are excluded — bodyweight-only
     *   exercises with zero weight have no meaningful average, so we return null
     *   and the caller omits the subline rather than showing "0.0kg avg".
     * - Case-insensitive on [ExerciseHistoryEntry.setType] ("normal" / "Normal"
     *   / "NORMAL" all count).
     *
     * Returns null when the input is empty or contains no qualifying normal sets.
     */
    fun avgNormalWeightKg(entries: List<ExerciseHistoryEntry>): Float? {
        if (entries.isEmpty()) return null
        val normalWeights = entries
            .asSequence()
            .filter { it.setType.equals("normal", ignoreCase = true) }
            .mapNotNull { it.weightKg }
            .filter { it > 0f }
            .toList()
        if (normalWeights.isEmpty()) return null
        return normalWeights.sum() / normalWeights.size
    }

    /**
     * From a full exercise-history list (newest first by workout), keep only the
     * entries that share the most recent workoutId. Used to render the
     * "Last session" expansion on routine and log screens.
     *
     * Relies on the API's ordering — the first entry's workoutId is the latest
     * session. Returns an empty list for empty input.
     */
    fun latestSessionEntries(entries: List<ExerciseHistoryEntry>): List<ExerciseHistoryEntry> {
        val latestId = entries.firstOrNull()?.workoutId ?: return emptyList()
        return entries.filter { it.workoutId == latestId }
    }
}
