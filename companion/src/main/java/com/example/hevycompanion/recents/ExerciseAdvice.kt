package com.example.hevycompanion.recents

/**
 * One advisor-prescribed warmup set: a percentage-derived weight and a rep
 * target. The companion counterpart to the watch's warmup `ActiveSet`, trimmed
 * to the two fields the read-only display needs.
 */
data class WarmupSet(val weightKg: Float, val reps: Int)

/**
 * The result of running the progressive-overload rolling average over one
 * exercise's history. Mirrors what the watch writes onto a normal set
 * (`weightKg` + `poBaseWeightKg`), collapsed to a single next-session figure.
 *
 * @property targetKg the weight to aim for next session, or null when PO can't
 *   be computed (no history, no equipment, or no usable weights).
 * @property baseKg   the "was X kg" reference the target was built from — only
 *   set when [increased] is true (a genuine PO bump). Null on the fallback path.
 * @property increased true when a qualifying workout triggered a PO bump; false
 *   when [targetKg] is just the most recent session's weight carried forward.
 */
data class PoOutcome(
    val targetKg: Float?,
    val baseKg: Float?,
    val increased: Boolean,
) {
    companion object {
        /** PO could not be computed for this exercise. */
        val NONE = PoOutcome(targetKg = null, baseKg = null, increased = false)
    }
}

/**
 * Everything the Workout Detail screen shows when an exercise row is expanded:
 * the next-session PO target, the advised warmup sets that ramp up to it, and
 * the average working weight from the last session. Assembled per
 * exercise-template id in [WorkoutDetailViewModel].
 *
 * @property avgLastNormalKg the mean weight of the normal sets from the most
 *   recent prior session, or null when there were no weighted normal sets
 *   (bodyweight-only or first-ever time). See [LastSessionStats].
 */
data class ExerciseAdvice(
    val po: PoOutcome,
    val warmups: List<WarmupSet>,
    val avgLastNormalKg: Float? = null,
) {
    val hasAnything: Boolean
        get() = po.targetKg != null || warmups.isNotEmpty() || avgLastNormalKg != null

    companion object {
        val EMPTY = ExerciseAdvice(po = PoOutcome.NONE, warmups = emptyList())
    }
}
