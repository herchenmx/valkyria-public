package com.example.hevywatch.presentation.routine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import com.example.hevywatch.presentation.navigation.Screen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
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
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.data.model.RoutineExercise
import com.example.hevywatch.data.model.RoutineWorkoutVolume
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.theme.ChipPalette
import com.example.hevywatch.ui.components.FreshnessLine
import com.example.hevywatch.ui.components.PageIndicator
import com.example.hevywatch.ui.components.RefreshOverlay
import com.example.hevywatch.ui.components.RotatingLogo
import com.example.hevywatch.ui.components.slimButton
import com.example.hevywatch.util.DateFormatUtils
import java.time.Instant

private const val SWIPE_THRESHOLD = 80f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoutineDetailScreen(
    routineId: String,
    navController: NavController,
    viewModel: RoutineDetailViewModel = viewModel()
) {
    LaunchedEffect(routineId) {
        viewModel.load(routineId)
    }

    LaunchedEffect(viewModel.navigateTo) {
        viewModel.navigateTo?.let { route ->
            navController.navigate(route)
            viewModel.onNavigated()
        }
    }

    var showStartDialog by remember { mutableStateOf(false) }
    // currentPage MUST be declared before any potential early-return below.
    // If it's declared inside the `if (showProgress)` block (as previously)
    // and a refresh-driven recomposition causes early return BEFORE that
    // block runs, the slot for `remember { mutableIntStateOf(0) }` never
    // gets filled — and on the next composition where the if-block runs,
    // `remember` initializes fresh, dropping the user's page choice back to
    // 0. Hoisting it here means the slot is allocated unconditionally on
    // first composition and survives all subsequent early returns.
    var currentPage by remember { mutableIntStateOf(0) }
    val routine = viewModel.routine
    val hevyApp = LocalContext.current.applicationContext as HevyApp

    AppScaffold(
        onTimerClick = { navController.navigate(Screen.LOG_WORKOUT) }
    ) {
        // Full-screen spinner only on the very first load when there's no
        // cached routine to paint yet. A user-tapped Refresh is handled per-
        // page (ExercisesPage / ProgressPage own their own RefreshOverlay)
        // so currentPage state, expanded-row state, and listState all
        // survive the refresh — none of them get re-initialized by an
        // early-return swallowing their composable scope.
        if (viewModel.isLoading && routine == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RotatingLogo()
            }
            return@AppScaffold
        }

        if (routine == null) {
            // B5 — terminal "not found" state: give the user explicit Back and
            // Retry affordances rather than stranding them at a dead-end label
            // (e.g. when the routine was deleted server-side and our cache is
            // stale).
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Routine not found",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier.slimButton,
                            colors = ButtonDefaults.secondaryButtonColors()
                        ) { Text("Back") }
                        Button(
                            onClick = { viewModel.refresh() },
                            modifier = Modifier.slimButton
                        ) { Text("Retry") }
                    }
                }
            }
            return@AppScaffold
        }

        if (showStartDialog) {
            // Treat the dialog as its own back-stack layer: a swipe-back must
            // dismiss it, NOT pop all the way to the folder list. Without
            // this, the inline overlay is invisible to the navigation stack
            // and back goes straight past it. (Backlog: confirm-dialog
            // swipe-back behaviour.)
            BackHandler { showStartDialog = false }
            ConfirmStartDialog(
                routineTitle = routine.title,
                onConfirm = {
                    showStartDialog = false
                    viewModel.onStartWorkout()
                },
                onDismiss = { showStartDialog = false }
            )
            return@AppScaffold
        }

        if (viewModel.showProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentPage) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = {
                                when {
                                    totalDrag < -SWIPE_THRESHOLD && currentPage == 0 ->
                                        currentPage = 1
                                    totalDrag > SWIPE_THRESHOLD && currentPage == 1 ->
                                        currentPage = 0
                                    totalDrag > SWIPE_THRESHOLD && currentPage == 0 ->
                                        navController.popBackStack()
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                totalDrag += dragAmount
                                change.consume()
                            }
                        )
                    }
            ) {
                when (currentPage) {
                    0 -> ExercisesPage(
                        routine = routine,
                        viewModel = viewModel,
                        hevyApp = hevyApp,
                        currentPage = currentPage,
                        pageCount = 2,
                        onPageTap = { currentPage = it },
                        onRequestStartDialog = {
                            if (hevyApp.activeWorkout == null) showStartDialog = true
                        }
                    )
                    1 -> ProgressPage(
                        viewModel = viewModel,
                        refreshedAtMs = hevyApp.routineDetailRefreshedAtMs,
                        currentPage = currentPage,
                        pageCount = 2,
                        onPageTap = { currentPage = it }
                    )
                }
            }
        } else {
            // Non-PO routines: single page, no pager, no dots
            ExercisesPage(
                routine = routine,
                viewModel = viewModel,
                hevyApp = hevyApp,
                currentPage = 0,
                pageCount = 1,
                onPageTap = {},
                onRequestStartDialog = {
                    if (hevyApp.activeWorkout == null) showStartDialog = true
                }
            )
        }
    }
}

