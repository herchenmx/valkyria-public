package com.example.hevycompanion.muscle

/**
 * The set of `primary_muscle_group` values returned by Hevy's `/v1/exercise_templates`.
 * Derived from the watch module's WarmupAdvisor; not all of these map to body-heatmap
 * overlays (neck, cardio, other, full_body have no overlay).
 */
object HevyMuscleGroup {
    const val ABDOMINALS = "abdominals"
    const val ABDUCTORS = "abductors"
    const val ADDUCTORS = "adductors"
    const val BICEPS = "biceps"
    const val CALVES = "calves"
    const val CARDIO = "cardio"
    const val CHEST = "chest"
    const val FOREARMS = "forearms"
    const val FULL_BODY = "full_body"
    const val GLUTES = "glutes"
    const val HAMSTRINGS = "hamstrings"
    const val LATS = "lats"
    const val LOWER_BACK = "lower_back"
    const val NECK = "neck"
    const val OTHER = "other"
    const val QUADRICEPS = "quadriceps"
    const val SHOULDERS = "shoulders"
    const val TRAPS = "traps"
    const val TRICEPS = "triceps"
    const val UPPER_BACK = "upper_back"

    /** The anatomical Hevy groups that have a matching muscle card in
     *  [LiftoffMuscleCards]. Excludes `cardio`, `full_body`, `neck`, `other` —
     *  those are surfaced as chips under the grid. */
    val ANATOMICAL: List<String> = listOf(
        ABDOMINALS, ABDUCTORS, ADDUCTORS, BICEPS, CALVES, CHEST, FOREARMS,
        GLUTES, HAMSTRINGS, LATS, LOWER_BACK, QUADRICEPS, SHOULDERS, TRAPS,
        TRICEPS, UPPER_BACK
    )
}
