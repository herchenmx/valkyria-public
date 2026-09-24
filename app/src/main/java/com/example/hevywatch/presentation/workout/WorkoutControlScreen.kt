package com.example.hevywatch.presentation.workout

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.components.slimButton

@Composable
fun WorkoutControlScreen(navController: NavController) {
    val activity = LocalActivity.current as ComponentActivity
    val vm: LogWorkoutViewModel = viewModel(activity)

    // Stem-button-2 gateway between "pause workout?" (running) and "resume workout?"
    // (paused). Both prompts follow the app-wide N/Y convention: N closes the prompt
    // without doing anything, Y applies the action and returns to the previous screen.
    // Discarding a workout is reached exclusively via the Discard button on
    // LogWorkoutScreen — not from here.

    AppScaffold {
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
                    text = if (vm.isPaused) "Resume workout?" else "Pause workout?",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = vm.workout?.name ?: "",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.slimButton,
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) { Text("N") }
                    Button(
                        onClick = {
                            if (vm.isPaused) vm.resumeWorkout() else vm.pauseWorkout()
                            navController.popBackStack()
                        },
                        modifier = Modifier.slimButton,
                        colors = ButtonDefaults.primaryButtonColors()
                    ) { Text("Y") }
                }
            }
        }
    }
}
