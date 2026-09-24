package com.example.hevywatch.data.model

/**
 * Completion status of a single exercise in a workout compared to its routine prescription.
 *
 * For [Status.SUBSTITUTED] rows the [title] / [exerciseTemplateId] describe the
 * exercise the user *actually did*, while [prescribedTitle] holds the name of
 * the routine slot it filled (e.g. did "Lateral Raise (Dumbbell)", prescribed
 * "Lateral Raise (Machine)"). For [Status.EXTRA] rows there is no prescription,
 * so [prescribedNormalSets] is 0.
 *
 * [expectedWarmupSets] is how many warmup sets the warmup advisor would have
 * prescribed for this exercise at this session's logged working weight (0 when
 * the exercise doesn't qualify, or when catalog metadata / a working weight
 * wasn't available). It feeds [isComplete] so a row only reads "done" once both
 * its normal sets AND its advised warmups are logged.
 */
data class ExerciseCompletionStatus(
    val title: String,
    val exerciseTemplateId: String,
    val prescribedNormalSets: Int,
    val recordedNormalSets: Int,
    val recordedWarmupSets: Int,
    val status: Status,
    /** Routine-slot name a [Status.SUBSTITUTED] row stood in for; null otherwise. */
    val prescribedTitle: String? = null,
    /** Warmup sets the advisor would prescribe at this session's working weight. */
    val expectedWarmupSets: Int = 0,
    /** Working weight actually logged this session (first normal set), or null
     *  when nothing was logged. Shown on the chip once ≥1 normal set exists. */
    val loggedWorkingWeightKg: Float? = null,
    /** Progressive-overload target weight for the prescribed exercise — shown on
     *  the chip when NO normal set is logged yet (a still-MISSING slot). Null
     *  when PO can't be computed. */
    val poTargetKg: Float? = null,
    /** True when [poTargetKg] is a genuine PO bump (paints the weight green). */
    val poIncreased: Boolean = false
) {
    // Order matters: rows are sorted by ordinal, so this is the display order
    // (done-then-swapped-then-shortfall-then-missing, extras last).
    enum class Status { COMPLETE, SUBSTITUTED, INCOMPLETE, MISSING, EXTRA }

    /**
     * Fully done iff every prescribed normal set AND every advised warmup set
     * was logged. Drives the Workout Detail row colour (green vs blue) so
     * completion — not OG-vs-swap — is what the tint conveys.
     */
    val isComplete: Boolean
        get() = recordedNormalSets >= prescribedNormalSets &&
            recordedWarmupSets >= expectedWarmupSets
}
