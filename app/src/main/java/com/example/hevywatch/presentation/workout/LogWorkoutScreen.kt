package com.example.hevywatch.presentation.workout

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.foundation.layout.PaddingValues
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import androidx.compose.ui.platform.LocalContext
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.LastSessionStats
import com.example.hevywatch.data.SuggestedWeight
import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.components.OfflineBanner
import com.example.hevywatch.ui.components.CompanionSyncBanner
import com.example.hevywatch.ui.components.RefreshErrorBanner
import com.example.hevywatch.ui.components.RotatingLogo
import com.example.hevywatch.ui.components.observableClick
import com.example.hevywatch.ui.components.observableClickable
import com.example.hevywatch.ui.components.slimButton
import com.example.hevywatch.ui.theme.ChipPalette
import com.example.hevywatch.ui.theme.BrandOrange
import com.example.hevywatch.ui.theme.hevyExtendedColors
import com.example.hevywatch.util.FormatUtils
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

@Composable
fun LogWorkoutScreen(navController: NavController) {
    val activity = LocalActivity.current as ComponentActivity
    val vm: LogWorkoutViewModel = viewModel(activity)

    LaunchedEffect(Unit) { vm.init() }

    // Coming back to the screen: if the workout has been sitting paused past
    // the threshold, prompt rather than silently leaving it paused (which is
    // how sessions end up stale and need the resume POST+DELETE cleanup).
    LaunchedEffect(Unit) { vm.checkAbandonment() }

    // JIT BODY_SENSORS prompt — fires when the VM signals on workout start
    // (user opted into HR in Settings but the runtime grant is missing).
    val bodySensorsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onBodySensorsResult(granted) }
    LaunchedEffect(vm.requestBodySensorsPermission) {
        if (vm.requestBodySensorsPermission) {
            bodySensorsLauncher.launch(android.Manifest.permission.BODY_SENSORS)
        }
    }

    // Stem button 2 is collected once at the NavHost level (see MainActivity)
    // so a press with LOG_SET stacked over LOG_WORKOUT doesn't navigate twice.

    LaunchedEffect(vm.navigateTo) {
        vm.navigateTo?.let { route ->
            navController.navigate(route)
            vm.onNavigated()
        }
    }

    val hevyApp = LocalContext.current.applicationContext as HevyApp

    var showFinishDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Suppress accidental swipe-back: a stray gesture mid-workout used to pop
    // the screen, leaving the in-progress sets behind a routine list. The
    // explicit Finish / Discard chips remain the only way out.
    BackHandler(enabled = vm.workout != null) { /* swallowed */ }

    val workout = vm.workout
    val listState = rememberScalingLazyListState()
    // Long-press a chip to expand its last-session breakdown; only one open at a time.
    var expandedExerciseId by remember { mutableStateOf<String?>(null) }

    if (workout == null || !vm.isHistoryLoaded) {
        // U1 — after 15 s without history loading, swap the spinner for a
        // visible error + retry chip. Most loads finish in 1-3 s on a healthy
        // BT-tethered link; 15 s implies the network is wedged and the user
        // would otherwise stare at the spinner indefinitely.
        var slowLoad by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try {
                withTimeout(15_000L) {
                    snapshotFlow { vm.isHistoryLoaded && vm.workout != null }
                        .first { it }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                slowLoad = true
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!slowLoad) {
                RotatingLogo()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "History load is slow.\nCheck your connection.",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            slowLoad = false
                            vm.init()
                        },
                        modifier = Modifier.slimButton,
                    ) { Text("Retry") }
                }
            }
        }
        return
    }

    // Takes priority over the other prompts: if the workout has been sitting
    // paused, deal with that before anything else.
    if (vm.showAbandonmentNudge) {
        AbandonmentDialog(
            onFinish = {
                vm.dismissAbandonmentNudge()
                vm.resumeWorkout()   // un-pause so the elapsed clock is sane
                vm.finishWorkout()
            },
            onKeepPaused = { vm.dismissAbandonmentNudge() },
            onDiscard = {
                vm.dismissAbandonmentNudge()
                val routineId = vm.workout?.routineId
                vm.clearWorkout()
                if (routineId != null) {
                    navController.popBackStack()
                } else {
                    navController.navigate(Screen.ROUTINE_FOLDERS) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            },
        )
        return
    }

    if (showFinishDialog) {
        ConfirmDialog(
            title = "Finish workout?",
            onConfirm = { showFinishDialog = false; vm.finishWorkout() },
            onCancel = { showFinishDialog = false }
        )
        return
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = "Discard workout?",
            onConfirm = {
                showDiscardDialog = false
                val routineId = vm.workout?.routineId
                vm.clearWorkout()
                if (routineId != null) {
                    // Pop LOG_WORKOUT → land on ROUTINE_DETAIL
                    navController.popBackStack()
                } else {
                    navController.navigate(Screen.ROUTINE_FOLDERS) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            },
            onCancel = { showDiscardDialog = false }
        )
        return
    }

    if (vm.showFallbackPrompt) {
        ConfirmDialog(
            title = vm.fallbackError ?: "Save via public API?",
            onConfirm = { vm.confirmFallbackToPublic() },
            onCancel = { vm.dismissFallbackPrompt() }
        )
        return
    }

    // Phase E — full-screen spinner while finishWorkout retries the
    // connectivity check (up to 10 s for a BT-tether bring-up).
    if (vm.isAwaitingConnectivity) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RotatingLogo(size = 40.dp)
                Spacer(Modifier.height(8.dp))
                Text("Connecting…", style = MaterialTheme.typography.caption1)
            }
        }
        return
    }

    // Phase E — 10 s retry expired. User confirms; workout exits but the
    // recovery file stays so the next launch can offer "Resume workout?".
    if (vm.showNoConnectivityWarning) {
        ConfirmDialog(
            title = "No connection — workout saved on-device. Tap OK to end recording and retry later.",
            onConfirm = { vm.confirmNoConnectivityExit() },
            onCancel = { vm.confirmNoConnectivityExit() }
        )
        return
    }

    if (vm.showWarmupAdvisorPrompt) {
        ConfirmDialog(
            title = "Override prescribed warmups with advisor?",
            onConfirm = { vm.confirmWarmupOverride() },
            onCancel = { vm.keepRoutineWarmups() }
        )
        return
    }

    // In-workout substitution: Y opens the Swap Exercise screen, N logs the
    // prescribed exercise as-is. Only armed for swap-eligible exercises
    // (see LogWorkoutViewModel.canSwap).
    if (vm.swapPromptExerciseIndex != null) {
        ConfirmDialog(
            title = "Swap exercise?",
            onConfirm = {
                vm.confirmSwapPrompt()
                navController.navigate(Screen.SWAP_EXERCISE)
            },
            onCancel = { vm.declineSwapPrompt() }
        )
        return
    }

    AppScaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        onTimerClick = { navController.navigate(Screen.WORKOUT_CONTROL) }
    ) {
        if (vm.isSaving) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Explicit size — fullscreen default would push the
                    // caption(s) below it off the visible area.
                    RotatingLogo(size = 40.dp)
                    Spacer(Modifier.height(8.dp))
                    val progress = vm.saveProgress
                    // Phase label: which path is being tried. Falls back to a
                    // bare "Saving…" before the first attempt is published.
                    Text(
                        text = when {
                            progress == null -> "Saving…"
                            progress.phase == SaveProgress.Phase.FALLBACK -> "Backup save"
                            else -> "Saving"
                        },
                        style = MaterialTheme.typography.caption1
                    )
                    progress?.let { p ->
                        // Attempt x/y so a stalled/retrying save is legible
                        // instead of an opaque spinner.
                        Text(
                            text = "Attempt ${p.attempt}/${p.maxAttempts}",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                        // Why the previous attempt failed (incl. HTTP code, e.g.
                        // "Rate limited (429)") — only once there's been a failure.
                        p.lastError?.let { reason ->
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
            return@AppScaffold
        }

        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 28.dp, bottom = 16.dp)
        ) {
            // Workout title
            item {
                Text(
                    text = workout.name,
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )
            }

            // Offline strip — only visible while the watch has no Internet-capable network
            item { OfflineBanner() }
            // Token-refresh-error strip — only visible while a refresh has failed
            item { RefreshErrorBanner() }
            // Watch-→-companion sync strip — only visible when a watch-initiated
            // refresh succeeded but the push to the companion failed.
            item { CompanionSyncBanner() }

            // Save error if any. U3 — banner auto-dismisses after 10 s so the
            // user isn't stuck staring at a stale "No completed sets" message
            // for an hour after switching exercises.
            vm.saveError?.let { err ->
                item {
                    LaunchedEffect(err) {
                        // A rejected request body stays put — see saveErrorSticky.
                        if (!vm.saveErrorSticky) {
                            kotlinx.coroutines.delay(10_000L)
                            vm.dismissSaveError()
                        }
                    }
                    Text(
                        text = err,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .observableClickable { vm.dismissSaveError() }
                    )
                }
            }

            // Something went wrong on a save that still succeeded — a failed
            // delete of the resumed original, say. Deliberately not styled as
            // an error: the workout IS saved, and colouring it red would send
            // the user hunting for lost data that is not lost. It still has to
            // appear, because "worked, but not entirely" is exactly the case
            // that otherwise goes unnoticed until Hevy shows a duplicate.
            vm.saveNotice?.let { notice ->
                item {
                    LaunchedEffect(notice) {
                        kotlinx.coroutines.delay(10_000L)
                        vm.dismissSaveNotice()
                    }
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .observableClickable { vm.dismissSaveNotice() }
                    )
                }
            }

            // Exercise chips
            itemsIndexed(workout.exercises) { exIdx, exercise ->
                val lastSessionEntries = LastSessionStats.latestSessionEntries(
                    hevyApp.exerciseHistoryCache[exercise.exerciseTemplateId]
                        ?.exerciseHistory.orEmpty()
                )
                ExerciseChip(
                    exercise = exercise,
                    targetWeightKg = exercise.sets.firstOrNull { it.setType == SetType.NORMAL }?.weightKg,
                    suggestedWeight = vm.exerciseSuggestedWeights[exercise.exerciseTemplateId],
                    poWillIncrease = exercise.exerciseTemplateId in vm.weightIncreasedExercises,
                    lastSessionEntries = lastSessionEntries,
                    isExpanded = expandedExerciseId == exercise.exerciseTemplateId,
                    onClick = { vm.onExerciseChipTap(exIdx) },
                    onLongClick = {
                        expandedExerciseId = if (expandedExerciseId == exercise.exerciseTemplateId) {
                            null
                        } else {
                            exercise.exerciseTemplateId
                        }
                    }
                )
            }

            // Finish button
            item {
                Button(
                    onClick = observableClick { showFinishDialog = true },
                    modifier = Modifier.fillMaxWidth(0.85f).slimButton,
                    colors = ButtonDefaults.primaryButtonColors(),
                    enabled = vm.hasAnyCompletedSets
                ) {
                    Text("Finish Workout")
                }
            }

            // Discard button
            item {
                Button(
                    onClick = observableClick { showDiscardDialog = true },
                    modifier = Modifier.fillMaxWidth(0.85f).slimButton,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text("Discard")
                }
            }
        }
    }
}

