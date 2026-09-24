package com.example.hevycompanion.browse

import com.example.hevycompanion.generate.mm.MmExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmExerciseListFilterTest {

    private val benchPress = ex(
        id = "1", name = "Bench Press",
        type = "strength", category = "Compound", area = "Chest",
        equipment = listOf("Barbell", "Bench"),
        movementPattern = listOf("Push"),
        subAreas = listOf("Chest | Sternal"),
    )
    private val cablePullover = ex(
        id = "2", name = "Cable Pullover",
        type = "strength", category = "Isolation", area = "Back",
        equipment = listOf("Cable"),
        movementPattern = listOf("Pull"),
        subAreas = listOf("Back | Lats"),
    )
    private val airSquat = ex(
        id = "3", name = "Air Squat",
        type = "bodyweight", category = "Compound", area = "Legs",
        equipment = listOf("Bodyweight"),
        movementPattern = listOf("Squat"),
        subAreas = listOf("Legs | Quads"),
    )
    private val plank = ex(
        id = "4", name = "Plank",
        type = "time", category = "Isolation", area = "Abs & Core",
        equipment = listOf("Bodyweight"),
        movementPattern = emptyList(),
        subAreas = listOf("Abs & Core | Core"),
    )

    private val all = listOf(benchPress, cablePullover, airSquat, plank)

    @Test fun `empty filter returns the full catalog`() {
        assertEquals(all, MmExerciseListFilterEngine.apply(all, MmExerciseListFilter()))
    }

    @Test fun `query is case-insensitive substring on name`() {
        val out = MmExerciseListFilterEngine.apply(all, MmExerciseListFilter(query = "press"))
        assertEquals(listOf(benchPress), out)
    }

    @Test fun `area filter narrows correctly`() {
        val out = MmExerciseListFilterEngine.apply(
            all, MmExerciseListFilter(areas = setOf("Legs", "Chest")),
        )
        assertEquals(listOf(benchPress, airSquat), out)
    }

    @Test fun `sub-area filter narrows by exact pipe-delimited string`() {
        // Pre-fill that the Browser's "tap a Liftoff card while in Source.MM"
        // path produces — picking the Quads sub-area should keep only Air Squat.
        val out = MmExerciseListFilterEngine.apply(
            all, MmExerciseListFilter(subAreas = setOf("Legs | Quads")),
        )
        assertEquals(listOf(airSquat), out)
    }

    @Test fun `sub-area filter excludes rows whose sub_areas list is disjoint`() {
        // A row with no overlap with the selection is dropped. Two unrelated
        // sub-areas → empty result, no defensive "show everything" fallback.
        val out = MmExerciseListFilterEngine.apply(
            all, MmExerciseListFilter(subAreas = setOf("Shoulders | Deltoid")),
        )
        assertTrue(out.isEmpty())
    }

    @Test fun `area and sub-area combine disjunctively when both populated`() {
        // The Browser's multi-select muscle grid pre-fills BOTH `areas` (for
        // cards with no sub-area split, e.g. Upper Chest) and `subAreas` (for
        // cards with sub-areas, e.g. Quadriceps) when the user picks across
        // the two. The filter engine OR's them so multi-selecting "Chest" +
        // "Quadriceps" returns the union, not the (empty) intersection.
        val out = MmExerciseListFilterEngine.apply(
            all,
            MmExerciseListFilter(
                areas = setOf("Chest"),
                subAreas = setOf("Legs | Quads"),
            ),
        )
        assertEquals(listOf(benchPress, airSquat), out)
    }

    @Test fun `area filter alone still narrows strictly when sub-area is empty`() {
        // Disjunctive behaviour ONLY kicks in when both clauses are populated.
        // Area-only must still strictly filter, otherwise the dropdown filter
        // UX in the LIST view would silently show extra rows.
        val out = MmExerciseListFilterEngine.apply(
            all, MmExerciseListFilter(areas = setOf("Legs")),
        )
        assertEquals(listOf(airSquat), out)
    }

    @Test fun `area-and-subarea OR combines conjunctively with other dimensions`() {
        // The OR'd muscle clause must still AND with equipment / category /
        // type / movement_pattern. Picking Chest+Quads cards AND filtering to
        // Compound + Bodyweight should leave only Air Squat (Compound +
        // Bodyweight from the Quads side; Bench Press is excluded because
        // it's Barbell, not Bodyweight).
        val out = MmExerciseListFilterEngine.apply(
            all,
            MmExerciseListFilter(
                areas = setOf("Chest"),
                subAreas = setOf("Legs | Quads"),
                equipment = setOf("Bodyweight"),
            ),
        )
        assertEquals(listOf(airSquat), out)
    }

    @Test fun `equipment filter passes if any equipment overlaps the selection`() {
        // Bench press has both Barbell AND Bench — selecting only Bench should
        // still match it. Multi-valued equipment is OR within the row.
        val out = MmExerciseListFilterEngine.apply(
            all, MmExerciseListFilter(equipment = setOf("Bench")),
        )
        assertEquals(listOf(benchPress), out)
    }

    @Test fun `category filter narrows correctly`() {
        val out = MmExerciseListFilterEngine.apply(
            all, MmExerciseListFilter(categories = setOf("Isolation")),
        )
        assertEquals(listOf(cablePullover, plank), out)
    }

    @Test fun `type filter narrows correctly`() {
        val out = MmExerciseListFilterEngine.apply(
            all, MmExerciseListFilter(types = setOf("time")),
        )
        assertEquals(listOf(plank), out)
    }

    @Test fun `movement pattern filter excludes rows with no patterns`() {
        // Plank has empty movementPattern. A "Push" filter should leave only
        // bench press; plank is filtered out for having no patterns at all.
        val out = MmExerciseListFilterEngine.apply(
            all, MmExerciseListFilter(movementPatterns = setOf("Push")),
        )
        assertEquals(listOf(benchPress), out)
    }

    @Test fun `dimensions combine conjunctively`() {
        // Compound + Bodyweight → Air Squat only.
        val out = MmExerciseListFilterEngine.apply(
            all,
            MmExerciseListFilter(
                categories = setOf("Compound"),
                equipment = setOf("Bodyweight"),
            ),
        )
        assertEquals(listOf(airSquat), out)
    }

    @Test fun `query combines conjunctively with chip filters`() {
        val out = MmExerciseListFilterEngine.apply(
            all,
            MmExerciseListFilter(query = "squat", areas = setOf("Chest")),
        )
        // No row is both "squat"-named and Chest-area.
        assertTrue(out.isEmpty())
    }

    @Test fun `isEmpty reflects the filter state`() {
        assertTrue(MmExerciseListFilter().isEmpty)
        assertTrue(MmExerciseListFilter(query = "  ").isEmpty)
        assertTrue(!MmExerciseListFilter(query = "x").isEmpty)
        assertTrue(!MmExerciseListFilter(movementPatterns = setOf("Push")).isEmpty)
        assertTrue(!MmExerciseListFilter(subAreas = setOf("Legs | Quads")).isEmpty)
    }

    private fun ex(
        id: String,
        name: String,
        type: String?,
        category: String,
        area: String,
        equipment: List<String>,
        movementPattern: List<String>,
        subAreas: List<String> = emptyList(),
    ) = MmExercise(
        id = id,
        name = name,
        type = type,
        isPaid = false,
        category = category,
        area = area,
        subAreas = subAreas,
        equipment = equipment,
        movementPattern = movementPattern,
        targetMuscles = emptyList(),
        synergistMuscles = emptyList(),
        stabilizerMuscles = emptyList(),
        defaultWorkoutFields = emptyList(),
        thumbnailUrl = null,
        videoUrl = null,
        videoDurationSec = null,
    )
}
