package com.example.hevywatch.data.model

import java.util.UUID

data class ActiveExercise(
    val id: String = UUID.randomUUID().toString(),
    val exerciseTemplateId: String,
    val title: String,
    val exerciseType: ExerciseType = ExerciseType.WEIGHT_AND_REPS,
    val sets: List<ActiveSet> = emptyList(),
    val restTimerSeconds: Int? = null,
    val supersetId: String? = null,
    val notes: String? = null,
    val equipment: String? = null,
    val primaryMuscleGroup: String? = null,
    /** True once this slot was swapped to a substitute mid-workout (or resumed
     *  as a substitute). Drives the live `swap` chip tag. Defaults false so
     *  older persisted/crash-recovery workouts deserialize cleanly. */
    val wasSwapped: Boolean = false,
    /**
     * Fixed base resistance (kg) of the bar/Smith carriage/machine sled this
     * exercise is being logged on — the part of the weight the lifter can't
     * remove. `null` = not yet decided (the log screen prompts on first engage
     * for base-capable equipment); a number (incl. `0` = "no base / log total")
     * = decided. Stored weights stay TRUE TOTAL (base + plates); this only drives
     * the log screen's plate lens (`display = total − base`) and the plate-ramped
     * warmup ladder. Persists with the session so resume/crash-recovery restore
     * it; defaults null so older persisted workouts deserialize cleanly.
     */
    val baseResistanceKg: Float? = null
) {
    /** True if the exercise has real equipment (not bodyweight/none/absent). */
    val hasEquipment: Boolean
        get() = !equipment.isNullOrBlank() && !equipment.equals("none", ignoreCase = true)
}
