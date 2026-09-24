package com.example.hevywatch.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.MaterialTheme
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults
import com.example.hevywatch.HevyApp
import com.example.hevywatch.presentation.workout.restRemainingSeconds
import com.example.hevywatch.ui.theme.BrandOrange
import com.example.hevywatch.util.FormatUtils
import kotlinx.coroutines.delay

/**
 * Applied to every Button modifier to cap height and produce a slimmer look.
 */
/** Tick cadence for the in-clock workout duration text. */
private const val WORKOUT_DURATION_TICK_MS: Long = 5_000L

val Modifier.slimButton: Modifier
    get() = then(
        Modifier.layout { measurable, constraints ->
            val maxH = 40.dp.roundToPx()
            val placeable = measurable.measure(constraints.copy(maxHeight = maxH))
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    )

/**
 * Replaces the default TimeText.
 * When a workout is active, prepends the session timer (blue = running, grey = paused)
 * to the system clock using TimeText's native startLinearContent / startCurvedContent.
 * This is the only reliable way to render in Scaffold's timeText slot.
 */
@Composable
fun WorkoutAwareTimeText() {
    val context = LocalContext.current
    val hevyApp = remember { context.applicationContext as HevyApp }
    val activeWorkout = hevyApp.activeWorkout
    val pausedAt = hevyApp.workoutPausedAt
    val isPaused = pausedAt != null

    var duration by remember { mutableStateOf("") }
    LaunchedEffect(activeWorkout?.startTimeMs, isPaused) {
        if (activeWorkout == null) { duration = ""; return@LaunchedEffect }
        if (isPaused) {
            duration = FormatUtils.formatDuration(activeWorkout.startTimeMs, pausedAt!!)
        } else {
            while (true) {
                duration = FormatUtils.formatDuration(activeWorkout.startTimeMs)
                // P3 — workout duration ticks every 5 s instead of every 1 s.
                // Wrist-glance resolution can't tell "5:21" from "5:22" anyway,
                // and over a 60-minute workout this is 12 recomposes/min instead
                // of 60. Compounds with the rest-timer 1 s cadence below for a
                // meaningful per-workout CPU/Compose saving.
                delay(WORKOUT_DURATION_TICK_MS)
            }
        }
    }

    // Rest timer countdown shown to the right of the clock
    val restEndMs = hevyApp.restTimerEndMs
    var restCountdown by remember { mutableStateOf("") }
    LaunchedEffect(restEndMs) {
        if (restEndMs == null) { restCountdown = ""; return@LaunchedEffect }
        while (true) {
            // restTimerEndMs is elapsedRealtime-based (see RestTimerViewModel).
            // Shared restRemainingSeconds() rounds identically to the rest-timer
            // screen so the two countdowns always show the same number.
            val now = SystemClock.elapsedRealtime()
            val remMs = restEndMs - now
            val remaining = restRemainingSeconds(restEndMs, now)
            if (remaining == 0) {
                restCountdown = ""
                hevyApp.restTimerEndMs = null
                break
            }
            restCountdown = "%d:%02d".format(remaining / 60, remaining % 60)
            // Re-tick at the next whole-second boundary rather than a fixed 1 s
            // cadence: a ± tap shifts the end time to an arbitrary sub-second phase,
            // and a fixed cadence would then flip this value up to ~1 s out of step
            // with the screen. +20 ms cushion guarantees we wake just past the
            // boundary instead of a hair before it (one wasted iteration).
            val toBoundary = remMs % 1000L
            delay((if (toBoundary <= 0L) 1000L else toBoundary) + 20L)
        }
    }

    val timerColor = if (isPaused) MaterialTheme.colors.onSecondary else BrandOrange
    val timerStyle = TimeTextDefaults.timeTextStyle(color = timerColor)
    val restStyle  = TimeTextDefaults.timeTextStyle(color = BrandOrange)

    // The center time-of-day uses this style; start/end content override it
    // with timerStyle/restStyle. Without an explicit colour the clock falls
    // back to Wear's fixed light default and is invisible on the light scheme.
    val clockStyle = TimeTextDefaults.timeTextStyle(color = MaterialTheme.colors.onBackground)

    if (activeWorkout != null) {
        TimeText(
            timeTextStyle = clockStyle,
            startLinearContent = {
                Text(text = duration, style = timerStyle)
            },
            startCurvedContent = {
                basicCurvedText(
                    text = duration,
                    style = CurvedTextStyle(color = timerColor, fontSize = timerStyle.fontSize)
                )
            },
            endLinearContent = if (restCountdown.isNotEmpty()) {
                { Text(text = restCountdown, style = restStyle) }
            } else null,
            endCurvedContent = if (restCountdown.isNotEmpty()) {
                {
                    basicCurvedText(
                        text = restCountdown,
                        style = CurvedTextStyle(color = BrandOrange, fontSize = restStyle.fontSize)
                    )
                }
            } else null
        )
    } else {
        // No active workout — plain clock, same themed style.
        TimeText(timeTextStyle = clockStyle)
    }
}

/**
 * App-wide Scaffold wrapper.
 * The onTimerClick tap is captured by a transparent Box overlay inside the content
 * (Scaffold's timeText layer does not propagate touch to the content layer below).
 */
@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    positionIndicator: @Composable () -> Unit = {},
    onTimerClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val hevyApp = remember { context.applicationContext as HevyApp }
    val workoutActive = hevyApp.activeWorkout != null

    Scaffold(
        modifier = modifier,
        timeText = { WorkoutAwareTimeText() },
        positionIndicator = positionIndicator
    ) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            content()
            // Transparent tap target over the TimeText row.
            // TimeText is display-only and does not consume pointer events,
            // so this overlay (drawn in the content layer) receives taps in that area.
            if (workoutActive && onTimerClick != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(30.dp)
                        .clickable { onTimerClick() }
                )
            }
        }
    }
}
