package com.example.hevycompanion.muscle

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The 3-column grid of Liftoff muscle cards used by the workout generator's
 * muscle picker. Multi-select only: tap to toggle, ✓ to confirm. The read-only
 * browse-by-muscle variant lives inside `BrowserScreen`.
 */
@Composable
fun MuscleSelectorScreen(
    mode: MuscleSelectorMode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Selection state is keyed on the LIFTOFF CARD'S DISPLAY NAME (which
    // `LiftoffMuscleCardsTest` enforces is unique), NOT on `card.hevyGroup`.
    // Multiple Liftoff cards collapse to the same Hevy group (Front/Middle/Rear
    // Delt → shoulders; Upper/Lower Chest → chest; Abdominals/Obliques →
    // abdominals). Keying selection on hevyGroup would light up every card
    // sharing that group whenever any one of them was tapped.
    var selectedCards by remember { mutableStateOf(initialCardSelection(mode)) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HeaderRow(
                selectedCount = selectedCards.size,
                onBack = onBack,
                onConfirm = {
                    mode.onConfirm(MuscleSelectorSelection.hevyGroupsFromCards(selectedCards))
                },
            )
        }
        // U5 — Select All / Clear All shortcuts. Toggling 20 tiles one at a
        // time is tedious when the user wants either everything or nothing
        // (e.g. "start from blank and pick three" or "all-body day").
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        selectedCards = LiftoffMuscleCards.ALL.map { it.displayName }.toSet()
                    },
                    enabled = selectedCards.size < LiftoffMuscleCards.ALL.size,
                ) { Text("Select all") }
                OutlinedButton(
                    onClick = { selectedCards = emptySet() },
                    enabled = selectedCards.isNotEmpty(),
                ) { Text("Clear") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Pick the muscle groups you want to work out:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(LiftoffMuscleCards.ALL) { card ->
            val isSelected = card.displayName in selectedCards
            MuscleCard(
                card = card,
                isSelected = isSelected,
                onClick = {
                    selectedCards = if (isSelected) selectedCards - card.displayName
                                    else selectedCards + card.displayName
                },
            )
        }
    }
}

/** Drives the tap behaviour and the header buttons. Multi-select only. */
data class MuscleSelectorMode(
    val initial: Set<String> = emptySet(),
    val onConfirm: (Set<String>) -> Unit,
) {
    companion object {
        fun Multi(initial: Set<String> = emptySet(), onConfirm: (Set<String>) -> Unit) =
            MuscleSelectorMode(initial, onConfirm)
    }
}

private fun initialCardSelection(mode: MuscleSelectorMode): Set<String> =
    MuscleSelectorSelection.cardsFromHevyGroups(mode.initial)

/**
 * Pure helpers that translate between the two muscle-selection vocabularies:
 *
 *  - **Liftoff card display names** (unique per card; what we use internally to
 *    track which specific tiles the user has highlighted)
 *  - **Hevy muscle group strings** (coarser, what the API filters on; multiple
 *    cards may share one group — Front/Middle/Rear Delt all → "shoulders",
 *    Upper/Lower Chest → "chest", Abdominals/Obliques → "abdominals")
 *
 * Extracted out of the Composable so we can unit-test the conversions on the
 * JVM without spinning up Compose UI test infrastructure. Regression for the
 * "tap one delt → all delts highlight" bug.
 */
internal object MuscleSelectorSelection {

    /** Selected card display names → deduped Hevy muscle group strings. */
    fun hevyGroupsFromCards(selectedDisplayNames: Set<String>): Set<String> =
        LiftoffMuscleCards.ALL
            .filter { it.displayName in selectedDisplayNames }
            .map { it.hevyGroup }
            .toSet()

    /**
     * Hevy muscle group strings → display names of every Liftoff card mapping
     * to one of those groups. Lossy in the case of N-to-1 collapse: if the
     * caller only cares about "chest", BOTH "Upper Chest" AND "Lower Chest"
     * cards will be pre-selected, since we have no finer-grained signal.
     */
    fun cardsFromHevyGroups(hevyGroups: Set<String>): Set<String> {
        if (hevyGroups.isEmpty()) return emptySet()
        return LiftoffMuscleCards.ALL
            .filter { it.hevyGroup in hevyGroups }
            .map { it.displayName }
            .toSet()
    }
}

@Composable
private fun HeaderRow(
    selectedCount: Int,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onBack) { Text("✕") }
        Spacer(Modifier.width(12.dp))
        Text(
            "$selectedCount Selected",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onConfirm,
            enabled = selectedCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) { Text("✓") }
    }
}

@Composable
private fun MuscleCard(
    card: LiftoffMuscleCard,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = Color(MuscleAssetMap.tintFor(card.hevyGroup))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                // Small filled dot in the corner, like Liftoff's checkmark.
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("✓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary) }
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
