package com.example.hevycompanion.browse

import com.example.hevycompanion.data.ExerciseTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HevySimilarExercisesTest {

    private val benchBarbell = tpl("BENCH_BB", "Bench Press (Barbell)", "barbell", "chest")
    private val inclineBarbell = tpl("INCL_BB", "Incline Bench Press (Barbell)", "barbell", "chest")
    private val benchDumbbell = tpl("BENCH_DB", "Bench Press (Dumbbell)", "dumbbell", "chest")
    private val squatBarbell = tpl("SQ_BB", "Squat (Barbell)", "barbell", "quadriceps")
    private val pullUp = tpl("PULLUP", "Pull Up", "none", "lats")
    private val chinUp = tpl("CHINUP", "Chin Up", "none", "lats")

    private val catalog = listOf(
        benchBarbell, inclineBarbell, benchDumbbell, squatBarbell, pullUp, chinUp,
    )

    @Test fun `matches on equipment AND primary muscle, excludes self`() {
        val out = HevySimilarExercises.find(benchBarbell, catalog)
        assertEquals(listOf(inclineBarbell), out)
    }

    @Test fun `mismatched equipment is excluded even with same muscle`() {
        // Bench Press DB shares muscle (chest) but not equipment (dumbbell != barbell)
        // — same rule the watch uses.
        val out = HevySimilarExercises.find(benchBarbell, catalog)
        assertTrue(benchDumbbell !in out)
    }

    @Test fun `mismatched muscle is excluded even with same equipment`() {
        val out = HevySimilarExercises.find(benchBarbell, catalog)
        assertTrue(squatBarbell !in out)
    }

    @Test fun `comparison is case-insensitive on both axes`() {
        // Hevy's REST catalog mixes casings; the watch normalises ignoreCase, mirror that.
        val mixedCase = tpl("BENCH_BB_UC", "Bench Press UC", "Barbell", "Chest")
        val out = HevySimilarExercises.find(benchBarbell, catalog + mixedCase)
        assertTrue(mixedCase in out)
    }

    @Test fun `bodyweight pairs are still grouped (unlike weight-suggestion)`() {
        // The watch's SimilarExerciseSuggestion skips equipment="none" because there
        // is no weight to suggest. For browsing, we want Pull Up <-> Chin Up.
        val out = HevySimilarExercises.find(pullUp, catalog)
        assertEquals(listOf(chinUp), out)
    }

    @Test fun `null equipment yields empty list`() {
        val noEquip = tpl("X", "Mystery", null, "chest")
        assertTrue(HevySimilarExercises.find(noEquip, catalog).isEmpty())
    }

    @Test fun `null muscle yields empty list`() {
        val noMuscle = tpl("X", "Mystery", "barbell", null)
        assertTrue(HevySimilarExercises.find(noMuscle, catalog).isEmpty())
    }

    @Test fun `result is sorted alphabetically by title`() {
        val a = tpl("A", "Zebra Curl", "barbell", "biceps")
        val b = tpl("B", "Anvil Curl", "barbell", "biceps")
        val c = tpl("C", "Mystery Curl", "barbell", "biceps")
        val target = tpl("T", "Target Curl", "barbell", "biceps")
        val out = HevySimilarExercises.find(target, listOf(a, b, c, target))
        assertEquals(listOf(b, c, a), out)
    }

    private fun tpl(id: String, title: String, equipment: String?, muscle: String?) =
        ExerciseTemplate(
            id = id,
            title = title,
            type = "weight_reps",
            primaryMuscleGroup = muscle,
            secondaryMuscleGroups = null,
            equipment = equipment,
            isCustom = false,
        )
}
