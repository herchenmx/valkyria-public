package com.example.hevycompanion.generate.mm

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
 * Pins the Liftoff-card multi-select toggle on the M&M generator's "Pick
 * muscles" sub-mode. Mirrors `BrowserViewModelTest`'s MM card-tap tests since
 * the semantics are deliberately identical: cards with sub-areas toggle their
 * `mmSubAreas`, cards without (Upper/Lower Chest) toggle the area fallback,
 * and the same set drives `Mode.SubAreas.matches` via its dual area-or-subarea
 * matcher.
 */
@RunWith(RobolectricTestRunner::class)
class MmGeneratorViewModelTest {

    private lateinit var app: Application
    private lateinit var vm: MmGeneratorViewModel

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Wipe the M&M generator prefs so each test starts clean.
        app.getSharedPreferences("mm_generator", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        vm = MmGeneratorViewModel(app)
    }

    @Test fun `onMuscleCardTap with sub-areas adds them to selectedSubAreas`() {
        val quads = cardOf("Quadriceps")
        vm.onMuscleCardTap(quads)
        // Quadriceps maps to two sub-areas (Legs | Quads, Legs | Hip Flexors).
        // Tapping unions BOTH into selectedSubAreas.
        assertEquals(quads.mmSubAreas.toSet(), vm.selectedSubAreas)
    }

    @Test fun `onMuscleCardTap re-tap removes only that card's sub-areas`() {
        val quads = cardOf("Quadriceps")
        val biceps = cardOf("Biceps")
        vm.onMuscleCardTap(quads)
        vm.onMuscleCardTap(biceps)
        // Re-tap quads — leaves biceps's sub-area in place.
        vm.onMuscleCardTap(quads)
        assertEquals(biceps.mmSubAreas.toSet(), vm.selectedSubAreas)
    }

    @Test fun `onMuscleCardTap with no sub-areas adds the area fallback`() {
        // Upper Chest has no `Chest | …` sub-area in M&M — fall back to area
        // string "Chest". Mode.SubAreas.matches accepts both via
        // `ex.area in selected || ex.subAreas.any { it in selected }`.
        val upperChest = cardOf("Upper Chest")
        assertTrue(upperChest.mmSubAreas.isEmpty())
        vm.onMuscleCardTap(upperChest)
        assertEquals(setOf("Chest"), vm.selectedSubAreas)
    }

    @Test fun `mixing sub-areas and area-fallback cards works in one set`() {
        // Quads + Upper Chest land in the same Set<String> — sub-area entries
        // ("Legs | Quads", "Legs | Hip Flexors") plus area entry ("Chest").
        // The Mode.SubAreas matcher handles both kinds of strings.
        vm.onMuscleCardTap(cardOf("Quadriceps"))
        vm.onMuscleCardTap(cardOf("Upper Chest"))
        val expected = cardOf("Quadriceps").mmSubAreas.toSet() + setOf("Chest")
        assertEquals(expected, vm.selectedSubAreas)
    }

    @Test fun `isCardSelected reflects current selectedSubAreas`() {
        val biceps = cardOf("Biceps")
        val upperChest = cardOf("Upper Chest")
        // Initially nothing selected.
        assertTrue(!vm.isCardSelected(biceps))
        assertTrue(!vm.isCardSelected(upperChest))
        vm.onMuscleCardTap(biceps)
        assertTrue(vm.isCardSelected(biceps))
        vm.onMuscleCardTap(upperChest)
        assertTrue(vm.isCardSelected(upperChest))
        // The two cards' selections are independent.
        assertTrue(vm.isCardSelected(biceps))
    }

    @Test fun `selection persists across VM instances`() {
        // The card-tap toggle writes through MmGeneratorPrefs. A second VM
        // instance should observe the same selection — important so the
        // user's muscle picks survive process death between Setup and a
        // resumed Generate tap.
        val card = cardOf("Hamstrings")
        vm.onMuscleCardTap(card)
        val reloaded = MmGeneratorViewModel(app)
        assertTrue(reloaded.isCardSelected(card))
    }

    @Test fun `Upper and Lower Chest cards select-deselect together via shared area fallback`() {
        // Both cards map to mmAreaFallback = "Chest" (M&M's catalog has no
        // chest sub-area split). Tapping either toggles "Chest" in the set,
        // so both badges flip in lockstep — same documented limitation as
        // the Browser. Re-tapping any sibling-area card removes the shared
        // area: both badges go away at once.
        val upper = cardOf("Upper Chest")
        val lower = cardOf("Lower Chest")
        vm.onMuscleCardTap(upper)
        assertTrue(vm.isCardSelected(upper))
        assertTrue(vm.isCardSelected(lower))
        vm.onMuscleCardTap(lower)
        assertTrue(vm.selectedSubAreas.isEmpty())
        assertTrue(!vm.isCardSelected(upper))
        assertTrue(!vm.isCardSelected(lower))
    }

    private fun cardOf(displayName: String): LiftoffMuscleCard =
        LiftoffMuscleCards.ALL.first { it.displayName == displayName }
}
