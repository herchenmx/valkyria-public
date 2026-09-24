package com.example.hevywatch.presentation.routine

import androidx.compose.foundation.layout.Box
import com.example.hevywatch.presentation.navigation.Screen
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import com.example.hevywatch.ui.components.FreshnessLine
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.example.hevywatch.HevyApp
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.components.RotatingLogo
import com.example.hevywatch.ui.components.slimButton
import com.example.hevywatch.ui.theme.ChipPalette
import com.example.hevywatch.util.DateFormatUtils

@Composable
fun RoutineListScreen(
    folderId: String,
    navController: NavController,
    viewModel: RoutineListViewModel = viewModel()
) {
    val hevyApp = LocalContext.current.applicationContext as HevyApp

    LaunchedEffect(viewModel.navigateTo) {
        viewModel.navigateTo?.let { route ->
            navController.navigate(route)
            viewModel.onNavigated()
        }
    }

    // Filter routines that belong to the selected folder, sorted by last workout date ascending
    // (most recently worked routine at the bottom, never-worked routines at the top).
    // The synthetic UNCATEGORIZED_ID matches routines whose folderId is null.
    val filteredRoutines = remember(viewModel.routines, folderId, hevyApp.routineLastWorkoutAt) {
        val isUncategorized = folderId == RoutineFolderListViewModel.UNCATEGORIZED_ID
        viewModel.routines
            .filter { if (isUncategorized) it.folderId == null else it.folderId == folderId }
            .sortedBy { hevyApp.routineLastWorkoutAt[it.id] ?: "" }
    }

    val listState = rememberScalingLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { viewModel.isRefreshing }
            .drop(1)
            .collect { refreshing ->
                if (!refreshing) listState.animateScrollToItem(0)
            }
    }

    AppScaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        onTimerClick = { navController.navigate(Screen.LOG_WORKOUT) }
    ) {
        when {
            // First-ever load OR user-tapped refresh → full-screen spinner.
            (viewModel.isLoading && viewModel.routines.isEmpty()) || viewModel.isRefreshing -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    RotatingLogo()
                }
            }

            viewModel.error != null -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = viewModel.error ?: "Error",
                            style = MaterialTheme.typography.caption1,
                            color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                        Button(
                            onClick = viewModel::loadRoutines,
                            modifier = Modifier.slimButton
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            filteredRoutines.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No routines in this folder.",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            else -> {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    autoCentering = null,
                    contentPadding = PaddingValues(top = 28.dp, bottom = 16.dp)
                ) {
                    item {
                        Text(
                            text = "Routines",
                            style = MaterialTheme.typography.title3,
                            color = MaterialTheme.colors.onPrimary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    // Resume button if workout is active
                    if (hevyApp.activeWorkout != null) {
                        item {
                            Chip(
                                onClick = { navController.navigate(Screen.LOG_WORKOUT) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ChipDefaults.chipColors(backgroundColor = ChipPalette.ResumeChipBg),
                                label = {
                                    Text(
                                        text = "▶ Resume Workout",
                                        color = Color.White
                                    )
                                }
                            )
                        }
                    }

                    items(filteredRoutines) { routine ->
                        RoutineChip(
                            routine = routine,
                            onClick = { viewModel.onRoutineClick(routine.id) }
                        )
                    }

                    item {
                        Chip(
                            onClick = { viewModel.refresh() },
                            enabled = !viewModel.isRefreshing && !viewModel.isLoading,
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

                    item {
                        // Two independent sources: the routine list (routinesRefreshedAtMs)
                        // and the workout-history cache that powers the per-routine "last
                        // worked" date subtitle (recentRefreshedAtMs). When they disagree
                        // the caption renders "various" so the user knows the names and
                        // the dates are from different fetches.
                        FreshnessLine(
                            hevyApp.routinesRefreshedAtMs,
                            hevyApp.recentRefreshedAtMs,
                        )
                    }

                    // "Use this folder for the tile" chip — sets the
                    // TilePreferenceStore so the home tile renders this
                    // folder's routines. Hidden for the synthetic
                    // Uncategorized folder (the tile's filter wouldn't make
                    // sense). Disabled when this is already the tile's
                    // folder.
                    if (folderId != RoutineFolderListViewModel.UNCATEGORIZED_ID) {
                        item {
                            val isCurrent = hevyApp.tilePreferenceStore.tileFolderId == folderId
                            Chip(
                                onClick = {
                                    hevyApp.tilePreferenceStore.tileFolderId = folderId
                                    hevyApp.requestTileUpdate()
                                },
                                enabled = !isCurrent,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ChipDefaults.secondaryChipColors(),
                                label = {
                                    Text(
                                        text = if (isCurrent) "✓ Tile folder" else "Use for tile",
                                        color = MaterialTheme.colors.onSurface,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineChip(routine: Routine, onClick: () -> Unit) {
    val context = LocalContext.current
    val hevyApp = remember(context) { context.applicationContext as HevyApp }
    val lastWorkout = hevyApp.routineLastWorkoutAt[routine.id]

    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors(),
        label = {
            Text(
                text = routine.title,
                color = MaterialTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = {
            val count = routine.exerciseCount
            val countStr = "$count exercise${if (count == 1) "" else "s"}"
            val dateStr = lastWorkout?.let { " · ${formatWorkoutDate(it)}" } ?: ""
            Text(
                text = "$countStr$dateStr",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSecondary
            )
        }
    )
}

private fun formatWorkoutDate(isoTimestamp: String): String {
    // Match the Recent page (RoutineFolderListScreen) and the Progress page
    // (RoutineDetailScreen): "MMM d, ''yy" via DateFormatUtils.formatListDate,
    // so every workout-date subtitle in the app reads the same.
    val instant = RoutineProgressComputer.parseInstant(isoTimestamp)
    return DateFormatUtils.formatListDate(instant, fallback = isoTimestamp.take(10))
}
