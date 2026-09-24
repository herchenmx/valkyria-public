package com.example.hevycompanion.generate

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.muscle.ExerciseAvatarContent
import com.example.hevycompanion.muscle.ExerciseAvatarPreview
import com.example.hevycompanion.muscle.HevyMuscleGroup
import com.example.hevycompanion.muscle.MuscleAssetMap

/**
 * Liftoff's "generated workout" screen, ported.
 *
 * Layout:
 *   ┌──────────────────────────────────┐
 *   │ ← Back   Generated Workout       │
 *   │ [Muscles ▼] [1h ▼] [Heavy ▼]     │  ← filter chips (wrap to 2 rows)
 *   │ [Level ▼] [Category ▼] [Equip ▼] │
 *   │ Front Delt 31% • Triceps 22% …   │  ← %-per-muscle bar
 *   │                                  │
 *   │ N Exercises                      │
 *   │ ┌─ avatar  3 × Bench Press       │
 *   │ │          40 kg • 8 reps     ⋯ │
 *   │ │ avatar  3 × Lateral Raise     │
 *   │ │          11 kg • 8 reps     ⋯ │
 *   │ └────────────────────────────── │
 *   │ [ Regenerate ]                   │
 *   └──────────────────────────────────┘
 *
 * All multi-select filters (Level / Category / Equipment) are instant-apply —
 * every tap in the picker toggles the VM state, the sheet can be dismissed
 * at any time, and there's no "Set for this workout" confirm button. No
 * selected-count is displayed on the chips either (per Maria's request to
 * keep the chip row compact).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateWorkoutScreen(
    vm: GeneratorViewModel,
    onBack: () -> Unit,
    onAvatarTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var openSheet by remember { mutableStateOf<FilterSheet?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(12.dp))
            Text("Generated Workout", style = MaterialTheme.typography.titleLarge)
        }

        FilterChipsRow(
            duration = vm.duration,
            weights = vm.weights,
            onOpen = { openSheet = it },
        )

        val gen = vm.generated
        // Surface "no exercises because every chip in some dimension is
        // deselected" before falling through to the empty-state copy — beginners
        // wouldn't know to look at their filter row otherwise.
        vm.warningMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            vm.isLoading && gen == null -> LoadingRow()
            vm.errorMessage != null && gen == null ->
                Text(vm.errorMessage!!, color = MaterialTheme.colorScheme.error)
            gen == null && vm.warningMessage == null ->
                Text(
                    "Pick muscles to generate a workout.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            gen != null -> WorkoutPreview(
                gen = gen,
                onSwap = { vm.swapAt(it) },
                onAvatarTap = onAvatarTap,
            )
            else -> Unit
        }

        Spacer(Modifier.height(4.dp))
        if (vm.saveSuccess) {
            // Lightweight inline "toast" — the Save flow returned us here with
            // saveSuccess=true. Self-clears so it doesn't sit on screen for
            // the rest of the session (it used to linger until the user
            // happened to tap Regenerate).
            Text(
                "Saved ✓",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            LaunchedEffect(vm.saveSuccess) {
                delay(SAVE_BANNER_MS)
                vm.clearSaveSuccessFlag()
            }
        }
        val context = LocalContext.current
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = {
                    // Hands the workout to Android's native share sheet as
                    // plain text. `Intent.createChooser` forces the picker
                    // to show every time (vs. remembering the user's last
                    // choice) which matches the short-lived, "send this
                    // one thing to a friend" use case better than a
                    // stickier direct-share.
                    val gen = vm.generated ?: return@OutlinedButton
                    val shareText = formatWorkoutForSharing(
                        workout = gen,
                        selectedMuscles = vm.selectedMuscles,
                        duration = vm.duration,
                        weights = vm.weights,
                        selectedLevels = vm.selectedLevels,
                        selectedCategories = vm.selectedCategories,
                        selectedEquipment = vm.selectedEquipment,
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share workout"))
                },
                enabled = vm.generated != null,
                modifier = Modifier.weight(1f),
            ) { Text("Share") }
            // Save is the primary action — it's what the user came to this
            // screen to do. Regenerate was previously the filled button,
            // which made the throwaway action look like the goal.
            Button(
                onClick = { vm.enterSaveFolderPick() },
                enabled = vm.generated != null,
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(
                onClick = {
                    vm.clearSaveSuccessFlag()
                    vm.regenerate()
                },
                enabled = vm.generated != null,
                modifier = Modifier.weight(1f),
            ) { Text("Regenerate") }
        }
    }

    openSheet?.let { which ->
        ModalBottomSheet(
            onDismissRequest = { openSheet = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            when (which) {
                FilterSheet.MUSCLES -> MusclesPicker(
                    selected = vm.selectedMuscles,
                    onToggle = { vm.toggleMuscle(it) },
                )
                FilterSheet.DURATION -> DurationPicker(
                    selected = vm.duration,
                    onSelect = { vm.pickDuration(it); openSheet = null },
                )
                FilterSheet.WEIGHTS -> WeightsPicker(
                    selected = vm.weights,
                    onSelect = { vm.pickWeights(it); openSheet = null },
                )
                FilterSheet.LEVEL -> LevelPicker(
                    selected = vm.selectedLevels,
                    onToggle = { vm.toggleLevel(it) },
                )
                FilterSheet.CATEGORY -> CategoryPicker(
                    selected = vm.selectedCategories,
                    onToggle = { vm.toggleCategory(it) },
                )
                FilterSheet.EQUIPMENT -> EquipmentPicker(
                    selected = vm.selectedEquipment,
                    onToggle = { vm.toggleEquipment(it) },
                )
            }
        }
    }
}

private enum class FilterSheet { MUSCLES, DURATION, WEIGHTS, LEVEL, CATEGORY, EQUIPMENT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipsRow(
    duration: Duration,
    weights: Weights,
    onOpen: (FilterSheet) -> Unit,
) {
    // FlowRow so the six chips wrap onto a second line on narrow screens —
    // a plain Row would clip them off the right edge.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AssistChip(onClick = { onOpen(FilterSheet.MUSCLES) }, label = { Text("Muscles ▼") })
        AssistChip(onClick = { onOpen(FilterSheet.DURATION) }, label = { Text("${duration.label} ▼") })
        AssistChip(onClick = { onOpen(FilterSheet.WEIGHTS) }, label = { Text("${weights.displayName} ▼") })
        AssistChip(onClick = { onOpen(FilterSheet.LEVEL) }, label = { Text("Level ▼") })
        AssistChip(onClick = { onOpen(FilterSheet.CATEGORY) }, label = { Text("Category ▼") })
        AssistChip(onClick = { onOpen(FilterSheet.EQUIPMENT) }, label = { Text("Equipment ▼") })
    }
}

@Composable
private fun WorkoutPreview(
    gen: GeneratedWorkout,
    onSwap: (Int) -> Unit,
    onAvatarTap: (String) -> Unit,
) {
    // Long-press the avatar → show it full-size. Short-tap the avatar →
    // open the detail screen via the global ExerciseDetailViewModel
    // (forwarded by [onAvatarTap]). Same gesture vocabulary as the
    // browse-by-muscle screen.
    var previewing by remember { mutableStateOf<ExerciseTemplate?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (gen.muscleSplit.isNotEmpty()) MuscleSplitBar(gen.muscleSplit)

        Text("${gen.exercises.size} Exercises", style = MaterialTheme.typography.titleMedium)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(gen.exercises) { idx, ex ->
                ExerciseRow(
                    ex = ex,
                    onSwap = { onSwap(idx) },
                    onAvatarClick = { onAvatarTap(ex.template.id) },
                    onAvatarLongPress = { previewing = ex.template },
                )
            }
        }
    }

    previewing?.let { tpl ->
        ExerciseAvatarPreview(tpl, onDismiss = { previewing = null })
    }
}

@Composable
private fun MuscleSplitBar(shares: List<MuscleShare>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Target Muscles", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            for (share in shares) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(share.percent / 100f)
                        .height(8.dp)
                        .background(Color(MuscleAssetMap.tintFor(share.hevyMuscleGroup))),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (share in shares.take(4)) {
                Text(
                    "${MuscleAssetMap.displayName(share.hevyMuscleGroup)} ${share.percent.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExerciseRow(
    ex: GeneratedExercise,
    onSwap: () -> Unit,
    onAvatarClick: () -> Unit,
    onAvatarLongPress: () -> Unit,
) {
    // Avatar carries both tap gestures so they don't collide with the
    // row-level "⋯ Swap" action. Short-tap → detail screen via the global
    // ExerciseDetailViewModel; long-press → full-size avatar / video.
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
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClick = onAvatarClick, onLongClick = onAvatarLongPress),
                contentAlignment = Alignment.Center,
            ) {
                ExerciseAvatarContent(
                    exerciseTemplateId = ex.template.id,
                    exerciseTitle = ex.template.title,
                    placeholderTextStyle = MaterialTheme.typography.headlineSmall,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${ex.sets} × ${ex.template.title}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatSubline(ex),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Tap "⋯" to swap this exercise for a different one in the same pool.
            Text(
                "⋯",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clickable(onClick = onSwap),
            )
        }
    }
}

/**
 * "{kg} kg • {reps} reps" when weight > 0; just "{reps} reps" for bodyweight.
 * Matches Liftoff's row-subtitle format. Locale-pinned to US so the decimal
 * separator is always "." (avoids "36,7 kg" in DE locale, which would also
 * break the unit test for [formatSubline]).
 */
