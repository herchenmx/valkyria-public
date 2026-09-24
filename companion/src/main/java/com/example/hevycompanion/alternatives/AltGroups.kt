package com.example.hevycompanion.alternatives

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.muscle.MuscleAssetMap
import com.example.hevycompanion.recents.SubstitutionMap

/** One member of an alternatives group, resolved against the exercise catalog. */
data class AltExercise(
    val id: String,
    val title: String,
    val primaryMuscleGroup: String?,
    val equipment: String?,
)

/** A curated substitution group ready to render: a muscle-derived heading plus
 *  its interchangeable member exercises (curated order preserved). */
data class AltGroup(
    val heading: String,
    val exercises: List<AltExercise>,
)

/**
 * Pure builder that joins the curated [SubstitutionMap] groups against the
 * exercise catalog to produce renderable [AltGroup]s for the "Exercise
 * Alternatives" screen. No IO — the ViewModel supplies the catalog metadata so
 * the join rules stay unit-testable.
 */
object AltGroups {

    /**
     * Resolve every [SubstitutionMap] group against [metaById] (keyed by
     * **upper-cased** template id). Members missing from the catalog are
     * dropped; a group left with fewer than two resolvable members is omitted
     * entirely (a single alternative isn't a "grouping").
     */
    fun build(metaById: Map<String, ExerciseTemplate>): List<AltGroup> =
        SubstitutionMap.allGroups().mapNotNull { memberIds ->
            val members = memberIds.mapNotNull { id ->
                metaById[id.uppercase()]?.let { t ->
                    AltExercise(
                        id = id,
                        title = t.title,
                        primaryMuscleGroup = t.primaryMuscleGroup,
                        equipment = t.equipment,
                    )
                }
            }
            if (members.size < 2) return@mapNotNull null
            AltGroup(heading = headingFor(members), exercises = members)
        }

    /**
     * A display heading for a group: the display name of the most common
     * primary muscle group across its members, falling back to "Alternatives"
     * when no member carries a muscle group. Ties resolve to the
     * earliest-declared member's muscle (curated order), keeping output stable.
     */
    fun headingFor(members: List<AltExercise>): String {
        val dominant = members
            .mapNotNull { it.primaryMuscleGroup?.takeIf { m -> m.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .maxByOrNull { it.value }
            ?.key
        return dominant?.let { MuscleAssetMap.displayName(it) } ?: "Alternatives"
    }
}