/**
 * Build the unified [ExerciseChipStats] for a live/resumed exercise chip. Done
 * counts are completed sets; totals are prescribed sets. Weight is the target
 * (green when PO-bumped, amber `~` when only a similar-exercise estimate). A
 * swapped-in exercise carries the `swap` tag. Shared rendering lives in
 * [ExerciseChipUi]; the post-workout counterpart is `chipStatsForStatus`.
 */
internal fun logWorkoutChipStats(
    exercise: ActiveExercise,
    targetWeightKg: Float?,
    suggestedWeight: SuggestedWeight?,
    poWillIncrease: Boolean
): ExerciseChipStats {
    val isSuggested = suggestedWeight != null && !poWillIncrease
    val weightText = when {
        targetWeightKg != null && targetWeightKg > 0f ->
            FormatUtils.formatKgSmart(targetWeightKg, compact = true)
        isSuggested && suggestedWeight != null ->
            "~" + FormatUtils.formatKg(suggestedWeight.weightKg, decimals = 0, compact = true)
        else -> null
    }
    val weightKind = when {
        poWillIncrease -> WeightKind.PO
        isSuggested -> WeightKind.ESTIMATE
        else -> WeightKind.PLAIN
    }
    return ExerciseChipStats(
        warmupDone = exercise.sets.count { it.setType == SetType.WARMUP && it.completed },
        warmupTotal = exercise.sets.count { it.setType == SetType.WARMUP },
        normalDone = exercise.sets.count { it.setType == SetType.NORMAL && it.completed },
        normalTotal = exercise.sets.count { it.setType == SetType.NORMAL },
        weightText = weightText,
        weightKind = if (weightText == null) WeightKind.PLAIN else weightKind,
        kind = if (exercise.wasSwapped) ChipKind.SWAP else null
    )
}

