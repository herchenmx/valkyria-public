package com.example.hevycompanion.browse

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.HevyExerciseAttrs

/**
 * Filter state for the searchable Hevy exercise list.
 *
 * Empty sets = "no filter on this dimension" (i.e. accept everything). This
 * matches the chip UX where untoggling the last chip in a group reads as
 * "show all" rather than "hide all".
 *
 * Decoupled from the M&M list filter ([MmExerciseListFilter]) by design:
 * the two catalogs have different field names and different vocabularies, and
 * the user asked them to be independent. Don't extract a shared abstraction.
 */
data class HevyExerciseListFilter(
    val query: String = "",
    val muscleGroups: Set<String> = emptySet(),
    val equipment: Set<String> = emptySet(),
    val exerciseTypes: Set<String> = emptySet(),
    val levels: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = query.isBlank() &&
            muscleGroups.isEmpty() &&
            equipment.isEmpty() &&
            exerciseTypes.isEmpty() &&
            levels.isEmpty() &&
            categories.isEmpty()
}

object HevyExerciseListFilterEngine {

    /**
     * Apply the filter to the catalog. The match is conjunctive across
     * dimensions and disjunctive within a dimension (any selected muscle group
     * passes). Search is case-insensitive substring on title.
     *
     * [attrsById] supplies the level/category fields the public REST catalog
     * doesn't expose — same side table the workout generator uses.
     */
    fun apply(
        templates: List<ExerciseTemplate>,
        attrsById: Map<String, HevyExerciseAttrs>,
        filter: HevyExerciseListFilter,
    ): List<ExerciseTemplate> {
        val q = filter.query.trim().lowercase()
        return templates.filter { tpl ->
            if (q.isNotEmpty() && !tpl.title.lowercase().contains(q)) return@filter false
            if (filter.muscleGroups.isNotEmpty() &&
                tpl.primaryMuscleGroup?.lowercase() !in filter.muscleGroups
            ) return@filter false
            if (filter.equipment.isNotEmpty() &&
                tpl.equipment?.lowercase() !in filter.equipment
            ) return@filter false
            if (filter.exerciseTypes.isNotEmpty() &&
                tpl.type?.lowercase() !in filter.exerciseTypes
            ) return@filter false
            val attrs = attrsById[tpl.id]
            if (filter.levels.isNotEmpty()) {
                val levels = attrs?.level.orEmpty()
                if (levels.none { it in filter.levels }) return@filter false
            }
            if (filter.categories.isNotEmpty()) {
                val cat = attrs?.category
                if (cat == null || cat !in filter.categories) return@filter false
            }
            true
        }
    }
}
