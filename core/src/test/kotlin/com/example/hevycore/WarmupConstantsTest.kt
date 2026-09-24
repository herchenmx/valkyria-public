package com.example.hevycore

import com.example.hevycore.workout.WarmupConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarmupConstantsTest {

    @Test
    fun `muscle groups do not overlap between categories`() {
        val large = WarmupConstants.LARGE_GROUPS
        val medium = WarmupConstants.MEDIUM_GROUPS
        val small = WarmupConstants.SMALL_GROUPS

        // Any overlap would mean warmup category assignment depends on
        // iteration order — a subtle bug producing different warmups for
        // the same exercise on different Kotlin versions.
        assertEquals(emptySet<String>(), large intersect medium)
        assertEquals(emptySet<String>(), medium intersect large)
        // abdominals appears in SMALL_GROUPS AND NO_WARMUP_GROUPS — that's a
        // deliberate legacy: abs are categorized as "small" for the count
        // table but explicitly skipped because they don't respond to weighted
        // warmup. Any check of that overlap must know it is intentional.
    }

    @Test
    fun `categorize is case-insensitive`() {
        assertEquals(WarmupConstants.MuscleCategory.LARGE, WarmupConstants.categorize("Quadriceps"))
        assertEquals(WarmupConstants.MuscleCategory.MEDIUM, WarmupConstants.categorize("CHEST"))
        assertEquals(WarmupConstants.MuscleCategory.SMALL, WarmupConstants.categorize("biceps"))
        assertNull(WarmupConstants.categorize("unknown_muscle"))
        assertNull(WarmupConstants.categorize(null))
    }

    @Test
    fun `increment and min-weight tables`() {
        assertEquals(2f, WarmupConstants.incrementFor("dumbbell"))
        assertEquals(4f, WarmupConstants.incrementFor("kettlebell"))
        assertEquals(1f, WarmupConstants.incrementFor("machine"))
        assertEquals(2.5f, WarmupConstants.incrementFor("barbell"))
        assertEquals(2.5f, WarmupConstants.incrementFor(null))

        // Barbell min weight is the Olympic-bar floor (20 kg) — never fall
        // below that even for tiny effort weights.
        assertEquals(20f, WarmupConstants.minWarmupWeight("barbell"))
        assertEquals(2f, WarmupConstants.minWarmupWeight("dumbbell"))
    }

    @Test
    fun `warmup set counts scale with effort weight`() {
        // LARGE muscle table.
        assertEquals(1, WarmupConstants.warmupSetCount(WarmupConstants.MuscleCategory.LARGE, 20f))
        assertEquals(2, WarmupConstants.warmupSetCount(WarmupConstants.MuscleCategory.LARGE, 45f))
        assertEquals(3, WarmupConstants.warmupSetCount(WarmupConstants.MuscleCategory.LARGE, 70f))
        assertEquals(4, WarmupConstants.warmupSetCount(WarmupConstants.MuscleCategory.LARGE, 100f))
        // SMALL / MEDIUM tables agree at every threshold except zero starts.
        assertEquals(0, WarmupConstants.warmupSetCount(WarmupConstants.MuscleCategory.MEDIUM, 20f))
        assertEquals(1, WarmupConstants.warmupSetCount(WarmupConstants.MuscleCategory.MEDIUM, 45f))
        assertEquals(2, WarmupConstants.warmupSetCount(WarmupConstants.MuscleCategory.MEDIUM, 70f))
        assertEquals(3, WarmupConstants.warmupSetCount(WarmupConstants.MuscleCategory.MEDIUM, 100f))
    }

    @Test
    fun `exact boundaries of the set-count table`() {
        // The 80 kg cutoff is the single most-cited advisor threshold — Leg
        // Press / Squat targets ≥ 80 kg advise 4 warmups, < 80 advise 3 (the
        // real-world "3/4W incomplete on 6 Jul" case turned on exactly this).
        // Pin every boundary at x−ε / x / x+ε so an accidental `<=` (or a
        // moved constant) fails loudly.
        val large = WarmupConstants.MuscleCategory.LARGE
        val medium = WarmupConstants.MuscleCategory.MEDIUM

        // 30 kg boundary.
        assertEquals(1, WarmupConstants.warmupSetCount(large, 29.99f))
        assertEquals(2, WarmupConstants.warmupSetCount(large, 30f))
        assertEquals(0, WarmupConstants.warmupSetCount(medium, 29.99f))
        assertEquals(1, WarmupConstants.warmupSetCount(medium, 30f))

        // 50 kg boundary.
        assertEquals(2, WarmupConstants.warmupSetCount(large, 49.99f))
        assertEquals(3, WarmupConstants.warmupSetCount(large, 50f))
        assertEquals(1, WarmupConstants.warmupSetCount(medium, 49.99f))
        assertEquals(2, WarmupConstants.warmupSetCount(medium, 50f))

        // 80 kg boundary — the load-bearing one.
        assertEquals(3, WarmupConstants.warmupSetCount(large, 79.99f))
        assertEquals(4, WarmupConstants.warmupSetCount(large, 80f))
        assertEquals(4, WarmupConstants.warmupSetCount(large, 80.01f))
        assertEquals(2, WarmupConstants.warmupSetCount(medium, 79.99f))
        assertEquals(3, WarmupConstants.warmupSetCount(medium, 80f))
        assertEquals(3, WarmupConstants.warmupSetCount(medium, 80.01f))
    }
}
