package com.example.hevycompanion.trends

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val PoGreen = Color(0xFF4CAF50)   // matches the watch's ChipPalette.PoGreen

/**
 * Routine Trends — one routine's development over the trend window.
 *
 * Top half is the [TrendChart]: a point per workout of this routine, X time,
 * Y the selected total. Tapping a point selects that workout; the panel below
 * then answers the three questions the feature exists for:
 *
 *  1. **What were the totals** for that workout — volume, sets and reps;
 *  2. **What changed** against the chronologically preceding workout *of the
 *     same routine* — the same three deltas;
 *  3. **What caused it** — a per-exercise breakdown ordered by how much of the
 *     delta each exercise accounts for, with the set-by-set lines from both
 *     sessions as the evidence. Swapped exercises are paired rather than
 *     reported as one exercise vanishing and another appearing
 *     ([RoutineComparison]).
 */
@Composable
fun RoutineTrendDetailScreen(
    vm: RoutineTrendsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trend = vm.selectedTrend
    val points = vm.series
    val values = points.map { vm.metric.valueIn(it.totals) }
    val selectedIndex = points.indexOfFirst { it.workoutId == vm.selectedWorkoutId }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                text = trend?.title ?: "Routine",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                MetricSelector(
                    metric = vm.metric,
                    onMetric = vm::selectMetric,
                    scope = vm.scope,
                    onScope = vm::selectScope,
                )
            }

            item {
                if (points.isEmpty()) {
                    Text(
                        text = "No workouts for this routine in the last 12 months.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TrendChart(
                        points = points,
                        values = values,
                        selectedIndex = selectedIndex,
                        lineColor = MaterialTheme.colorScheme.primary,
                        gridColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        highlightColor = MaterialTheme.colorScheme.secondary,
                        formatValue = { TrendFormat.axisValue(vm.metric, it) },
                        formatDate = { TrendFormat.shortDate(it) },
                        onSelect = { index -> vm.selectWorkout(points[index].workoutId) },
                    )
                }
            }

            if (points.size > 1) {
                item {
                    Text(
                        text = "Tap a point to break that workout down.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val comparison = vm.comparison
            if (comparison != null) {
                item { HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) }
                item { SelectedWorkoutHeader(comparison) }
                item { TotalsPanel(comparison) }
                item { CausesHeader(comparison) }
                items(comparison.deltas, key = { it.kind.name + ":" + it.templateId }) { delta ->
                    ExerciseDeltaRow(delta = delta, hasPrevious = comparison.previous != null)
                }
            }
        }
    }
}

