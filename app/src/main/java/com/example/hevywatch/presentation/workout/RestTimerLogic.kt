package com.example.hevywatch.presentation.workout

import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.SetType
import kotlin.math.ceil

/**
 * Whole seconds still remaining on a rest timer, given the elapsedRealtime-based
 * end instant and the current elapsedRealtime.
 *
 * Rounds **up** (ceil): a countdown should display "1" for the entire final
 * second and only show "0" once it is genuinely up. Both the rest-timer screen
 * and the in-clock countdown call this so they round identically — flooring one
 * of them (plain integer division) made the screen read 1s higher than the clock
 * for the whole rest and showed "0:00" while a full second was still running.
 */
internal fun restRemainingSeconds(endMs: Long, nowMs: Long): Int =
    ceil((endMs - nowMs) / 1000.0).toInt().coerceAtLeast(0)

/**
 * Computes the rest duration (in seconds) after completing a set, based on:
 * - Set types (warmup vs normal) of the completed and next set
 * - Whether the exercise is unilateral (even normal-set count, ≥ 6)
 * - Position within warmup / normal sets
 *
 * Bilateral (odd normal-set count) warmup rest:
 *   warmup → warmup: 45s,  last warmup → normal: 60s
 *
 * Unilateral (even normal-set count) warmup rest:
 *   odd warmup → even warmup (switch sides): 0s — flow straight into the
 *     other side without a timer screen
 *   even warmup → odd warmup (weight increase): 30s
 *   last warmup → normal: 60s
 *
 * Unilateral normal rest:
 *   odd normal → even normal (switch sides): 0s
 *   even normal → odd normal: routine default
 *
 * Bilateral normal rest: routine default
 */
internal fun computeRestSecondsForSet(exercise: ActiveExercise, completedSetIndex: Int): Int {
    val sets = exercise.sets
    val completedSet = sets[completedSetIndex]
    val nextIndex = completedSetIndex + 1
    if (nextIndex >= sets.size) return 0
    val nextSet = sets[nextIndex]
    val defaultRest = exercise.restTimerSeconds ?: 0

    val normalCount = sets.count { it.setType == SetType.NORMAL }
    val isUnilateral = normalCount >= 6 && normalCount % 2 == 0

    // Warmup → Warmup
    if (completedSet.setType == SetType.WARMUP && nextSet.setType == SetType.WARMUP) {
        if (isUnilateral) {
            // Warmup number (1-based) of the set just completed
            val warmupNum = sets.take(completedSetIndex + 1).count { it.setType == SetType.WARMUP }
            return if (warmupNum % 2 == 1) 0 else 30  // odd→even: switch side, no rest
        }
        return 45
    }

    // Last warmup → first normal
    if (completedSet.setType == SetType.WARMUP && nextSet.setType == SetType.NORMAL) {
        return 60
    }

    // Normal → Normal
    if (completedSet.setType == SetType.NORMAL && nextSet.setType == SetType.NORMAL) {
        if (isUnilateral) {
            val normalNum = sets.take(completedSetIndex + 1).count { it.setType == SetType.NORMAL }
            return if (normalNum % 2 == 1) 0 else defaultRest  // odd→even: switch side, no rest
        }
        return defaultRest
    }

    return defaultRest
}
