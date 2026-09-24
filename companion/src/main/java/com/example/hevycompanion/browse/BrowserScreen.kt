package com.example.hevycompanion.browse

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.generate.EquipmentMap
import com.example.hevycompanion.generate.mm.MmExercise
import com.example.hevycompanion.muscle.ExerciseAvatarContent
import com.example.hevycompanion.muscle.ExerciseAvatarPreview
import com.example.hevycompanion.muscle.HevyMuscleGroup
import com.example.hevycompanion.muscle.LiftoffCardTile
import com.example.hevycompanion.muscle.LiftoffMuscleCards
import com.example.hevycompanion.muscle.MuscleAssetMap

/**
 * Unified Browser screen — Browse Exercises by Muscle + Hevy Exercise List +
 * M&M Exercise List under one surface.
 *
 * Top of the screen carries two segmented controls:
 *
 *  - **Source**: Hevy or M&M (mutually exclusive — never both at once). Each
 *    source keeps its own persisted filter selection, so flipping back and
 *    forth restores chip state per source.
 *  - **View**: List (search + dropdown filters + flat result list) or Muscle
 *    Grid (the 20-card Liftoff selector — tapping a card pre-fills the
 *    active source's muscle/area filter and switches back to List).
 *
 * One LazyColumn in List mode, one LazyVerticalGrid in MuscleGrid mode.
 * Detail rendering still goes through the global [ExerciseDetailViewModel]
 * via the supplied [onAvatarTap] callback — this screen is pure browse.
 */
@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    onBack: () -> Unit,
    onAvatarTap: (BrowseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Persistent header (Back + segmented controls). Sits outside the
        // LazyColumn so swapping view modes doesn't churn the segmented
        // buttons or scroll the header off-screen.
        Header(vm = vm, onBack = onBack)
        when (vm.viewMode) {
            ViewMode.LIST -> ListView(vm = vm, onAvatarTap = onAvatarTap)
            ViewMode.MUSCLE_GRID -> MuscleGridView(vm = vm)
        }
    }
}

@Composable
private fun Header(vm: BrowserViewModel, onBack: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(12.dp))
            Text("Browse Exercises", style = MaterialTheme.typography.titleLarge)
        }
        SourceSegmented(current = vm.source, onChange = { vm.setSource(it) })
        ViewModeSegmented(current = vm.viewMode, onChange = { vm.setViewMode(it) })
    }
}

@Composable
private fun SourceSegmented(current: Source, onChange: (Source) -> Unit) {
    val options = Source.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, src ->
            SegmentedButton(
                selected = current == src,
                onClick = { onChange(src) },
                shape = SegmentedButtonDefaults.itemShape(idx, options.size),
            ) { Text(src.displayLabel()) }
        }
    }
}

private fun Source.displayLabel(): String = when (this) {
    Source.HEVY -> "Hevy"
    Source.MM -> "M&M"
}

@Composable
private fun ViewModeSegmented(current: ViewMode, onChange: (ViewMode) -> Unit) {
    val options = ViewMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, mode ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(idx, options.size),
            ) { Text(mode.displayLabel()) }
        }
    }
}

private fun ViewMode.displayLabel(): String = when (this) {
    ViewMode.LIST -> "List"
    ViewMode.MUSCLE_GRID -> "▦ Muscle"
}

// ============================================================================
// LIST MODE
// ============================================================================

@Composable
private fun ListView(vm: BrowserViewModel, onAvatarTap: (BrowseItem) -> Unit) {
    when (vm.source) {
        Source.HEVY -> HevyListView(vm = vm, onAvatarTap = onAvatarTap)
        Source.MM -> MmListView(vm = vm, onAvatarTap = onAvatarTap)
    }
}

