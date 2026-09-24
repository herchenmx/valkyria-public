package com.example.hevywatch.presentation.workout

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.hevycore.chip.label
import com.example.hevywatch.ui.theme.ChipPalette
import com.example.hevywatch.ui.theme.HevyExtendedColors

// Chip state / kind / stats now live in :core so the watch's Wear-Material
// renderer and the companion's Material3 renderer share one shape (see
// [com.example.hevycore.chip.ExerciseChipData]). Re-exported here so
// existing watch call sites keep resolving without touching their imports.
typealias ChipState = com.example.hevycore.chip.ChipState
typealias ChipKind = com.example.hevycore.chip.ChipKind
typealias WeightKind = com.example.hevycore.chip.WeightKind
typealias ExerciseChipStats = com.example.hevycore.chip.ExerciseChipStats

/** Background tint for a chip [state] (green / blue / red). */
fun backgroundForChipState(state: ChipState, colors: HevyExtendedColors): Color = when (state) {
    ChipState.COMPLETE -> colors.statusCompleteBg
    ChipState.IN_PROGRESS -> colors.statusInProgressBg
    ChipState.MISSING -> colors.statusMissingBg
}

/**
 * Chip title row: the exercise name plus, when present, a dim trailing
 * `swap` / `extra` tag. Use as a Wear `Chip` `label`.
 */
@Composable
fun ExerciseChipLabel(title: String, kind: ChipKind?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (kind != null) {
            Text(
                text = kind.label(),
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSecondary,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/**
 * Chip secondary row: `X/Y W` (amber, hidden when no warmups) · `X/Y N` ·
 * weight. Use as a Wear `Chip` `secondaryLabel`.
 */
@Composable
fun ExerciseChipStatsRow(stats: ExerciseChipStats) {
    val warmupColor = ChipPalette.WarmupAmber
    val normalColor = MaterialTheme.colors.onSurface
    val weightColor = when (stats.weightKind) {
        WeightKind.PO -> ChipPalette.PoGreen
        WeightKind.ESTIMATE -> ChipPalette.WarmupAmber
        WeightKind.PLAIN -> MaterialTheme.colors.onSecondary
    }
    val showWarmup = stats.warmupTotal > 0 || stats.warmupDone > 0
    val normalText = if (stats.normalTotal > 0) {
        "${stats.normalDone}/${stats.normalTotal} N"
    } else {
        "${stats.normalDone} N"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showWarmup) {
            Text(
                text = "${stats.warmupDone}/${stats.warmupTotal} W",
                style = MaterialTheme.typography.caption2,
                color = warmupColor,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
        Text(
            text = normalText,
            style = MaterialTheme.typography.caption2,
            color = normalColor
        )
        if (stats.weightText != null) {
            Text(
                text = " · ${stats.weightText}",
                style = MaterialTheme.typography.caption2,
                color = weightColor
            )
        }
    }
}
