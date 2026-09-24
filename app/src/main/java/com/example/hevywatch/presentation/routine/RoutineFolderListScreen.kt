package com.example.hevywatch.presentation.routine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
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
import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.data.api.model.RoutineFolderResponse
import com.example.hevywatch.data.api.model.WorkoutSummaryResponse
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.components.FreshnessLine
import com.example.hevywatch.ui.components.PageIndicator
import com.example.hevywatch.ui.components.RefreshOverlay
import com.example.hevywatch.ui.components.RotatingLogo
import com.example.hevywatch.ui.components.slimButton
import com.example.hevywatch.ui.theme.ChipPalette
import com.example.hevywatch.util.DateFormatUtils

private const val SWIPE_THRESHOLD = 80f

@Composable
fun RoutineFolderListScreen(
    navController: NavController,
    viewModel: RoutineFolderListViewModel = viewModel()
) {
    val hevyApp = LocalContext.current.applicationContext as HevyApp

    LaunchedEffect(viewModel.navigateTo) {
        viewModel.navigateTo?.let { route ->
            navController.navigate(route)
            viewModel.onNavigated()
        }
    }

    AppScaffold(
        onTimerClick = { navController.navigate(Screen.LOG_WORKOUT) }
    ) {
        when {
            // First-ever load (no cache) — full-screen spinner.
            viewModel.isLoading && viewModel.folders.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    RotatingLogo()
                }
            }

            viewModel.error != null && viewModel.folders.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = viewModel.error ?: "Error",
                            style = MaterialTheme.typography.caption1,
                            color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.padding(4.dp))
                        Button(
                            onClick = viewModel::refreshFolders,
                            modifier = Modifier.slimButton
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            // The empty-folders branch has to ignore mid-refresh blanks. Even
            // though [refreshFolders] no longer wipes `folders`, we still
            // gate this defensively in case some future code path leaves
            // `folders` empty while a refresh is running — without the gate
            // the user briefly sees the "No folders found" message.
            viewModel.folders.isEmpty() && !viewModel.isRefreshingFolders -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No folders found.\nCreate one in the source app.",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            else -> {
                var currentPage by remember { mutableIntStateOf(0) }

                // Swipe detection: only consume swipe-left-to-next-page and
                // swipe-right-to-previous-page. On page 0 swiping right,
                // don't consume — lets SwipeDismissableNavHost handle back navigation.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(currentPage) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onDragEnd = {
                                    if (totalDrag < -SWIPE_THRESHOLD && currentPage == 0) {
                                        currentPage = 1
                                    } else if (totalDrag > SWIPE_THRESHOLD && currentPage == 1) {
                                        currentPage = 0
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    totalDrag += dragAmount
                                    if ((dragAmount < 0 && currentPage == 0) ||
                                        (dragAmount > 0 && currentPage == 1)) {
                                        change.consume()
                                    }
                                }
                            )
                        }
                ) {
                    when (currentPage) {
                        0 -> FoldersPage(
                            viewModel = viewModel,
                            hevyApp = hevyApp,
                            navController = navController,
                            currentPage = currentPage,
                            pageCount = 2,
                            onPageTap = { currentPage = it }
                        )
                        1 -> RecentWorkoutsPage(
                            recentWorkouts = viewModel.visibleRecentWorkouts,
                            onWorkoutClick = { viewModel.onWorkoutClick(it) },
                            onRefresh = { viewModel.refreshRecent() },
                            isRefreshing = viewModel.isRefreshingRecent,
                            refreshedAtMs = hevyApp.recentRefreshedAtMs,
                            currentPage = currentPage,
                            pageCount = 2,
                            onPageTap = { currentPage = it }
                        )
                    }
                }
            }
        }
    }
}

// ── Page 1: Folders ──────────────────────────────────────────────────────────

