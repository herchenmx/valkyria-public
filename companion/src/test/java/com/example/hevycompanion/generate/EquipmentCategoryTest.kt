package com.example.hevycompanion.generate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EquipmentCategoryTest {

    @Test fun `every known Hevy tag maps to a non-OTHER category`() {
        // The grouped equipment picker reads `KNOWN_HEVY_TAGS` to decide which
        // chips to render under each section header. If a known tag silently
        // falls into OTHER, it'll appear in the wrong group in the UI.
        for (tag in EquipmentMap.KNOWN_HEVY_TAGS) {
            val cat = EquipmentMap.categoryFor(tag)
            assertTrue(
                "tag '$tag' fell into OTHER — add it to a real category",
                cat != EquipmentCategory.OTHER || tag == "other",
            )
        }
    }

    @Test fun `unknown tag falls into OTHER instead of crashing`() {
        // Hevy may add new equipment values; we must not lose them.
        assertEquals(EquipmentCategory.OTHER, EquipmentMap.categoryFor("plasma_cannon"))
        assertEquals(EquipmentCategory.OTHER, EquipmentMap.categoryFor(null))
    }

    @Test fun `grouped covers every known tag exactly once`() {
        val flatGrouped = EquipmentMap.grouped().values.flatten()
        assertEquals(EquipmentMap.KNOWN_HEVY_TAGS.toSet(), flatGrouped.toSet())
        assertEquals(
            "duplicate tag in grouped()",
            flatGrouped.size,
            flatGrouped.toSet().size,
        )
    }

    @Test fun `displayLabel humanises snake_case tags`() {
        assertEquals("EZ Bar", EquipmentMap.displayLabel("ez_bar"))
        assertEquals("Resistance Band", EquipmentMap.displayLabel("resistance_band"))
        assertEquals("Bodyweight", EquipmentMap.displayLabel("none"))
        assertEquals("Barbell", EquipmentMap.displayLabel("barbell"))
    }
}
