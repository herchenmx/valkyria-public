package com.example.hevycompanion.muscle

import org.junit.Assert.assertEquals
import org.junit.Test

class MuscleAssetMapTest {

    @Test fun `displayName capitalises and special-cases multi-word groups`() {
        assertEquals("Biceps", MuscleAssetMap.displayName(HevyMuscleGroup.BICEPS))
        assertEquals("Lower Back", MuscleAssetMap.displayName(HevyMuscleGroup.LOWER_BACK))
        assertEquals("Upper Back", MuscleAssetMap.displayName(HevyMuscleGroup.UPPER_BACK))
        assertEquals("Full Body", MuscleAssetMap.displayName(HevyMuscleGroup.FULL_BODY))
    }

    @Test fun `every anatomical group has a distinct tint`() {
        // Visually distinguishing muscles in the selector grid relies on each
        // Hevy group getting its own colour; a collision would silently merge
        // two cards' colours.
        val tints = HevyMuscleGroup.ANATOMICAL.map { MuscleAssetMap.tintFor(it) }
        assertEquals(tints.size, tints.toSet().size)
    }
}
