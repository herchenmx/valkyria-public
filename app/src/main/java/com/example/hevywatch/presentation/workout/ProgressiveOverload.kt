package com.example.hevywatch.presentation.workout

import com.example.hevycore.exercise.AssistedBodyweight
import com.example.hevycore.workout.PoConstants
import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.SetType

// ── Progressive overload algorithm ────────────────────────────────────────────

internal fun computeProgressiveOverload(
    exercises: List<ActiveExercise>,
    historyMap: Map<String, ExerciseHistoryResponse>,
    bodyweightKg: Float = 0f
): Pair<List<ActiveExercise>, Set<String>> {
    val weightIncreasedIds = mutableSetOf<String>()
    val updated = exercises.map { exercise ->
        val entries = historyMap[exercise.exerciseTemplateId]
            ?.exerciseHistory.orEmpty()
        if (entries.isEmpty()) return@map exercise

        val byWorkout = entries.groupBy { it.workoutId }.values.take(PoConstants.LOOKBACK_WORKOUTS)
        val mostRecentEntries = byWorkout.firstOrNull() ?: return@map exercise

        val historyNormal = mostRecentEntries.filter { it.setType == "normal" }
        val routineNormal = exercise.sets.filter { it.setType == SetType.NORMAL }

        if (historyNormal.isEmpty() || routineNormal.isEmpty()) return@map exercise
        if (!exercise.hasEquipment) return@map exercise

        val repRangeTop = PoConstants.REP_RANGE_TOP
        val isAssisted = AssistedBodyweight.isAssisted(exercise.exerciseTemplateId)

        // Identify *every* "successful" workout in recent history. The qualifying criterion:
        //   - 3+ normal sets in the workout → ≥3 of them must hit 15 reps (outliers allowed)
        //   - 1–2 normal sets              → all of them must hit 15 reps (legacy behavior)
        // PO base is a recency-weighted average across qualifying workouts —
        // breakthrough low-rep sets don't pollute the next target, but a fast
        // weight ramp isn't dragged down by older, lighter sessions either:
        // the newest qualifying workout dominates (PoConstants.RECENCY_WEIGHT_DECAY).
        val equipIncrement = PoConstants.INCREMENT_KG

        val qualifyingByWorkout: List<List<com.example.hevywatch.data.api.model.ExerciseHistoryEntry>> =
            byWorkout.mapNotNull { workoutEntries ->
                val normal = workoutEntries.filter { it.setType == "normal" }
                if (normal.isEmpty()) return@mapNotNull null
                val qualifying = normal.filter { (it.reps ?: 0) >= repRangeTop }
                if (normal.size >= 3) {
                    if (qualifying.size >= 3) qualifying else null
                } else {
                    if (qualifying.size == normal.size) qualifying else null
                }
            }

        // Any 1 qualifying workout in the lookback window triggers PO — no
        // per-muscle threshold. The recency-weighted base keeps the cadence
        // honest without the old small-muscle 2-of-5 gate: older sessions
        // still damp the target, but the newest session leads, so a lifter on
        // a steady ramp gets a target that tracks their most recent work
        // rather than lagging behind the ramp-up weeks. Trade-off accepted: a
        // single bad *most-recent* week weighs heavily and can drag the target
        // below the previous best (the "heaviest qualifying" drop-protection
        // was intentionally dropped).
        if (qualifyingByWorkout.isNotEmpty()) {
            // One data point per qualifying workout (its mean effort), combined
            // with a recency-weighted average so the newest session dominates.
            // qualifyingByWorkout is newest-first, so index 0 is the most recent.
            val perWorkoutEfforts = qualifyingByWorkout.map { workoutEntries ->
                workoutEntries.mapNotNull { entry ->
                    entry.weightKg?.let { logged ->
                        if (isAssisted) (bodyweightKg - logged).coerceAtLeast(0f) else logged
                    }
                }
            }.filter { it.isNotEmpty() }
            if (perWorkoutEfforts.isEmpty()) return@map exercise

            val perWorkoutMeans = perWorkoutEfforts.map { it.sum() / it.size }
            val avgEffort = PoConstants.recencyWeightedMean(perWorkoutMeans)
            // All-same shortcut spans the entire pool: if every qualifying set
            // across every qualifying workout was logged at the same weight,
            // skip flooring so half-increment lifts (22.5kg pin, 12.5kg
            // dumbbell) survive. Weighting is irrelevant when all values match.
            val allEfforts = perWorkoutEfforts.flatten()
            val first = allEfforts[0]
            val allSame = allEfforts.all { kotlin.math.abs(it - first) < 0.001f }
            val baseEffort = if (allSame) first
                             else kotlin.math.floor(avgEffort / equipIncrement) * equipIncrement
            val targetEffort = baseEffort + equipIncrement

            // Convert back to logged space for storage / display / API submission.
            val baseLogged = if (isAssisted) {
                (bodyweightKg - baseEffort).coerceAtLeast(0f)
            } else baseEffort
            val targetLogged = if (isAssisted) {
                (bodyweightKg - targetEffort).coerceAtLeast(0f)
            } else targetEffort

            weightIncreasedIds.add(exercise.exerciseTemplateId)
            exercise.copy(
                sets = exercise.sets.map { s ->
                    if (s.setType != SetType.NORMAL) return@map s
                    // B9 — extend the B1 invariant to PO. A completed set holds
                    // the user's actual logged weight (often dropped below the
                    // PO target after the body refused). Crash recovery re-runs
                    // this pipeline on VM init, and an unconditional overwrite
                    // would clobber every completed set with one broadcast PO
                    // value, then a Finish-tap would POST that wrong weight to
                    // the API. Reps are untouched by PO, which is why the user-
                    // visible symptom is "reps right, weights all identical".
                    if (s.completed) return@map s
                    s.copy(weightKg = targetLogged, poBaseWeightKg = baseLogged)
                }
            )
        } else {
            // No successful workout in recent history — use most recent workout's
            // per-set weights as-is (no PO applied). Same B9 guard: a completed
            // set's logged weight is authoritative; don't replace it on recovery.
            var normalIdx = 0
            exercise.copy(
                sets = exercise.sets.map { s ->
                    if (s.setType != SetType.NORMAL) return@map s
                    val idx = normalIdx++
                    if (s.completed) return@map s
                    val weight = historyNormal.getOrNull(idx)?.weightKg
                        ?: historyNormal.firstOrNull()?.weightKg
                        ?: s.weightKg
                    s.copy(weightKg = weight)
                }
            )
        }
    }
    return updated to weightIncreasedIds
}
