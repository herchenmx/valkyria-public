package com.example.hevywatch.data

/**
 * Watch-side entry point for the shared substitution table. The GROUPS
 * table + `groupOf` / `substitutesFor` / `areInterchangeable` / `allGroups`
 * live in [com.example.hevycore.exercise.SubstitutionMap] so the two apps
 * can't drift; the [NAMES] map below is watch-only — the companion has its
 * own name catalog via the exercise-template repo.
 *
 * Substitution is only applied for routines in a Progressive-Overload folder
 * (see [com.example.hevywatch.data.store.ProgressiveOverloadStore]); the
 * scope check lives at the call site in
 * [com.example.hevywatch.presentation.workout.WorkoutDetailViewModel].
 */
object SubstitutionMap {

    fun groupOf(templateId: String): Int? =
        com.example.hevycore.exercise.SubstitutionMap.groupOf(templateId)

    fun areInterchangeable(a: String, b: String): Boolean =
        com.example.hevycore.exercise.SubstitutionMap.areInterchangeable(a, b)

    fun substitutesFor(templateId: String): List<String> =
        com.example.hevycore.exercise.SubstitutionMap.substitutesFor(templateId)

    /** Curated display name for [templateId], or null if unmapped.
     *  Case-insensitive. Watch-only — Hevy doesn't ship a runtime name
     *  catalog and a substitute usually isn't in any loaded routine, so
     *  this is the source of titles for the in-workout swap screen and the
     *  swapped-in exercise's `title`. Names match the official Hevy catalog
     *  as of curation. */
    fun nameOf(templateId: String): String? = NAMES[templateId.uppercase()]

