package com.example.hevywatch

import com.example.hevywatch.presentation.workout.clampWeightKg
import com.example.hevywatch.presentation.workout.formatPerSideValue
import com.example.hevywatch.presentation.workout.perSideDecimals
import com.example.hevywatch.presentation.workout.perSideKg
import com.example.hevywatch.presentation.workout.totalFromPerSideKg
import com.example.hevywatch.presentation.workout.usesPerSideDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-side lens on LogSetScreen: for a barbell whose bar weight is known, the
 * big number is the plates on ONE END of the bar, so the lifter doesn't have to
 * halve the plate total at the rack. Stored weights stay TRUE TOTAL — these
 * tests pin the display maths and, crucially, the numpad's inverse, since a
 * broken inverse would silently log double (or half) the intended weight.
 */
class PerSideDisplayTest {

    private val eps = 0.0001f

    // ── Eligibility ───────────────────────────────────────────────────────────

    @Test fun `barbell with a known bar shows per side`() {
        assertTrue(usesPerSideDisplay("barbell", 20f))
        assertTrue(usesPerSideDisplay("Barbell", 20f))   // equipment casing varies
        assertTrue(usesPerSideDisplay("barbell", 15f))   // women's / training bar
    }

    @Test fun `barbell without a decided bar weight stays on the plate total`() {
        // base null = prompt unanswered, base 0 = "log the total directly". In
        // both cases the displayed number still contains the bar, so halving it
        // would understate what goes on each end.
        assertFalse(usesPerSideDisplay("barbell", null))
        assertFalse(usesPerSideDisplay("barbell", 0f))
    }

    @Test fun `non barbell equipment never shows per side`() {
        assertFalse(usesPerSideDisplay("dumbbell", 20f))
        assertFalse(usesPerSideDisplay("kettlebell", 20f))
        assertFalse(usesPerSideDisplay("machine", 50f))  // incl. Smith
        assertFalse(usesPerSideDisplay("plate", 25f))    // plate-loaded sled
        assertFalse(usesPerSideDisplay("none", 20f))
        assertFalse(usesPerSideDisplay(null, 20f))
    }

    // ── Display maths ─────────────────────────────────────────────────────────

    @Test fun `per side is half the plate portion`() {
        assertEquals(20f, perSideKg(60f, 20f), eps)      // 60 total = bar + 2x20
        assertEquals(2.5f, perSideKg(25f, 20f), eps)
        assertEquals(0f, perSideKg(20f, 20f), eps)       // empty bar
    }

    @Test fun `per side floors at zero below the bar`() {
        // A prescribed warmup lighter than the bar itself (or a half-entered
        // manual weight) must not render as a negative plate load.
        assertEquals(0f, perSideKg(15f, 20f), eps)
        assertEquals(0f, perSideKg(0f, 20f), eps)
    }

    @Test fun `a plus one step moves the bar by one and each side by a half`() {
        // The +/- buttons keep stepping the REAL load by 1 kg, so the per-side
        // readout advances in 0.5 kg — the logged weight stays on its old grid.
        assertEquals(20f, perSideKg(60f, 20f), eps)
        assertEquals(20.5f, perSideKg(61f, 20f), eps)
    }

    // ── Numpad inverse ────────────────────────────────────────────────────────

    @Test fun `typing a per side value stores bar plus both sides`() {
        assertEquals(60f, totalFromPerSideKg(20f, 20f), eps)
        assertEquals(20f, totalFromPerSideKg(0f, 20f), eps)   // bar only
        assertEquals(45f, totalFromPerSideKg(12.5f, 20f), eps)
    }

    @Test fun `per side round trips through the numpad`() {
        for (side in listOf(0f, 1.25f, 12.5f, 20f, 60f)) {
            assertEquals(side, perSideKg(totalFromPerSideKg(side, 20f), 20f), eps)
        }
    }

    @Test fun `a negative typed value cannot pull the total below the bar`() {
        assertEquals(20f, totalFromPerSideKg(-5f, 20f), eps)
    }

    // ── Quarter-kg precision ──────────────────────────────────────────────────

    @Test fun `a quarter per side gets a second decimal`() {
        // The warmup ladder rounds plates onto the 2.5 kg microplate grid, so a
        // 22.5 kg plate portion is 11.25 a side. One decimal would show "11.3" —
        // a load no plate set can make, and one the numpad would then store.
        assertEquals(2, perSideDecimals(perSideKg(42.5f, 20f)))   // 11.25 a side
        assertEquals("%.2f".format(11.25f), formatPerSideValue(perSideKg(42.5f, 20f)))
    }

    @Test fun `whole and half values keep the screen's single decimal`() {
        assertEquals(1, perSideDecimals(perSideKg(60f, 20f)))     // 20.0 a side
        assertEquals(1, perSideDecimals(perSideKg(61f, 20f)))     // 20.5 a side
        assertEquals("%.1f".format(20f), formatPerSideValue(perSideKg(60f, 20f)))
    }

    @Test fun `a quarter per side survives the numpad round trip`() {
        val side = perSideKg(42.5f, 20f)
        assertEquals(42.5f, totalFromPerSideKg(side, 20f), eps)
        // What the numpad seeds, parses back to what it was seeded with.
        assertEquals(side, formatPerSideValue(side).replace(',', '.').toFloat(), eps)
    }

    @Test fun `an absurd per side entry still clamps at the weight ceiling`() {
        // The numpad caps the TYPED number at MAX_WEIGHT_KG; doubling it could
        // otherwise carry the stored total past the ceiling.
        assertEquals(
            com.example.hevywatch.presentation.workout.MAX_WEIGHT_KG,
            clampWeightKg(totalFromPerSideKg(999.9f, 20f)),
            eps
        )
    }
}
