package com.example.hevycompanion.recents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hevycompanion.data.WorkoutDetailExercise

private val PoGreen = Color(0xFF4CAF50)   // matches the watch's ChipPalette.PoGreen
private val WarmupAmber = Color(0xFFFFC107) // matches the watch's ChipPalette.WarmupAmber

/**
 * Workout Detail — shows what a logged workout completed / missed against its
 * routine, the companion counterpart to the watch's WorkoutDetailScreen.
 * Status-coloured rows (COMPLETE / INCOMPLETE / MISSING / SUBSTITUTED / EXTRA)
 * when a routine resolved; a plain logged-exercise list otherwise.
 *
 * Each exercise row is tap-to-expand: expanding reveals the next-session
 * progressive-overload target and the advised warmup sets for that exercise.
 * When the workout is missing normal sets, a Resume affordance is offered (the
 * choice of finishing here or on the watch is handled by [onResume]).
 */
@Composable
fun WorkoutDetailScreen(
    workoutId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: WorkoutDetailViewModel = viewModel(),
    onResume: (() -> Unit)? = null,
) {
    androidx.compose.runtime.LaunchedEffect(workoutId) { vm.load(workoutId) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                text = vm.workout?.title ?: "Workout",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (vm.canContinue && onResume != null) {
                TextButton(onClick = onResume) { Text("Resume") }
            }
        }
        HorizontalDivider()

        when {
            vm.isLoading -> CenteredState { CircularProgressIndicator() }
            vm.error != null -> CenteredState {
                Text(
                    text = vm.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
            }
            vm.workout == null -> CenteredState {
                Text("Workout not found", style = MaterialTheme.typography.bodyMedium)
            }
            else -> DetailBody(vm)
        }
    }
}

@Composable
private fun ColumnScope.DetailBody(vm: WorkoutDetailViewModel) {
    val workout = vm.workout ?: return
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val dateText = RecentsFormat.date(workout.startTime, fallback = workout.startTime?.take(10) ?: "")
        if (dateText.isNotEmpty()) {
            item {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        if (vm.exerciseStatuses.isNotEmpty()) {
            items(vm.exerciseStatuses, key = { it.exerciseTemplateId }) { status ->
                StatusRow(status, vm.advice[status.exerciseTemplateId])
            }
        } else {
            // No routine to compare against — list what was logged.
            items(workout.exercises, key = { it.exerciseTemplateId }) { exercise ->
                RawExerciseRow(exercise, vm.advice[exercise.exerciseTemplateId])
            }
        }
    }
}

@Composable
private fun StatusRow(status: ExerciseCompletionStatus, advice: ExerciseAdvice?) {
    val stats = chipStatsForStatus(status, advice)
    ExpandableRow(
        background = backgroundForChipState(stats.state),
        title = status.title,
        kind = stats.kind,
        stats = stats,
        subtitle = null,
        advice = advice,
    )
}

@Composable
private fun RawExerciseRow(exercise: WorkoutDetailExercise, advice: ExerciseAdvice?) {
    val normalSets = exercise.sets.count { it.type == "normal" }
    val warmupSets = exercise.sets.count { it.type == "warmup" }
    val subtitle = buildList {
        if (warmupSets > 0) add("${warmupSets}W")
        add("${normalSets}N")
    }.joinToString(" + ")
    ExpandableRow(
        background = MaterialTheme.colorScheme.surfaceVariant,
        title = exercise.title ?: exercise.exerciseTemplateId,
        kind = null,
        stats = null,
        subtitle = subtitle,
        advice = advice,
    )
}

/** A status/exercise row that expands on tap to show PO + warmup advice. */
@Composable
private fun ExpandableRow(
    background: Color,
    title: String,
    kind: ChipKind?,
    stats: ExerciseChipStats?,
    subtitle: String?,
    advice: ExerciseAdvice?,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val hasAdvice = advice?.hasAnything == true
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .let { if (hasAdvice) it.clickable { expanded = !expanded } else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (kind != null) {
                    Text(
                        text = kind.tagLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (hasAdvice) {
                    Text(
                        text = if (expanded) "▾" else "▸",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (stats != null) {
                ExerciseChipStatsRow(stats)
            } else {
                Text(
                    text = subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded && advice != null) {
                advice?.let { AdvicePanel(it) }
            }
        }
    }
}

/** PO next-session target + advised warmups, shown when a row is expanded. */
@Composable
private fun AdvicePanel(advice: ExerciseAdvice) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val target = advice.po.targetKg
        if (target != null) {
            val base = advice.po.baseKg
            val label = buildString {
                append("Next session: ")
                append(RecentsFormat.kg(target))
                if (advice.po.increased && base != null) append("  (was ${RecentsFormat.kg(base)})")
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (advice.po.increased) PoGreen else MaterialTheme.colorScheme.onSurface,
            )
        }

        advice.avgLastNormalKg?.let { avg ->
            Text(
                text = "Last session avg: ${RecentsFormat.kg(avg)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (advice.warmups.isNotEmpty()) {
            Text(
                text = "Warmups",
                style = MaterialTheme.typography.bodySmall,
                color = WarmupAmber,
            )
            advice.warmups.forEachIndexed { i, w ->
                Text(
                    text = "  ${i + 1}.  ${w.reps} × ${RecentsFormat.kg(w.weightKg)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.CenteredState(content: @Composable () -> Unit) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        content()
    }
}
