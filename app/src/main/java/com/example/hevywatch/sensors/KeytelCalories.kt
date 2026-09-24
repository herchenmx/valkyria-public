package com.example.hevywatch.sensors

import com.example.hevywatch.data.model.HeartRateSample
import com.example.hevywatch.data.store.Sex
import kotlin.math.roundToInt

/**
 * Keytel et al. (2005) regression for predicting energy expenditure from heart
 * rate during submaximal exercise. Two sex-specific equations; we expose a
 * sex-neutral path that averages the two for [Sex.UNSPECIFIED].
 *
 *   Men:   E (kJ/min) = −55.0969 + 0.6309·HR + 0.1988·W + 0.2017·A
 *   Women: E (kJ/min) = −20.4022 + 0.4472·HR − 0.1263·W + 0.0740·A
 *
 * Each sample is treated as "the BPM for the minute starting at this sample's
 * timestamp". Since the sampler polls once per minute, we just sum the per-
 * minute kcal rate over all samples. Result is rounded to Int to match the
 * server-side `total_calories: Integer` field.
 *
 * Limitations the README of any honest calorie estimator should call out:
 *  - Trained on cycle ergometer at submaximal intensities; resistance work is
 *    out of distribution.
 *  - n ≈ 115; statistical confidence on individuals is poor (±15–20%).
 *  - Polar/Garmin use proprietary algorithms with measured HRmax/VO2max;
 *    Keytel is the best literature baseline when those aren't available.
 *
 * Pure helper — no Android dependencies, easily unit-tested.
 */
object KeytelCalories {

    private const val KJ_PER_KCAL = 4.184

    /**
     * Total kcal across all samples, given user demographics.
     *
     * Returns null when sex is [Sex.UNSPECIFIED] AND the caller wants strict
     * mode — but the current contract is sex-neutral averaging, so we never
     * actually return null. Result is rounded to a non-negative Int (Keytel
     * can predict negative kJ/min at very low HR, which we clamp out before
     * summing rather than after).
     */
    fun totalKcal(
        samples: List<HeartRateSample>,
        weightKg: Double,
        ageYears: Int,
        sex: Sex
    ): Int {
        if (samples.isEmpty()) return 0
        val kjPerMinute = samples.sumOf { kjPerMinute(it.bpm, weightKg, ageYears, sex) }
        // 1 sample = 1 minute of accrual.
        val kcal = kjPerMinute / KJ_PER_KCAL
        return kcal.coerceAtLeast(0.0).roundToInt()
    }

    /** Per-minute kcal for one HR reading + demographics, suitable for live
     *  display ("burned ~X cal/min right now"). Not used yet — the v2 POST
     *  sums totals at workout end — but exposed for future UI work. */
    fun kcalPerMinute(
        bpm: Double,
        weightKg: Double,
        ageYears: Int,
        sex: Sex
    ): Double = kjPerMinute(bpm, weightKg, ageYears, sex)
        .coerceAtLeast(0.0) / KJ_PER_KCAL

    private fun kjPerMinute(
        bpm: Double,
        weightKg: Double,
        ageYears: Int,
        sex: Sex
    ): Double = when (sex) {
        Sex.MALE -> male(bpm, weightKg, ageYears)
        Sex.FEMALE -> female(bpm, weightKg, ageYears)
        // Sex-neutral: average the two equations. Per the user choice on
        // UNSPECIFIED — fail open rather than fail closed.
        Sex.UNSPECIFIED -> (male(bpm, weightKg, ageYears) +
            female(bpm, weightKg, ageYears)) / 2.0
    }.coerceAtLeast(0.0)

    private fun male(bpm: Double, weightKg: Double, ageYears: Int): Double =
        -55.0969 + 0.6309 * bpm + 0.1988 * weightKg + 0.2017 * ageYears

    private fun female(bpm: Double, weightKg: Double, ageYears: Int): Double =
        -20.4022 + 0.4472 * bpm - 0.1263 * weightKg + 0.0740 * ageYears
}
