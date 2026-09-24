package com.example.hevywatch.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.presentation.workout.LogWorkoutViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Phase C (+ post-install tuning) — per-window screen-brightness control that
 * applies **app-wide** whenever Hevy is in the foreground. The default (dim)
 * and max (bright-on-touch) levels, plus the idle-to-dim duration, are
 * user-tunable on the Settings screen (`BrightnessSettingsStore`).
 *
 * Cohorts:
 *   - **AlwaysBright** (workout-only exception): LogSetScreen on a WARMUP
 *     set, or rest-timer screen when the just-completed set was a WARMUP.
 *     Warmup choreography needs sharp visibility for plate-loading /
 *     dumbbell selection and the user doesn't want it dimming on them
 *     mid-setup. Brightness = max, no idle ramp.
 *   - **AllowDim** (every other foreground screen, workout or not): touch
 *     ramps to max, then drops back to default after the configured idle
 *     window. Covers ModeSelection, RoutineFolders, RoutineList,
 *     RoutineDetail, LogWorkout overview, LogSet on a normal set,
 *     RestTimer between normal sets, Congrats, Settings.
 *
 * Brightness is applied via [android.view.WindowManager.LayoutParams.screenBrightness];
 * no permission required (we only override our own window). On dispose the
 * override is reset to `BRIGHTNESS_OVERRIDE_NONE` so the user's system
 * brightness governs once Hevy leaves the foreground.
 */

internal enum class Cohort { AlwaysBright, AllowDim }

/**
 * Pure cohort decision — extracted so the per-screen rules can be tested
 * without instantiating the full Compose tree. Inputs:
 *   - [hasActiveWorkout]: HevyApp.activeWorkout != null
 *   - [currentRoute]: NavController's current destination route
 *   - [currentSetType]: type of the *current* set (used on LogSetScreen)
 *   - [lastCompletedSetType]: type of the *just-completed* set (used on
 *     the rest-timer screen — warmup → anything stays bright)
 */
internal fun brightnessCohort(
    hasActiveWorkout: Boolean,
    currentRoute: String?,
    currentSetType: SetType?,
    lastCompletedSetType: SetType?,
): Cohort {
    if (hasActiveWorkout && currentRoute != null) {
        if (currentRoute.startsWith("rest_timer") && lastCompletedSetType == SetType.WARMUP) {
            return Cohort.AlwaysBright
        }
        if (currentRoute == Screen.LOG_SET && currentSetType == SetType.WARMUP) {
            return Cohort.AlwaysBright
        }
    }
    return Cohort.AllowDim
}

@Composable
fun BrightnessCoordinator(
    navController: NavController,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val hevyApp = remember { context.applicationContext as HevyApp }
    val activity = LocalActivity.current as ComponentActivity
    val logVm: LogWorkoutViewModel = viewModel(activity)
    val settings = hevyApp.brightnessSettingsStore

    val backStackEntry by navController.currentBackStackEntryFlow.collectAsState(initial = null)
    val currentRoute = backStackEntry?.destination?.route
    val activeWorkout = hevyApp.activeWorkout

    val cohort: Cohort = brightnessCohort(
        hasActiveWorkout = activeWorkout != null,
        currentRoute = currentRoute,
        currentSetType = logVm.currentSet?.setType,
        lastCompletedSetType = logVm.lastCompletedSetType,
    )

    val defaultBrightness = settings.defaultBrightness
    val maxBrightness = settings.maxBrightness
    val idleMs = settings.idleSeconds.toLong() * 1000L

    var lastTouchMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isDimmed by remember { mutableStateOf(false) }

    // Idle-to-dim, driven reactively instead of by a polling loop. The old
    // version woke every 250 ms for the *entire* workout (4 Hz × ~1 h); this
    // observes touches via snapshotFlow and arms a single delay per touch,
    // so a resting (untouched) screen costs zero wakeups until it dims once.
    // collectLatest cancels the pending delay the moment a new touch arrives.
    // snapshotFlow reads lastTouchMs without forcing recomposition (unlike a
    // LaunchedEffect key would).
    LaunchedEffect(cohort, idleMs) {
        if (cohort != Cohort.AllowDim) {
            isDimmed = false
            return@LaunchedEffect
        }
        snapshotFlow { lastTouchMs }.collectLatest { touchMs ->
            isDimmed = false
            val remaining = idleMs - (System.currentTimeMillis() - touchMs)
            if (remaining > 0) delay(remaining)
            isDimmed = true
        }
    }

    val targetBrightness: Float = when (cohort) {
        Cohort.AlwaysBright -> maxBrightness
        Cohort.AllowDim -> if (isDimmed) defaultBrightness else maxBrightness
    }

    DisposableEffect(targetBrightness) {
        val lp = activity.window.attributes
        lp.screenBrightness = targetBrightness
        activity.window.attributes = lp
        onDispose {
            val out = activity.window.attributes
            out.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = out
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // PointerEventPass.Initial: observe events *before* children
                // get them, without consuming. Every pointer down / move /
                // scroll updates the last-touch timestamp; children still
                // receive the same events as normal.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouchMs = System.currentTimeMillis()
                        if (isDimmed) isDimmed = false
                    }
                }
            }
    ) {
        content()
    }
}
