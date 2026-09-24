package com.example.hevywatch.presentation.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.LastSessionStats
import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.data.model.ExerciseCompletionStatus
import com.example.hevywatch.data.model.ExerciseCompletionStatus.Status
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.components.FreshnessLine
import com.example.hevywatch.ui.components.RefreshOverlay
import com.example.hevywatch.ui.components.RotatingLogo
import com.example.hevywatch.ui.theme.HevyExtendedColors
import com.example.hevywatch.ui.theme.hevyExtendedColors
import com.example.hevywatch.util.DateFormatUtils
import com.example.hevywatch.util.FormatUtils

@Composable
fun WorkoutDetailScreen(
    workoutId: String,
    navController: NavController,
    viewModel: WorkoutDetailViewModel = viewModel()
) {
    LaunchedEffect(workoutId) {
        viewModel.load(workoutId)
    }

    LaunchedEffect(viewModel.navigateTo) {
        viewModel.navigateTo?.let { route ->
            navController.navigate(route)
            viewModel.onNavigated()
        }
    }

    val hevyApp = LocalContext.current.applicationContext as HevyApp
    // Hoisted above any early-return so the slot survives recompositions
    // through the refresh overlay (mirrors RoutineDetailScreen's currentPage).
    var expandedExerciseId by remember { mutableStateOf<String?>(null) }

    AppScaffold(
        onTimerClick = { navController.navigate(Screen.LOG_WORKOUT) }
    ) {
        // Initial-load spinner only when there's no workout to paint yet. A
        // user-tapped Refresh keeps the column mounted underneath a
        // RefreshOverlay so expandedExerciseId / listState survive.
        if (viewModel.isLoading && viewModel.workout == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RotatingLogo()
            }
            return@AppScaffold
        }

        val workout = viewModel.workout
        if (workout == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Workout not found")
            }
            return@AppScaffold
        }

        val listState = rememberScalingLazyListState()

        Box(Modifier.fillMaxSize()) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                autoCentering = null,
                contentPadding = PaddingValues(top = 28.dp, bottom = 16.dp)
            ) {
                // Title
                item {
                    Text(
                        text = workout.title ?: "Workout",
                        style = MaterialTheme.typography.title3,
                        color = MaterialTheme.colors.onPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                // Date
                item {
                    val dateText = run {
                        val instant = RoutineProgressComputer.parseInstant(workout.startTime)
                        DateFormatUtils.formatListDate(instant, fallback = workout.startTime.take(10))
                    }
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }

                // Exercise rows: status-coloured when we resolved a routine,
                // raw workout exercises otherwise. Both paths flow through
                // ExerciseRow so tap-to-expand "Last session" works either way.
                if (viewModel.exerciseStatuses.isNotEmpty()) {
                    items(viewModel.exerciseStatuses) { status ->
                        // hevyApp.exerciseHistoryCache is a plain MutableMap;
                        // reads from it don't subscribe. Keying on the
                        // refresh timestamp forces a re-derivation when the
                        // user taps Refresh — same pattern as
                        // RoutineDetailScreen.
                        val refreshKey = hevyApp.workoutDetailRefreshedAtMs
                        val lastSessionEntries = remember(status.exerciseTemplateId, refreshKey) {
                            LastSessionStats.latestSessionEntries(
                                hevyApp.exerciseHistoryCache[status.exerciseTemplateId]
                                    ?.exerciseHistory.orEmpty()
                            )
                        }
                        val stats = chipStatsForStatus(status)
                        ExerciseRow(
                            title = status.title,
                            kind = stats.kind,
                            stats = stats,
                            subtitle = null,
                            backgroundColor = backgroundForChipState(stats.state, hevyExtendedColors),
                            lastSessionEntries = lastSessionEntries,
                            isExpanded = expandedExerciseId == status.exerciseTemplateId,
                            onClick = {
                                expandedExerciseId = if (expandedExerciseId == status.exerciseTemplateId) {
                                    null
                                } else {
                                    status.exerciseTemplateId
                                }
                            }
                        )
                    }
                } else {
                    items(workout.exercises) { exercise ->
                        val refreshKey = hevyApp.workoutDetailRefreshedAtMs
                        val templateId = exercise.exerciseTemplateId
                        val lastSessionEntries = remember(templateId, refreshKey) {
                            LastSessionStats.latestSessionEntries(
                                hevyApp.exerciseHistoryCache[templateId]
                                    ?.exerciseHistory.orEmpty()
                            )
                        }
                        val normalSets = exercise.sets.count { it.type == "normal" }
                        val warmupSets = exercise.sets.count { it.type == "warmup" }
                        val subtitle = buildList {
                            if (warmupSets > 0) add("${warmupSets}W")
                            add("${normalSets}N")
                        }.joinToString(" + ")
                        ExerciseRow(
                            title = exercise.title ?: templateId,
                            kind = null,
                            stats = null,
                            subtitle = subtitle,
                            backgroundColor = null,
                            lastSessionEntries = lastSessionEntries,
                            isExpanded = expandedExerciseId == templateId,
                            onClick = {
                                expandedExerciseId = if (expandedExerciseId == templateId) {
                                    null
                                } else {
                                    templateId
                                }
                            }
                        )
                    }
                }

                // Resume button (only for incomplete workouts)
                if (viewModel.canContinue) {
                    item {
                        Button(
                            onClick = { viewModel.continueWorkout() },
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .padding(top = 8.dp),
                            colors = ButtonDefaults.primaryButtonColors()
                        ) {
                            Text("Resume")
                        }
                    }
                }

                // Refresh chip + freshness line — same pattern as
                // RoutineDetailScreen, anchored to workoutDetailRefreshedAtMs.
                item {
                    RefreshChipDetail(
                        enabled = !viewModel.isRefreshing && !viewModel.isLoading,
                        onClick = { viewModel.refresh() }
                    )
                }
                item {
                    FreshnessLine(hevyApp.workoutDetailRefreshedAtMs)
                }
            }

            PositionIndicator(scalingLazyListState = listState)

            if (viewModel.isRefreshing) {
                RefreshOverlay()
            }
        }
    }
}

