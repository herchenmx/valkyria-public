package com.example.hevycore.exercise

/**
 * Exercise-substitution groups for Progressive-Overload routines. Shared
 * between the watch and the companion so the curated group table can't drift.
 *
 * When a gym doesn't have the prescribed machine, the user logs a similar
 * exercise instead (e.g. *Lateral Raise (Dumbbell)* for a prescribed
 * *Lateral Raise (Machine)* slot) and later edits the stored workout to swap
 * the exercise in. Without this map the swapped workout reads as "incomplete"
 * because completion is matched by exact `exercise_template_id`.
 *
 * Each entry is a set of template IDs that are mutually interchangeable: any
 * member satisfies a routine slot prescribing any other member. The map is
 * the SOLE authority — it intentionally crosses muscle-group boundaries
 * (e.g. "Unilateral Glutes" pairs a hamstring hinge with quad lunges), so no
 * anatomical heuristic guards the lookup.
 *
 * Substitution is only applied for routines in a Progressive-Overload folder;
 * the scope check lives at each caller (watch's `WorkoutDetailViewModel`,
 * companion's `WorkoutCompletion.buildCompletionStatuses`).
 */
object SubstitutionMap {

    private val GROUPS: List<Set<String>> = listOf(
        // Lateral Raise: Machine / Dumbbell / Cable + Upright Row (Barbell/Cable)
        setOf("D5D0354D", "422B08F1", "BE289E45", "7AB9A362", "286C1D0B"),
        // Triceps: Pushdown / Extension (DB/Machine/Barbell/Cable) / Seated Press / Rope / Kickback (DB/Cable)
        setOf("93A552C6", "3765684D", "3092FADD", "234BC743", "2F8D3067", "21310F5F", "94B7239B", "6127A3AD", "EC3B69A3"),
        // Rotation: Torso Rotation / Cable Twist / Landmine 180 / Russian Twist
        setOf("FBB62888", "A2D838BD", "923874CA", "2982AA23"),
        // Unilateral Glutes: SL RDL / Reverse Lunge / Bulgarian Split Squat / Rear Kick / Glute Kickback / DB Step Up / Single Leg Press
        setOf("937292AB", "818BA121", "B5D3A742", "1ADF8723", "CBA02382", "BF6ECE89", "3FD83744"),
        // Thrust: Hip Thrust (Barbell/Machine/Smith)
        setOf("D57C2EC7", "68CE0B9B", "291ABA92"),
        // Squats: Barbell / Sumo / Belt / Box / Hack / Pendulum / Machine / Smith
        setOf("D04AC939", "6622E5A0", "3D1CDC75", "38FC1AB9", "1E42FD5F", "30E293E3", "CC35A01F", "DDCC3821"),
        // Glutes: RDL (Barbell/DB) / Deadlift (Barbell/DB/Smith/Trap bar)
        setOf("2B4B7310", "72CFFAD5", "C6272009", "5F4E6DD3", "20870ED5", "B923B230"),
        // Shoulder: Seated Shoulder Press / OHP (Barbell/DB/Smith) / Plate Raise / Seated OHP (Barbell/DB) / Shoulder Press (DB/Machine Plates)
        setOf("9237BAD1", "7B8D84E8", "54E60954", "6AC96645", "B09A1304", "91AF29E0", "9930DF71", "878CD1D0", "059E835D"),
        // Chest/Bench Press (flat): every horizontal/incline/decline chest- &
        // bench-press variant is mutually interchangeable — machine, Smith,
        // cable, barbell, dumbbell, band. Close-Grip Bench is excluded (it's
        // a triceps-primary movement, not a chest swap).
        setOf(
            "7EB3F7C3", "24706DCD", "FBF92739", "FAF31231", "FFC106CB",
            "0FBF7195", "3A6FA3D1", "99C1F2AD", "79D0BB3A", "50DFDFAB",
            "DA0F0470", "E644F828", "867AC3B6", "3601968B", "07B38369",
            "18487FA7", "EAC7D9C5",
        ),
        // Chest Fly: Machine / Pec Deck / Dumbbell
        setOf("78683336", "9DCE2D64", "12017185"),
        // Reverse Fly: Chest-Supported (DB) / Face Pull / Rear Delt (DB/Machine)
        setOf("B582299E", "BE640BA0", "E5988A0A", "D8281C62"),
        // Lats: Lat Pulldown (Cable/Machine/Close-Grip) / Pull Up (Assisted) / Pullover (Machine)
        setOf("6A6C31A5", "2C37EC5E", "B123DD01", "473CF5B8", "4E5257DE"),
        // Row: DB Row / Bent Over (BB/DB) / Cable Rows (Bar/V-Grip) / Incline / Seated / Iso-Lateral ×3 / Landmine / T Bar
        setOf("F1E57334", "55E6546F", "F1D60854", "914F3A96", "1DF4A847", "23E92538", "BC3492DA", "91FAFBA3", "AA1EB7D8", "0393F233", "D7D7FCCE", "08A2974E"),
        // Crunch: Crunch (Machine) / Cable Crunch
        setOf("EB43ADD4", "23A48484"),
        // Leg Press: Leg Press (Machine) / Leg Press Horizontal (Machine)
        setOf("C7973E0E", "0EB695C9"),
    )

    /** templateId (upper-cased) → group index. Built once from [GROUPS]. */
    private val groupIndexByTemplate: Map<String, Int> = buildMap {
        GROUPS.forEachIndexed { idx, group ->
            group.forEach { put(it.uppercase(), idx) }
        }
    }

    /**
     * The substitution-group index for [templateId], or null if the exercise
     * isn't in any group. Two exercises are interchangeable iff this returns
     * the same non-null value for both. Case-insensitive.
     */
    fun groupOf(templateId: String): Int? = groupIndexByTemplate[templateId.uppercase()]

    /** True if [a] and [b] are different exercises in the same substitution group. */
    fun areInterchangeable(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return false
        val ga = groupOf(a) ?: return false
        return ga == groupOf(b)
    }

    /**
     * All OTHER members of [templateId]'s group — the acceptable substitutes,
     * preserving the curated order, excluding the exercise itself. Empty if the
     * exercise isn't mapped. IDs are returned upper-cased.
     */
    fun substitutesFor(templateId: String): List<String> {
        val idx = groupOf(templateId) ?: return emptyList()
        val self = templateId.uppercase()
        return GROUPS[idx].map { it.uppercase() }.filter { it != self }
    }

    /**
     * Every curated substitution group as an ordered list of upper-cased
     * template IDs, in the same declaration order (primary variant tends to
     * lead each group). Used by the companion's "Exercise Alternatives"
     * browsing screen; the watch doesn't render the flat list.
     */
    fun allGroups(): List<List<String>> =
        GROUPS.map { group -> group.map { it.uppercase() } }
}
