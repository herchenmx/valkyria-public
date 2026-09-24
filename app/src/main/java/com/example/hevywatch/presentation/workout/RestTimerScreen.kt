package com.example.hevywatch.presentation.workout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.activity.ComponentActivity
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.components.observableClick
import com.example.hevywatch.ui.components.slimButton
import com.example.hevywatch.ui.theme.ChipPalette
import com.example.hevywatch.ui.theme.BrandOrange

@Composable
fun RestTimerScreen(
    seconds: Int,
    navController: NavController,
    viewModel: RestTimerViewModel = viewModel(LocalActivity.current as ComponentActivity)
) {
    // RestTimerViewModel is now activity-scoped (was NavBackStackEntry-scoped),
    // so its tick coroutine survives navigation away from RestTimerScreen. That
    // lets the countdown haptic fire even if the user is on LogSetScreen /
    // LogWorkoutScreen when the rest period ends — matches Wear OS UX
    // expectations. shouldFireHaptic() in the VM gates on app foreground +
    // active workout so we don't buzz on background or post-workout state.

    // Screen-on + dim is handled app-wide by WorkoutDisplayController for the
    // duration of the workout — no per-screen FLAG_KEEP_SCREEN_ON needed here.

    LaunchedEffect(Unit) { viewModel.start(seconds) }

    // Stem button 2 is collected once at the NavHost level (see MainActivity)
    // so a press with the rest timer stacked over LogSet doesn't navigate twice.

    LaunchedEffect(viewModel.navigateBack) {
        if (viewModel.navigateBack) {
            navController.popBackStack()
            viewModel.onNavigated()
        }
    }

    // Read next set info from the active workout
    val logVm: LogWorkoutViewModel = viewModel(LocalActivity.current as ComponentActivity)
    val nextSet = logVm.currentSet
    // Show the PLATE portion (total − base) so the preview matches the LogSet
    // picker the user is about to see — they load plates for the next set during
    // rest. base is 0 for free weights / undecided exercises, so this is a no-op
    // there. Stored weight stays true-total.
    val nextBaseKg = logVm.currentExercise?.baseResistanceKg ?: 0f
    // ...and in the same lens: on a barbell with a known bar weight the LogSet
    // picker counts plates PER SIDE, so the preview does too. Showing the plate
    // total here and half of it on the next screen would be worse than showing
    // neither — rest is exactly when the plates go on.
    val nextPerSide = usesPerSideDisplay(
        logVm.currentExercise?.equipment,
        logVm.currentExercise?.baseResistanceKg
    )
    val nextWeightKg = nextSet?.weightKg?.let {
        if (nextPerSide) perSideKg(it, nextBaseKg) else (it - nextBaseKg).coerceAtLeast(0f)
    }
    // Mirror the LogSet picker's initial-rep rule so the next-set preview
    // shown under the timer ring matches the value the picker will land on
    // when the timer ends. Previously this read `nextSet.reps` raw, which
    // (a) showed 10 for the first normal set of a PO routine that prescribes
    // 10-15 and (b) carried the previously-logged reps forward for later
    // normal sets in that exercise — both diverging from the LogSet picker's
    // PO floor of 15 reps.
    val isPoRoutine = logVm.workout?.progressiveOverload == true
    val nextReps = nextSet?.let { initialRepsForSet(it, isPoRoutine) }
    val nextWeightText = run {
        val weightStr = if (nextWeightKg != null && nextWeightKg > 0f) {
            val fmt = when {
                nextWeightKg == nextWeightKg.toLong().toFloat() -> "%.0f"
                // A per-side figure can carry a quarter (11.25 a side off a
                // 22.5 kg plate portion); one decimal would round it away.
                nextPerSide -> "%.${perSideDecimals(nextWeightKg)}f"
                else -> "%.1f"
            }
            "${fmt.format(nextWeightKg)}kg"
        } else null
        val repsStr = nextReps?.takeIf { it > 0 }?.toString()
        when {
            weightStr != null && repsStr != null -> "$weightStr × $repsStr"
            weightStr != null -> weightStr
            repsStr != null -> "$repsStr reps"
            else -> null
        }
    }
    val nextWeightColor = when {
        nextSet?.poBaseWeightKg != null -> ChipPalette.ConfirmGreen  // PO green
        // Amber (ChipPalette.WarmupAmber) so advisor / warmup hints don't
        // visually merge with the BrandOrange rest-timer accent.
        nextSet?.isSimilarSuggestion == true -> ChipPalette.WarmupAmber
        nextSet?.setType == SetType.WARMUP -> ChipPalette.WarmupAmber
        else -> MaterialTheme.colors.onSurface
    }

    AppScaffold(onTimerClick = { navController.navigate(Screen.WORKOUT_CONTROL) }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 12.dp)
        ) {
            // −15 and +15 are anchored to the two edges and the ring is centred
            // on the screen independently, rather than the three sitting in a
            // Row. A Row lays its children out from the start edge, so the
            // moment their combined width exceeds the display the row can no
            // longer be centred: it anchors left, overflows right, and drags
            // the ring off-centre with it. That is what bumping the buttons
            // 40dp -> 44dp did on the 320px Scallop 2 screen. Anchoring makes
            // the ring's position independent of whatever the buttons measure,
            // so it stays dead centre at any button size or font scale.
            CompactButton(
                onClick = observableClick { viewModel.subtractTime(15) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(44.dp),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text("−15", style = MaterialTheme.typography.caption2)
            }

            CompactButton(
                onClick = observableClick { viewModel.addTime(15) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(44.dp),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text("+15", style = MaterialTheme.typography.caption2)
            }

            // Timer ring — centred on the screen, not relative to the buttons
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(100.dp)
            ) {
                CircularProgressIndicator(
                    progress = viewModel.progress,
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 6.dp,
                    indicatorColor = BrandOrange,
                    trackColor = MaterialTheme.colors.onSecondary.copy(alpha = 0.2f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = viewModel.formattedTime,
                        style = MaterialTheme.typography.display3,
                        textAlign = TextAlign.Center
                    )
                    if (nextWeightText != null) {
                        Text(
                            text = nextWeightText,
                            style = MaterialTheme.typography.caption1,
                            color = nextWeightColor,
                            textAlign = TextAlign.Center
                        )
                        // Own line rather than a "/side" suffix: the preview sits
                        // inside the 100dp timer ring, where the suffix would run
                        // the weight × reps line past the stroke.
                        if (nextPerSide && (nextWeightKg ?: 0f) > 0f) {
                            Text(
                                text = "per side",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // U4 — "ready?" instead of the older "rest" label.
                        // "rest" reads as a noun (rest period), so a user
                        // sitting on the timer thought their next set hadn't
                        // been queued. "ready?" reads as a prompt and makes
                        // it clear the next set is queued, just waiting on
                        // the timer.
                        Text(
                            text = "ready?",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSecondary
                        )
                    }
                }
            }

            // Skip pinned to bottom
            Button(
                onClick = observableClick { viewModel.dismiss() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.55f)
                    .slimButton,
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text("Skip")
            }
        }
    }
}
