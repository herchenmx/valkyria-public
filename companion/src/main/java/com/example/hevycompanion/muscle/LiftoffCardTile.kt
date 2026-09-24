package com.example.hevycompanion.muscle

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shared visual for a single Liftoff muscle tile. Composites the silhouette
 * tile and the muscle overlay (tinted by [MuscleAssetMap.tintFor]) and shows
 * a primary-coloured selection ring + corner ✓ badge when [isSelected].
 *
 * Used by:
 *  - [com.example.hevycompanion.browse.BrowserScreen]'s Muscle Grid view
 *  - [com.example.hevycompanion.muscle.MuscleSelectorScreen]'s multi-select picker
 *    (the Hevy generator's muscle step)
 *  - [com.example.hevycompanion.generate.mm.MmGenerateScreen]'s "Pick muscles"
 *    sub-mode in Setup (the M&M generator's muscle step)
 */
@Composable
fun LiftoffCardTile(
    card: LiftoffMuscleCard,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = Color(MuscleAssetMap.tintFor(card.hevyGroup))
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .let {
                    if (isSelected) it.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    ) else it
                }
                .clickable(onClick = onClick),
        ) {
            Image(
                painter = painterResource(card.silhouetteRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Image(
                painter = painterResource(card.overlayRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.width(1.dp))
        Text(
            card.displayName,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
