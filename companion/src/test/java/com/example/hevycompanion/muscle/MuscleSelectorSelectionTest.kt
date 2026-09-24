package com.example.hevycompanion.muscle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the bug where tapping any single delt / chest / abs
 * sub-card auto-highlighted every other card sharing the same Hevy group
 * (because the picker keyed selection on `card.hevyGroup` instead of
 * `card.displayName`).
 *
 * These cover the pure conversion functions used by the multi-select
 * `MuscleSelectorScreen`; they don't touch Compose so they run as plain JVM
 * unit tests.
 */
class MuscleSelectorSelectionTest {

    // ---- hevyGroupsFromCards (selection → filter input) -------------------

    @Test fun `tapping one delt does NOT pull in the other delts`() {
        // The user reported: tapping "Front Delt" lit up "Middle Delt" and
        // "Rear Delt" too. Internally we now key on displayName, so only
        // "Front Delt" should be in the selection set; the conversion to
        // Hevy groups still yields ["shoulders"] (correct API filter) but
        // the picker UI must only show ONE highlighted card.
        val selectedCards = setOf("Front Delt")
        val hevyGroups = MuscleSelectorSelection.hevyGroupsFromCards(selectedCards)
        assertEquals(setOf(HevyMuscleGroup.SHOULDERS), hevyGroups)
    }

    @Test fun `tapping all three delts dedupes to a single shoulders group`() {
        val selectedCards = setOf("Front Delt", "Middle Delt", "Rear Delt")
        val hevyGroups = MuscleSelectorSelection.hevyGroupsFromCards(selectedCards)
        assertEquals(setOf(HevyMuscleGroup.SHOULDERS), hevyGroups)
    }

    @Test fun `tapping one chest does NOT pull in the other chest`() {
        assertEquals(
            setOf(HevyMuscleGroup.CHEST),
            MuscleSelectorSelection.hevyGroupsFromCards(setOf("Upper Chest")),
        )
    }

    @Test fun `tapping abdominals does NOT pull in obliques`() {
        assertEquals(
            setOf(HevyMuscleGroup.ABDOMINALS),
            MuscleSelectorSelection.hevyGroupsFromCards(setOf("Abdominals")),
        )
    }

    @Test fun `mixed selection across collapsed and 1-to-1 cards yields the right group set`() {
        val selectedCards = setOf("Front Delt", "Biceps", "Upper Chest", "Quadriceps")
        assertEquals(
            setOf(
                HevyMuscleGroup.SHOULDERS,
                HevyMuscleGroup.BICEPS,
                HevyMuscleGroup.CHEST,
                HevyMuscleGroup.QUADRICEPS,
            ),
            MuscleSelectorSelection.hevyGroupsFromCards(selectedCards),
        )
    }

    @Test fun `unknown card name is silently dropped (defensive)`() {
        // If a future refactor introduces card names not in LiftoffMuscleCards.ALL,
        // we shouldn't crash — just ignore them.
        val hevyGroups = MuscleSelectorSelection.hevyGroupsFromCards(setOf("Front Delt", "Imaginary Muscle"))
        assertEquals(setOf(HevyMuscleGroup.SHOULDERS), hevyGroups)
    }

    @Test fun `empty input yields empty output`() {
        assertEquals(emptySet<String>(), MuscleSelectorSelection.hevyGroupsFromCards(emptySet()))
    }

    // ---- cardsFromHevyGroups (re-open with prior selection) ---------------

    @Test fun `pre-selecting shoulders highlights all three delt cards (lossy expansion)`() {
        // We don't track which sub-card the user originally tapped, so re-opening
        // the picker with shoulders pre-selected expands to every shoulder card.
        // Documented as acceptable-for-v1 in MuscleSelectorScreen kdoc.
        val cards = MuscleSelectorSelection.cardsFromHevyGroups(setOf(HevyMuscleGroup.SHOULDERS))
        assertEquals(setOf("Front Delt", "Middle Delt", "Rear Delt"), cards)
    }

    @Test fun `1-to-1 group pre-selects exactly one card`() {
        assertEquals(
            setOf("Biceps"),
            MuscleSelectorSelection.cardsFromHevyGroups(setOf(HevyMuscleGroup.BICEPS)),
        )
    }

    @Test fun `roundtrip from full multi-select preserves the hevy group set`() {
        // Pre-select shoulders + biceps → expands to 3 delts + biceps card →
        // converting BACK collapses to {shoulders, biceps}. This guarantees
        // re-opening + re-confirming an existing selection doesn't silently
        // change which exercises the generator filters to.
        val original = setOf(HevyMuscleGroup.SHOULDERS, HevyMuscleGroup.BICEPS)
        val cards = MuscleSelectorSelection.cardsFromHevyGroups(original)
        assertTrue(cards.size > original.size)  // expanded
        val roundTripped = MuscleSelectorSelection.hevyGroupsFromCards(cards)
        assertEquals(original, roundTripped)
    }

    @Test fun `empty hevy groups yields empty cards`() {
        assertEquals(emptySet<String>(), MuscleSelectorSelection.cardsFromHevyGroups(emptySet()))
    }
}
