package com.example.hevycompanion.muscle

import com.example.hevycompanion.R

/**
 * The 20 muscle cards Liftoff shows in its "generate workout" picker,
 * each with the specific silhouette tile + overlay drawable pair that
 * Liftoff uses (so the muscle lands at its anatomical position when drawn
 * on a single tile — no stacking needed).
 *
 * Each card maps to:
 *  - exactly one Hevy muscle group ([hevyGroup]). Multiple cards may carry
 *    the same Hevy group (Upper + Lower Chest → chest; Front/Middle/Rear Delt
 *    → shoulders; Abdominals + Obliques → abdominals).
 *  - one M&M area ([mmAreaFallback]) plus zero or more M&M sub-areas
 *    ([mmSubAreas]) — used when the unified Browser is in [Source.MM]: tapping
 *    a card pre-fills the M&M sub-area filter (or area filter if no sub-areas
 *    are listed). Sub-area strings are verbatim "Area | Sub" entries from
 *    `mm_catalog_runtime.json`. Forearms exists as its own Liftoff card but
 *    M&M files it under Arms — picking that card filters M&M to
 *    `Arms | Forearms`.
 */
data class LiftoffMuscleCard(
    val displayName: String,
    val silhouetteRes: Int,
    val overlayRes: Int,
    val hevyGroup: String,
    val mmAreaFallback: String,
    val mmSubAreas: List<String> = emptyList(),
)

object LiftoffMuscleCards {

    /** Order follows Liftoff's own grid (roughly top-down anatomy). */
    val ALL: List<LiftoffMuscleCard> = listOf(
        // Upper front: chest + front/middle delt + forearms
        card("Front Delt",   R.drawable.liftoff_musclebody_upperfront, R.drawable.liftoff_muscle_frontdelt,   HevyMuscleGroup.SHOULDERS, "Shoulders", listOf("Shoulders | Deltoid")),
        card("Upper Chest",  R.drawable.liftoff_musclebody_upperfront, R.drawable.liftoff_muscle_upperchest,  HevyMuscleGroup.CHEST,     "Chest"),
        card("Lower Chest",  R.drawable.liftoff_musclebody_upperfront, R.drawable.liftoff_muscle_lowerchest,  HevyMuscleGroup.CHEST,     "Chest"),
        card("Biceps",       R.drawable.liftoff_musclebody_middlefront, R.drawable.liftoff_muscle_biceps,     HevyMuscleGroup.BICEPS,    "Arms",      listOf("Arms | Biceps & Elbow Flexors")),
        card("Forearms",     R.drawable.liftoff_musclebody_middlefront, R.drawable.liftoff_muscle_forearms,   HevyMuscleGroup.FOREARMS,  "Arms",      listOf("Arms | Forearms")),
        card("Abdominals",   R.drawable.liftoff_musclebody_middlefront, R.drawable.liftoff_muscle_abdominals, HevyMuscleGroup.ABDOMINALS, "Abs & Core", listOf("Abs & Core | Rectus Abdominis", "Abs & Core | Core")),
        card("Obliques",     R.drawable.liftoff_musclebody_middlefront, R.drawable.liftoff_muscle_obliques,   HevyMuscleGroup.ABDOMINALS, "Abs & Core", listOf("Abs & Core | Obliques")),
        card("Abductors",    R.drawable.liftoff_musclebody_lowerfront, R.drawable.liftoff_muscle_abductors,   HevyMuscleGroup.ABDUCTORS, "Legs",      listOf("Legs | Hip Abductors")),
        card("Adductors",    R.drawable.liftoff_musclebody_lowerfront, R.drawable.liftoff_muscle_adductors,   HevyMuscleGroup.ADDUCTORS, "Legs",      listOf("Legs | Hip Adductors")),
        card("Quadriceps",   R.drawable.liftoff_musclebody_lowerfront, R.drawable.liftoff_muscle_quadriceps,  HevyMuscleGroup.QUADRICEPS, "Legs",     listOf("Legs | Quads", "Legs | Hip Flexors")),
        card("Middle Delt",  R.drawable.liftoff_musclebody_upperback,  R.drawable.liftoff_muscle_middledelt,  HevyMuscleGroup.SHOULDERS, "Shoulders", listOf("Shoulders | Deltoid", "Shoulders | Rotator Cuff")),
        card("Rear Delt",    R.drawable.liftoff_musclebody_upperback,  R.drawable.liftoff_muscle_reardelt,    HevyMuscleGroup.SHOULDERS, "Shoulders", listOf("Shoulders | Deltoid")),
        card("Traps",        R.drawable.liftoff_musclebody_upperback,  R.drawable.liftoff_muscle_traps,       HevyMuscleGroup.TRAPS,     "Back",      listOf("Back | Trapezius")),
        card("Upper Back",   R.drawable.liftoff_musclebody_upperback,  R.drawable.liftoff_muscle_upperback,   HevyMuscleGroup.UPPER_BACK, "Back",     listOf("Back | Trapezius")),
        card("Triceps",      R.drawable.liftoff_musclebody_middleback, R.drawable.liftoff_muscle_triceps,     HevyMuscleGroup.TRICEPS,   "Arms",      listOf("Arms | Triceps")),
        card("Lats",         R.drawable.liftoff_musclebody_middleback, R.drawable.liftoff_muscle_lats,        HevyMuscleGroup.LATS,      "Back",      listOf("Back | Lats")),
        card("Lower Back",   R.drawable.liftoff_musclebody_middleback, R.drawable.liftoff_muscle_lowerback,   HevyMuscleGroup.LOWER_BACK, "Back",     listOf("Back | Erector Spinae")),
        card("Glutes",       R.drawable.liftoff_musclebody_middleback, R.drawable.liftoff_muscle_glutes,      HevyMuscleGroup.GLUTES,    "Legs",      listOf("Legs | Glutes", "Legs | Hip Rotators")),
        card("Hamstrings",   R.drawable.liftoff_musclebody_lowerback,  R.drawable.liftoff_muscle_hamstrings,  HevyMuscleGroup.HAMSTRINGS, "Legs",     listOf("Legs | Hamstrings")),
        card("Calves",       R.drawable.liftoff_musclebody_lowerback,  R.drawable.liftoff_muscle_calves,      HevyMuscleGroup.CALVES,    "Legs",      listOf("Legs | Calves")),
    )

    private fun card(
        displayName: String,
        silhouetteRes: Int,
        overlayRes: Int,
        hevyGroup: String,
        mmAreaFallback: String,
        mmSubAreas: List<String> = emptyList(),
    ) = LiftoffMuscleCard(displayName, silhouetteRes, overlayRes, hevyGroup, mmAreaFallback, mmSubAreas)
}
