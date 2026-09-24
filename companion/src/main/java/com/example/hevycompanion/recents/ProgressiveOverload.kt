package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseHistoryEntry
import com.example.hevycore.exercise.AssistedBodyweight
import com.example.hevycore.workout.PoConstants
import kotlin.math.abs
import kotlin.math.floor

/**
 * Companion port of the watch's progressive-overload rolling average
 * (`com.example.hevywatch.presentation.workout.ProgressiveOverload` +
 * `PoIncrement` + `AssistedBodyweight`). The two apps are separate installs and
 * don't share code, so the algorithm is duplicated here — same shape, but
 * operating on the public API's [ExerciseHistoryEntry] (set type string
 * `"normal"`) instead of the watch's private-API model, and collapsed to a
 * single next-session figure ([PoOutcome]) rather than writing weights onto an
 * editable set list.
 *
 * See PRD-WATCH-APP.md §"Progressive Overload (PO)" for the full rationale.
 */
object ProgressiveOverload {

    // Constants sourced from :core so watch + companion can't drift. Previously
    // both modules kept identical copies; a rename on one side broke PO parity.
    private val LOOKBACK_WORKOUTS get() = PoConstants.LOOKBACK_WORKOUTS
    private val REP_RANGE_TOP get() = PoConstants.REP_RANGE_TOP
    private val INCREMENT get() = PoConstants.INCREMENT_KG

    /**
     * Compute the next-session PO target for one exercise from its logged
     * history (newest-first, flat across workouts — exactly what
     * `GET /v1/exercise_history/{id}` returns).
     *
     * @param history       the exercise's logged sets, newest first.
     * @param hasEquipment  false for bodyweight-only exercises (no PO — the
     *   watch gates on `ActiveExercise.hasEquipment`).
     * @param exerciseTemplateId used to detect assisted-bodyweight exercises.
     * @param bodyweightKg  the user's bodyweight, for assisted-exercise math.
     */
    fun compute(
        history: List<ExerciseHistoryEntry>,
        hasEquipment: Boolean,
        exerciseTemplateId: String,
        bodyweightKg: Float,
    ): PoOutcome {
        if (history.isEmpty()) return PoOutcome.NONE
        if (!hasEquipment) return PoOutcome.NONE

        // Group the flat history into workouts (newest first), most recent few.
        val byWorkout = history.groupBy { it.workoutId }.values.take(LOOKBACK_WORKOUTS)
        val mostRecent = byWorkout.firstOrNull() ?: return PoOutcome.NONE
        val mostRecentNormal = mostRecent.filter { it.setType == "normal" }
        if (mostRecentNormal.isEmpty()) return PoOutcome.NONE

        val assisted = AssistedBodyweight.isAssisted(exerciseTemplateId)

        // Identify every "successful" workout in the lookback window:
        //   - 3+ normal sets → ≥3 hit the rep floor (breakthrough outliers allowed)
        //   - 1–2 normal sets → all of them hit the rep floor (legacy criterion)
        val qualifyingByWorkout: List<List<ExerciseHistoryEntry>> =
            byWorkout.mapNotNull { workoutEntries ->
                val normal = workoutEntries.filter { it.setType == "normal" }
                if (normal.isEmpty()) return@mapNotNull null
                val qualifying = normal.filter { (it.reps ?: 0) >= REP_RANGE_TOP }
                if (normal.size >= 3) {
                    if (qualifying.size >= 3) qualifying else null
                } else {
                    if (qualifying.size == normal.size) qualifying else null
                }
            }

        if (qualifyingByWorkout.isNotEmpty()) {
            // One data point per qualifying workout (its mean effort), combined
            // with a recency-weighted average so the newest session leads.
            // qualifyingByWorkout is newest-first, so index 0 is the most recent.
            val perWorkoutEfforts = qualifyingByWorkout.map { workoutEntries ->
                workoutEntries.mapNotNull { entry ->
                    entry.weightKg?.let { logged ->
                        if (assisted) (bodyweightKg - logged).coerceAtLeast(0f) else logged
                    }
                }
            }.filter { it.isNotEmpty() }
            if (perWorkoutEfforts.isEmpty()) return PoOutcome.NONE

            val perWorkoutMeans = perWorkoutEfforts.map { it.sum() / it.size }
            val avgEffort = PoConstants.recencyWeightedMean(perWorkoutMeans)
            // All-same shortcut spanning the whole pool: skip flooring so
            // half-increment lifts (22.5kg pin, 12.5kg dumbbell) survive.
            val allEfforts = perWorkoutEfforts.flatten()
            val first = allEfforts[0]
            val allSame = allEfforts.all { abs(it - first) < 0.001f }
            val baseEffort = if (allSame) first else floor(avgEffort / INCREMENT) * INCREMENT
            val targetEffort = baseEffort + INCREMENT

            val baseLogged = if (assisted) (bodyweightKg - baseEffort).coerceAtLeast(0f) else baseEffort
            val targetLogged = if (assisted) (bodyweightKg - targetEffort).coerceAtLeast(0f) else targetEffort

            return PoOutcome(targetKg = targetLogged, baseKg = baseLogged, increased = true)
        }

        // No qualifying workout — carry the most recent session's first normal
        // set weight forward as the target, no bump.
        val carry = mostRecentNormal.firstOrNull()?.weightKg ?: return PoOutcome.NONE
        return PoOutcome(targetKg = carry, baseKg = null, increased = false)
    }
}
