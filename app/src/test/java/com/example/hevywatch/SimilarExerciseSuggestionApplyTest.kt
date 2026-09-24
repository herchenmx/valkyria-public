package com.example.hevywatch

import com.example.hevywatch.data.SuggestedWeight
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.applySimilarSuggestion
import com.example.hevywatch.presentation.workout.exerciseNeedsSimilarSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the per-exercise gate and apply step of the similar-exercise weight
 * suggestion. Previously the gate considered `weight_kg: 0` (a real API value
 * for "no working weight prescribed") to be a prescribed weight, so the
 * suggestion was added to the chip-display map but skipped when overwriting
 * `set.weightKg` — LogSetScreen then read `0` from the set and the user saw
 * a blank picker even though the chip showed a suggestion.
 */
class SimilarExerciseSuggestionApplyTest {

    private fun exercise(sets: List<ActiveSet>) = ActiveExercise(
        exerciseTemplateId = "t1",
        title = "Squat",
        equipment = "barbell",
        sets = sets,
    )

    private fun normal(weightKg: Float?) = ActiveSet(setType = SetType.NORMAL, weightKg = weightKg)
    private fun warmup(weightKg: Float?) = ActiveSet(setType = SetType.WARMUP, weightKg = weightKg)

    // ── exerciseNeedsSimilarSuggestion ────────────────────────────────────────

    @Test
    fun `needs suggestion when every normal set has null weight`() {
        val ex = exercise(listOf(normal(null), normal(null), normal(null)))
        assertTrue(exerciseNeedsSimilarSuggestion(ex))
    }

    @Test
    fun `needs suggestion when every normal set has zero weight (API returns 0 not null)`() {
        val ex = exercise(listOf(normal(0f), normal(0f), normal(0f)))
        assertTrue(exerciseNeedsSimilarSuggestion(ex))
    }

    @Test
    fun `needs suggestion when normal sets mix zero and null weights`() {
        val ex = exercise(listOf(normal(0f), normal(null), normal(0f)))
        assertTrue(exerciseNeedsSimilarSuggestion(ex))
    }

    @Test
    fun `does NOT need suggestion when any normal set has a real working weight`() {
        val ex = exercise(listOf(normal(null), normal(60f), normal(0f)))
        assertFalse(exerciseNeedsSimilarSuggestion(ex))
    }

    @Test
    fun `warmup sets with weight do not satisfy the gate`() {
        val ex = exercise(listOf(warmup(40f), normal(null), normal(0f)))
        assertTrue(exerciseNeedsSimilarSuggestion(ex))
    }

    // ── applySimilarSuggestion ────────────────────────────────────────────────

    @Test
    fun `apply overwrites null normal-set weights with suggestion and flags them`() {
        val ex = exercise(listOf(normal(null), normal(null), normal(null)))
        val result = applySimilarSuggestion(ex, SuggestedWeight(20f, "Bench Press"))

        result.sets.forEach { s ->
            assertEquals(20f, s.weightKg)
            assertTrue(s.isSimilarSuggestion)
        }
    }

    @Test
    fun `apply overwrites zero normal-set weights with suggestion`() {
        // Regression: `weight_kg: 0` from the routine API used to bypass the
        // overwrite (the older `s.weightKg == null` gate), leaving LogSetScreen
        // showing 0.0 while the LogWorkoutScreen chip showed the suggestion.
        val ex = exercise(listOf(normal(0f), normal(0f), normal(0f)))
        val result = applySimilarSuggestion(ex, SuggestedWeight(25f, "Bench Press"))

        result.sets.forEach { s ->
            assertEquals(25f, s.weightKg)
            assertTrue(s.isSimilarSuggestion)
        }
    }

    @Test
    fun `apply does not touch warmup sets`() {
        val ex = exercise(listOf(warmup(10f), normal(null), normal(null)))
        val result = applySimilarSuggestion(ex, SuggestedWeight(30f, "Bench Press"))

        assertEquals(SetType.WARMUP, result.sets[0].setType)
        assertEquals(10f, result.sets[0].weightKg)
        assertFalse(result.sets[0].isSimilarSuggestion)

        assertEquals(30f, result.sets[1].weightKg)
        assertTrue(result.sets[1].isSimilarSuggestion)
        assertEquals(30f, result.sets[2].weightKg)
        assertTrue(result.sets[2].isSimilarSuggestion)
    }

    @Test
    fun `apply preserves non-weight fields on normal sets`() {
        val original = ActiveSet(
            setType = SetType.NORMAL,
            weightKg = null,
            reps = 12,
            repRangeStart = 10,
            repRangeEnd = 15,
        )
        val ex = exercise(listOf(original))
        val result = applySimilarSuggestion(ex, SuggestedWeight(40f, "Bench Press"))

        assertEquals(12, result.sets[0].reps)
        assertEquals(10, result.sets[0].repRangeStart)
        assertEquals(15, result.sets[0].repRangeEnd)
    }
}
