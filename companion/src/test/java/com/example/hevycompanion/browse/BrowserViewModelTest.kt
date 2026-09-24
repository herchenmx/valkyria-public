package com.example.hevycompanion.browse

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.hevycompanion.muscle.LiftoffMuscleCard
import com.example.hevycompanion.muscle.LiftoffMuscleCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the unified Browser's source-switching contract: each source keeps its
 * own filter state, the muscle-card grid pre-fills the active source's
 * muscle/area filter (and only the active source's), and the
 * "tap-card-then-back-to-list" UX always lands on LIST mode.
 *
 * Doesn't exercise the Hevy network fetch path — that's covered by
 * `ExerciseTemplateRepoTest` end-to-end against a MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserViewModelTest {

    private lateinit var app: Application
    private lateinit var vm: BrowserViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Wipe the prefs file so each test starts clean.
        BrowsePrefs(app).clear()
        vm = BrowserViewModel(app)
    }

    @Test fun `default source is HEVY and view mode is LIST`() {
        assertEquals(Source.HEVY, vm.source)
        assertEquals(ViewMode.LIST, vm.viewMode)
    }

    @Test fun `setSource flips the active source and persists it`() {
        vm.setSource(Source.MM)
        assertEquals(Source.MM, vm.source)
        // A freshly-constructed VM should observe the persisted source.
        assertEquals(Source.MM, BrowserViewModel(app).source)
    }

    @Test fun `setSource is a no-op when source is unchanged`() {
        // Toggle the M&M filter to something non-empty, then re-set to the
        // current source. The filter state must survive — re-setting the
        // current source must not reset prefs or trigger an inadvertent
        // catalog reload.
        vm.setSource(Source.MM)
        vm.toggleMmArea("Legs")
        vm.setSource(Source.MM)
        assertEquals(setOf("Legs"), vm.mmFilter.areas)
    }

    @Test fun `Hevy filter and M&M filter are kept independent across source flips`() {
        // Apply a Hevy filter, swap to MM, apply an M&M filter, swap back —
        // the Hevy filter must round-trip through the prefs round-trip
        // unchanged (regression for "switching erases the inactive source's
        // chip selection").
        vm.toggleHevyMuscle("chest")
        assertEquals(setOf("chest"), vm.hevyFilter.muscleGroups)

        vm.setSource(Source.MM)
        vm.toggleMmArea("Legs")
        assertEquals(setOf("Legs"), vm.mmFilter.areas)

        vm.setSource(Source.HEVY)
        assertEquals(setOf("chest"), vm.hevyFilter.muscleGroups)
        assertEquals(setOf("Legs"), vm.mmFilter.areas)
    }

    @Test fun `onMuscleCardTap with HEVY source toggles muscleGroups and stays in MUSCLE_GRID`() {
        vm.setViewMode(ViewMode.MUSCLE_GRID)
        vm.toggleHevyMuscle("biceps")
        val card = cardOf("Quadriceps")
        // Tap adds — multi-select toggle, not single-replace.
        vm.onMuscleCardTap(card)
        assertEquals(setOf("biceps", card.hevyGroup), vm.hevyFilter.muscleGroups)
        // Stays in MUSCLE_GRID so the user can keep picking.
        assertEquals(ViewMode.MUSCLE_GRID, vm.viewMode)
        // Re-tap removes.
        vm.onMuscleCardTap(card)
        assertEquals(setOf("biceps"), vm.hevyFilter.muscleGroups)
    }

    @Test fun `onMuscleCardTap with MM source toggles sub-areas additively`() {
        vm.setSource(Source.MM)
        vm.setViewMode(ViewMode.MUSCLE_GRID)
        val quads = cardOf("Quadriceps")
        val biceps = cardOf("Biceps")
        // Quadriceps card maps to two M&M sub-areas (Legs | Quads, Legs | Hip
        // Flexors). Biceps maps to one. Multi-select unions both.
        vm.onMuscleCardTap(quads)
        vm.onMuscleCardTap(biceps)
        val expected = quads.mmSubAreas.toSet() + biceps.mmSubAreas.toSet()
        assertEquals(expected, vm.mmFilter.subAreas)
        assertEquals(ViewMode.MUSCLE_GRID, vm.viewMode)
        // Re-tap removes the card's sub-areas only — leaves biceps alone.
        vm.onMuscleCardTap(quads)
        assertEquals(biceps.mmSubAreas.toSet(), vm.mmFilter.subAreas)
    }

    @Test fun `onMuscleCardTap with MM source toggles area for cards with no sub-areas`() {
        vm.setSource(Source.MM)
        // Upper Chest has no M&M sub-area split — toggles area instead.
        val upperChest = cardOf("Upper Chest")
        assertTrue(upperChest.mmSubAreas.isEmpty())
        vm.onMuscleCardTap(upperChest)
        assertEquals(setOf(upperChest.mmAreaFallback), vm.mmFilter.areas)
        // Re-tap clears the area — toggle.
        vm.onMuscleCardTap(upperChest)
        assertTrue(vm.mmFilter.areas.isEmpty())
    }

    @Test fun `area-fallback cards sharing one M&M area select-deselect together`() {
        // Both Upper Chest + Lower Chest map to the same M&M area ("Chest")
        // because the catalog has no `Chest | …` sub-area split. Tapping
        // either card toggles "Chest" in the areas filter, so both cards'
        // badges flip in lockstep. Documented limitation: in this degenerate
        // case the user can't multi-select Upper-but-not-Lower (M&M doesn't
        // expose a sub-area to filter on).
        vm.setSource(Source.MM)
        val upperChest = cardOf("Upper Chest")
        val lowerChest = cardOf("Lower Chest")
        vm.onMuscleCardTap(upperChest)
        assertTrue(vm.isCardSelected(upperChest))
        assertTrue(vm.isCardSelected(lowerChest))  // shared area → both badged
        vm.onMuscleCardTap(lowerChest)
        // Re-tapping any sibling-area card removes the shared area: both
        // badges go away at once.
        assertTrue(vm.mmFilter.areas.isEmpty())
        assertTrue(!vm.isCardSelected(upperChest))
        assertTrue(!vm.isCardSelected(lowerChest))
    }

    @Test fun `onMuscleCardTap MM mixes areas and sub-areas in the same selection`() {
        // The disjunctive area/sub-area filter join means picking cards from
        // both buckets (Upper Chest + Quadriceps) must populate both filter
        // dimensions independently. The engine OR's them, so the result is
        // the union of "anything in Chest area" and "anything tagged with the
        // Quad sub-areas".
        vm.setSource(Source.MM)
        vm.onMuscleCardTap(cardOf("Upper Chest"))
        vm.onMuscleCardTap(cardOf("Quadriceps"))
        assertEquals(setOf("Chest"), vm.mmFilter.areas)
        assertEquals(cardOf("Quadriceps").mmSubAreas.toSet(), vm.mmFilter.subAreas)
    }

    @Test fun `onOtherGroupTap with HEVY source toggles muscleGroups and stays in MUSCLE_GRID`() {
        vm.setViewMode(ViewMode.MUSCLE_GRID)
        vm.onOtherGroupTap("cardio")
        assertEquals(setOf("cardio"), vm.hevyFilter.muscleGroups)
        // Stays in MUSCLE_GRID — the user might tap another chip.
        assertEquals(ViewMode.MUSCLE_GRID, vm.viewMode)
        vm.onOtherGroupTap("full_body")
        assertEquals(setOf("cardio", "full_body"), vm.hevyFilter.muscleGroups)
        // Re-tap removes.
        vm.onOtherGroupTap("cardio")
        assertEquals(setOf("full_body"), vm.hevyFilter.muscleGroups)
    }

    @Test fun `onOtherGroupTap is a no-op when source is MM`() {
        // The non-anatomical Hevy groups (cardio / full_body / neck / other)
        // don't exist in M&M. Calling this entry point on MM source must
        // leave both filters untouched.
        vm.setSource(Source.MM)
        vm.setViewMode(ViewMode.MUSCLE_GRID)
        vm.onOtherGroupTap("cardio")
        assertTrue(vm.hevyFilter.muscleGroups.isEmpty())
        assertTrue(vm.mmFilter.isEmpty)
    }

    @Test fun `isCardSelected reflects the active source's filter`() {
        val biceps = cardOf("Biceps")
        // HEVY: card selected when its hevyGroup is in muscleGroups.
        assertTrue(!vm.isCardSelected(biceps))
        vm.toggleHevyMuscle("biceps")
        assertTrue(vm.isCardSelected(biceps))

        // MM with sub-areas: selected when ALL of card.mmSubAreas are present.
        vm.setSource(Source.MM)
        assertTrue(!vm.isCardSelected(biceps))
        vm.onMuscleCardTap(biceps)
        assertTrue(vm.isCardSelected(biceps))

        // MM area-fallback card: selected when the area is in filter.areas.
        val upperChest = cardOf("Upper Chest")
        assertTrue(!vm.isCardSelected(upperChest))
        vm.onMuscleCardTap(upperChest)
        assertTrue(vm.isCardSelected(upperChest))
    }

    @Test fun `isOtherGroupSelected only ever reports true on HEVY`() {
        assertTrue(!vm.isOtherGroupSelected("cardio"))
        vm.onOtherGroupTap("cardio")
        assertTrue(vm.isOtherGroupSelected("cardio"))
        // After flipping to MM, the same Hevy filter still has "cardio" but
        // the helper must short-circuit — chips don't render under MM.
        vm.setSource(Source.MM)
        assertTrue(!vm.isOtherGroupSelected("cardio"))
    }

    @Test fun `clear filters wipes only the active source`() {
        vm.toggleHevyMuscle("chest")
        vm.setSource(Source.MM)
        vm.toggleMmArea("Legs")
        vm.clearMmFilters()
        assertTrue(vm.mmFilter.isEmpty)
        // Hevy filter survives — the user's per-source state is independent.
        assertEquals(setOf("chest"), BrowserViewModel(app).hevyFilter.muscleGroups)
    }

    private fun cardOf(displayName: String): LiftoffMuscleCard =
        LiftoffMuscleCards.ALL.first { it.displayName == displayName }
}
