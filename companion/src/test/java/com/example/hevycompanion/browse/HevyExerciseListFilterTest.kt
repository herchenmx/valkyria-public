package com.example.hevycompanion.browse

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.HevyExerciseAttrs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HevyExerciseListFilterTest {

    private val benchPress = tpl(
        id = "BENCH",
        title = "Bench Press (Barbell)",
        type = "weight_reps",
        muscle = "chest",
        equipment = "barbell",
    )
    private val pullUp = tpl(
        id = "PULLUP",
        title = "Pull Up",
        type = "bodyweight_reps",
        muscle = "lats",
        equipment = "none",
    )
    private val squat = tpl(
        id = "SQUAT",
        title = "Squat (Barbell)",
        type = "weight_reps",
        muscle = "quadriceps",
        equipment = "barbell",
    )
    private val running = tpl(
        id = "RUN",
        title = "Running",
        type = "distance_duration",
        muscle = "cardio",
        equipment = "none",
    )

    private val all = listOf(benchPress, pullUp, squat, running)
    private val attrs = mapOf(
        "BENCH"  to HevyExerciseAttrs(level = listOf("beginner", "intermediate"), goal = emptyList(), category = "compound"),
        "PULLUP" to HevyExerciseAttrs(level = listOf("intermediate", "advanced"), goal = emptyList(), category = "compound"),
        "SQUAT"  to HevyExerciseAttrs(level = listOf("advanced"), goal = emptyList(), category = "compound"),
        "RUN"    to HevyExerciseAttrs(level = listOf("beginner"), goal = emptyList(), category = null),
    )

    @Test fun `empty filter returns the full catalog unchanged`() {
        val out = HevyExerciseListFilterEngine.apply(all, attrs, HevyExerciseListFilter())
        assertEquals(all, out)
    }

    @Test fun `query is case-insensitive substring on title`() {
        val out = HevyExerciseListFilterEngine.apply(all, attrs, HevyExerciseListFilter(query = "PRESS"))
        assertEquals(listOf(benchPress), out)
    }

    @Test fun `query of just whitespace is treated as no query`() {
        val out = HevyExerciseListFilterEngine.apply(all, attrs, HevyExerciseListFilter(query = "   "))
        assertEquals(all, out)
    }

    @Test fun `muscle filter is disjunctive within the dimension`() {
        val out = HevyExerciseListFilterEngine.apply(
            all, attrs,
            HevyExerciseListFilter(muscleGroups = setOf("chest", "lats")),
        )
        assertEquals(listOf(benchPress, pullUp), out)
    }

    @Test fun `equipment filter narrows correctly`() {
        val out = HevyExerciseListFilterEngine.apply(
            all, attrs,
            HevyExerciseListFilter(equipment = setOf("barbell")),
        )
        assertEquals(listOf(benchPress, squat), out)
    }

    @Test fun `exercise type filter narrows correctly`() {
        val out = HevyExerciseListFilterEngine.apply(
            all, attrs,
            HevyExerciseListFilter(exerciseTypes = setOf("bodyweight_reps")),
        )
        assertEquals(listOf(pullUp), out)
    }

    @Test fun `level filter passes if any exercise level overlaps the selection`() {
        // beginner ∈ {bench, running}; squat is advanced-only so it's filtered out.
        val out = HevyExerciseListFilterEngine.apply(
            all, attrs,
            HevyExerciseListFilter(levels = setOf("beginner")),
        )
        assertEquals(listOf(benchPress, running), out)
    }

    @Test fun `category filter excludes rows with no category attr`() {
        // running has category=null, so it should be filtered out even though it's
        // not "isolation" — the user asked for "compound", and unknown categories
        // are ambiguous so we err on the side of excluding them.
        val out = HevyExerciseListFilterEngine.apply(
            all, attrs,
            HevyExerciseListFilter(categories = setOf("compound")),
        )
        assertEquals(listOf(benchPress, pullUp, squat), out)
    }

    @Test fun `dimensions combine conjunctively`() {
        // chest AND barbell AND beginner → only bench press qualifies.
        val out = HevyExerciseListFilterEngine.apply(
            all, attrs,
            HevyExerciseListFilter(
                muscleGroups = setOf("chest"),
                equipment = setOf("barbell"),
                levels = setOf("beginner"),
            ),
        )
        assertEquals(listOf(benchPress), out)
    }

    @Test fun `query combines conjunctively with chip filters`() {
        val out = HevyExerciseListFilterEngine.apply(
            all, attrs,
            HevyExerciseListFilter(query = "press", equipment = setOf("none")),
        )
        // No row matches both "press" in title and equipment=none.
        assertTrue(out.isEmpty())
    }

    @Test fun `missing attrs disables the level filter for that row`() {
        // Pull up has attrs in our map; remove them and the level filter should
        // exclude it because there are no levels to match against.
        val noPullUpAttrs = attrs - "PULLUP"
        val out = HevyExerciseListFilterEngine.apply(
            all, noPullUpAttrs,
            HevyExerciseListFilter(levels = setOf("intermediate")),
        )
        assertEquals(listOf(benchPress), out)
    }

    @Test fun `isEmpty reflects the filter state`() {
        assertTrue(HevyExerciseListFilter().isEmpty)
        assertTrue(HevyExerciseListFilter(query = "  ").isEmpty)
        assertTrue(!HevyExerciseListFilter(query = "x").isEmpty)
        assertTrue(!HevyExerciseListFilter(equipment = setOf("barbell")).isEmpty)
    }

    private fun tpl(
        id: String,
        title: String,
        type: String?,
        muscle: String?,
        equipment: String?,
    ) = ExerciseTemplate(
        id = id,
        title = title,
        type = type,
        primaryMuscleGroup = muscle,
        secondaryMuscleGroups = null,
        equipment = equipment,
        isCustom = false,
    )
}