internal fun formatSubline(ex: GeneratedExercise): String {
    if (ex.weightKg <= 0f) return "${ex.reps} reps"
    val weightStr = if (ex.weightKg % 1f == 0f) "${ex.weightKg.toInt()}"
                    else "%.1f".format(java.util.Locale.US, ex.weightKg)
    return "$weightStr kg • ${ex.reps} reps"
}

@Composable
private fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("Loading exercise catalog…", style = MaterialTheme.typography.bodySmall)
    }
}

// ---- bottom-sheet pickers --------------------------------------------------

@Composable
private fun MusclesPicker(selected: Set<String>, onToggle: (String) -> Unit) {
    // Restricted to the anatomical groups — same subset the initial full-screen
    // muscle picker uses in multi-select mode. The generator's %-per-muscle
    // split bar is built on anatomical primary/secondary tags, and listing
    // cardio / full_body / neck / other here would let the user pick a group
    // with no matching exercises (generator would silently produce nothing).
    PickerSheetTitle("Muscles")
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(HevyMuscleGroup.ANATOMICAL) { hevyMuscle ->
            PickerRow(
                label = MuscleAssetMap.displayName(hevyMuscle),
                isSelected = hevyMuscle in selected,
                onClick = { onToggle(hevyMuscle) },
            )
        }
    }
}