@Composable
private fun MetricSelector(
    metric: TrendMetric,
    onMetric: (TrendMetric) -> Unit,
    scope: SetScope,
    onScope: (SetScope) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TrendMetric.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = metric == entry,
                    onClick = { onMetric(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, TrendMetric.entries.size),
                ) { Text(entry.label) }
            }
        }
        // Warmups are in the totals by default so the volume reconciles with
        // the figure Hevy puts on the workout; excluding them is the quickest
        // way to tell "I warmed up more" apart from "I worked harder".
        TextButton(
            onClick = { onScope(if (scope == SetScope.ALL) SetScope.WORKING else SetScope.ALL) },
        ) {
            Text(
                text = if (scope == SetScope.ALL) "Counting all sets — exclude warmups"
                else "Counting working sets only — include warmups",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SelectedWorkoutHeader(comparison: RoutineComparison.Comparison) {
    Column {
        Text(
            text = TrendFormat.date(comparison.current.startEpochMs),
            style = MaterialTheme.typography.titleSmall,
        )
        val previous = comparison.previous
        Text(
            text = if (previous == null) {
                "Oldest workout in the window — nothing to compare against."
            } else {
                "vs ${TrendFormat.date(previous.startEpochMs)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Volume / sets / reps for the selected workout, each with its delta. */
@Composable
private fun TotalsPanel(comparison: RoutineComparison.Comparison) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TotalRow(
            label = "Volume",
            value = TrendFormat.volume(comparison.currentTotals.volumeKg),
            delta = comparison.volumeDeltaKg?.let { TrendFormat.signedVolume(it) },
            deltaSign = comparison.volumeDeltaKg,
        )
        TotalRow(
            label = "Sets",
            value = comparison.currentTotals.sets.toString(),
            delta = comparison.setsDelta?.let { TrendFormat.signedInt(it) },
            deltaSign = comparison.setsDelta?.toDouble(),
        )
        TotalRow(
            label = "Reps",
            value = comparison.currentTotals.reps.toString(),
            delta = comparison.repsDelta?.let { TrendFormat.signedInt(it) },
            deltaSign = comparison.repsDelta?.toDouble(),
        )
    }
}

@Composable
private fun TotalRow(label: String, value: String, delta: String?, deltaSign: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
        if (delta != null) {
            Text(
                text = delta,
                style = MaterialTheme.typography.bodyMedium,
                color = deltaColor(deltaSign),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun CausesHeader(comparison: RoutineComparison.Comparison) {
    Text(
        text = if (comparison.previous == null) "This workout, exercise by exercise"
        else "What caused the change",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * One exercise's share of the delta. Collapsed it shows the exercise, its
 * volume delta and the named causes; expanded it shows both sessions' sets so
 * the number can be checked by hand.
 */
@Composable
private fun ExerciseDeltaRow(delta: RoutineComparison.ExerciseDelta, hasPrevious: Boolean) {
    var expanded by rememberSaveable(delta.templateId, delta.kind) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = delta.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                kindTag(delta.kind, hasPrevious)?.let { tag ->
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    text = if (hasPrevious) TrendFormat.signedVolume(delta.volumeDeltaKg)
                    else TrendFormat.volume(delta.current.volumeKg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasPrevious) deltaColor(delta.volumeDeltaKg)
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            Text(
                text = if (hasPrevious) {
                    RoutineComparison.causes(delta).joinToString("  ·  ")
                } else {
                    "${delta.current.sets} sets · ${delta.current.reps} reps"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedVisibility(visible = expanded) {
                SetLinePanel(delta = delta, hasPrevious = hasPrevious)
            }
        }
    }
}

/** Both sessions' sets, folded into "N × reps @ weight" runs. */
@Composable
private fun SetLinePanel(delta: RoutineComparison.ExerciseDelta, hasPrevious: Boolean) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SetLineGroup(
            heading = "This workout",
            lines = delta.currentSetLines,
            emptyText = "nothing logged",
        )
        if (hasPrevious) {
            SetLineGroup(
                heading = delta.previousTitle?.let { "Previous — $it" } ?: "Previous workout",
                lines = delta.previousSetLines,
                emptyText = "nothing logged",
            )
            Text(
                text = "sets ${TrendFormat.signedInt(delta.setsDelta)}  ·  " +
                    "reps ${TrendFormat.signedInt(delta.repsDelta)}  ·  " +
                    "volume ${TrendFormat.signedVolume(delta.volumeDeltaKg)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SetLineGroup(heading: String, lines: List<String>, emptyText: String) {
    Text(
        text = heading,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (lines.isEmpty()) {
        Text(
            text = "  $emptyText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        lines.forEach { line ->
            Text(
                text = "  $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Dim trailing tag, matching the Workout Detail chip's `swap` / `extra`. */
private fun kindTag(kind: RoutineComparison.Kind, hasPrevious: Boolean): String? {
    if (!hasPrevious) return null
    return when (kind) {
        RoutineComparison.Kind.SAME -> null
        RoutineComparison.Kind.SWAPPED -> "swap"
        RoutineComparison.Kind.ADDED -> "new"
        RoutineComparison.Kind.DROPPED -> "dropped"
    }
}

@Composable
private fun deltaColor(delta: Double?): Color = when {
    delta == null || delta == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
    delta > 0.0 -> PoGreen
    else -> MaterialTheme.colorScheme.error
}