    private val NAMES: Map<String, String> = mapOf(
        "D5D0354D" to "Lateral Raise (Machine)",
        "422B08F1" to "Lateral Raise (Dumbbell)",
        "BE289E45" to "Lateral Raise (Cable)",
        "7AB9A362" to "Upright Row (Barbell)",
        "286C1D0B" to "Upright Row (Cable)",
        "93A552C6" to "Triceps Pushdown",
        "3765684D" to "Triceps Extension (Dumbbell)",
        "3092FADD" to "Triceps Extension (Machine)",
        "234BC743" to "Seated Triceps Press",
        "2F8D3067" to "Triceps Extension (Barbell)",
        "21310F5F" to "Triceps Extension (Cable)",
        "94B7239B" to "Triceps Rope Pushdown",
        "6127A3AD" to "Triceps Kickback (Dumbbell)",
        "EC3B69A3" to "Triceps Kickback (Cable)",
        "FBB62888" to "Torso Rotation",
        "A2D838BD" to "Cable Twist (Up to down)",
        "923874CA" to "Landmine 180",
        "2982AA23" to "Russian Twist (Weighted)",
        "937292AB" to "Single Leg Romanian Deadlift (Dumbbell)",
        "818BA121" to "Reverse Lunge (Barbell)",
        "B5D3A742" to "Bulgarian Split Squat",
        "1ADF8723" to "Rear Kick (Machine)",
        "CBA02382" to "Glute Kickback (Machine)",
        "BF6ECE89" to "Dumbbell Step Up",
        "3FD83744" to "Single Leg Press (Machine)",
        "D57C2EC7" to "Hip Thrust (Barbell)",
        "68CE0B9B" to "Hip Thrust (Machine)",
        "291ABA92" to "Hip Thrust (Smith Machine)",
        "D04AC939" to "Squat (Barbell)",
        "6622E5A0" to "Sumo Squat (Barbell)",
        "3D1CDC75" to "Belt Squat (Machine)",
        "38FC1AB9" to "Box Squat (Barbell)",
        "1E42FD5F" to "Hack Squat (Machine)",
        "30E293E3" to "Pendulum Squat (Machine)",
        "CC35A01F" to "Squat (Machine)",
        "DDCC3821" to "Squat (Smith Machine)",
        "2B4B7310" to "Romanian Deadlift (Barbell)",
        "72CFFAD5" to "Romanian Deadlift (Dumbbell)",
        "C6272009" to "Deadlift (Barbell)",
        "5F4E6DD3" to "Deadlift (Dumbbell)",
        "20870ED5" to "Deadlift (Smith Machine)",
        "B923B230" to "Deadlift (Trap bar)",
        "9237BAD1" to "Seated Shoulder Press (Machine)",
        "7B8D84E8" to "Overhead Press (Barbell)",
        "54E60954" to "Overhead Plate Raise",
        "6AC96645" to "Overhead Press (Dumbbell)",
        "B09A1304" to "Overhead Press (Smith Machine)",
        "91AF29E0" to "Seated Overhead Press (Barbell)",
        "9930DF71" to "Seated Overhead Press (Dumbbell)",
        "878CD1D0" to "Shoulder Press (Dumbbell)",
        "059E835D" to "Shoulder Press (Machine Plates)",
        "7EB3F7C3" to "Chest Press (Machine)",
        "24706DCD" to "Iso-Lateral Chest Press (Machine)",
        "FBF92739" to "Incline Chest Press (Machine)",
        "FAF31231" to "Decline Bench Press (Machine)",
        "FFC106CB" to "Decline Bench Press (Smith Machine)",
        "0FBF7195" to "Bench Press (Smith Machine)",
        "3A6FA3D1" to "Incline Bench Press (Smith Machine)",
        "99C1F2AD" to "Bench Press (Cable)",
        "79D0BB3A" to "Bench Press (Barbell)",
        "50DFDFAB" to "Incline Bench Press (Barbell)",
        "DA0F0470" to "Decline Bench Press (Barbell)",
        "E644F828" to "Bench Press - Wide Grip (Barbell)",
        "867AC3B6" to "Feet Up Bench Press (Barbell)",
        "3601968B" to "Bench Press (Dumbbell)",
        "07B38369" to "Incline Bench Press (Dumbbell)",
        "18487FA7" to "Decline Bench Press (Dumbbell)",
        "EAC7D9C5" to "Chest Press (Band)",
        "78683336" to "Chest Fly (Machine)",
        "9DCE2D64" to "Butterfly (Pec Deck)",
        "12017185" to "Chest Fly (Dumbbell)",
        "B582299E" to "Chest Supported Reverse Fly (Dumbbell)",
        "BE640BA0" to "Face Pull",
        "E5988A0A" to "Rear Delt Reverse Fly (Dumbbell)",
        "D8281C62" to "Rear Delt Reverse Fly (Machine)",
        "6A6C31A5" to "Lat Pulldown (Cable)",
        "2C37EC5E" to "Pull Up (Assisted)",
        "B123DD01" to "Pullover (Machine)",
        "473CF5B8" to "Lat Pulldown (Machine)",
        "4E5257DE" to "Lat Pulldown - Close Grip (Cable)",
        "F1E57334" to "Dumbbell Row",
        "55E6546F" to "Bent Over Row (Barbell)",
        "F1D60854" to "Seated Cable Row - Bar Grip",
        "914F3A96" to "Chest Supported Incline Row (Dumbbell)",
        "1DF4A847" to "Seated Row (Machine)",
        "23E92538" to "Bent Over Row (Dumbbell)",
        "BC3492DA" to "Iso-Lateral High Row (Machine)",
        "91FAFBA3" to "Iso-Lateral Low Row",
        "AA1EB7D8" to "Iso-Lateral Row (Machine)",
        "0393F233" to "Seated Cable Row - V Grip (Cable)",
        "D7D7FCCE" to "Landmine Row",
        "08A2974E" to "T Bar Row",
        "EB43ADD4" to "Crunch (Machine)",
        "23A48484" to "Cable Crunch",
        "C7973E0E" to "Leg Press (Machine)",
        "0EB695C9" to "Leg Press Horizontal (Machine)",
    )
}