/**
 * Map a completion status to the unified [ExerciseChipStats] (shared with
 * LogWorkoutScreen via [ExerciseChipUi]). Tint by completion (green/blue/red);
 * the swap/extra distinction rides the [ChipKind] tag, not a colour. Weight: the
 * PO target (green when bumped) until the first normal set is logged, then the
 * logged working weight.
 */
internal fun chipStatsForStatus(status: ExerciseCompletionStatus): ExerciseChipStats {
    val kind = when (status.status) {
        Status.SUBSTITUTED -> ChipKind.SWAP
        Status.EXTRA -> ChipKind.EXTRA
        else -> null
    }
    val (weightKg, weightKind) = if (status.recordedNormalSets == 0) {
        status.poTargetKg to (if (status.poIncreased) WeightKind.PO else WeightKind.PLAIN)
    } else {
        status.loggedWorkingWeightKg to WeightKind.PLAIN
    }
    val weightText = weightKg?.takeIf { it > 0f }?.let { FormatUtils.formatKgSmart(it, compact = true) }
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

@Composable
private fun RefreshChipDetail(enabled: Boolean, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors(),
        label = {
            Text(
                text = "↻  Refresh",
                color = MaterialTheme.colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

/**
 * Unified exercise row for WorkoutDetailScreen. Tap toggles the "Last session"
 * breakdown (only one row open at a time, owned by the parent). When
 * [backgroundColor] is non-null the chip uses that tint to convey status;
 * null falls back to the default secondary chip colour for the no-routine
 * fallback path.
 */
@Composable
private fun ExerciseRow(
    title: String,
    kind: ChipKind?,
    stats: ExerciseChipStats?,
    subtitle: String?,
    backgroundColor: Color?,
    lastSessionEntries: List<ExerciseHistoryEntry>,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val chipColors = backgroundColor
        ?.let { ChipDefaults.chipColors(backgroundColor = it) }
        ?: ChipDefaults.secondaryChipColors()

    Column(modifier = Modifier.fillMaxWidth()) {
        Chip(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = chipColors,
            label = { ExerciseChipLabel(title, kind) },
            secondaryLabel = {
                if (stats != null) {
                    ExerciseChipStatsRow(stats)
                } else {
                    Text(
                        text = subtitle.orEmpty(),
                        color = MaterialTheme.colors.onSecondary
                    )
                }
            }
        )

        if (isExpanded && lastSessionEntries.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = "Last session",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
                LastSessionStats.avgNormalWeightKg(lastSessionEntries)?.let { avg ->
                    Text(
                        text = "avg ${FormatUtils.formatKg(avg, compact = true)}",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                lastSessionEntries.forEachIndexed { idx, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val typeLetter = when (entry.setType.lowercase()) {
                            "warmup"  -> " W"
                            "dropset" -> " D"
                            "failure" -> " F"
                            else      -> ""
                        }
                        Text(
                            text = "${idx + 1}$typeLetter",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSecondary
                        )
                        val parts = buildList {
                            entry.weightKg?.let { if (it > 0f) add(FormatUtils.formatKg(it, compact = true)) }
                            entry.reps?.let { if (it > 0) add("$it") }
                            entry.durationSeconds?.let { if (it > 0) add("${it}s") }
                            entry.distanceMeters?.let { if (it > 0f) add("${"%.0f".format(it)}m") }
                        }
                        Text(
                            text = parts.joinToString(" × ").ifEmpty { "—" },
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onBackground
                        )
                    }
                }
            }
        }
    }
}
