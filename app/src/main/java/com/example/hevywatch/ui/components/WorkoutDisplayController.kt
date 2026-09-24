package com.example.hevywatch.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.hevywatch.HevyApp

/**
 * Window-level screen-on policy while a workout is being recorded.
 *
 * Sets [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] whenever the user is
 * actively logging (workout exists AND not paused), so the display never
 * sleeps mid-set. Pausing the workout releases the flag so the OS ambient
 * timeout takes over — otherwise a workout paused to answer the phone
 * silently drains the battery until the user comes back.
 *
 * Brightness is owned by [BrightnessCoordinator], which is nested inside
 * this controller and applies the user's Settings-configured levels with
 * per-screen cohorts. Both controllers used to write `screenBrightness`
 * and raced each other; this file no longer touches brightness.
 */
@Composable
fun WorkoutDisplayController(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val hevyApp = remember(context) { context.applicationContext as HevyApp }
    val keepScreenOn = shouldKeepScreenOn(
        workoutActive = hevyApp.activeWorkout != null,
        isPaused = hevyApp.workoutPausedAt != null,
    )

    DisposableEffect(activity, keepScreenOn) {
        val window = activity?.window
        if (window != null && keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    content()
}

/**
 * Pure predicate — extracted so the pause-gate is unit-testable without
 * pulling in Compose or the window system.
 */
internal fun shouldKeepScreenOn(workoutActive: Boolean, isPaused: Boolean): Boolean =
    workoutActive && !isPaused
