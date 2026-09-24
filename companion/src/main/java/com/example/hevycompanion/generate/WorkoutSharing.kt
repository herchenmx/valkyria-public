package com.example.hevycompanion.generate

import com.example.hevycompanion.muscle.MuscleAssetMap
import java.util.Locale

/**
 * Plain-text rendering of a [GeneratedWorkout] + the filters that produced it,
 * suitable for Android's native share sheet (Intent.ACTION_SEND / text/plain).
 *
 * Output shape (example):
 *
 * ```
 * I've put together the following workout for you:
 *
 * * Bench Press (Barbell) - 3 sets - 8 reps per set - 40 kg (20kg bar, plus 10kg per side)
 * * Incline Dumbbell Press - 3 sets - 8 reps per set - 15 kg (7.5kg per side)
 * * Push-Up - 3 sets - 8 reps per set - bodyweight
 *
 * This is based on the following filters:
 *
 * * target muscles: Chest, Shoulders, Triceps
 * * desired duration: 1h
 * * weights: Heavy
 * * level: Advanced
 * * category: Compound, Isolation
 * * available equipment: Barbell, Dumbbell, Machine
 *
 * Let me know if you want me to change anything
 * ```
 *
 * Barbell and dumbbell exercises get a per-side breakdown in brackets so the
 * recipient doesn't have to do the math when loading plates. Olympic bar is
 * assumed to weigh 20 kg (the standard in every gym the user lifts at); the
 * remainder is split evenly between the two sides.
 *
 * Recipients need the filter summary to know what to tweak when asking for a
 * different workout. Pure function (no Android deps) so it's trivially
 * testable on the JVM.
 *
 * Empty workout → empty string (the Share button is already disabled when
 * nothing is generated, but if the filters somehow produce zero exercises
 * it's better to fall silent than send a hollow header-plus-filters blob).
 */
fun formatWorkoutForSharing(
    workout: GeneratedWorkout,
    selectedMuscles: Set<String>,
    duration: Duration,
    weights: Weights,
    selectedLevels: Set<Level>,
    selectedCategories: Set<Category>,
    selectedEquipment: Set<String>,
): String {
    if (workout.exercises.isEmpty()) return ""

    val exerciseBullets = workout.exercises.joinToString("\n") { ex ->
        "* ${ex.template.title} - ${ex.sets} sets - ${ex.reps} reps per set - ${formatWeightKg(ex.weightKg, ex.template.equipment)}"
    }

    // Filters — all sorted for deterministic output so two identical-filter
    // workouts produce identical share text (makes it obvious when the user
    // has tweaked something vs. when they haven't).
    val muscles = selectedMuscles
        .map { MuscleAssetMap.displayName(it) }
        .sorted()
        .joinToString(", ")
        .ifEmpty { "(none)" }
    val equipment = selectedEquipment
        .map { EquipmentMap.displayLabel(it) }
        .sorted()
        .joinToString(", ")
        .ifEmpty { "(none)" }
    val levels = selectedLevels
        .map { it.displayName }
        .sorted()
        .joinToString(", ")
        .ifEmpty { "(none)" }
    val categories = selectedCategories
        .map { it.displayName }
        .sorted()
        .joinToString(", ")
        .ifEmpty { "(none)" }

    return buildString {
        appendLine("I've put together the following workout for you:")
        appendLine()
        appendLine(exerciseBullets)
        appendLine()
        appendLine("This is based on the following filters:")
        appendLine()
        appendLine("* target muscles: $muscles")
        appendLine("* desired duration: ${duration.label}")
        appendLine("* weights: ${weights.displayName}")
        appendLine("* level: $levels")
        appendLine("* category: $categories")
        appendLine("* available equipment: $equipment")
        appendLine()
        append("Let me know if you want me to change anything")
    }
}

/**
 * Human-readable weight for the share text, with a per-side breakdown in
 * brackets for barbell/dumbbell exercises. Pinned to US locale so German
 * users (and anyone else on a decimal-comma locale) don't send out "36,7 kg"
 * strings that recipients on other locales mis-parse. The row subtitle in
 * [GenerateWorkoutScreen.formatSubline] uses the same rule for the same
 * reason.
 */
private fun formatWeightKg(kg: Float, equipment: String?): String {
    if (kg <= 0f) return "bodyweight"
    val main = "${formatKgNumber(kg)} kg"
    return when (equipment?.lowercase()) {
        "dumbbell" -> "$main (${formatKgNumber(kg / 2f)}kg per side)"
        "barbell" -> "$main (${formatKgNumber(BARBELL_BAR_KG)}kg bar, plus ${formatKgNumber((kg - BARBELL_BAR_KG) / 2f)}kg per side)"
        else -> main
    }
}

private fun formatKgNumber(kg: Float): String =
    if (kg % 1f == 0f) kg.toInt().toString()
    else "%.1f".format(Locale.US, kg)

private const val BARBELL_BAR_KG: Float = 20f
