package com.example.hevycompanion.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.hevycompanion.generate.mm.MmExercise

/**
 * Detail page for an M&M exercise: looping demo mp4 at the top, the row's
 * full taxonomy (area, sub-areas, equipment, movement pattern, category,
 * type, target / synergist / stabilizer muscles), and a "Similar exercises"
 * section ranked by [MmSimilarExercises].
 *
 * Tapping a row in the similar list re-targets the detail screen at that
 * exercise — same in-place navigation as the Hevy detail screen, so the
 * list view-model owns the current selection and there's no nav stack to
 * unwind on back press.
 */
@Composable
fun MmExerciseDetailScreen(
    target: MmExercise,
    catalog: List<MmExercise>,
    onBack: () -> Unit,
    onSimilarTap: (MmExercise) -> Unit,
    modifier: Modifier = Modifier,
) {
    val similar = remember(target.id, catalog) {
        MmSimilarExercises.find(target, catalog)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                OutlinedButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.width(12.dp))
                Text(target.name, style = MaterialTheme.typography.titleLarge)
            }
        }

        item { MmMediaBox(target) }

        item { MmDetailsBlock(target) }

        item {
            HorizontalDivider()
            Text(
                "Similar exercises",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (similar.isEmpty()) {
            item {
                Text(
                    "No exercises in the same area share enough taxonomy to count as similar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(similar, key = { it.id }) { ex ->
                MmSimilarRow(ex, onClick = { onSimilarTap(ex) })
            }
        }

        item { Spacer(Modifier.size(16.dp)) }
    }
}

/**
 * Mp4 if available, else thumbnail, else "No preview" fallback. Matches
 * the long-press preview's chain so the visual stays consistent.
 */
@Composable
private fun MmMediaBox(target: MmExercise) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            target.videoUrl?.endsWith(".mp4") == true ->
                com.example.hevycompanion.ui.LoopingVideoPlayer(target.videoUrl)
            target.thumbnailUrl != null ->
                AsyncImage(
                    model = target.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            else -> Text(
                "No preview available",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MmDetailsBlock(target: MmExercise) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailRow("Area", target.area)
        target.subAreas.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.let { DetailRow("Sub-areas", it) }
        target.category.takeIf { it.isNotBlank() }
            ?.let { DetailRow("Category", it) }
        target.type?.takeIf { it.isNotBlank() }
            ?.let { DetailRow("Type", it.replaceFirstChar { c -> c.uppercase() }) }
        target.equipment.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.let { DetailRow("Equipment", it) }
        target.movementPattern.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.let { DetailRow("Movement", it) }
        target.targetMuscles.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.let { DetailRow("Target muscles", it) }
        target.synergistMuscles.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.let { DetailRow("Synergist muscles", it) }
        target.stabilizerMuscles.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.let { DetailRow("Stabilizer muscles", it) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MmSimilarRow(ex: MmExercise, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (ex.thumbnailUrl != null) {
                    AsyncImage(
                        model = ex.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        ex.name.firstOrNull()?.uppercase().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ex.name, style = MaterialTheme.typography.bodyLarge)
                val sub = listOfNotNull(
                    ex.equipment.firstOrNull(),
                    ex.movementPattern.firstOrNull(),
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

