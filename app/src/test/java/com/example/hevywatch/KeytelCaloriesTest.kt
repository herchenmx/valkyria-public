package com.example.hevywatch

import com.example.hevywatch.data.model.HeartRateSample
import com.example.hevywatch.data.store.Sex
import com.example.hevywatch.sensors.KeytelCalories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Keytel 2005 regression. Worked example for traceability:
 *
 *   Men, HR=120, W=80kg, age=30:
 *     kJ/min = −55.0969 + 0.6309·120 + 0.1988·80 + 0.2017·30
 *            = −55.0969 + 75.708 + 15.904 + 6.051 = 42.5661
 *     kcal/min = 42.5661 / 4.184 ≈ 10.17
 *
 *   Women, same HR/W/A:
 *     kJ/min = −20.4022 + 0.4472·120 − 0.1263·80 + 0.0740·30
 *            = −20.4022 + 53.664 − 10.104 + 2.22 = 25.3778
 *     kcal/min = 25.3778 / 4.184 ≈ 6.07
 *
 *   Unspecified (mean): (42.5661 + 25.3778) / 2 / 4.184 ≈ 8.12 kcal/min
 *
 * Tests stay tolerant to ±0.5 kcal of rounding noise.
 */
class KeytelCaloriesTest {

    private fun sample(bpm: Double, ts: Long = 0L) = HeartRateSample(bpm = bpm, timestamp_ms = ts)

    @Test
    fun `male — one minute at HR 120 W 80kg age 30 is ~10 kcal`() {
        val kcal = KeytelCalories.totalKcal(
            samples = listOf(sample(120.0)),
            weightKg = 80.0,
            ageYears = 30,
            sex = Sex.MALE
        )
        // 10.17 rounds to 10
        assertEquals(10, kcal)
    }

    @Test
    fun `female — one minute at HR 120 W 80kg age 30 is ~6 kcal`() {
        val kcal = KeytelCalories.totalKcal(
            samples = listOf(sample(120.0)),
            weightKg = 80.0,
            ageYears = 30,
            sex = Sex.FEMALE
        )
        // 6.07 rounds to 6
        assertEquals(6, kcal)
    }

    @Test
    fun `unspecified — averages male and female formulas`() {
        val male = KeytelCalories.kcalPerMinute(120.0, 80.0, 30, Sex.MALE)
        val female = KeytelCalories.kcalPerMinute(120.0, 80.0, 30, Sex.FEMALE)
        val neutral = KeytelCalories.kcalPerMinute(120.0, 80.0, 30, Sex.UNSPECIFIED)
        assertEquals((male + female) / 2.0, neutral, 0.001)
    }

    @Test
    fun `summation across minutes is the sum of per-minute kcals`() {
        // 5 samples (5 minutes) at HR 120 ≈ 5 × 10 kcal = 50 kcal
        val samples = List(5) { sample(120.0, ts = (it * 60_000L)) }
        val kcal = KeytelCalories.totalKcal(samples, 80.0, 30, Sex.MALE)
        // Allow ±1 for rounding-at-end vs rounding-per-step accumulation.
        assertTrue("expected ~50, got $kcal", kcal in 49..51)
    }

    @Test
    fun `empty samples list returns zero`() {
        val kcal = KeytelCalories.totalKcal(emptyList(), 80.0, 30, Sex.MALE)
        assertEquals(0, kcal)
    }

    @Test
    fun `low-HR clamp — Keytel can predict negative kJ at very low HR, total floors at 0`() {
        // At HR=50, male formula yields: -55.0969 + 0.6309·50 + 0.1988·80 + 0.2017·30
        //   = -55.0969 + 31.545 + 15.904 + 6.051 ≈ -1.60 kJ/min — negative.
        // We clamp per-minute contribution to >= 0 so the total is never
        // negative even if a sample slips below the formula's valid range.
        val kcal = KeytelCalories.totalKcal(
            samples = listOf(sample(50.0)),
            weightKg = 80.0,
            ageYears = 30,
            sex = Sex.MALE
        )
        assertEquals(0, kcal)
    }

    @Test
    fun `kcalPerMinute clamps a single low-HR reading to zero`() {
        // The live per-minute path (used for a future cal/min readout) applies
        // the same non-negative clamp as the total path. At HR=50 the male
        // formula is negative (~-1.6 kJ/min); the exposed rate must floor at 0
        // rather than surface a negative "burning -0.4 cal/min".
        val rate = KeytelCalories.kcalPerMinute(50.0, 80.0, 30, Sex.MALE)
        assertEquals(0.0, rate, 0.0001)
    }

    @Test
    fun `age coefficient nudges the result upward for older lifters`() {
        val young = KeytelCalories.kcalPerMinute(120.0, 80.0, 25, Sex.MALE)
        val old = KeytelCalories.kcalPerMinute(120.0, 80.0, 65, Sex.MALE)
        // Per Keytel: age coefficient for men is +0.2017 kJ/min per year.
        // 40-year span × 0.2017 / 4.184 ≈ 1.93 kcal/min difference. Verify the
        // direction (older > younger) without pinning an exact value.
        assertTrue("older should be higher: young=$young old=$old", old > young)
    }
}
