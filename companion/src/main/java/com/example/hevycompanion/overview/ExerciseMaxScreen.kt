package com.example.hevycompanion.overview

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hevycompanion.generate.EquipmentMap
import com.example.hevycompanion.muscle.MuscleAssetMap

/**
 * Strength Overview — every weight-rep exercise the user has performed, grouped
 * by primary muscle group, showing the highest average weight they sustained
 * for 3+ normal sets of 15+ reps in a single workout (see [ExerciseMax]).
 *
 * Muscle groups are collapsible; the list is searchable by name and filterable
 * by equipment; the exercises within a group sort by name (default) or by the
 * date of the highest weight. Read-only, plus a CSV export of the same table.
 */
@Composable
fun ExerciseMaxScreen(
    vm: ExerciseMaxViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Bucket-G item 40 — optional jump-to-Alternatives affordance so users
    // browsing their strength data can pivot to "similar exercises I could
    // swap in" without navigating back to home first. Null-safe so this
    // screen still renders in isolation (e.g. previews / tests).
    onOpenAlternatives: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize()) {
        // Header: Back · title · [Alt] · Export CSV
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                text = "Strength Overview",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (onOpenAlternatives != null) {
                TextButton(onClick = onOpenAlternatives) { Text("Alt") }
            }
            TextButton(
                onClick = {
                    vm.exportCsvIntent(context)?.let { intent ->
                        context.startActivity(Intent.createChooser(intent, "Export CSV"))
                    }
                },
                enabled = vm.rows.isNotEmpty(),
            ) { Text("Export CSV") }
        }
        HorizontalDivider()

        when {
            vm.isLoading -> LoadingState(scanned = vm.scanned, total = vm.total)
            vm.error != null -> ErrorState(message = vm.error!!, onRetry = vm::retry)
            vm.rows.isEmpty() -> EmptyState()
            else -> {
                Controls(vm)
                HorizontalDivider()
                if (vm.sections.isEmpty()) {
                    NoMatchesState()
                } else {
                    OverviewList(vm = vm, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Controls(vm: ExerciseMaxViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Search exercises") },
            singleLine = true,
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    TextButton(onClick = { vm.setQuery("") }) { Text("✕") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Equipment filter chips (multi-select; empty = all).
        if (vm.knownEquipment.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.knownEquipment.forEach { equip ->
                    FilterChip(
                        selected = equip in vm.equipmentFilter,
                        onClick = { vm.toggleEquipmentFilter(equip) },
                        label = { Text(EquipmentMap.displayLabel(equip)) },
                    )
                }
            }
        }

        // Sort.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Sort",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                OverviewSort.entries.forEachIndexed { idx, mode ->
                    SegmentedButton(
                        selected = vm.sort == mode,
                        onClick = { vm.setSort(mode) },
                        shape = SegmentedButtonDefaults.itemShape(idx, OverviewSort.entries.size),
                    ) {
                        Text(if (mode == OverviewSort.NAME_ASC) "Name" else "Date")
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewList(vm: ExerciseMaxViewModel, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        for (section in vm.sections) {
            // While a filter is active, auto-expand so matches are visible.
            val expanded = vm.isFiltering || vm.isMuscleExpanded(section.muscleGroup)
            item(key = "m_${section.muscleGroup}") {
                MuscleHeader(
                    name = MuscleAssetMap.displayName(section.muscleGroup),
                    count = section.exerciseCount,
                    expanded = expanded,
                    onClick = { vm.toggleMuscle(section.muscleGroup) },
                )
            }
            if (expanded) {
                items(section.rows, key = { it.templateId }) { row ->
                    ExerciseRow(row)
                }
            }
            item(key = "d_${section.muscleGroup}") {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun MuscleHeader(name: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExerciseRow(row: ExerciseMaxRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyMedium)
            val subtitle = buildString {
                append(EquipmentMap.displayLabel(row.equipment))
                val date = OverviewFormat.date(row.workoutStartTime)
                if (date.isNotEmpty()) append("  ·  ").append(date)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${OverviewFormat.kg(row.highestKg)} kg",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            // Epley estimate, dimmed and prefixed so it reads as a derived
            // figure rather than a second lifted weight. See
            // ExerciseMax.epley1rm for why this is a comparison number, not
            // a weight to attempt.
            row.estimated1rmKg?.let { oneRm ->
                Text(
                    text = "1RM ~${OverviewFormat.kg(oneRm)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.LoadingState(scanned: Int, total: Int) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = if (total > 0) "Scanning history  $scanned / $total" else "Loading your exercises…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColumnScope.ErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun ColumnScope.EmptyState() {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = "No qualifying lifts yet.\nDo 3+ normal sets of 15+ reps to land on this list.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun ColumnScope.NoMatchesState() {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = "No exercises match your search / filter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}
