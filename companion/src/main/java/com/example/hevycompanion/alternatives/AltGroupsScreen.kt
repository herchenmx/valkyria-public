package com.example.hevycompanion.alternatives

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.generate.EquipmentMap
import com.example.hevycompanion.muscle.ExerciseAvatarContent
import com.example.hevycompanion.muscle.ExerciseAvatarPreview
import com.example.hevycompanion.muscle.MuscleAssetMap

/**
 * "Exercise Alternatives" — a browsable view of the curated substitution groups
 * (the same groups the watch/companion use to accept a swapped-in exercise as
 * completing a routine slot). Each group is a card of interchangeable exercises
 * shown with their catalogue pictures: tap an exercise to open its detail,
 * long-press for the demo-clip preview overlay.
 *
 * Read-only and public-api backed, so it works regardless of login state.
 */
@Composable
fun AltGroupsScreen(
    vm: AltGroupsViewModel,
    onBack: () -> Unit,
    onExerciseTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(12.dp))
            Text("Exercise Alternatives", style = MaterialTheme.typography.titleLarge)
        }

        when {
            vm.isLoading && vm.groups.isEmpty() -> Centered {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            }
            vm.error != null && vm.groups.isEmpty() -> Centered {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(vm.error!!, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { vm.retry() }) { Text("Retry") }
                }
            }
            else -> GroupsList(vm.groups, onExerciseTap)
        }
    }
}

/** Fills the remaining column height under the header and centres its content
 *  (matches the Browser / Strength-Overview centred-state idiom). */
@Composable
private fun ColumnScope.Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun GroupsList(groups: List<AltGroup>, onExerciseTap: (String) -> Unit) {
    var previewing by remember { mutableStateOf<ExerciseTemplate?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        groups.forEachIndexed { i, group ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = if (i == 0) 8.dp else 0.dp),
                    ) {
                        Text(
                            text = group.heading,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${group.exercises.size} exercises",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    group.exercises.forEach { ex ->
                        AltExerciseRow(
                            ex = ex,
                            onTap = { onExerciseTap(ex.id) },
                            onLongPress = {
                                previewing = ExerciseTemplate(
                                    id = ex.id,
                                    title = ex.title,
                                    primaryMuscleGroup = ex.primaryMuscleGroup,
                                    equipment = ex.equipment,
                                )
                            },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.size(16.dp)) }
    }

    previewing?.let { ex -> ExerciseAvatarPreview(ex, onDismiss = { previewing = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AltExerciseRow(
    ex: AltExercise,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                ExerciseAvatarContent(
                    exerciseTemplateId = ex.id,
                    exerciseTitle = ex.title,
                    placeholderTextStyle = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ex.title, style = MaterialTheme.typography.titleMedium)
                val sub = listOfNotNull(
                    ex.equipment?.takeIf { it.isNotBlank() }?.let { EquipmentMap.displayLabel(it) },
                    ex.primaryMuscleGroup?.takeIf { it.isNotBlank() }?.let { MuscleAssetMap.displayName(it) },
                ).joinToString(" • ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
