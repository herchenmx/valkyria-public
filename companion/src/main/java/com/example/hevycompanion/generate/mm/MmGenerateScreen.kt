package com.example.hevycompanion.generate.mm

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.hevycompanion.muscle.LiftoffCardTile
import com.example.hevycompanion.muscle.LiftoffMuscleCards

/**
 * Single-screen setup + result for the M&M generator. Top section is the
 * filter form; tapping "Generate" rolls a workout and shows it inline below.
 *
 * Deliberately simpler than the Hevy generator: no save-to-routine flow
 * (M&M ids aren't valid in Hevy), no muscle-picker step (the mode controls
 * scoping), and no per-exercise weight prescription (the M&M catalog has no
 * 1RM / weight reference).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MmGenerateScreen(
    vm: MmGeneratorViewModel,
    onBack: () -> Unit,
    onAvatarTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(12.dp))
            Text("M&M Generator", style = MaterialTheme.typography.titleLarge)
        }

        when (vm.screen) {
            MmGeneratorViewModel.Screen.Closed,
            MmGeneratorViewModel.Screen.Setup -> SetupSection(vm)
            MmGeneratorViewModel.Screen.Result -> ResultSection(vm, onAvatarTap = onAvatarTap)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupSection(vm: MmGeneratorViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionLabel("Mode")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = vm.modeKind == MmGeneratorPrefs.MODE_SPLIT,
                    onClick = { vm.pickModeKind(MmGeneratorPrefs.MODE_SPLIT) },
                    label = { Text("Split day") },
                )
                FilterChip(
                    selected = vm.modeKind == MmGeneratorPrefs.MODE_SUB_AREAS,
                    onClick = { vm.pickModeKind(MmGeneratorPrefs.MODE_SUB_AREAS) },
                    label = { Text("Pick muscles") },
                )
            }
        }

        if (vm.modeKind == MmGeneratorPrefs.MODE_SPLIT) {
            item {
                SectionLabel("Split")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SplitDay.entries.forEach { d ->
                        FilterChip(
                            selected = vm.splitDay == d,
                            onClick = { vm.pickSplitDay(d) },
                            label = { Text(d.displayName) },
                        )
                    }
                }
            }
        } else {
            // "Pick muscles" sub-mode: the same Liftoff card grid the Browser
            // and Hevy generator use, multi-select. Tapping a card toggles
            // its mmSubAreas (or mmAreaFallback for cards like Upper/Lower
            // Chest that have no `Chest | …` sub-area in M&M's catalog) into
            // [MmGeneratorViewModel.selectedSubAreas]. `Mode.SubAreas.matches`
            // accepts both area- and sub-area-level entries in the same set,
            // so no algorithm change is needed.
            //
            // We render the grid as 7 manual rows of 3 inside the LazyColumn
            // (vs. a nested LazyVerticalGrid) because nested lazy scrollables
            // are disallowed and the 20-card grid is small enough that
            // chunked rows are cheap to lay out.
            item {
                SectionLabel("Pick muscles (${countSelectedCards(vm)}/${LiftoffMuscleCards.ALL.size})")
            }
            items(items = LiftoffMuscleCards.ALL.chunked(3)) { rowCards ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowCards.forEach { card ->
                        LiftoffCardTile(
                            card = card,
                            isSelected = vm.isCardSelected(card),
                            onClick = { vm.onMuscleCardTap(card) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pad the last row out to 3 columns so the trailing
                    // tiles align with the rest of the grid.
                    repeat(3 - rowCards.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            SectionLabel("Equipment (${vm.selectedEquipment.size}/${vm.knownEquipment.size})")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.knownEquipment.forEach { eq ->
                    FilterChip(
                        selected = eq in vm.selectedEquipment,
                        onClick = { vm.toggleEquipment(eq) },
                        label = { Text(eq, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
        }

        item {
            SectionLabel("Exercise types")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExerciseType.entries.forEach { t ->
                    FilterChip(
                        selected = t in vm.selectedTypes,
                        onClick = { vm.toggleType(t) },
                        label = { Text(t.displayName) },
                    )
                }
            }
        }

        item {
            SectionLabel("Duration")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MmDuration.entries.forEach { d ->
                    FilterChip(
                        selected = vm.duration == d,
                        onClick = { vm.pickDuration(d) },
                        label = { Text(d.label) },
                    )
                }
            }
        }

        item {
            SectionLabel("Extras")
            ToggleRow("Add warmup", vm.addWarmup) { vm.toggleAddWarmup(it) }
            ToggleRow("Add cooldown", vm.addCooldown) { vm.toggleAddCooldown(it) }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.generate() }, modifier = Modifier.fillMaxWidth()) {
                Text("Generate")
            }
        }
    }
}

@Composable
private fun ResultSection(vm: MmGeneratorViewModel, onAvatarTap: (String) -> Unit) {
    val gen = vm.generated ?: run {
        // Surface the specific "all-deselected" reason if there is one,
        // otherwise the generic empty-state copy.
        Text(vm.warningMessage ?: "No workout generated.")
        return
    }
    var previewing by remember { mutableStateOf<MmExercise?>(null) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.backToSetup() }) { Text("Edit filters") }
                Button(onClick = { vm.regenerate() }) { Text("Regenerate") }
            }
        }
        if (gen.volumeByMuscle.isNotEmpty()) {
            item { VolumeBar(gen.volumeByMuscle) }
            item { HorizontalDivider() }
        }
        itemsIndexed(gen.blocks) { idx, block ->
            BlockCard(
                block = block,
                onSwap = { vm.swapAt(idx) },
                onAvatarClick = { onAvatarTap(block.exercise.id) },
                onAvatarLongPress = { previewing = block.exercise },
            )
        }
    }

    previewing?.let { ex ->
        MmExercisePreview(ex = ex, onDismiss = { previewing = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockCard(
    block: MmBlock,
    onSwap: () -> Unit,
    onAvatarClick: () -> Unit,
    onAvatarLongPress: () -> Unit,
) {
    val ex = block.exercise
    val rolePrefix = when (block.role) {
        BlockRole.WARMUP -> "Warmup • "
        BlockRole.COOLDOWN -> "Cooldown • "
        BlockRole.MAIN -> ""
    }
    val rxLabel = if (block.isTime) "${block.sets} × ${block.repsOrSeconds}s"
                  else "${block.sets} × ${block.repsOrSeconds} reps"
    // Avatar carries both gestures so they don't collide with the row's
    // "⋯ Swap" action. Short-tap → MmExerciseDetailScreen via the global
    // ExerciseDetailViewModel; long-press → looping demo overlay.
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
                    .size(72.dp)
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
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$rolePrefix${ex.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = rxLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                val tags = buildList {
                    add(ex.category)
                    add(ex.area)
                    if (ex.equipment.isNotEmpty()) add(ex.equipment.first())
                    if (ex.movementPattern.isNotEmpty()) add(ex.movementPattern.first())
                }.joinToString(" • ")
                Text(
                    tags,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ex.targetMuscles.isNotEmpty()) {
                    Text(
                        "Target: " + ex.targetMuscles.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Swap button — the avatar already owns the long-press preview
            // gesture, so this only needs the short-tap.
            Text(
                "⋯",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .combinedClickable(
                        onClick = onSwap,
                        onLongClick = onAvatarLongPress,
                    ),
            )
        }
    }
}

/**
 * Full-screen long-press preview: loops the M&M demo mp4 (or shows the
 * thumbnail if there's no video). Mirrors the muscle-browse / Hevy-generator
 * preview UX so the gesture feels the same across both generators.
 */
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
                        else ->
                            Text(
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

/**
 * Same shape as the muscle-browse [LoopingVideoPlayer] (private to that
 * package, so we keep a sibling here to avoid leaking it). Silent loop on
 * the framework VideoView; releases the decoder on dispose.
 */
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

@Composable
private fun VolumeBar(shares: List<MmMuscleShare>) {
    Column {
        Text("Top muscles", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = shares.take(6).joinToString("  •  ") {
                "${it.muscle} ${it.percent.toInt()}%"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
}

/** Count of Liftoff cards currently considered selected (whatever's in
 *  [MmGeneratorViewModel.selectedSubAreas] that maps back to a card). */
private fun countSelectedCards(vm: MmGeneratorViewModel): Int =
    LiftoffMuscleCards.ALL.count { vm.isCardSelected(it) }

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