@Composable
internal fun ExerciseChip(
    exercise: ActiveExercise,
    targetWeightKg: Float?,
    suggestedWeight: SuggestedWeight? = null,
    poWillIncrease: Boolean,
    lastSessionEntries: List<ExerciseHistoryEntry>,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // Unified chip stats (shared with WorkoutDetailScreen via ExerciseChipUi):
    // green when every set is done, blue while partial, red before anything is
    // logged. A swapped-in exercise carries the `swap` tag.
    val stats = remember(
        exercise.sets, targetWeightKg, suggestedWeight, poWillIncrease, exercise.wasSwapped
    ) {
        logWorkoutChipStats(exercise, targetWeightKg, suggestedWeight, poWillIncrease)
    }
    val extended = hevyExtendedColors
    val chipColors = ChipDefaults.chipColors(
        backgroundColor = backgroundForChipState(stats.state, extended)
    )

    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Chip(
            onClick = observableClick(onClick),
            // Long-press detection layered on the Chip. We can't use
            // `detectTapGestures` here — the Chip's internal `Modifier.clickable`
            // consumes the down event on the Main pass before our outer detector
            // ever wakes up, so `onLongPress` would never fire. Instead, listen
            // on the Initial pass (outer-to-inner dispatch). On long-press,
            // consume the eventual up on the Initial pass too, so the Chip's
            // clickable sees a cancelled gesture and skips its own onClick when
            // the user lifts. Quick taps don't consume, so the Chip's onClick
            // (= openSet) still fires normally.
            //
            // Because we suppress the Chip's clickable on long-press, the
            // framework never emits an AccessibilityEvent for that path. Dispatch
            // TYPE_VIEW_LONG_CLICKED ourselves so external AccessibilityServices
            // (e.g. WearControl's power-profile telemetry) observe the gesture.
            modifier = Modifier
                .fillMaxWidth()
                // Bucket-G item 37 — visible accent border on the expanded
                // chip so the user knows the long-press registered and can
                // see at a glance which chip's per-set breakdown is open.
                // The haptic pulse (below) fires on long-press; this border
                // is the persistent visual signal. Border only draws when
                // isExpanded so unexpanded chips paint identically to before.
                .then(
                    if (isExpanded) Modifier.border(
                        BorderStroke(1.dp, BrandOrange),
                        RoundedCornerShape(percent = 50)
                    ) else Modifier
                )
                .pointerInput(exercise.exerciseTemplateId) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        try {
                            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                waitForUpOrCancellation(pass = PointerEventPass.Initial)
                            }
                            // Up arrived before timeout — tap; let Chip handle.
                        } catch (_: PointerEventTimeoutCancellationException) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                            onLongClick()
                            // Drain & consume until up so the Chip's clickable
                            // sees a cancelled press and doesn't fire onClick.
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    }
                },
            colors = chipColors,
            label = { ExerciseChipLabel(exercise.title, stats.kind) },
            secondaryLabel = { ExerciseChipStatsRow(stats) }
        )

        // Expanded: last-session per-set breakdown (mirrors RoutineDetailScreen).
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