@Composable
private fun HevyListView(vm: BrowserViewModel, onAvatarTap: (BrowseItem) -> Unit) {
    var previewing by remember { mutableStateOf<ExerciseTemplate?>(null) }
    val (suggested, rest) = vm.curatedSplit
    val showSuggested = suggested.isNotEmpty()
    val flat = if (showSuggested) emptyList() else vm.filteredHevy

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            // U6 — clear-icon trailing chip when the query is non-empty.
            // Tapping the chip wipes the text without forcing the user to
            // delete character-by-character or pull up the soft keyboard's
            // long-press menu.
            OutlinedTextField(
                value = vm.hevyFilter.query,
                onValueChange = { vm.setHevyQuery(it) },
                singleLine = true,
                label = { Text("Search by name") },
                trailingIcon = if (vm.hevyFilter.query.isNotEmpty()) {
                    {
                        Text(
                            text = "✕",
                            modifier = Modifier
                                .clickable { vm.setHevyQuery("") }
                                .padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            FilterDropdown(
                label = "Muscle",
                options = vm.knownHevyMuscleGroups,
                selected = vm.hevyFilter.muscleGroups,
                labelFor = { MuscleAssetMap.displayName(it) },
                onToggle = { vm.toggleHevyMuscle(it) },
            )
        }
        item {
            FilterDropdown(
                label = "Equipment",
                options = vm.knownHevyEquipment,
                selected = vm.hevyFilter.equipment,
                labelFor = { EquipmentMap.displayLabel(it) },
                onToggle = { vm.toggleHevyEquipment(it) },
            )
        }
        item {
            FilterDropdown(
                label = "Type",
                options = vm.knownHevyExerciseTypes,
                selected = vm.hevyFilter.exerciseTypes,
                labelFor = { it.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } },
                onToggle = { vm.toggleHevyExerciseType(it) },
            )
        }
        item {
            FilterDropdown(
                label = "Level",
                options = vm.knownHevyLevels,
                selected = vm.hevyFilter.levels,
                labelFor = { it.replaceFirstChar { c -> c.uppercase() } },
                onToggle = { vm.toggleHevyLevel(it) },
            )
        }
        item {
            FilterDropdown(
                label = "Category",
                options = vm.knownHevyCategories,
                selected = vm.hevyFilter.categories,
                labelFor = { it.replace('-', ' ').replaceFirstChar { c -> c.uppercase() } },
                onToggle = { vm.toggleHevyCategory(it) },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(top = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${vm.filteredHevy.size} / ${vm.hevyTemplates.size} exercises",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!vm.hevyFilter.isEmpty) {
                    TextButton(onClick = { vm.clearHevyFilters() }) { Text("Clear filters") }
                }
            }
        }

        if (vm.isLoadingHevy && vm.hevyTemplates.isEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Loading exercise catalog…", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (vm.hevyError != null && vm.hevyTemplates.isEmpty()) {
            item {
                Text(
                    vm.hevyError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (vm.filteredHevy.isEmpty() && vm.hevyTemplates.isNotEmpty()) {
            item {
                // U7 — inline Clear-filters CTA on the empty state. The same
                // button at the top of the filter row is often scrolled off
                // by the time the user sees the empty list; surfacing it
                // here removes the "where is the reset" friction.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "No exercises match the current filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.clearHevyFilters() }) {
                        Text("Clear filters")
                    }
                }
            }
        }

        if (showSuggested) {
            item {
                Text("Suggested", style = MaterialTheme.typography.titleMedium)
            }
            items(suggested, key = { "s-" + it.id }) { ex ->
                HevyRow(
                    ex,
                    isSuggested = true,
                    onAvatarClick = { onAvatarTap(BrowseItem.Hevy(ex.id)) },
                    onAvatarLongPress = { previewing = ex },
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("All exercises", style = MaterialTheme.typography.titleMedium)
            }
            items(rest, key = { "a-" + it.id }) { ex ->
                HevyRow(
                    ex,
                    isSuggested = false,
                    onAvatarClick = { onAvatarTap(BrowseItem.Hevy(ex.id)) },
                    onAvatarLongPress = { previewing = ex },
                )
            }
        } else {
            items(flat, key = { it.id }) { ex ->
                HevyRow(
                    ex,
                    isSuggested = false,
                    onAvatarClick = { onAvatarTap(BrowseItem.Hevy(ex.id)) },
                    onAvatarLongPress = { previewing = ex },
                )
            }
        }

        item { Spacer(Modifier.size(16.dp)) }
    }

    previewing?.let { ex -> ExerciseAvatarPreview(ex, onDismiss = { previewing = null }) }
}

@Composable
private fun MmListView(vm: BrowserViewModel, onAvatarTap: (BrowseItem) -> Unit) {
    var previewing by remember { mutableStateOf<MmExercise?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = vm.mmFilter.query,
                onValueChange = { vm.setMmQuery(it) },
                singleLine = true,
                label = { Text("Search by name") },
                trailingIcon = if (vm.mmFilter.query.isNotEmpty()) {
                    {
                        Text(
                            text = "✕",
                            modifier = Modifier
                                .clickable { vm.setMmQuery("") }
                                .padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            FilterDropdown(
                label = "Area",
                options = vm.knownMmAreas,
                selected = vm.mmFilter.areas,
                onToggle = { vm.toggleMmArea(it) },
            )
        }
        item {
            FilterDropdown(
                label = "Sub-area",
                options = vm.knownMmSubAreas,
                selected = vm.mmFilter.subAreas,
                labelFor = { it.substringAfter(" | ", it) },
                onToggle = { vm.toggleMmSubArea(it) },
            )
        }
        item {
            FilterDropdown(
                label = "Equipment",
                options = vm.knownMmEquipment,
                selected = vm.mmFilter.equipment,
                onToggle = { vm.toggleMmEquipment(it) },
            )
        }
        item {
            FilterDropdown(
                label = "Category",
                options = vm.knownMmCategories,
                selected = vm.mmFilter.categories,
                onToggle = { vm.toggleMmCategory(it) },
            )
        }
        item {
            FilterDropdown(
                label = "Type",
                options = vm.knownMmTypes,
                selected = vm.mmFilter.types,
                labelFor = { it.replaceFirstChar { c -> c.uppercase() } },
                onToggle = { vm.toggleMmType(it) },
            )
        }
        item {
            FilterDropdown(
                label = "Movement pattern",
                options = vm.knownMmMovementPatterns,
                selected = vm.mmFilter.movementPatterns,
                onToggle = { vm.toggleMmMovementPattern(it) },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(top = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${vm.filteredMm.size} / ${vm.mmCatalog.size} exercises",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!vm.mmFilter.isEmpty) {
                    TextButton(onClick = { vm.clearMmFilters() }) { Text("Clear filters") }
                }
            }
        }

        if (vm.filteredMm.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "No exercises match the current filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.clearMmFilters() }) {
                        Text("Clear filters")
                    }
                }
            }
        }

        items(vm.filteredMm, key = { it.id }) { ex ->
            MmRow(
                ex,
                onAvatarClick = { onAvatarTap(BrowseItem.Mm(ex.id)) },
                onAvatarLongPress = { previewing = ex },
            )
        }
        item { Spacer(Modifier.size(16.dp)) }
    }

    previewing?.let { ex -> MmExercisePreview(ex, onDismiss = { previewing = null }) }
}

// ============================================================================
// MUSCLE GRID MODE
// ============================================================================

@Composable
private fun MuscleGridView(vm: BrowserViewModel) {
    // Live-count selected cards so the header CTA can show "Show N results"
    // and stay disabled when nothing is picked yet (matching the Generator's
    // multi-select picker UX).
    val selectedCount = remember(vm.source, vm.hevyFilter, vm.mmFilter) {
        var count = 0
        for (card in LiftoffMuscleCards.ALL) if (vm.isCardSelected(card)) count++
        if (vm.source == Source.HEVY) {
            for (grp in EXTRA_HEVY_GROUPS) if (vm.isOtherGroupSelected(grp)) count++
        }
        count
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Tap muscles to filter — multi-select.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Button(
                    onClick = { vm.setViewMode(ViewMode.LIST) },
                    enabled = selectedCount > 0,
                ) {
                    Text(if (selectedCount > 0) "✓ Show ($selectedCount)" else "✓ Show")
                }
            }
        }
        items(LiftoffMuscleCards.ALL) { card ->
            LiftoffCardTile(
                card = card,
                isSelected = vm.isCardSelected(card),
                onClick = { vm.onMuscleCardTap(card) },
            )
        }
        if (vm.source == Source.HEVY) {
            // The non-anatomical Hevy groups don't exist as Liftoff cards, so
            // they live below the grid as chips. M&M has no analogue (its
            // catalog has no full-body / cardio / neck / other rows the way
            // Hevy does), so the chip row only renders for Source.HEVY.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Other", style = MaterialTheme.typography.titleMedium)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (grp in EXTRA_HEVY_GROUPS) {
                        FilterChip(
                            selected = vm.isOtherGroupSelected(grp),
                            onClick = { vm.onOtherGroupTap(grp) },
                            label = { Text(MuscleAssetMap.displayName(grp)) },
                        )
                    }
                }
            }
        }
    }
}

