package com.example.hevycompanion.recents

import com.example.hevycore.exercise.AssistedBodyweight
import com.example.hevycore.workout.WarmupConstants
import kotlin.math.floor

/**
 * Companion port of the watch's warmup advisor
 * (`com.example.hevywatch.presentation.workout.WarmupAdvisor.suggestWarmupSets`).
 * Verbatim algorithm — muscle-group + equipment + working-weight buckets, the
 * same protocol table, the same equipment rounding and barbell 20 kg floor, the
 * same unilateral doubling and assisted-bodyweight effort-space conversion — but
 * returning a trimmed [WarmupSet] list for read-only display rather than
 * injecting editable sets into a live workout.
 *
 * See PRD-WATCH-APP.md §"Warmup Advisor" for the full specification.
 */
object WarmupAdvisor {
    // Tables sourced from :core (WarmupConstants) so watch + companion can't
    // drift. Muscle-group sets, protocol table, rounding rules — all shared.

    /**
     * Prescribe warmup sets for an exercise given its working weight.
     *
     * @param primaryMuscleGroup the exercise's `primary_muscle_group`.
     * @param equipment          the exercise's `equipment`.
     * @param workingWeightKg    the logged working weight (the PO target, or the
     *   most recent session's weight).
     * @param normalSetCount     prescribed/logged normal-set count — drives the
     *   unilateral doubling (≥6 and even → two warmups per level).
     * @param exerciseTemplateId used to detect assisted-bodyweight exercises.
     * @param bodyweightKg       the user's bodyweight, for assisted math.
     */
    fun suggest(
        primaryMuscleGroup: String?,
        equipment: String?,
        workingWeightKg: Float,
        normalSetCount: Int,
        exerciseTemplateId: String?,
        bodyweightKg: Float,
    ): List<WarmupSet> {
        if (equipment?.lowercase() !in WarmupConstants.WEIGHTED_EQUIPMENT) return emptyList()
        if (primaryMuscleGroup?.lowercase() in WarmupConstants.NO_WARMUP_GROUPS) return emptyList()

        val category = WarmupConstants.categorize(primaryMuscleGroup) ?: return emptyList()

        val assisted = AssistedBodyweight.isAssisted(exerciseTemplateId)

        val effortWeightKg = if (assisted) {
            (bodyweightKg - workingWeightKg).coerceAtLeast(0f)
        } else workingWeightKg
        if (effortWeightKg <= 0f) return emptyList()

        val setCount = WarmupConstants.warmupSetCount(category, effortWeightKg)
        if (setCount == 0) return emptyList()

        val increment = WarmupConstants.incrementFor(equipment)
        val minWeight = WarmupConstants.minWarmupWeight(equipment)

        val protocol = WarmupConstants.PROTOCOLS[setCount] ?: return emptyList()
        val single = protocol.map { (pct, reps) ->
            val raw = effortWeightKg * pct
            val effortRounded = (floor(raw / increment) * increment).coerceAtLeast(minWeight)
            val loggedKg = if (assisted) (bodyweightKg - effortRounded).coerceAtLeast(0f) else effortRounded
            WarmupSet(weightKg = loggedKg, reps = reps)
        }

        // Unilateral exercises (≥6 normal sets, even count): double every warmup
        // so each side gets one at each weight level.
        return if (normalSetCount >= 6 && normalSetCount % 2 == 0) {
            single.flatMap { listOf(it, it.copy()) }
        } else {
            single
        }
    }
}
