package com.example.hevywatch.presentation.workout

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.presentation.workout.WorkoutHistoryApplier.SwapCandidate
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.components.RotatingLogo
import com.example.hevywatch.ui.components.observableClick
import com.example.hevywatch.ui.theme.ChipPalette
import com.example.hevywatch.util.FormatUtils

/**
 * In-workout substitute picker. Reached only via the "Swap exercise? Y" prompt
 * on [LogWorkoutScreen] for swap-eligible exercises (PO folder, has substitutes,
 * not yet started). Lists each acceptable substitute with its own pre-computed
 * PO target weight; tapping one replaces the exercise in place (warmups + PO
 * weight applied) and jumps straight to logging it. State lives in the
 * activity-scoped [LogWorkoutViewModel].
 */
@Composable
fun SwapExerciseScreen(navController: NavController) {
    val activity = LocalActivity.current as ComponentActivity
    val vm: LogWorkoutViewModel = viewModel(activity)

    val prescribed = vm.workout?.exercises?.getOrNull(vm.swapExerciseIndex ?: -1)

    // Defensive: if we landed here with nothing armed (process death / stray
    // nav), bail back to the workout.
    LaunchedEffect(prescribed) {
        if (prescribed == null) navController.popBackStack()
    }

    LaunchedEffect(Unit) { vm.loadSwapCandidates() }

    val listState = rememberScalingLazyListState()

    /** Splice the substitute in (or keep the original) and go log it, dropping
     *  this screen from the back stack so a swipe-back lands on the workout. */
    fun goLog() {
        navController.navigate(Screen.LOG_SET) {
            popUpTo(Screen.LOG_WORKOUT)
        }
    }

    AppScaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        if (vm.isSwapLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RotatingLogo()
            }
            return@AppScaffold
        }

        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 28.dp, bottom = 16.dp)
        ) {
            item {
                Text(
                    text = "Swap",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.onPrimary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            item {
                Text(
                    text = "instead of ${prescribed?.title ?: ""}",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                )
            }

            items(vm.swapCandidates) { candidate ->
                SwapCandidateChip(
                    candidate = candidate,
                    onClick = { vm.applySwap(candidate); goLog() }
                )
            }

            item {
                Chip(
                    onClick = observableClick { vm.keepPrescribedFromSwap(); goLog() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors(),
                    label = {
                        Text(
                            text = "Keep prescribed",
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

@Composable
private fun SwapCandidateChip(
    candidate: SwapCandidate,
    onClick: () -> Unit
) {
    // Mirror the live-logging weight semantics: PO bump = green, similar
    // estimate = amber (~), last-session = default.
    val weightColor = when (candidate.source) {
        SwapCandidate.Source.PROGRESSIVE_OVERLOAD -> ChipPalette.ConfirmGreen
        SwapCandidate.Source.SIMILAR -> ChipPalette.WarmupAmber
        else -> MaterialTheme.colors.onSurface
    }
    val weightText = candidate.targetWeightKg?.let { w ->
        when (candidate.source) {
            SwapCandidate.Source.PROGRESSIVE_OVERLOAD -> "${FormatUtils.formatKg(w)} ↑"
            SwapCandidate.Source.SIMILAR -> "~${FormatUtils.formatKg(w)}"
            else -> FormatUtils.formatKg(w)
        }
    } ?: "—"

    Chip(
        onClick = observableClick(onClick),
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors(),
        label = {
            Text(
                text = candidate.title,
                color = MaterialTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = {
            Text(
                text = weightText,
                color = weightColor,
                maxLines = 1
            )
        }
    )
}