@Composable
private fun DurationPicker(selected: Duration, onSelect: (Duration) -> Unit) {
    PickerSheetTitle("Duration")
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(Duration.entries.toList()) { d ->
            PickerRow(
                label = d.label,
                isSelected = d == selected,
                onClick = { onSelect(d) },
            )
        }
    }
}

@Composable
private fun WeightsPicker(selected: Weights, onSelect: (Weights) -> Unit) {
    PickerSheetTitle("Weights")
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(Weights.entries.toList()) { w ->
            PickerRow(
                label = w.displayName,
                isSelected = w == selected,
                onClick = { onSelect(w) },
            )
        }
    }
}

@Composable
private fun LevelPicker(selected: Set<Level>, onToggle: (Level) -> Unit) {
    PickerSheetTitle("Level")
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(Level.entries.toList()) { lvl ->
            PickerRow(
                label = lvl.displayName,
                isSelected = lvl in selected,
                onClick = { onToggle(lvl) },
            )
        }
    }
}

@Composable
private fun CategoryPicker(selected: Set<Category>, onToggle: (Category) -> Unit) {
    PickerSheetTitle("Category")
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(Category.entries.toList()) { cat ->
            PickerRow(
                label = cat.displayName,
                isSelected = cat in selected,
                onClick = { onToggle(cat) },
            )
        }
    }
}

@Composable
private fun EquipmentPicker(selected: Set<String>, onToggle: (String) -> Unit) {
    PickerSheetTitle("Available Equipment")
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        for ((category, tags) in EquipmentMap.grouped()) {
            if (tags.isEmpty()) continue
            item {
                Text(
                    category.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                HorizontalDivider()
            }
            items(tags) { tag ->
                PickerRow(
                    label = EquipmentMap.displayLabel(tag),
                    isSelected = tag in selected,
                    onClick = { onToggle(tag) },
                )
            }
        }
    }
}

@Composable
private fun PickerSheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    HorizontalDivider()
}

@Composable
private fun PickerRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (isSelected) "✓" else "○",
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How long the inline "Saved ✓" banner stays before self-clearing. */
private const val SAVE_BANNER_MS = 3_000L
