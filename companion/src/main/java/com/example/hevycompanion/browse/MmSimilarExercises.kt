package com.example.hevycompanion.browse

import com.example.hevycompanion.generate.mm.MmExercise

/**
 * Picks "similar" exercises for the M&M detail page.
 *
 * Hard filter: same [MmExercise.area]; self excluded by id. Cross-area
 * similars (Legs ↔ Back) are noise — every M&M row carries an `area`, so
 * the filter is reliable and prevents unrelated rows leaking into the list.
 *
 * Score (additive across signals that overlap):
 *   - +3 per shared `targetMuscle`              — primary movers; dominant signal
 *   - +2 if any `movementPattern` overlaps      — only when both have one
 *   - +1 per shared `equipment`                 — soft signal, not a filter
 *   - +1 if same `category`                     — Compound vs Isolation
 *   - +0.5 per shared `synergistMuscle`         — supporting movers
 *
 * Floor: drop entries with score &lt; 1 (one weak hit beyond `area`). Stretch
 * / cardio rows with no targetMuscles will fall out → empty similar list,
 * which the UI hides cleanly.
 *
 * Sort: score desc; tiebreak alphabetical by `name`. Cap: top 8 (any more
 * and the screen fills with near-identical variants).
 *
 * Equipment is intentionally a soft signal, not a filter — looking at
 * "Romanian Deadlift (Barbell)" should still surface "Romanian Deadlift
 * (Kettlebell)" as a sibling. movementPattern is moderately weighted
 * because only a subset of the catalog has it; when both sides are blank
 * the bonus is silently skipped rather than penalised.
 */
object MmSimilarExercises {

    private const val DEFAULT_CAP = 8
    private const val SCORE_FLOOR = 1.0

    fun find(
        target: MmExercise,
        catalog: List<MmExercise>,
        cap: Int = DEFAULT_CAP,
    ): List<MmExercise> {
        if (target.area.isBlank()) return emptyList()
        val targetTargets = target.targetMuscles.toSet()
        val targetEquip = target.equipment.toSet()
        val targetMp = target.movementPattern.toSet()
        val targetSyn = target.synergistMuscles.toSet()

        return catalog.asSequence()
            .filter { it.id != target.id }
            .filter { it.area == target.area }
            .map { c -> c to score(target, c, targetTargets, targetEquip, targetMp, targetSyn) }
            .filter { (_, s) -> s >= SCORE_FLOOR }
            .sortedWith(
                compareByDescending<Pair<MmExercise, Double>> { it.second }
                    .thenBy { it.first.name.lowercase() },
            )
            .take(cap)
            .map { it.first }
            .toList()
    }

    private fun score(
        target: MmExercise,
        c: MmExercise,
        targetTargets: Set<String>,
        targetEquip: Set<String>,
        targetMp: Set<String>,
        targetSyn: Set<String>,
    ): Double {
        var s = 0.0
        s += 3 * (targetTargets intersect c.targetMuscles.toSet()).size
        if (targetMp.isNotEmpty() && c.movementPattern.isNotEmpty() &&
            (targetMp intersect c.movementPattern.toSet()).isNotEmpty()
        ) {
            s += 2
        }
        s += (targetEquip intersect c.equipment.toSet()).size
        if (target.category.isNotBlank() && target.category == c.category) s += 1
        s += 0.5 * (targetSyn intersect c.synergistMuscles.toSet()).size
        return s
    }
}