// ── Page 1: Exercises ────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExercisesPage(
    routine: Routine,
    viewModel: RoutineDetailViewModel,
    hevyApp: HevyApp,
    currentPage: Int,
    pageCount: Int,
    onPageTap: (Int) -> Unit,
    onRequestStartDialog: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    // Shared state for "only one exercise expanded at a time"
    var expandedExerciseId by remember { mutableStateOf<String?>(null) }

    // Scroll back to the top when a user-tapped refresh finishes. Tracking
    // viewModel.isRefreshing (a state-backed property) means snapshotFlow
    // subscribes correctly. Works because RefreshOverlay below keeps the
    // column mounted — listState is always attached to a real layout.
    LaunchedEffect(listState) {
        snapshotFlow { viewModel.isRefreshing }
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
            // Page dots (only when multiple pages)
            if (pageCount > 1) {
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
            }

            // Routine title (double-tap to start workout)
            item {
                Text(
                    text = routine.title,
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .combinedClickable(
                            onClick = {},
                            onDoubleClick = { onRequestStartDialog() }
                        )
                )
            }

            // Exercises — chips; tap to expand last session (only one expanded at a time)
            items(routine.exercises) { exercise ->
                // hevyApp.exerciseHistoryCache is a plain MutableMap (not
                // Compose state), so a refresh that mutates it doesn't
                // recompose the rows that read from it — visible rows kept
                // showing stale "Last session" data until the user scrolled
                // them off-screen and back. Keying the lookup on the
                // routineDetailRefreshedAtMs Compose state forces a re-read
                // (and re-derivation of `avgNormalWeightKg`) on every
                // successful refresh, so the visible rows update in place.
                val refreshKey = hevyApp.routineDetailRefreshedAtMs
                val lastSessionEntries = remember(exercise.exerciseTemplateId, refreshKey) {
                    LastSessionStats.latestSessionEntries(
                        hevyApp.exerciseHistoryCache[exercise.exerciseTemplateId]
                            ?.exerciseHistory.orEmpty()
                    )
                }
                ExerciseRow(
                    exercise = exercise,
                    lastSessionEntries = lastSessionEntries,
                    isExpanded = expandedExerciseId == exercise.exerciseTemplateId,
                    onClick = {
                        expandedExerciseId = if (expandedExerciseId == exercise.exerciseTemplateId) {
                            null
                        } else {
                            exercise.exerciseTemplateId
                        }
                    }
                )
            }

            // Resume button if a workout is active, otherwise Refresh
            if (viewModel.hasActiveWorkout) {
                item {
                    Button(
                        onClick = { viewModel.onStartWorkout() },
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ButtonDefaults.primaryButtonColors()
                    ) { Text("Resume Workout") }
                }
            }

            item {
                RefreshChipDetail(
                    enabled = !viewModel.isRefreshing && !viewModel.isLoading,
                    onClick = { viewModel.refresh() }
                )
            }

            item {
                FreshnessLine(hevyApp.routineDetailRefreshedAtMs)
            }
        }

        PositionIndicator(scalingLazyListState = listState)

        if (viewModel.isRefreshing) {
            RefreshOverlay()
        }
    }
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
                text = "\u21BB  Refresh",
                color = MaterialTheme.colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

// ── Page 2: Progress ─────────────────────────────────────────────────────────

