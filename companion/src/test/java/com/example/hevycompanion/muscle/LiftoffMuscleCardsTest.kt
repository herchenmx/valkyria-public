package com.example.hevycompanion.muscle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftoffMuscleCardsTest {

    @Test fun `exactly 20 Liftoff muscle cards`() {
        assertEquals(20, LiftoffMuscleCards.ALL.size)
    }

    @Test fun `every card has a unique display name`() {
        val names = LiftoffMuscleCards.ALL.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun `every Liftoff card resolves to a known Hevy anatomical group`() {
        // If a card mapped to an unknown Hevy string, tapping it would filter
        // the catalog to zero matches silently.
        for (card in LiftoffMuscleCards.ALL) {
            assertTrue(
                "card '${card.displayName}' has unknown hevyGroup '${card.hevyGroup}'",
                card.hevyGroup in HevyMuscleGroup.ANATOMICAL,
            )
        }
    }

    @Test fun `chest has both upper and lower cards`() {
        val chestCards = LiftoffMuscleCards.ALL.filter { it.hevyGroup == HevyMuscleGroup.CHEST }
        assertEquals(setOf("Upper Chest", "Lower Chest"), chestCards.map { it.displayName }.toSet())
    }

    @Test fun `shoulders has all three delts`() {
        val shoulderCards = LiftoffMuscleCards.ALL.filter { it.hevyGroup == HevyMuscleGroup.SHOULDERS }
        assertEquals(
            setOf("Front Delt", "Middle Delt", "Rear Delt"),
            shoulderCards.map { it.displayName }.toSet(),
        )
    }

    @Test fun `abdominals has abs and obliques`() {
        val absCards = LiftoffMuscleCards.ALL.filter { it.hevyGroup == HevyMuscleGroup.ABDOMINALS }
        assertEquals(setOf("Abdominals", "Obliques"), absCards.map { it.displayName }.toSet())
    }

    @Test fun `every anatomical Hevy group is covered by at least one card`() {
        // The selector is the ONLY way to reach per-muscle exercise lists for
        // these groups — a missing card would hide an entire Hevy category.
        val covered = LiftoffMuscleCards.ALL.map { it.hevyGroup }.toSet()
        for (grp in HevyMuscleGroup.ANATOMICAL) {
            assertTrue("Hevy group '$grp' has no Liftoff card", grp in covered)
        }
    }

    @Test fun `every card has valid drawable ids (non-zero)`() {
        for (card in LiftoffMuscleCards.ALL) {
            assertTrue(card.silhouetteRes != 0)
            assertTrue(card.overlayRes != 0)
        }
    }

    // The Browser uses these mappings to pre-fill the M&M filter when a card
    // is tapped while Source.MM is active. Missing values would silently skip
    // that card's pre-fill and dump the user back at the unfiltered list.
    @Test fun `every card has a non-blank M&M area fallback`() {
        for (card in LiftoffMuscleCards.ALL) {
            assertTrue(
                "card '${card.displayName}' has blank mmAreaFallback",
                card.mmAreaFallback.isNotBlank(),
            )
        }
    }

    @Test fun `every M&M area fallback is one of the seven catalog areas`() {
        // From `python3 -c '...' on mm_catalog_runtime.json`:
        // ['Abs & Core', 'Arms', 'Back', 'Chest', 'Legs', 'Neck', 'Shoulders']
        // No card maps to Neck (no Liftoff card targets it), but every other
        // card's fallback must hit one of the seven canonical areas.
        val canonical = setOf("Abs & Core", "Arms", "Back", "Chest", "Legs", "Neck", "Shoulders")
        for (card in LiftoffMuscleCards.ALL) {
            assertTrue(
                "card '${card.displayName}' mmAreaFallback '${card.mmAreaFallback}' not in catalog",
                card.mmAreaFallback in canonical,
            )
        }
    }

    @Test fun `every M&M sub-area entry follows the pipe-delimited shape and matches its card area`() {
        // Sub-area strings come straight from the bundled catalog JSON, so
        // they must obey the "Area | Sub" pipe-delimited shape and the area
        // half must agree with the card's own mmAreaFallback. A typo here
        // would silently produce a sub-area filter with zero matches.
        for (card in LiftoffMuscleCards.ALL) {
            for (sub in card.mmSubAreas) {
                assertTrue(
                    "card '${card.displayName}' sub-area '$sub' missing pipe",
                    sub.contains(" | "),
                )
                val areaPart = sub.substringBefore(" | ")
                assertEquals(
                    "card '${card.displayName}' sub-area '$sub' area mismatch",
                    card.mmAreaFallback,
                    areaPart,
                )
            }
        }
    }

    @Test fun `forearms card maps to M&M Arms even though Liftoff treats it standalone`() {
        // M&M files forearms under Arms, not as a top-level area. Confirms the
        // adapter table didn't skip it (Forearms is the only Liftoff card
        // whose hevyGroup vocabulary doesn't trivially map to an M&M area).
        val forearms = cardOf("Forearms")
        assertEquals("Arms", forearms.mmAreaFallback)
        assertTrue("Arms | Forearms" in forearms.mmSubAreas)
    }

    // Anatomically-paired overlays MUST share a silhouette tile, otherwise the
    // overlay lands in the wrong body region (e.g. forearms drawn on the
    // upper-front tile renders the muscle floating above the elbow).
    @Test fun `arm overlays sit on the same silhouette as biceps`() {
        val biceps = cardOf("Biceps")
        assertEquals(biceps.silhouetteRes, cardOf("Forearms").silhouetteRes)
    }

    @Test fun `hip-adjacent leg overlays sit on the same silhouette as quadriceps`() {
        val quads = cardOf("Quadriceps")
        assertEquals(quads.silhouetteRes, cardOf("Abductors").silhouetteRes)
        assertEquals(quads.silhouetteRes, cardOf("Adductors").silhouetteRes)
    }

    @Test fun `lats sit on the same silhouette as lower back`() {
        assertEquals(cardOf("Lower Back").silhouetteRes, cardOf("Lats").silhouetteRes)
    }

    private fun cardOf(name: String): LiftoffMuscleCard {
        val c = LiftoffMuscleCards.ALL.firstOrNull { it.displayName == name }
        assertNotNull("missing card '$name'", c)
        return c!!
    }
}
