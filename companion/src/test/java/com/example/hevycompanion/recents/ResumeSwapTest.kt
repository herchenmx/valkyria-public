package com.example.hevycompanion.recents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Companion Resume swap support: `SubstitutionMap.substitutesFor` (ported from
 * the watch) and the recency sort key used to order the swap picker.
 */
class ResumeSwapTest {

    // ── substitutesFor ────────────────────────────────────────────────────────

    @Test
    fun `substitutesFor returns other group members and excludes self`() {
        val subs = SubstitutionMap.substitutesFor("D5D0354D") // Lateral Raise (Machine)
        assertFalse(subs.contains("D5D0354D"))
        assertTrue(subs.contains("422B08F1")) // Dumbbell
        assertTrue(subs.contains("BE289E45")) // Cable
    }

    @Test
    fun `substitutesFor includes iso-lateral chest press for chest press machine`() {
        // The flat chest/bench-press group shipped earlier.
        assertTrue(SubstitutionMap.substitutesFor("7EB3F7C3").contains("24706DCD"))
    }

    @Test
    fun `substitutesFor is empty for an ungrouped exercise`() {
        assertTrue(SubstitutionMap.substitutesFor("37FCC2BB").isEmpty()) // Bicep Curl
    }

    // ── recency sort key ──────────────────────────────────────────────────────

    @Test
    fun `never-used exercise sorts last via MIN_VALUE`() {
        assertEquals(Long.MIN_VALUE, ResumeWorkoutViewModel.lastUsedEpochMsOf(emptyList()))
        assertEquals(Long.MIN_VALUE, ResumeWorkoutViewModel.lastUsedEpochMsOf(listOf(null, "not-a-date")))
    }

    @Test
    fun `picks the most recent session and a recent exercise outranks an older one`() {
        val recent = ResumeWorkoutViewModel.lastUsedEpochMsOf(
            listOf("2026-06-20T08:00:00+00:00", "2026-06-26T11:54:03+00:00")
        )
        assertEquals(Instant.parse("2026-06-26T11:54:03Z").toEpochMilli(), recent)

        val older = ResumeWorkoutViewModel.lastUsedEpochMsOf(listOf("2026-01-02T09:00:00Z"))
        assertTrue("recent sorts ahead of old", recent > older)
    }

    // ── missing-warmup offer (resume can log skipped warmups) ──────────────────

    @Test
    fun `missingWarmups offers the still-owed advisor warmups`() {
        val advised = listOf(WarmupSet(40f, 10), WarmupSet(60f, 6), WarmupSet(80f, 3))
        // None logged → all three owed (e.g. all normals done, warmups skipped).
        assertEquals(3, ResumeWorkoutViewModel.missingWarmups(advised, 0).size)
        // One logged → the two heavier ones still owed.
        assertEquals(listOf(WarmupSet(60f, 6), WarmupSet(80f, 3)), ResumeWorkoutViewModel.missingWarmups(advised, 1))
        // All (or more) logged → nothing owed.
        assertTrue(ResumeWorkoutViewModel.missingWarmups(advised, 3).isEmpty())
        assertTrue(ResumeWorkoutViewModel.missingWarmups(advised, 5).isEmpty())
        // No advised warmups → nothing owed (a green exercise stays out of resume).
        assertTrue(ResumeWorkoutViewModel.missingWarmups(emptyList(), 0).isEmpty())
    }
}
