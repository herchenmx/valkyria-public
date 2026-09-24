package com.example.hevycompanion.muscle

/**
 * The static picker Liftoff uses in its `getSuggestedExercisesByMuscleGroup`
 * (extracted verbatim from their Hermes bundle). 20 Liftoff muscle keys, each
 * mapped to 1–3 Liftoff exercise slugs.
 *
 * The slugs are used to look up bundled avatar PNGs AND to match against
 * Hevy's exercise titles by normalization (see [LiftoffSlug.normalize]).
 */
object LiftoffCuratedPicks {

    /** Liftoff muscle key → ordered list of Liftoff exercise slugs. */
    private val MAP: Map<String, List<String>> = mapOf(
        "Abdominals"  to listOf("cable_crunch", "machine_seated_crunch", "high_pulley_crunch"),
        "Biceps"      to listOf("dumbbell_curl", "barbell_curl", "hammer_curl"),
        "Triceps"     to listOf("tricep_pushdown", "tricep_extension", "dumbbell_push_press"),
        "Calves"      to listOf("machine_calf_raise", "barbell_calf_raise", "dumbbell_calf_raise"),
        "Upper Chest" to listOf("bench_press", "dumbbell_fly", "cable_fly"),
        "Lower Chest" to listOf("bench_press", "dumbbell_fly", "cable_fly"),
        "Front Delt"  to listOf("shoulder_press", "push_press", "arnold_press"),
        "Middle Delt" to listOf("dumbbell_lateral_raise", "push_press", "cable_lateral_raise"),
        "Rear Delt"   to listOf("face_pull", "dumbbell_reverse_fly", "cable_reverse_fly"),
        "Forearms"    to listOf("reverse_barbell_curl", "wrist_curl", "dumbbell_reverse_curl"),
        "Glutes"      to listOf("squat", "glute_kickback", "deadlift"),
        "Quadriceps"  to listOf("leg_extension", "horizontal_leg_press", "hack_squat"),
        "Hamstrings"  to listOf("squat", "deadlift", "hex_bar_deadlift"),
        "Upper Back"  to listOf("bent_over_row", "dumbbell_row", "seated_cable_row"),
        "Lower Back"  to listOf("deadlift", "power_clean", "machine_back_extension"),
        "Lats"        to listOf("seated_cable_row", "lat_pulldown", "machine_row"),
        "Traps"       to listOf("barbell_shrug", "dumbbell_shrug", "dumbbell_high_pull"),
        "Obliques"    to listOf("dumbbell_side_bend", "cable_woodchopper", "torso_rotation_machine"),
        "Abductors"   to listOf("hip_abduction"),
        "Adductors"   to listOf("hip_adduction")
    )

    /** Hevy muscle group → union of Liftoff's curated picks from all Liftoff
     *  buckets that fall under it (deduped, preserving first-seen order). */
    fun picksForHevyGroup(hevyGroup: String): List<String> {
        val liftoffKeys: List<String> = when (hevyGroup) {
            HevyMuscleGroup.CHEST      -> listOf("Upper Chest", "Lower Chest")
            HevyMuscleGroup.SHOULDERS  -> listOf("Front Delt", "Middle Delt", "Rear Delt")
            HevyMuscleGroup.ABDOMINALS -> listOf("Abdominals", "Obliques")
            HevyMuscleGroup.BICEPS     -> listOf("Biceps")
            HevyMuscleGroup.TRICEPS    -> listOf("Triceps")
            HevyMuscleGroup.CALVES     -> listOf("Calves")
            HevyMuscleGroup.FOREARMS   -> listOf("Forearms")
            HevyMuscleGroup.GLUTES     -> listOf("Glutes")
            HevyMuscleGroup.QUADRICEPS -> listOf("Quadriceps")
            HevyMuscleGroup.HAMSTRINGS -> listOf("Hamstrings")
            HevyMuscleGroup.UPPER_BACK -> listOf("Upper Back")
            HevyMuscleGroup.LOWER_BACK -> listOf("Lower Back")
            HevyMuscleGroup.LATS       -> listOf("Lats")
            HevyMuscleGroup.TRAPS      -> listOf("Traps")
            HevyMuscleGroup.ABDUCTORS  -> listOf("Abductors")
            HevyMuscleGroup.ADDUCTORS  -> listOf("Adductors")
            else -> emptyList()
        }
        val seen = LinkedHashSet<String>()
        liftoffKeys.forEach { k -> MAP[k]?.forEach { seen += it } }
        return seen.toList()
    }
}