@Composable
private fun ConfirmDialog(
    title: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.onPrimary,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = observableClick(onCancel),
                    modifier = Modifier.slimButton,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) { Text("N") }
                Button(
                    onClick = observableClick(onConfirm),
                    modifier = Modifier.slimButton
                ) { Text("Y") }
            }
        }
    }
}

/**
 * Post-pause abandonment nudge. Three outcomes rather than the usual Y/N, so
 * it uses a stacked chip list instead of [ConfirmDialog] — three full-width
 * rows read better than three squeezed buttons on a 320 px screen, and each
 * row is a comfortable tap target.
 */
@Composable
private fun AbandonmentDialog(
    onFinish: () -> Unit,
    onKeepPaused: () -> Unit,
    onDiscard: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Still training?",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.onPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "This workout has been paused a while.",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Chip(
                onClick = observableClick(onFinish),
                label = { Text("Finish now") },
                modifier = Modifier.fillMaxWidth(0.9f),
                colors = ChipDefaults.primaryChipColors(),
            )
            Chip(
                onClick = observableClick(onKeepPaused),
                label = { Text("Keep paused") },
                modifier = Modifier.fillMaxWidth(0.9f),
                colors = ChipDefaults.secondaryChipColors(),
            )
            Chip(
                onClick = observableClick(onDiscard),
                label = { Text("Discard") },
                modifier = Modifier.fillMaxWidth(0.9f),
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}
