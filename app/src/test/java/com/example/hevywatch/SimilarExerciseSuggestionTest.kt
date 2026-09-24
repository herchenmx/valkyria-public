package com.example.hevywatch

import com.example.hevywatch.data.scaleSimilarExerciseWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Target weight suggestion for a never-worked exercise: take 60% of the matched
 * similar exercise's last working weight and round to the nearest 2.5kg.
 */
class SimilarExerciseSuggestionTest {

    @Test
    fun `face pull example - 18kg rear delt fly rounds to 10kg`() {
        // 18 * 0.60 = 10.8 → nearest 2.5 multiple is 10.0 (delta 0.8 vs 12.5 delta 1.7).
        assertEquals(10f, scaleSimilarExerciseWeight(18f))
    }

    @Test
    fun `20kg similar rounds to 12_5kg`() {
        // 20 * 0.60 = 12.0 → nearest 2.5 multiple is 12.5 (delta 0.5 vs 10.0 delta 2.0).
        assertEquals(12.5f, scaleSimilarExerciseWeight(20f))
    }

    @Test
    fun `40kg similar rounds to 25kg`() {
        // 40 * 0.60 = 24.0 → nearest 2.5 multiple is 25.0 (delta 1.0 vs 22.5 delta 1.5).
        assertEquals(25f, scaleSimilarExerciseWeight(40f))
    }

    @Test
    fun `50kg similar rounds to 30kg`() {
        // 50 * 0.60 = 30.0 → exact 2.5 multiple.
        assertEquals(30f, scaleSimilarExerciseWeight(50f))
    }

    @Test
    fun `10kg similar rounds to 5kg`() {
        // 10 * 0.60 = 6.0 → nearest 2.5 multiple is 5.0 (delta 1.0 vs 7.5 delta 1.5).
        assertEquals(5f, scaleSimilarExerciseWeight(10f))
    }

    @Test
    fun `8kg similar rounds to 5kg`() {
        // 8 * 0.60 = 4.8 → nearest 2.5 multiple is 5.0.
        assertEquals(5f, scaleSimilarExerciseWeight(8f))
    }

    @Test
    fun `2kg similar rounds to 0kg`() {
        // 2 * 0.60 = 1.2 → nearest 2.5 multiple is 0.0. Caller skips zero suggestions.
        assertEquals(0f, scaleSimilarExerciseWeight(2f))
    }

    @Test
    fun `100kg similar rounds to 60kg`() {
        // 100 * 0.60 = 60.0 → exact 2.5 multiple.
        assertEquals(60f, scaleSimilarExerciseWeight(100f))
    }

    // ── Barbell 20kg floor on normal-set suggestion ───────────────────────────
    // Matches the warmup advisor's barbell floor — a barbell normal set can't
    // start below the 20kg bar either.

    @Test
    fun `barbell - landmine row example - 32_5kg bent over row stays at 20kg`() {
        // 32.5 * 0.60 = 19.5 → nearest 2.5 = 20.0 (floor already matches).
        assertEquals(20f, scaleSimilarExerciseWeight(32.5f, "barbell"))
    }

    @Test
    fun `barbell - 25kg similar is floored to 20kg`() {
        // 25 * 0.60 = 15 → without floor; barbell floor coerces to 20.
        assertEquals(20f, scaleSimilarExerciseWeight(25f, "barbell"))
    }

    @Test
    fun `barbell - 10kg similar is floored to 20kg`() {
        // 10 * 0.60 = 6 → rounds to 5; barbell floor coerces to 20.
        assertEquals(20f, scaleSimilarExerciseWeight(10f, "barbell"))
    }

    @Test
    fun `barbell - 50kg similar stays at 30kg (no floor override)`() {
        // 50 * 0.60 = 30 → above 20kg, floor doesn't kick in.
        assertEquals(30f, scaleSimilarExerciseWeight(50f, "barbell"))
    }

    @Test
    fun `machine - 25kg similar is NOT floored`() {
        // Machine has no 20kg floor — stays at 15kg.
        assertEquals(15f, scaleSimilarExerciseWeight(25f, "machine"))
    }

    @Test
    fun `dumbbell - 18kg similar is NOT floored`() {
        // 18 * 0.60 = 10.8 → rounds to 10.0; dumbbell has no 20kg floor.
        assertEquals(10f, scaleSimilarExerciseWeight(18f, "dumbbell"))
    }

    @Test
    fun `equipment case insensitive`() {
        // "Barbell" / "BARBELL" should behave the same as "barbell".
        assertEquals(20f, scaleSimilarExerciseWeight(25f, "Barbell"))
        assertEquals(20f, scaleSimilarExerciseWeight(25f, "BARBELL"))
    }
}
