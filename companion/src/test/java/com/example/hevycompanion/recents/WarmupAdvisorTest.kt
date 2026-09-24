package com.example.hevycompanion.recents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the companion's warmup-advisor port against the watch's protocol tables
 * (PRD-WATCH-APP.md §"Warmup Advisor").
 */
class WarmupAdvisorTest {

    @Test fun `large muscle heavy machine gets the 4-set protocol`() {
        // quadriceps (LARGE) + machine + 100kg → 4 sets at 30/50/70/85%, machine
        // increment 1kg → 30/50/70/85, reps 12/8/4/2.
        val sets = WarmupAdvisor.suggest(
            primaryMuscleGroup = "quadriceps", equipment = "machine",
            workingWeightKg = 100f, normalSetCount = 3, exerciseTemplateId = "X", bodyweightKg = 57f,
        )
        assertEquals(4, sets.size)
        assertEquals(WarmupSet(30f, 12), sets[0])
        assertEquals(WarmupSet(50f, 8), sets[1])
        assertEquals(WarmupSet(70f, 4), sets[2])
        assertEquals(WarmupSet(85f, 2), sets[3])
    }

    @Test fun `bodyweight equipment gets no warmups`() {
        val sets = WarmupAdvisor.suggest("chest", "none", 0f, 3, "X", 57f)
        assertTrue(sets.isEmpty())
    }

    @Test fun `suppressed muscle group gets no warmups`() {
        val sets = WarmupAdvisor.suggest("abdominals", "machine", 80f, 3, "X", 57f)
        assertTrue(sets.isEmpty())
    }

    @Test fun `unilateral exercise doubles every warmup set`() {
        // 6 normal sets (even, ≥6) → each warmup level appears twice.
        val sets = WarmupAdvisor.suggest("quadriceps", "machine", 100f, 6, "X", 57f)
        assertEquals(8, sets.size)
        assertEquals(sets[0], sets[1]) // first level doubled
        assertEquals(WarmupSet(30f, 12), sets[0])
    }

    @Test fun `assisted exercise warmups come out higher in logged kg`() {
        // Assisted lat pulldown E9E4089F, machine + lats (LARGE), bodyweight 57,
        // working logged 9 → effort 48 → 2-set protocol (50/70%). Effort warmups
        // 24/33 → logged 57−24=33, 57−33=24 (more assist = easier warmup).
        val sets = WarmupAdvisor.suggest("lats", "machine", 9f, 3, "E9E4089F", 57f)
        assertEquals(2, sets.size)
        assertEquals(WarmupSet(33f, 10), sets[0])
        assertEquals(WarmupSet(24f, 5), sets[1])
    }
}
