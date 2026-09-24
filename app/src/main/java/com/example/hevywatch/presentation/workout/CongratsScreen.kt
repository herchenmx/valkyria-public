package com.example.hevywatch.presentation.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.hevywatch.ui.theme.hevyExtendedColors
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import com.example.hevywatch.HevyApp
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.components.slimButton
import com.example.hevywatch.ui.theme.BrandOrange

@Composable
fun CongratsScreen(navController: NavController) {
    val context = LocalContext.current
    val hevyApp = remember { context.applicationContext as HevyApp }
    val summary = hevyApp.lastWorkoutSummary
    val listState = rememberScalingLazyListState()

    // Read here, in the composable body: a ScalingLazyColumn's content lambda is
    // a LazyListScope builder, not a @Composable scope, so remember() cannot be
    // called inside it. Read once and cleared, so it reports for this workout
    // only -- without it a working fallback hides a broken primary path
    // indefinitely: the workout saves, nothing is shown, the fault goes unseen.
    val submitDiagnostic = remember { hevyApp.lastSubmitDiagnostic?.also { hevyApp.lastSubmitDiagnostic = null } }

    AppScaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 32.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (submitDiagnostic != null) {
                item {
                    Text(
                        text = "Saved, but: $submitDiagnostic",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Title
            item {
                Text(
                    text = "Nice Work!",
                    style = MaterialTheme.typography.title2,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }

            if (summary != null) {
                // Workout name
                item {
                    Text(
                        text = summary.title,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                }

                // Stats
                item { StatRow("DURATION", summary.durationFormatted) }
                item { StatRow("VOLUME",   summary.volumeKg) }
                item { StatRow("SETS",     summary.totalSets.toString()) }

                // Exercise breakdown — each row: title, optional PR badge, completed-set count
                items(summary.exercises) { ex ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ex.title,
                            style = MaterialTheme.typography.caption2,
                            color = BrandOrange,
                            modifier = Modifier.weight(1f)
                        )
                        ex.pr?.let { pr ->
                            PrBadge(pr)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = "${ex.completedSets} set${if (ex.completedSets == 1) "" else "s"}",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSecondary
                        )
                    }
                }
            }

            // Done button
            item {
                Button(
                    onClick = {
                        navController.navigate(Screen.ROUTINE_FOLDERS) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.8f).slimButton.padding(top = 8.dp)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun PrBadge(pr: PrType) {
    val ext = hevyExtendedColors
    Text(
        text = pr.label,
        style = MaterialTheme.typography.caption3,
        color = ext.prBadgeFg,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ext.prBadgeBg)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onBackground,
            fontWeight = FontWeight.Bold
        )
    }
}
