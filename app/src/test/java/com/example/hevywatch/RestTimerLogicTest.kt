package com.example.hevywatch

import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.computeRestSecondsForSet
import com.example.hevywatch.presentation.workout.restRemainingSeconds
import org.junit.Assert.assertEquals
import org.junit.Test

class RestTimerLogicTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun warmup(w: Float = 40f) = ActiveSet(setType = SetType.WARMUP, weightKg = w, reps = 10)
    private fun normal(w: Float = 100f) = ActiveSet(setType = SetType.NORMAL, weightKg = w, reps = 10)

    private fun exercise(
        sets: List<ActiveSet>,
        restSeconds: Int? = 90
    ) = ActiveExercise(
        exerciseTemplateId = "ex1",
        title = "Test Exercise",
        sets = sets,
        restTimerSeconds = restSeconds
    )

    // ── Bilateral (odd normal-set count) ─────────────────────────────────────

    @Test
    fun `bilateral - warmup to warmup is 45s`() {
        val ex = exercise(sets = listOf(warmup(), warmup(), warmup(), normal(), normal(), normal()))
        assertEquals(45, computeRestSecondsForSet(ex, 0))  // warmup 1 → warmup 2
        assertEquals(45, computeRestSecondsForSet(ex, 1))  // warmup 2 → warmup 3
    }

    @Test
    fun `bilateral - last warmup to first normal is 60s`() {
        val ex = exercise(sets = listOf(warmup(), warmup(), warmup(), normal(), normal(), normal()))
        assertEquals(60, computeRestSecondsForSet(ex, 2))  // warmup 3 → normal 1
    }

    @Test
    fun `bilateral - normal to normal uses routine default`() {
        val ex = exercise(
            sets = listOf(warmup(), normal(), normal(), normal()),
            restSeconds = 120
        )
        assertEquals(120, computeRestSecondsForSet(ex, 1))  // normal 1 → normal 2
        assertEquals(120, computeRestSecondsForSet(ex, 2))  // normal 2 → normal 3
    }

    @Test
    fun `bilateral - no warmups, normal to normal uses default`() {
        val ex = exercise(
            sets = listOf(normal(), normal(), normal()),
            restSeconds = 90
        )
        assertEquals(90, computeRestSecondsForSet(ex, 0))
        assertEquals(90, computeRestSecondsForSet(ex, 1))
    }

    @Test
    fun `bilateral - last set returns 0`() {
        val ex = exercise(sets = listOf(normal(), normal(), normal()))
        assertEquals(0, computeRestSecondsForSet(ex, 2))
    }

    // ── Unilateral (even normal-set count, >= 6) ─────────────────────────────

    @Test
    fun `unilateral - no warmups, odd normal set skips rest (switch sides)`() {
        val ex = exercise(
            sets = List(6) { normal() },
            restSeconds = 90
        )
        assertEquals(0, computeRestSecondsForSet(ex, 0))  // normal 1 (odd) → switch sides, no rest
        assertEquals(0, computeRestSecondsForSet(ex, 2))  // normal 3 (odd) → switch sides, no rest
        assertEquals(0, computeRestSecondsForSet(ex, 4))  // normal 5 (odd) → switch sides, no rest
    }

    @Test
    fun `unilateral - no warmups, even normal set uses default`() {
        val ex = exercise(
            sets = List(6) { normal() },
            restSeconds = 90
        )
        assertEquals(90, computeRestSecondsForSet(ex, 1))  // normal 2 (even) → default
        assertEquals(90, computeRestSecondsForSet(ex, 3))  // normal 4 (even) → default
    }

    @Test
    fun `8 normal sets - odd set skips rest, even uses default`() {
        val ex = exercise(
            sets = List(8) { normal() },
            restSeconds = 60
        )
        assertEquals(0, computeRestSecondsForSet(ex, 0))   // odd → switch sides
        assertEquals(60, computeRestSecondsForSet(ex, 1))  // even → default
        assertEquals(0, computeRestSecondsForSet(ex, 2))   // odd → switch sides
        assertEquals(60, computeRestSecondsForSet(ex, 3))  // even → default
    }

    @Test
    fun `unilateral - warmup odd skips rest (switch side)`() {
        // 6 warmups + 6 normals
        val sets = List(6) { warmup() } + List(6) { normal() }
        val ex = exercise(sets = sets)
        assertEquals(0, computeRestSecondsForSet(ex, 0))   // warmup 1 → warmup 2: 0s
        assertEquals(0, computeRestSecondsForSet(ex, 2))   // warmup 3 → warmup 4: 0s
        assertEquals(0, computeRestSecondsForSet(ex, 4))   // warmup 5 → warmup 6: 0s
    }

    @Test
    fun `unilateral - warmup even gives 30s (next weight)`() {
        val sets = List(6) { warmup() } + List(6) { normal() }
        val ex = exercise(sets = sets)
        assertEquals(30, computeRestSecondsForSet(ex, 1))  // warmup 2 → warmup 3: 30s
        assertEquals(30, computeRestSecondsForSet(ex, 3))  // warmup 4 → warmup 5: 30s
    }

    @Test
    fun `unilateral - last warmup to first normal is 60s`() {
        val sets = List(6) { warmup() } + List(6) { normal() }
        val ex = exercise(sets = sets)
        assertEquals(60, computeRestSecondsForSet(ex, 5))  // warmup 6 → normal 1: 60s
    }

    @Test
    fun `unilateral - normal rest alternates 0s and default after warmups`() {
        val sets = List(6) { warmup() } + List(6) { normal() }
        val ex = exercise(sets = sets, restSeconds = 90)
        assertEquals(0, computeRestSecondsForSet(ex, 6))   // normal 1 (odd) → switch sides
        assertEquals(90, computeRestSecondsForSet(ex, 7))  // normal 2 (even) → default
        assertEquals(0, computeRestSecondsForSet(ex, 8))   // normal 3 (odd) → switch sides
        assertEquals(90, computeRestSecondsForSet(ex, 9))  // normal 4 (even) → default
        assertEquals(0, computeRestSecondsForSet(ex, 10))  // normal 5 (odd) → switch sides
    }

    @Test
    fun `unilateral - last normal set returns 0`() {
        val sets = List(6) { normal() }
        val ex = exercise(sets = sets)
        assertEquals(0, computeRestSecondsForSet(ex, 5))
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `4 normal sets is NOT unilateral (less than 6)`() {
        val ex = exercise(
            sets = List(4) { normal() },
            restSeconds = 90
        )
        // Should use default, not the 15s unilateral pattern
        assertEquals(90, computeRestSecondsForSet(ex, 0))
        assertEquals(90, computeRestSecondsForSet(ex, 1))
    }

    @Test
    fun `null rest seconds treated as 0`() {
        val ex = exercise(
            sets = listOf(normal(), normal(), normal()),
            restSeconds = null
        )
        assertEquals(0, computeRestSecondsForSet(ex, 0))
    }


    // ── Full scenario: unilateral with warmups (from PRD) ────────────────────

    @Test
    fun `full unilateral scenario - 6 warmups + 6 normals with 90s default`() {
        val sets = List(6) { warmup() } + List(6) { normal() }
        val ex = exercise(sets = sets, restSeconds = 90)

        // Warmup phase
        assertEquals(0, computeRestSecondsForSet(ex, 0))    // W1 → W2: switch sides
        assertEquals(30, computeRestSecondsForSet(ex, 1))   // W2 → W3: 30s
        assertEquals(0, computeRestSecondsForSet(ex, 2))    // W3 → W4: switch sides
        assertEquals(30, computeRestSecondsForSet(ex, 3))   // W4 → W5: 30s
        assertEquals(0, computeRestSecondsForSet(ex, 4))    // W5 → W6: switch sides
        assertEquals(60, computeRestSecondsForSet(ex, 5))   // W6 → N1: 60s

        // Normal phase
        assertEquals(0, computeRestSecondsForSet(ex, 6))    // N1 → N2: switch sides
        assertEquals(90, computeRestSecondsForSet(ex, 7))   // N2 → N3: 90s
        assertEquals(0, computeRestSecondsForSet(ex, 8))    // N3 → N4: switch sides
        assertEquals(90, computeRestSecondsForSet(ex, 9))   // N4 → N5: 90s
        assertEquals(0, computeRestSecondsForSet(ex, 10))   // N5 → N6: switch sides
        assertEquals(0, computeRestSecondsForSet(ex, 11))   // N6 (last): 0
    }

    // ── Full scenario: bilateral with warmups ────────────────────────────────

    @Test
    fun `full bilateral scenario - 3 warmups + 3 normals with 90s default`() {
        val sets = List(3) { warmup() } + List(3) { normal() }
        val ex = exercise(sets = sets, restSeconds = 90)

        assertEquals(45, computeRestSecondsForSet(ex, 0))   // W1 → W2: 45s
        assertEquals(45, computeRestSecondsForSet(ex, 1))   // W2 → W3: 45s
        assertEquals(60, computeRestSecondsForSet(ex, 2))   // W3 → N1: 60s
        assertEquals(90, computeRestSecondsForSet(ex, 3))   // N1 → N2: default
        assertEquals(90, computeRestSecondsForSet(ex, 4))   // N2 → N3: default
        assertEquals(0, computeRestSecondsForSet(ex, 5))    // N3 (last): 0
    }

    // ── restRemainingSeconds (countdown rounding) ────────────────────────────
    // Both the rest-timer screen and the in-clock countdown call this, so these
    // assertions are exactly what keeps the two displays from drifting 1s apart.

    @Test
    fun `remaining rounds up - exact second remaining shows that second`() {
        // 5.000s left → "5"
        assertEquals(5, restRemainingSeconds(endMs = 5_000L, nowMs = 0L))
    }

    @Test
    fun `remaining rounds up - sub-second remainder shows the higher second`() {
        // 4.999s left → "5" (NOT 4, which integer-division floor produced)
        assertEquals(5, restRemainingSeconds(endMs = 4_999L, nowMs = 0L))
        // 0.001s left → "1": still resting, must not read 0 yet
        assertEquals(1, restRemainingSeconds(endMs = 9_001L, nowMs = 9_000L))
    }

    @Test
    fun `remaining is 0 only at or after the end instant`() {
        assertEquals(0, restRemainingSeconds(endMs = 1_000L, nowMs = 1_000L))   // exactly up
        assertEquals(0, restRemainingSeconds(endMs = 1_000L, nowMs = 1_500L))   // overshot → floored at 0
    }

    @Test
    fun `remaining counts down whole-second boundaries`() {
        // Walk a 3s timer; the value must drop exactly as each boundary is crossed.
        assertEquals(3, restRemainingSeconds(endMs = 3_000L, nowMs = 0L))
        assertEquals(3, restRemainingSeconds(endMs = 3_000L, nowMs = 1L))      // 2.999 → 3
        assertEquals(2, restRemainingSeconds(endMs = 3_000L, nowMs = 1_000L))  // 2.000 → 2
        assertEquals(1, restRemainingSeconds(endMs = 3_000L, nowMs = 2_000L))  // 1.000 → 1
        assertEquals(1, restRemainingSeconds(endMs = 3_000L, nowMs = 2_999L))  // 0.001 → 1
        assertEquals(0, restRemainingSeconds(endMs = 3_000L, nowMs = 3_000L))  // 0.000 → 0
    }
}
