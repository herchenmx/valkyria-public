package com.example.hevywatch.sensors

import com.example.hevywatch.data.api.model.BiometricsBody
import com.example.hevywatch.data.api.model.HeartRateSampleBody
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.store.Sex

/**
 * Constructs the [BiometricsBody] for a v2 POST from an [ActiveWorkout]'s
 * recorded HR samples + user demographics. Pure function — moved out of the
 * VM so it can be unit-tested without a real Android Context.
 *
 * Returns null when the active workout has no HR samples; the builder then
 * omits the field entirely (rather than sending {total_calories: 0, samples:
 * []}, which would still trigger server-side biometric processing for a
 * workout with no data).
 */
object BiometricsBuilder {

    fun fromActiveWorkout(
        workout: ActiveWorkout,
        weightKg: Double,
        ageYears: Int,
        sex: Sex
    ): BiometricsBody? {
        if (workout.heartRateSamples.isEmpty()) return null
        val totalCalories = KeytelCalories.totalKcal(
            samples = workout.heartRateSamples,
            weightKg = weightKg,
            ageYears = ageYears,
            sex = sex
        )
        return BiometricsBody(
            totalCalories = totalCalories,
            heartRateSamples = workout.heartRateSamples.map {
                HeartRateSampleBody(bpm = it.bpm, timestampMs = it.timestamp_ms)
            }
        )
    }

    /** Resume-path variant: merges biometrics from the original workout with
     *  samples collected during the resumed segment. Recomputes total_calories
     *  on the combined sample list — the server does NOT recompute on POST, it
     *  trusts whatever total_calories we send. Returns null when neither side
     *  has any samples (the builder then omits the field entirely). */
    fun mergeForResume(
        original: BiometricsBody?,
        newSamples: List<com.example.hevywatch.data.model.HeartRateSample>,
        weightKg: Double,
        ageYears: Int,
        sex: Sex
    ): BiometricsBody? {
        val originalSamples = original?.heartRateSamples.orEmpty()
        if (originalSamples.isEmpty() && newSamples.isEmpty()) return null
        val mappedNew = newSamples.map { HeartRateSampleBody(bpm = it.bpm, timestampMs = it.timestamp_ms) }
        val combined = (originalSamples + mappedNew).sortedBy { it.timestampMs }
        // For Keytel summation we need our internal HeartRateSample shape.
        val asInternal = combined.map {
            com.example.hevywatch.data.model.HeartRateSample(bpm = it.bpm, timestamp_ms = it.timestampMs)
        }
        val totalCalories = KeytelCalories.totalKcal(asInternal, weightKg, ageYears, sex)
        return BiometricsBody(
            totalCalories = totalCalories,
            heartRateSamples = combined
        )
    }
}