@Composable
private fun ProgressPage(
    viewModel: RoutineDetailViewModel,
    refreshedAtMs: Long,
    currentPage: Int,
    pageCount: Int,
    onPageTap: (Int) -> Unit
) {
    val progress = viewModel.routineProgress
    val averageDeltaKg = viewModel.averageDeltaKg
    val refreshEnabled = !viewModel.isRefreshing && !viewModel.isLoading

    val listState = rememberScalingLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { viewModel.isRefreshing }
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
            // Page dots
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

            // Title
            item {
                Text(
                    text = "Progress",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Average delta subtitle
            item {
                val avgText = if (averageDeltaKg != null) {
                    val sign = if (averageDeltaKg >= 0) "+" else ""
                    "avg. $sign${"%.0f".format(averageDeltaKg)}kg"
                } else {
                    "no data yet"
                }
                Text(
                    text = avgText,
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }

            if (progress.isEmpty()) {
                item {
                    Text(
                        text = "Work out this routine\nto see progress",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            items(progress) { entry ->
                ProgressRow(entry)
            }

            item {
                RefreshChipDetail(
                    enabled = refreshEnabled,
                    onClick = { viewModel.refresh() }
                )
            }

            item {
                FreshnessLine(refreshedAtMs)
            }
        }

        PositionIndicator(scalingLazyListState = listState)

        if (viewModel.isRefreshing) {
            RefreshOverlay()
        }
    }
}

@Composable
private fun ProgressRow(entry: RoutineWorkoutVolume) {
    val dateText = run {
        val instant = RoutineProgressComputer.parseInstant(entry.date)
        DateFormatUtils.formatListDate(instant, fallback = entry.date.take(10))
    }

    val deltaText = when {
        entry.deltaKg == null -> "baseline"
        entry.deltaKg >= 0 -> "+${"%.0f".format(entry.deltaKg)}kg"
        else -> "${"%.0f".format(entry.deltaKg)}kg"
    }

    val deltaColor = when {
        entry.deltaKg == null -> MaterialTheme.colors.onSecondary
        entry.deltaKg >= 0 -> ChipPalette.DeltaPositive // green
        else -> ChipPalette.DeltaNegative // red
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dateText,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onBackground
        )
        Text(
            text = deltaText,
            style = MaterialTheme.typography.caption2,
            color = deltaColor
        )
    }
}

// ── Confirm Start Dialog ─────────────────────────────────────────────────────

@Composable
private fun ConfirmStartDialog(
    routineTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Start workout?",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.onPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = routineTitle,
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.slimButton,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text("N")
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.slimButton,
                    colors = ButtonDefaults.primaryButtonColors()
                ) {
                    Text("Y")
                }
            }
        }
    }
}

// ── Exercise Row (chip — tap to expand last session; only one expanded at a time) ──

@Composable
private fun ExerciseRow(
    exercise: RoutineExercise,
    lastSessionEntries: List<ExerciseHistoryEntry>,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val avgNormalWeight = LastSessionStats.avgNormalWeightKg(lastSessionEntries)

    Column(modifier = Modifier.fillMaxWidth()) {
        Chip(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ChipDefaults.secondaryChipColors(),
            label = {
                Text(
                    text = exercise.title,
                    color = MaterialTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            secondaryLabel = avgNormalWeight?.let { avg ->
                {
                    Text(
                        text = "avg ${com.example.hevywatch.util.FormatUtils.formatKg(avg, compact = true)}",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSecondary
                    )
                }
            }
        )

        // Expanded: last session stats
        if (isExpanded && lastSessionEntries.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = "Last session",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
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
                            entry.weightKg?.let { if (it > 0f) add(com.example.hevywatch.util.FormatUtils.formatKg(it, compact = true)) }
                            entry.reps?.let { if (it > 0) add("$it") }
                            entry.durationSeconds?.let { if (it > 0) add("${it}s") }
                            entry.distanceMeters?.let { if (it > 0f) add("${"%.0f".format(it)}m") }
                        }
                        Text(
                            text = parts.joinToString(" \u00D7 ").ifEmpty { "\u2014" },
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onBackground
                        )
                    }
                }
            }
        }
    }
}
