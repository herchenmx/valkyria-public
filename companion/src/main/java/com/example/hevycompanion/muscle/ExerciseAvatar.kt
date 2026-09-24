package com.example.hevycompanion.muscle

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hevycompanion.ui.LoopingVideoPlayer
import coil.compose.AsyncImage
import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.HevyImageUrlMap
import com.example.hevycompanion.data.HevyVideoUrlMap

/**
 * Renders an exercise avatar using a three-tier fallback chain:
 *
 *  1. **Bundled Liftoff avatar** (`liftoff_ex_<slug>` drawable) — preferred
 *     because it's an offline, guaranteed-available cartoon silhouette, and
 *     keeps the visual style consistent across the ~606 exercises Liftoff
 *     ships art for.
 *  2. **Hevy CDN thumbnail** (`HevyImageUrlMap.urlFor`) — when Liftoff has
 *     no slug match for this exercise. Especially important since the slug
 *     resolver now refuses to fall back to the bare-base avatar for
 *     equipment-qualified titles (e.g. "Bench Press (Cable)") to avoid
 *     showing a misleading wrong-equipment image. Coil loads the URL async
 *     and disk-caches it automatically.
 *  3. **First-letter placeholder** — only when BOTH the above miss. For
 *     the ~20 exercises where neither source has an image (cardio-style
 *     catch-alls like "Walking", "HIIT", "Cycling").
 *
 * Caller wraps this in their own `Box` so sizing / clipping / background
 * stay at the call site (list rows want 72 dp circles, the preview overlay
 * wants an 80%-screen rounded square — same content, different container).
 */
@Composable
fun ExerciseAvatarContent(
    exerciseTemplateId: String,
    exerciseTitle: String,
    placeholderTextStyle: TextStyle = MaterialTheme.typography.titleMedium,
    placeholderText: String = exerciseTitle.take(1).uppercase(),
) {
    val context = LocalContext.current
    val liftoffRes = remember(exerciseTitle) {
        LiftoffSlug.resolveAvatarResId(context, exerciseTitle)
    }
    val hevyUrl = remember(exerciseTemplateId, liftoffRes) {
        if (liftoffRes != 0) null
        else HevyImageUrlMap.urlFor(context, exerciseTemplateId)
    }
    when {
        liftoffRes != 0 -> Image(
            painter = painterResource(liftoffRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        hevyUrl != null -> AsyncImage(
            model = hevyUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        else -> Text(
            text = placeholderText,
            style = placeholderTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Press-and-hold preview overlay. Streams Hevy's demo-clip mp4 for the
 * exercise from the CDN (`HevyVideoUrlMap`), auto-playing on a loop on a
 * dimmed black background. When no video URL exists — or the `url` field
 * is actually a still image (cardio placeholders like "Air Bike", "Jump
 * Rope" — ~6 of the 412 catalogued entries) — falls back to the enlarged
 * static avatar (same Liftoff → Hevy thumbnail → placeholder chain as the
 * list-row avatar).
 *
 * Dismisses on tap-anywhere, long-press, or back press.
 *
 * Shared between the unified Browser (long-press an exercise row in the
 * Hevy list) and [com.example.hevycompanion.generate.GenerateWorkoutScreen]
 * (long-press a row in a generated workout).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExerciseAvatarPreview(ex: ExerciseTemplate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mediaUrl = remember(ex.id) { HevyVideoUrlMap.urlFor(context, ex.id) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .combinedClickable(onClick = onDismiss, onLongClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .padding(16.dp),
            ) {
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
                            LoopingVideoPlayer(mediaUrl)
                        mediaUrl != null ->
                            // .jpg/.png — Hevy's cardio placeholders.
                            AsyncImage(
                                model = mediaUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        else -> ExerciseAvatarContent(
                            exerciseTemplateId = ex.id,
                            exerciseTitle = ex.title,
                            placeholderTextStyle = MaterialTheme.typography.bodyLarge,
                            placeholderText = "No preview available",
                        )
                    }
                }
                Text(
                    ex.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                val sub = listOfNotNull(
                    ex.equipment?.takeIf { it.isNotBlank() }
                        ?.replaceFirstChar { it.uppercase() },
                    ex.primaryMuscleGroup?.takeIf { it.isNotBlank() }
                        ?.let { MuscleAssetMap.displayName(it) },
                ).joinToString(" • ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

