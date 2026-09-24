package com.example.hevycompanion.muscle

/**
 * Stable pastel colour + display-name helpers per Hevy muscle group.
 * The colour is purely for visual variety in the [MuscleSelectorScreen]
 * cards — it has no strength-ranking semantics.
 */
object MuscleAssetMap {

    /** Stable pastel color (ARGB) per Hevy muscle group. */
    fun tintFor(hevyGroup: String): Long = when (hevyGroup) {
        HevyMuscleGroup.ABDOMINALS -> 0xFFB7A8E5  // lavender
        HevyMuscleGroup.ABDUCTORS  -> 0xFFF6B8D0  // pink
        HevyMuscleGroup.ADDUCTORS  -> 0xFFC8B6E2  // lilac
        HevyMuscleGroup.BICEPS     -> 0xFFF5D76E  // yellow
        HevyMuscleGroup.CALVES     -> 0xFF9FE2CF  // mint
        HevyMuscleGroup.CHEST      -> 0xFF86D1F2  // sky blue
        HevyMuscleGroup.FOREARMS   -> 0xFFE8B98C  // peach
        HevyMuscleGroup.GLUTES     -> 0xFFE89CC1  // rose
        HevyMuscleGroup.HAMSTRINGS -> 0xFF8CC8B4  // teal
        HevyMuscleGroup.LATS       -> 0xFFB6E285  // lime
        HevyMuscleGroup.LOWER_BACK -> 0xFFE2A6A6  // coral
        HevyMuscleGroup.QUADRICEPS -> 0xFF9AB8E9  // periwinkle
        HevyMuscleGroup.SHOULDERS  -> 0xFFF5B17E  // orange
        HevyMuscleGroup.TRAPS      -> 0xFFC4E89C  // chartreuse
        HevyMuscleGroup.TRICEPS    -> 0xFF98E1E3  // aqua
        HevyMuscleGroup.UPPER_BACK -> 0xFFB8D080  // olive-green
        else                       -> 0xFFBDBDBD  // gray fallback
    }

    /** Display label for a Hevy muscle group. */
    fun displayName(hevyGroup: String): String = when (hevyGroup) {
        HevyMuscleGroup.LOWER_BACK -> "Lower Back"
        HevyMuscleGroup.UPPER_BACK -> "Upper Back"
        HevyMuscleGroup.FULL_BODY  -> "Full Body"
        else -> hevyGroup.replaceFirstChar { it.uppercase() }
    }
}