@Composable
private fun FoldersPage(
    viewModel: RoutineFolderListViewModel,
    hevyApp: HevyApp,
    navController: NavController,
    currentPage: Int,
    pageCount: Int,
    onPageTap: (Int) -> Unit
) {
    val listState = rememberScalingLazyListState()

    // Scroll back to the top on the isRefreshing → false transition. Works
    // because [RefreshOverlay] keeps the column mounted underneath, so
    // listState is always attached to a real layout when the call lands.
    LaunchedEffect(listState) {
        snapshotFlow { viewModel.isRefreshingFolders }
            .drop(1)
            .collect { refreshing ->
                if (!refreshing) listState.animateScrollToItem(0)
            }
    }

    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 28.dp, bottom = 16.dp)
        ) {
            item {
                PageIndicator(
                    currentPage = currentPage,
                    pageCount = pageCount,
                    onPageTap = onPageTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
            }

            item {
                Text(
                    text = "Folders",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.onPrimary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

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

            // Append the synthetic "Uncategorized" folder when there are
            // routines without a folder_id, then cap at the user's configured
            // limit (default FOLDERS_DISPLAYED_LIMIT = 5). Folders beyond the
            // cap stay reachable through the source app.
            val displayFolders = RoutineFolderListViewModel.displayedFolders(
                viewModel.folders, hevyApp.cachedRoutines,
                limit = hevyApp.displayLimitsStore.folderListLimit,
            )
            items(displayFolders) { folder ->
                FolderChip(
                    folder = folder,
                    routineCount = if (viewModel.routineCountsLoaded) {
                        viewModel.routineCountsByFolder[folder.id] ?: 0
                    } else null,
                    onClick = { viewModel.onFolderClick(folder.id) }
                )
            }

            item {
                RefreshChip(onClick = { viewModel.refreshFolders() })
            }

            item {
                // Folders Page 0 paints from two endpoints — /v1/routine_folders
                // (folder names) and /v1/routines (per-folder counts shown in
                // the chip subtitles). Pass both stamps so the caption falls
                // back to "various" if a stale routines fetch is still backing
                // the counts after a folder-only refresh.
                FreshnessLine(
                    hevyApp.foldersRefreshedAtMs,
                    hevyApp.routinesRefreshedAtMs,
                )
            }

            item {
                Chip(
                    onClick = { navController.navigate(Screen.SETTINGS) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors(),
                    label = {
                        Text(
                            text = "⚙  Settings",
                            color = MaterialTheme.colors.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }
        }

        PositionIndicator(scalingLazyListState = listState)

        if (viewModel.isRefreshingFolders) {
            RefreshOverlay()
        }
    }
}

/** Plain "↻ Refresh" chip — no inline spinner. The page-level full-screen
 *  spinner takes over when a refresh is in flight, so the chip never has to
 *  signal in-flight state itself. */
@Composable
private fun RefreshChip(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
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

// ── Page 2: Recent Workouts ──────────────────────────────────────────────────

@Composable
private fun RecentWorkoutsPage(
    recentWorkouts: List<WorkoutSummaryResponse>,
    onWorkoutClick: (String) -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    refreshedAtMs: Long,
    currentPage: Int,
    pageCount: Int,
    onPageTap: (Int) -> Unit
) {
    val listState = rememberScalingLazyListState()

    // `isRefreshing` is a Boolean *parameter* (not a state-backed property),
    // so reading it inside `snapshotFlow { ... }` directly captures the
    // value at LaunchedEffect-launch time — the flow then never re-emits
    // even when the parent's state flips. `rememberUpdatedState` wraps it
    // in a real `State<Boolean>` that's updated on every recomposition, so
    // snapshotFlow can subscribe to changes and fire the scroll-to-top.
    val refreshingState = rememberUpdatedState(isRefreshing)
    LaunchedEffect(listState) {
        snapshotFlow { refreshingState.value }
            .drop(1)
            .collect { refreshing ->
                if (!refreshing) listState.animateScrollToItem(0)
            }
    }

    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 28.dp, bottom = 16.dp)
        ) {
            item {
                PageIndicator(
                    currentPage = currentPage,
                    pageCount = pageCount,
                    onPageTap = onPageTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
            }

            item {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.onPrimary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (recentWorkouts.isEmpty()) {
                item {
                    Text(
                        text = "No recent workouts",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            items(recentWorkouts) { workout ->
                WorkoutChip(workout = workout, onClick = { onWorkoutClick(workout.id) })
            }

            item {
                RefreshChip(onClick = onRefresh)
            }

            item {
                FreshnessLine(refreshedAtMs)
            }
        }

        PositionIndicator(scalingLazyListState = listState)

        if (isRefreshing) {
            RefreshOverlay()
        }
    }
}

@Composable
private fun WorkoutChip(workout: WorkoutSummaryResponse, onClick: () -> Unit) {
    val dateText = run {
        // Show the workout's start_time — same field that drives the
        // page sort, so the visible date matches the row order.
        val ts = workout.startTime
        val instant = RoutineProgressComputer.parseInstant(ts)
        DateFormatUtils.formatListDateTime(instant, fallback = ts.take(16))
    }

    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors(),
        label = {
            Text(
                text = workout.title ?: "Workout",
                color = MaterialTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = {
            Text(
                text = dateText,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSecondary
            )
        }
    )
}

@Composable
private fun FolderChip(
    folder: RoutineFolderResponse,
    routineCount: Int?,
    onClick: () -> Unit
) {
    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors(),
        label = {
            Text(
                text = folder.title,
                color = MaterialTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = routineCount?.let {
            {
                Text(
                    text = if (it == 1) "1 routine" else "$it routines",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSecondary
                )
            }
        }
    )
}
