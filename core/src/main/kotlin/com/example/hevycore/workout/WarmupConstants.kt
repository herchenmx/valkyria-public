package com.example.hevycore.workout

/**
 * Muscle-group tables and rounding rules shared by the watch's
 * `WarmupAdvisor.suggestWarmupSets` and the companion's
 * `WarmupAdvisor.suggest`. Previously duplicated verbatim in both modules —
 * if one side added `"neck"` to [NO_WARMUP_GROUPS] without the other, the
 * two apps would prescribe different warmups for the same exercise. Pull all
 * of the tables in here so the compiler enforces sync.
 */
object WarmupConstants {

    val WEIGHTED_EQUIPMENT: Set<String> =
        setOf("barbell", "dumbbell", "kettlebell", "machine", "plate")

    /**
     * Equipment whose loaded weight sits on top of a fixed **base resistance** the
     * lifter can't remove — the empty bar (20 kg), a Smith carriage (~10 kg), or a
     * machine's starting stack/sled (leg press 50 kg, squat machine 25–30 kg). The
     * true weight moved is `base + plates`, but the number the lifter sets is only
     * the plate portion. Dumbbells / kettlebells have no such base (the whole
     * implement is the load), so they're excluded — the base-resistance lens never
     * prompts for them. Smith-machine variants report equipment `machine`, so this
     * one set covers bar, Smith, and plate-loaded/selectorised machines.
     */
    val BASE_RESISTANCE_EQUIPMENT: Set<String> =
        setOf("barbell", "machine", "plate")

    /** True when [equipment] can carry a fixed base resistance (see
     *  [BASE_RESISTANCE_EQUIPMENT]). */
    fun equipmentHasBaseResistance(equipment: String?): Boolean =
        equipment?.lowercase() in BASE_RESISTANCE_EQUIPMENT

    val NO_WARMUP_GROUPS: Set<String> =
        setOf("abdominals", "forearms", "neck", "cardio", "other")

    val LARGE_GROUPS: Set<String> =
        setOf("quadriceps", "hamstrings", "glutes", "lats", "upper_back", "lower_back")

    val MEDIUM_GROUPS: Set<String> =
        setOf("chest", "traps", "full_body", "abductors", "adductors")

    val SMALL_GROUPS: Set<String> =
        setOf("biceps", "triceps", "calves", "shoulders", "abdominals")

    /** Warmup protocols: set count → list of `(percentageOfWorkingWeight, reps)`. */
    val PROTOCOLS: Map<Int, List<Pair<Float, Int>>> = mapOf(
        1 to listOf(0.60f to 8),
        2 to listOf(0.50f to 10, 0.70f to 5),
        3 to listOf(0.40f to 10, 0.60f to 6, 0.80f to 3),
        4 to listOf(0.30f to 12, 0.50f to 8, 0.70f to 4, 0.85f to 2),
    )

    enum class MuscleCategory { LARGE, MEDIUM, SMALL }

    fun categorize(primaryMuscleGroup: String?): MuscleCategory? =
        when (primaryMuscleGroup?.lowercase()) {
            in LARGE_GROUPS -> MuscleCategory.LARGE
            in MEDIUM_GROUPS -> MuscleCategory.MEDIUM
            in SMALL_GROUPS -> MuscleCategory.SMALL
            else -> null
        }

    /** Rounding increment per equipment type (kg). Dumbbells/kettlebells are
     *  discrete; machine pins step 1 kg on many stacks; everything else follows
     *  the 2.5 kg microplate convention. */
    fun incrementFor(equipment: String?): Float = when (equipment?.lowercase()) {
        "dumbbell" -> 2f
        "kettlebell" -> 4f
        "machine" -> 1f
        else -> 2.5f
    }

    /** Minimum warmup weight — barbell can't go below the 20 kg Olympic bar
     *  itself; everything else is capped at one increment. */
    fun minWarmupWeight(equipment: String?): Float =
        if (equipment?.lowercase() == "barbell") 20f else incrementFor(equipment)

    /** Set-count table given the effort weight (post-assisted-conversion). */
    fun warmupSetCount(category: MuscleCategory, effortWeightKg: Float): Int =
        when (category) {
            MuscleCategory.LARGE -> when {
                effortWeightKg < 30f -> 1
                effortWeightKg < 50f -> 2
                effortWeightKg < 80f -> 3
                else -> 4
            }
            MuscleCategory.MEDIUM, MuscleCategory.SMALL -> when {
                effortWeightKg < 30f -> 0
                effortWeightKg < 50f -> 1
                effortWeightKg < 80f -> 2
                else -> 3
            }
        }
}
