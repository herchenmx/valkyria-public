package com.example.hevycompanion.browse

import com.example.hevycompanion.generate.mm.MmExercise

/**
 * Filter state for the M&M side of the unified Browser.
 *
 * Empty sets = "no filter on this dimension" (show everything). Decoupled by
 * design from [HevyExerciseListFilter]: the M&M catalog has a richer taxonomy
 * (movement_pattern lists, multi-equipment, four muscle roles, sub-areas) and
 * the Browser keeps the filter shapes per-source.
 *
 * [areas] and [subAreas] are joined **disjunctively** when both are populated:
 * a row passes the muscle clause if it matches EITHER the area filter OR the
 * sub-area filter. The unified Browser's multi-select muscle grid relies on
 * this — tapping Quadriceps adds `Legs | Quads` + `Legs | Hip Flexors` to
 * `subAreas`, and tapping Upper Chest (which has no `Chest | …` sub-area in
 * the M&M catalog) adds `Chest` to `areas`. Without the disjunctive join the
 * intersection would be empty, so multi-selecting across the two would
 * silently zero out the result list.
 */
data class MmExerciseListFilter(
    val query: String = "",
    val areas: Set<String> = emptySet(),
    val subAreas: Set<String> = emptySet(),
    val equipment: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val movementPatterns: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = query.isBlank() &&
            areas.isEmpty() &&
            subAreas.isEmpty() &&
            equipment.isEmpty() &&
            categories.isEmpty() &&
            types.isEmpty() &&
            movementPatterns.isEmpty()
}

object MmExerciseListFilterEngine {

    /**
     * Apply the filter to the catalog. Conjunctive across dimensions, with
     * one exception: [MmExerciseListFilter.areas] and
     * [MmExerciseListFilter.subAreas] are joined **disjunctively** when both
     * are populated — a row passes the muscle clause if it matches the area
     * filter OR the sub-area filter. See the kdoc on [MmExerciseListFilter]
     * for why. List fields ([MmExercise.equipment],
     * [MmExercise.movementPattern], [MmExercise.subAreas]) match if there's
     * any overlap with the selection. Search is case-insensitive substring
     * on `name`.
     */
    fun apply(
        catalog: List<MmExercise>,
        filter: MmExerciseListFilter,
    ): List<MmExercise> {
        val q = filter.query.trim().lowercase()
        val hasAreaClause = filter.areas.isNotEmpty()
        val hasSubAreaClause = filter.subAreas.isNotEmpty()
        return catalog.filter { ex ->
            if (q.isNotEmpty() && !ex.name.lowercase().contains(q)) return@filter false
            if (hasAreaClause || hasSubAreaClause) {
                val areaPass = !hasAreaClause || ex.area in filter.areas
                val subPass = !hasSubAreaClause || ex.subAreas.any { it in filter.subAreas }
                if (hasAreaClause && hasSubAreaClause) {
                    // Both populated → OR'd. Either path is enough.
                    if (!(ex.area in filter.areas ||
                          ex.subAreas.any { it in filter.subAreas })) return@filter false
                } else if (!areaPass || !subPass) {
                    return@filter false
                }
            }
            if (filter.equipment.isNotEmpty() &&
                ex.equipment.none { it in filter.equipment }
            ) return@filter false
            if (filter.categories.isNotEmpty() && ex.category !in filter.categories) return@filter false
            if (filter.types.isNotEmpty() && ex.type !in filter.types) return@filter false
            if (filter.movementPatterns.isNotEmpty() &&
                ex.movementPattern.none { it in filter.movementPatterns }
            ) return@filter false
            true
        }
    }
}
