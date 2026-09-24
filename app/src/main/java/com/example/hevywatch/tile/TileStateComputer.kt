package com.example.hevywatch.tile

import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout

/**
 * Pure tile-state computation extracted from [HevyTileService] so the layout
 * decisions (between sets vs. between exercises, big/small text content,
 * weight color) can be unit-tested without touching the Tiles framework.
 *
 * The values mirror the constants in [HevyTileService]; do NOT inline them
 * differently here, or the tile will render inconsistently with what tests
 * verify.
 */
internal object TileColors {
    const val TRACK_GRAY  = 0xFF333333.toInt()
    const val RING_OUTER  = 0xFF4FC3F7.toInt()
    const val RING_INNER  = 0xFF81D4FA.toInt()
    const val WEIGHT_PO   = 0xFF66BB6A.toInt()
    const val WEIGHT_SUGG = 0xFFFFA726.toInt()
    const val WHITE       = 0xFFFFFFFF.toInt()
}

internal data class ActiveTileState(
    val outerProgressDeg: Float,
    val innerProgressDeg: Float,
    val betweenSets: Boolean,
    val bigText: String,
    val bigColor: Int,
    val smallText: String,
    val smallColor: Int,
)

/** "33.5" / "30" / "–" — same rules the tile uses for the big weight readout. */
internal fun formatWeight(kg: Float?): String =
    if (kg != null && kg > 0f) {
        if (kg == kg.toLong().toFloat()) "%.0f".format(kg) else "%.1f".format(kg)
    } else "–"

/** Color of the big weight readout: green for PO, orange for advisor, white otherwise. */
internal fun weightColor(set: ActiveSet): Int = when {
    set.poBaseWeightKg != null  -> TileColors.WEIGHT_PO
    set.isSimilarSuggestion     -> TileColors.WEIGHT_SUGG
    else                        -> TileColors.WHITE
}

internal fun computeActiveTileState(workout: ActiveWorkout): ActiveTileState {
    val exercises = workout.exercises
    val totalExercises = exercises.size.coerceAtLeast(1)
    val completedExercises = exercises.count { ex -> ex.sets.all { it.completed } }
    val outerDeg = (completedExercises.toFloat() / totalExercises * 360f).coerceIn(0f, 360f)

    val currentExercise: ActiveExercise? =
        exercises.firstOrNull { ex -> ex.sets.any { !it.completed } }
    val totalSets = currentExercise?.sets?.size?.coerceAtLeast(1) ?: 1
    val completedSets = currentExercise?.sets?.count { it.completed } ?: 0
    val innerDeg = (completedSets.toFloat() / totalSets * 360f).coerceIn(0f, 360f)
    val nextSet: ActiveSet? = currentExercise?.sets?.firstOrNull { !it.completed }
    val betweenSets = currentExercise != null && completedSets > 0

    val bigText: String
    val bigColor: Int
    val smallText: String
    val smallColor: Int

    if (betweenSets && nextSet != null) {
        bigText = "${formatWeight(nextSet.weightKg)}kg"
        bigColor = weightColor(nextSet)
        val minReps = nextSet.repRangeStart ?: nextSet.reps
        smallText = if (minReps != null && minReps > 0) "$minReps reps" else ""
        smallColor = TileColors.WHITE
    } else if (currentExercise != null) {
        bigText = currentExercise.title
        bigColor = TileColors.WEIGHT_SUGG
        val firstSet = currentExercise.sets.firstOrNull()
        val w = formatWeight(firstSet?.weightKg)
        val r = firstSet?.repRangeStart ?: firstSet?.reps
        val parts = listOfNotNull(
            if (w != "–") "${w}kg" else null,
            if (r != null && r > 0) "×$r" else null
        )
        smallText = parts.joinToString(" ")
        smallColor = TileColors.WEIGHT_SUGG
    } else {
        bigText = "Done!"
        bigColor = TileColors.WHITE
        smallText = ""
        smallColor = TileColors.WHITE
    }

    return ActiveTileState(
        outerProgressDeg = outerDeg,
        innerProgressDeg = innerDeg,
        betweenSets = betweenSets,
        bigText = bigText,
        bigColor = bigColor,
        smallText = smallText,
        smallColor = smallColor,
    )
}
