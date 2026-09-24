package com.example.hevycompanion.browse

import android.net.Uri
import android.widget.VideoView
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.HevyExerciseAttrs
import com.example.hevycompanion.data.HevyVideoUrlMap
import com.example.hevycompanion.generate.EquipmentMap
import com.example.hevycompanion.muscle.ExerciseAvatarContent
import com.example.hevycompanion.muscle.MuscleAssetMap

/**
 * Detail page for a Hevy exercise: looping demo video at the top, taxonomy
 * details (equipment / muscle / type / level / category) below, and a
 * "Similar exercises" section listing other catalog rows that share the
 * same equipment AND primary muscle group — the same matching rule the
 * watch app uses for its weight-suggestion feature, see
 * [HevySimilarExercises].
 *
 * Tapping a row in the similar list re-targets the detail screen at that
 * exercise (the list view-model owns the current selection so we don't need
 * a navigation stack — selecting a sibling just swaps the id).
 */
@Composable
fun HevyExerciseDetailScreen(
    target: ExerciseTemplate,
    catalog: List<ExerciseTemplate>,
    attrs: HevyExerciseAttrs?,
    onBack: () -> Unit,
    onSimilarTap: (ExerciseTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val similar = remember(target.id, catalog) {
        HevySimilarExercises.find(target, catalog)
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
                Text(target.title, style = MaterialTheme.typography.titleLarge)
            }
        }

        item { ExerciseMediaBox(target) }

        item { ExerciseDetailsBlock(target, attrs) }

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
                    "No exercises in the catalog share this equipment and primary muscle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(similar, key = { it.id }) { ex ->
                SimilarExerciseRow(ex, onClick = { onSimilarTap(ex) })
            }
        }

        item { Spacer(Modifier.size(16.dp)) }
    }
}

/**
 * Looping mp4 if Hevy has one for this template, falling back to the static
 * avatar (Liftoff slug → Hevy CDN thumbnail → first-letter placeholder) so
 * the slot is never empty. Same fallback chain the long-press preview uses.
 */
@Composable
private fun ExerciseMediaBox(target: ExerciseTemplate) {
    val context = LocalContext.current
    val mediaUrl = remember(target.id) { HevyVideoUrlMap.urlFor(context, target.id) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            mediaUrl != null && mediaUrl.endsWith(".mp4") ->
                DetailLoopingVideo(mediaUrl)
            mediaUrl != null ->
                AsyncImage(
                    model = mediaUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            else -> ExerciseAvatarContent(
                exerciseTemplateId = target.id,
                exerciseTitle = target.title,
                placeholderTextStyle = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ExerciseDetailsBlock(target: ExerciseTemplate, attrs: HevyExerciseAttrs?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        target.equipment?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Equipment", EquipmentMap.displayLabel(it))
        }
        target.primaryMuscleGroup?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Primary muscle", MuscleAssetMap.displayName(it))
        }
        target.secondaryMuscleGroups
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { MuscleAssetMap.displayName(it) }
            ?.let { DetailRow("Secondary muscles", it) }
        target.type?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Type", it.replace('_', ' ').replaceFirstChar { c -> c.uppercase() })
        }
        attrs?.level?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
            ?.let { DetailRow("Level", it) }
        attrs?.category?.takeIf { it.isNotBlank() }
            ?.let { DetailRow("Category", it.replace('-', ' ').replaceFirstChar { c -> c.uppercase() }) }
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
private fun SimilarExerciseRow(ex: ExerciseTemplate, onClick: () -> Unit) {
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
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                ExerciseAvatarContent(
                    exerciseTemplateId = ex.id,
                    exerciseTitle = ex.title,
                    placeholderTextStyle = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(ex.title, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * VideoView-backed looping mp4. Same shape as the long-press preview's
 * player but kept private to the detail screen so we don't accidentally
 * couple two unrelated UIs through a shared helper.
 *
 * Wrapped in `key(url)` because tapping a similar exercise re-targets the
 * detail screen *in place*: same composable slot in the parent LazyColumn,
 * different URL. `AndroidView`'s `factory` lambda only runs once per slot,
 * so without re-keying the old `VideoView` would stay on screen until the
 * slot was disposed (e.g. by scrolling it off-screen and back). Re-keying
 * forces a fresh slot — old VideoView torn down via DisposableEffect, new
 * one created with the new URI.
 */
@Composable
private fun DetailLoopingVideo(url: String) {
    val context = LocalContext.current
    key(url) {
        val videoView = remember {
            VideoView(context).apply {
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    runCatching { mp.setVolume(0f, 0f) }
                    start()
                }
            }
        }
        DisposableEffect(videoView) { onDispose { videoView.stopPlayback() } }
        AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize())
    }
}
