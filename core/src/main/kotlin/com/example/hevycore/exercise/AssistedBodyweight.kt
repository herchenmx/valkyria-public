package com.example.hevycore.exercise

/**
 * Counter-weight assisted exercises (chest/tricep dip, pull-up, chin-up on the
 * assisted machine). The "weight" the user logs is the stack assistance — i.e.
 * the kilograms of bodyweight the machine takes off them — so:
 *
 *   effective_work_kg = bodyweight_kg − logged_kg
 *
 * Lower logged kg ⇒ less assistance ⇒ more bodyweight moved ⇒ harder. Progress
 * for these exercises means the logged number goes DOWN over time, opposite of
 * normal weighted lifts.
 *
 * The Hevy API accepts only non-negative weights, so storage and UI stay in
 * logged space and only convert to effective space inside computations
 * (warmup, PO, volume, PR). Shared between watch (`:app`) and companion
 * (`:companion`) via `:core` — before extraction each module hardcoded the
 * same four template IDs and the sets could silently drift.
 */
object AssistedBodyweight {

    /** Exercise template IDs that use counter-weight assistance. */
    val TEMPLATE_IDS: Set<String> = setOf(
        "2C37EC5E",  // Chest dip (assisted)
        "4B4BF8C2",  // Tricep dip (assisted)
        "D23C609B",  // Pull-up (assisted)
        "E9E4089F",  // Chin-up (assisted)
    )

    /** The single routine where assisted-bodyweight volume should be summed
     *  in effective space. Watch-only concept currently — but the constant
     *  lives here for a possible future companion routine-summary port. */
    const val ROUTINE_ID: String = "1d8f11d4-d533-442e-8d47-780d25d07964"

    fun isAssisted(templateId: String?): Boolean =
        templateId != null && templateId.uppercase() in TEMPLATE_IDS

    /** Convert a logged stack-assist weight to the effective work performed
     *  (bw − logged). */
    fun toEffective(loggedKg: Float, bodyweightKg: Float): Float =
        (bodyweightKg - loggedKg).coerceAtLeast(0f)

    /** Convert an effective work weight back to logged stack-assist weight
     *  (bw − effective). */
    fun toLogged(effectiveKg: Float, bodyweightKg: Float): Float =
        (bodyweightKg - effectiveKg).coerceAtLeast(0f)
}
