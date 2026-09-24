package com.example.hevycompanion.generate

/**
 * Liftoff groups its 97-item equipment list into named categories
 * ("Small Weights", "Bars & Plates", …). Hevy's exercise catalog tags
 * each exercise with a single coarser equipment string (about 11 known
 * values), so we collapse Liftoff's categories to map onto Hevy's tags.
 *
 * Used by the workout generator to:
 *  - render the equipment picker as grouped sections (the UI Liftoff shows)
 *  - filter the exercise pool to "selected equipment" + always-on bodyweight
 *
 * Unknown / future Hevy equipment values fall into [OTHER] so the catalog
 * is never silently truncated.
 */
enum class EquipmentCategory(val displayName: String) {
    SMALL_WEIGHTS("Small Weights"),
    BARS_AND_PLATES("Bars & Plates"),
    CABLES_AND_MACHINES("Cables & Machines"),
    BODYWEIGHT_AND_BANDS("Bodyweight & Bands"),
    OTHER("Other"),
}

object EquipmentMap {

    /**
     * Hevy equipment tags observed in the public `/v1/exercise_templates`
     * catalog. We group them into [EquipmentCategory]s for the picker UI.
     * Anything else (a new tag Hevy adds later) lands in [EquipmentCategory.OTHER].
     */
    val KNOWN_HEVY_TAGS: List<String> = listOf(
        "barbell",
        "dumbbell",
        "kettlebell",
        "machine",
        "cable",
        "plate",
        "ez_bar",
        "resistance_band",
        "suspension",
        "none",
        "other",
    )

    fun categoryFor(hevyEquipment: String?): EquipmentCategory =
        when (hevyEquipment?.lowercase()) {
            "dumbbell", "kettlebell" -> EquipmentCategory.SMALL_WEIGHTS
            "barbell", "ez_bar", "plate" -> EquipmentCategory.BARS_AND_PLATES
            "cable", "machine" -> EquipmentCategory.CABLES_AND_MACHINES
            "none", "resistance_band", "suspension" -> EquipmentCategory.BODYWEIGHT_AND_BANDS
            else -> EquipmentCategory.OTHER
        }

    /** Returns Hevy tags grouped by category, in [EquipmentCategory] order. */
    fun grouped(): Map<EquipmentCategory, List<String>> =
        EquipmentCategory.entries.associateWith { cat ->
            KNOWN_HEVY_TAGS.filter { categoryFor(it) == cat }
        }

    /** Display label for a single Hevy equipment tag (e.g. `ez_bar` → "EZ Bar"). */
    fun displayLabel(hevyEquipment: String): String = when (hevyEquipment.lowercase()) {
        "ez_bar" -> "EZ Bar"
        "resistance_band" -> "Resistance Band"
        "none" -> "Bodyweight"
        else -> hevyEquipment.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
