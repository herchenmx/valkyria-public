package com.example.hevywatch.presentation.workout

import com.example.hevycore.exercise.AssistedBodyweight
import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType

// ── PR types ──────────────────────────────────────────────────────────────────

enum class PrType(val label: String) {
    ONE_REP_MAX("1RM PR"),
    WEIGHT("Weight PR"),
    REPS("Reps PR")
}

// ── Epley 1RM table (index = reps-1, value = fraction of true 1RM) ────────────

private val ONE_REP_MAX_TABLE = floatArrayOf(
    1.000f, 0.970f, 0.940f, 0.910f, 0.880f, 0.850f, 0.820f, 0.790f, 0.760f, 0.730f,
    0.700f, 0.670f, 0.640f, 0.610f, 0.580f, 0.550f, 0.530f, 0.510f, 0.490f, 0.470f,
    0.450f, 0.440f, 0.430f, 0.420f, 0.410f, 0.400f, 0.390f, 0.380f, 0.370f, 0.360f,
    0.350f, 0.340f, 0.330f, 0.320f, 0.310f, 0.300f
)

internal fun computeOneRepMax(weightKg: Float?, reps: Int?): Float? {
    val w = weightKg ?: return null
    val r = reps ?: return null
    if (r <= 0 || w <= 0f || r > ONE_REP_MAX_TABLE.size) return null
    val mult = ONE_REP_MAX_TABLE[r - 1]
    return if (mult > 0f) w / mult else null
}

// ── Best lifts snapshot per exercise (used for PR detection) ──────────────────

/**
 * Snapshot of an exercise's best efforts. For conventional exercises [bestWeightKg] and
 * [bestOneRepMax] hold logged kg. For assisted-bodyweight exercises (chinup/dip machine)
 * they hold *effective* kg (bodyweight − logged) so the natural ">"-comparison still
 * means "harder" — a lower logged kg there yields a higher effective kg.
 */
internal data class ExerciseBest(
    val bestOneRepMax: Float? = null,
    val bestWeightKg: Float? = null,
    val bestReps: Int? = null
)

internal fun detectPrType(best: ExerciseBest, weightKg: Float?, reps: Int?): PrType? {
    computeOneRepMax(weightKg, reps)?.let { newOrm ->
        if (newOrm > (best.bestOneRepMax ?: 0f)) return PrType.ONE_REP_MAX
    }
    weightKg?.let { w ->
        if (w > (best.bestWeightKg ?: 0f)) return PrType.WEIGHT
    }
    reps?.let { r ->
        if (r > (best.bestReps ?: 0)) return PrType.REPS
    }
    return null
}

// ── Workout-level PR scan ─────────────────────────────────────────────────────

/**
 * Build an [ExerciseBest] from the pre-workout history snapshot. Only normal sets
 * are considered (warmups / dropsets / failure sets are not eligible for PRs).
 * Returns null when there's nothing to compare against — used by [findBestPrInWorkout]
 * to skip exercises with no prior history (first-ever session of an exercise).
 *
 * For assisted-bodyweight exercises the snapshot is built in effective-work space, so
 * "best" means the highest bodyweight portion ever shifted on this exercise.
 */
internal fun historyToBest(
    history: ExerciseHistoryResponse?,
    exerciseTemplateId: String? = null,
    bodyweightKg: Float = 0f
): ExerciseBest? {
    val entries = history?.exerciseHistory
        ?.filter { it.setType.equals("normal", ignoreCase = true) }
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    val isAssisted = AssistedBodyweight.isAssisted(exerciseTemplateId)

    var bestOrm: Float? = null
    var bestWeight: Float? = null
    var bestReps: Int? = null
    entries.forEach { e ->
        val effW = e.weightKg?.let { logged ->
            if (isAssisted) (bodyweightKg - logged).coerceAtLeast(0f) else logged
        }
        computeOneRepMax(effW, e.reps)?.let { orm ->
            if (orm > (bestOrm ?: 0f)) bestOrm = orm
        }
        effW?.let { w ->
            if (w > (bestWeight ?: 0f)) bestWeight = w
        }
        e.reps?.let { r ->
            if (r > (bestReps ?: 0)) bestReps = r
        }
    }
    return ExerciseBest(bestOrm, bestWeight, bestReps)
}

/**
 * Scan every completed normal set in [sets] and return the strongest PR the lifter hit,
 * or null if none. Priority mirrors [detectPrType]: 1RM > weight > reps. Warmup / failure
 * / dropset sets are excluded so that a 5-rep warmup at 20kg doesn't get crowned as a PR.
 *
 * For assisted-bodyweight exercises each set's logged kg is converted to effective work
 * before comparison, so a lower logged kg that beats the prior best effective is rightly
 * detected as a Weight PR.
 */
internal fun findBestPrInWorkout(
    best: ExerciseBest?,
    sets: List<ActiveSet>,
    exerciseTemplateId: String? = null,
    bodyweightKg: Float = 0f
): PrType? {
    if (best == null) return null  // No prior history — can't claim a PR on first-ever session.
    val isAssisted = AssistedBodyweight.isAssisted(exerciseTemplateId)
    var strongest: PrType? = null
    sets.asSequence()
        .filter { it.completed && it.setType == SetType.NORMAL }
        .forEach { s ->
            val effW = s.weightKg?.let { logged ->
                if (isAssisted) (bodyweightKg - logged).coerceAtLeast(0f) else logged
            }
            val pr = detectPrType(best, effW, s.reps) ?: return@forEach
            strongest = when {
                strongest == null -> pr
                pr == PrType.ONE_REP_MAX -> pr
                pr == PrType.WEIGHT && strongest == PrType.REPS -> pr
                else -> strongest
            }
        }
    return strongest
}
