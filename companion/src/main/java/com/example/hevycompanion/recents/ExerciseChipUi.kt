package com.example.hevycompanion.recents

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.hevycore.chip.label

/**
 * Companion (Material3) renderer for the unified exercise chip. Data shape,
 * chip state, and kind-tag semantics live in :core so the watch (Wear
 * Material) and this file can't drift. Keeps the companion Workout Detail
 * chip reading identically to the watch's: completion state via background
 * tint (green/blue/red), a `swap`/`extra` kind tag instead of a separate
 * colour, `X/Y W` + `X/Y N` counts, and the weight (PO target until the
 * first normal set is logged, then the logged working weight).
 */
private val PoGreen = Color(0xFF4CAF50)
private val WarmupAmber = Color(0xFFFFC107)
private val CompleteBg = Color(0xFF1B3A1F)
private val InProgressBg = Color(0xFF1A2F3E)
private val MissingBg = Color(0xFF3E1A1A)

typealias ChipState = com.example.hevycore.chip.ChipState
typealias ChipKind = com.example.hevycore.chip.ChipKind
typealias WeightKind = com.example.hevycore.chip.WeightKind
typealias ExerciseChipStats = com.example.hevycore.chip.ExerciseChipStats

/** "60kg" / "62.5kg" — compact, matching the watch's `formatKgSmart(compact)`. */
private fun compactKg(kg: Float): String {
    val n = if (kg == kg.toLong().toFloat()) kg.toLong().toString() else kg.toString()
    return "${n}kg"
}

/**
 * Map a completion status (+ its async-fetched PO [advice]) to the unified chip
 * stats. Weight: the PO target (green when bumped) until the first normal set is
 * logged — a still-missing slot — then the logged working weight.
 */
fun chipStatsForStatus(status: ExerciseCompletionStatus, advice: ExerciseAdvice?): ExerciseChipStats {
    val kind = when (status.status) {
        ExerciseCompletionStatus.Status.SUBSTITUTED -> ChipKind.SWAP
        ExerciseCompletionStatus.Status.EXTRA -> ChipKind.EXTRA
        else -> null
    }
    val po = advice?.po
    val (weightKg, weightKind) = if (status.recordedNormalSets == 0) {
        po?.targetKg to (if (po?.increased == true) WeightKind.PO else WeightKind.PLAIN)
    } else {
        status.loggedWorkingWeightKg to WeightKind.PLAIN
    }
    val weightText = weightKg?.takeIf { it > 0f }?.let { compactKg(it) }
    return ExerciseChipStats(
        warmupDone = status.recordedWarmupSets,
        warmupTotal = status.expectedWarmupSets,
        normalDone = status.recordedNormalSets,
        normalTotal = status.prescribedNormalSets,
        weightText = weightText,
        weightKind = if (weightText == null) WeightKind.PLAIN else weightKind,
        kind = kind
    )
}

fun backgroundForChipState(state: ChipState): Color = when (state) {
    ChipState.COMPLETE -> CompleteBg
    ChipState.IN_PROGRESS -> InProgressBg
    ChipState.MISSING -> MissingBg
}

fun ChipKind.tagLabel(): String = label()


/** `X/Y W` (amber, hidden when no warmups) · `X/Y N` · weight. */
@Composable
fun ExerciseChipStatsRow(stats: ExerciseChipStats) {
    val showWarmup = stats.warmupTotal > 0 || stats.warmupDone > 0
    val normalText = if (stats.normalTotal > 0) {
        "${stats.normalDone}/${stats.normalTotal} N"
    } else {
        "${stats.normalDone} N"
    }
    val weightColor = when (stats.weightKind) {
        WeightKind.PO -> PoGreen
        WeightKind.ESTIMATE -> WarmupAmber
        WeightKind.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showWarmup) {
            Text(
                text = "${stats.warmupDone}/${stats.warmupTotal} W",
                style = MaterialTheme.typography.bodySmall,
                color = WarmupAmber,
                modifier = androidx.compose.ui.Modifier.padding(end = 8.dp)
            )
        }
        Text(
            text = normalText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (stats.weightText != null) {
            Text(
                text = " · ${stats.weightText}",
                style = MaterialTheme.typography.bodySmall,
                color = weightColor
            )
        }
    }
}