private val EXTRA_HEVY_GROUPS = listOf(
    HevyMuscleGroup.FULL_BODY,
    HevyMuscleGroup.CARDIO,
    HevyMuscleGroup.NECK,
    HevyMuscleGroup.OTHER,
)

// ============================================================================
// SHARED ROW HELPERS
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HevyRow(
    ex: ExerciseTemplate,
    isSuggested: Boolean,
    onAvatarClick: () -> Unit,
    onAvatarLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClick = onAvatarClick, onLongClick = onAvatarLongPress),
                contentAlignment = Alignment.Center,
            ) {
                ExerciseAvatarContent(
                    exerciseTemplateId = ex.id,
                    exerciseTitle = ex.title,
                    placeholderTextStyle = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ex.title, style = MaterialTheme.typography.titleMedium)
                val sub = listOfNotNull(
                    ex.equipment?.takeIf { it.isNotBlank() }?.let { EquipmentMap.displayLabel(it) },
                    ex.primaryMuscleGroup?.takeIf { it.isNotBlank() }?.let { MuscleAssetMap.displayName(it) },
                ).joinToString(" • ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isSuggested) {
                Text(
                    "★",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MmRow(
    ex: MmExercise,
    onAvatarClick: () -> Unit,
    onAvatarLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClick = onAvatarClick, onLongClick = onAvatarLongPress),
                contentAlignment = Alignment.Center,
            ) {
                if (ex.thumbnailUrl != null) {
                    AsyncImage(
                        model = ex.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        ex.name.firstOrNull()?.uppercase().orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ex.name, style = MaterialTheme.typography.titleMedium)
                val tagSub = buildList {
                    add(ex.area)
                    if (ex.equipment.isNotEmpty()) add(ex.equipment.first())
                    if (ex.movementPattern.isNotEmpty()) add(ex.movementPattern.first())
                }.joinToString(" • ")
                Text(
                    tagSub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ex.targetMuscles.isNotEmpty()) {
                    Text(
                        "Target: " + ex.targetMuscles.take(3).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    options: List<String>,
    selected: Set<String>,
    labelFor: (String) -> String = { it },
    onToggle: (String) -> Unit,
) {
    if (options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "$label (${selected.size}/${options.size})",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            options.forEach { opt ->
                val isSelected = opt in selected
                DropdownMenuItem(
                    text = { Text(labelFor(opt)) },
                    onClick = { onToggle(opt) },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

// ============================================================================
// M&M PREVIEW (looping mp4)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MmExercisePreview(ex: MmExercise, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .combinedClickable(onClick = onDismiss, onLongClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(0.85f).padding(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        ex.videoUrl?.endsWith(".mp4") == true ->
                            MmLoopingVideoPlayer(ex.videoUrl)
                        ex.thumbnailUrl != null ->
                            AsyncImage(
                                model = ex.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        else -> Text(
                            "No preview available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(ex.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                val sub = listOfNotNull(
                    ex.equipment.firstOrNull(),
                    ex.area,
                    ex.targetMuscles.firstOrNull(),
                ).joinToString(" • ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MmLoopingVideoPlayer(url: String) {
    val context = LocalContext.current
    val videoView = remember(url) {
        VideoView(context).apply {
            setVideoURI(Uri.parse(url))
            setOnPreparedListener { mp ->
                mp.isLooping = true
                runCatching { mp.setVolume(0f, 0f) }
                start()
            }
        }
    }
    DisposableEffect(videoView) { onDispose { videoView.stopPlayback() } }
    AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize())
}
