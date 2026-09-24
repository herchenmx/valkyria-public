package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseHistoryEntry

/**
 * Companion port of the watch's `com.example.hevywatch.data.LastSessionStats`.
 * The two apps are separate installs and don't share code, so the aggregation
 * rules are duplicated here, operating on the public API's
 * [ExerciseHistoryEntry] (nullable set-type string) instead of the watch's
 * private-API model.
 *
 * Stateless so the rules can be unit-tested without a ViewModel.
 */
object LastSessionStats {

    /**
     * From a flat exercise-history list (newest first, as
     * `GET /v1/exercise_history/{id}` returns it), keep only the entries that
     * share the most recent [ExerciseHistoryEntry.workoutId] — i.e. the last
     * session the user actually did this exercise.
     *
     * Relies on the API's newest-first ordering. Empty for empty input.
     */
    fun latestSessionEntries(entries: List<ExerciseHistoryEntry>): List<ExerciseHistoryEntry> {
        val latestId = entries.firstOrNull()?.workoutId ?: return emptyList()
        return entries.filter { it.workoutId == latestId }
    }

    /**
     * Average weight (kg) across the "normal" sets of an exercise's **most
     * recent** session.
     *
     * - Warmup / dropset / failure sets are excluded — the number summarises the
     *   working weight, not the cumulative session load.
     * - Entries with null or non-positive weight are excluded — a bodyweight-only
     *   exercise has no meaningful average, so we return null and the caller omits
     *   the line rather than showing "0 kg avg".
     * - Case-insensitive on [ExerciseHistoryEntry.setType].
     *
     * Returns null when there are no qualifying normal sets in the last session.
     */
    fun avgLastSessionNormalKg(entries: List<ExerciseHistoryEntry>): Float? {
        val normalWeights = latestSessionEntries(entries)
            .asSequence()
            .filter { it.setType.equals("normal", ignoreCase = true) }
            .mapNotNull { it.weightKg }
            .filter { it > 0f }
            .toList()
        if (normalWeights.isEmpty()) return null
        return normalWeights.sum() / normalWeights.size
    }
}
