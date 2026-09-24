package com.example.hevycore.workout

/**
 * Progressive-overload constants shared by the watch's
 * `ProgressiveOverload.computeProgressiveOverload` and the companion's
 * `ProgressiveOverload.compute`. Duplication of these constants across the
 * two modules is the exact drift risk the extraction targets: a change to
 * the rolling-average window or the rep-range floor on one side alone would
 * give the two apps different PO targets on the same lifting history.
 */
object PoConstants {

    /** How many recent workouts back to look when computing the PO base.
     *  The recency-weighted average pools qualifying sets from up to this
     *  many sessions. Kept short (3) so a fast weight ramp isn't dragged
     *  down by older, much-lighter ramp-up sessions still in the window —
     *  see [recencyWeightedMean] and PRD-WATCH-APP.md §"Progressive Overload". */
    const val LOOKBACK_WORKOUTS = 3

    /** Rep floor a set must hit to count as "successful". */
    const val REP_RANGE_TOP = 15

    /** Universal 1.0 kg PO increment — applies to barbell, dumbbell,
     *  kettlebell, machine, plate. The user micro-loads with 0.5 kg /
     *  1.25 kg fractional plates across the full equipment range, so a
     *  single small step gives PO a gentle cadence on heavy compounds
     *  without changing anything for the rest. */
    const val INCREMENT_KG: Float = 1.0f

    /** Recency decay factor for the PO base: each older qualifying workout
     *  counts 1/[RECENCY_WEIGHT_DECAY] as much as the next-newer one. With
     *  the [LOOKBACK_WORKOUTS] = 3 window this yields weights 9 : 3 : 1
     *  (newest : middle : oldest), so the most recent session dominates while
     *  older sessions still damp a one-off spike or drop. */
    const val RECENCY_WEIGHT_DECAY: Float = 3.0f

    /**
     * Recency-weighted mean of one data point per qualifying workout,
     * [meansNewestFirst] ordered most-recent first. The newest workout gets
     * weight 1, each older one 1/[RECENCY_WEIGHT_DECAY] of its successor
     * (geometric decay). Set count within a workout does NOT affect the
     * weighting — each qualifying session is a single point at its own mean,
     * so a high-volume day can't outvote the lifter's recency.
     *
     * Empty input returns 0f; callers guard against empty pools upstream.
     * Shared by the watch's `computeProgressiveOverload` and the companion's
     * `ProgressiveOverload.compute` so the two apps can't drift.
     */
    fun recencyWeightedMean(meansNewestFirst: List<Float>): Float {
        var weightedSum = 0f
        var weightTotal = 0f
        var weight = 1f
        for (mean in meansNewestFirst) {
            weightedSum += mean * weight
            weightTotal += weight
            weight /= RECENCY_WEIGHT_DECAY
        }
        return if (weightTotal == 0f) 0f else weightedSum / weightTotal
    }
}
