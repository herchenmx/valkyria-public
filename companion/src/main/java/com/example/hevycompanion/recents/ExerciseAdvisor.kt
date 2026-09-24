package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseHistoryEntry

/**
 * Ties the progressive-overload and warmup ports together into one
 * [ExerciseAdvice] for a single exercise. Pure (no IO) so it's directly unit
 * tested — the [WorkoutDetailViewModel] does the history fetch and feeds the
 * result in.
 */
object ExerciseAdvisor {

    /** Equipment values that mean "bodyweight" — no PO target, no warmups. */
    private val NO_EQUIPMENT = setOf("", "none")

    /**
     * @param history          the exercise's logged sets (newest first), with
     *   the currently-viewed workout already filtered out so PO resolves against
     *   prior *completed* sessions (mirrors the watch's resume history filter).
     * @param equipment        the exercise's `equipment` (catalog).
     * @param primaryMuscleGroup the exercise's `primary_muscle_group` (catalog).
     * @param normalSetCount   prescribed normal-set count (or logged count for an
     *   un-prescribed extra) — drives the warmup advisor's unilateral doubling.
     */
    fun adviseFor(
        history: List<ExerciseHistoryEntry>,
        equipment: String?,
        primaryMuscleGroup: String?,
        exerciseTemplateId: String,
        normalSetCount: Int,
        bodyweightKg: Float,
    ): ExerciseAdvice {
        val hasEquipment = equipment != null && equipment.lowercase() !in NO_EQUIPMENT
        val po = ProgressiveOverload.compute(
            history = history,
            hasEquipment = hasEquipment,
            exerciseTemplateId = exerciseTemplateId,
            bodyweightKg = bodyweightKg,
        )
        val workingWeightKg = po.targetKg ?: 0f
        val warmups = WarmupAdvisor.suggest(
            primaryMuscleGroup = primaryMuscleGroup,
            equipment = equipment,
            workingWeightKg = workingWeightKg,
            normalSetCount = normalSetCount,
            exerciseTemplateId = exerciseTemplateId,
            bodyweightKg = bodyweightKg,
        )
        return ExerciseAdvice(
            po = po,
            warmups = warmups,
            avgLastNormalKg = LastSessionStats.avgLastSessionNormalKg(history),
        )
    }
}
