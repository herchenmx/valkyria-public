package com.example.hevywatch

import com.example.hevywatch.data.api.model.BiometricsBody
import com.example.hevywatch.data.api.model.HeartRateSampleBody
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.HeartRateSample
import com.example.hevywatch.data.store.Sex
import com.example.hevywatch.sensors.BiometricsBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the BiometricsBuilder behavior on both the new-workout (`fromActiveWorkout`)
 * and resume-merge (`mergeForResume`) paths. KeytelCalories has its own unit
 * tests for the kJ→kcal math; here we focus on the assembly:
 *  - empty input → null (so the v2 POST omits the biometrics field entirely)
 *  - field names + types map correctly through to BiometricsBody
 *  - merge sorts samples chronologically and recomputes total_calories on the
 *    full combined list (not just the new samples)
 */
class BiometricsBuilderTest {

    private fun active(samples: List<HeartRateSample>) = ActiveWorkout(
        name = "Test", startTimeMs = 0L, heartRateSamples = samples
    )

    private fun sample(bpm: Double, ts: Long) =
        HeartRateSample(bpm = bpm, timestamp_ms = ts)

    // ── fromActiveWorkout (fresh POST path) ─────────────────────────────────

    @Test
    fun `fromActiveWorkout returns null when there are no samples`() {
        val out = BiometricsBuilder.fromActiveWorkout(
            workout = active(emptyList()),
            weightKg = 70.0, ageYears = 30, sex = Sex.MALE
        )
        assertNull(out)
    }

    @Test
    fun `fromActiveWorkout carries samples through verbatim`() {
        val samples = listOf(sample(100.0, 1_000_000L), sample(120.0, 1_060_000L))
        val out = BiometricsBuilder.fromActiveWorkout(
            workout = active(samples),
            weightKg = 70.0, ageYears = 30, sex = Sex.MALE
        )
        assertNotNull(out)
        assertEquals(2, out!!.heartRateSamples.size)
        assertEquals(100.0, out.heartRateSamples[0].bpm, 0.001)
        assertEquals(1_000_000L, out.heartRateSamples[0].timestampMs)
        assertEquals(120.0, out.heartRateSamples[1].bpm, 0.001)
        assertEquals(1_060_000L, out.heartRateSamples[1].timestampMs)
        // total_calories is non-null (KeytelCalories.totalKcal returns an Int)
        assertNotNull(out.totalCalories)
    }

    // ── mergeForResume (resume POST+DELETE path) ────────────────────────────

    @Test
    fun `mergeForResume returns null when both sides are empty`() {
        val out = BiometricsBuilder.mergeForResume(
            original = null, newSamples = emptyList(),
            weightKg = 70.0, ageYears = 30, sex = Sex.MALE
        )
        assertNull(out)
    }

    @Test
    fun `mergeForResume returns null when original is null and new samples are empty`() {
        val out = BiometricsBuilder.mergeForResume(
            original = null, newSamples = emptyList(),
            weightKg = 70.0, ageYears = 30, sex = Sex.MALE
        )
        assertNull(out)
    }

    @Test
    fun `mergeForResume sorts samples chronologically across both sources`() {
        val original = BiometricsBody(
            totalCalories = 12,
            heartRateSamples = listOf(
                HeartRateSampleBody(80.0, 200_000L),
                HeartRateSampleBody(90.0, 300_000L)
            )
        )
        // New samples interleave around the originals: one before, one after.
        val newSamples = listOf(
            sample(70.0, 100_000L),    // earliest
            sample(110.0, 400_000L)    // latest
        )
        val out = BiometricsBuilder.mergeForResume(
            original = original, newSamples = newSamples,
            weightKg = 70.0, ageYears = 30, sex = Sex.MALE
        )
        assertNotNull(out)
        assertEquals(
            listOf(100_000L, 200_000L, 300_000L, 400_000L),
            out!!.heartRateSamples.map { it.timestampMs }
        )
    }

    @Test
    fun `mergeForResume recomputes total_calories on the full combined list`() {
        // Two original samples (already on the server) + two new ones.
        // total_calories must be > the original 0 — we compute fresh over all 4.
        val original = BiometricsBody(
            totalCalories = 0,                       // intentionally wrong / stale
            heartRateSamples = listOf(
                HeartRateSampleBody(120.0, 100_000L),
                HeartRateSampleBody(125.0, 160_000L)
            )
        )
        val newSamples = listOf(
            sample(130.0, 220_000L),
            sample(135.0, 280_000L)
        )
        val out = BiometricsBuilder.mergeForResume(
            original = original, newSamples = newSamples,
            weightKg = 80.0, ageYears = 30, sex = Sex.MALE
        )
        assertNotNull(out)
        // For Male @ HR≥120, kJ/min is solidly positive — total over 4 min
        // should yield >> 0 kcal. We don't pin the exact number (KeytelCaloriesTest
        // owns the math); just assert the recompute didn't silently keep the 0.
        assertTrue("total_calories should be recomputed > 0", out!!.totalCalories!! > 0)
    }

    @Test
    fun `mergeForResume works when only new samples exist`() {
        val out = BiometricsBuilder.mergeForResume(
            original = null,
            newSamples = listOf(sample(100.0, 1_000L)),
            weightKg = 70.0, ageYears = 30, sex = Sex.MALE
        )
        assertNotNull(out)
        assertEquals(1, out!!.heartRateSamples.size)
        assertEquals(100.0, out.heartRateSamples[0].bpm, 0.001)
    }

    @Test
    fun `mergeForResume works when only original samples exist (no new ones)`() {
        // Edge case: a workout was resumed but no HR was sampled during the
        // resumed segment (sensor off / permission denied / very short resume).
        // Merge should still produce a body so the original's HR chart on the
        // server doesn't regress to empty.
        val original = BiometricsBody(
            totalCalories = 5,
            heartRateSamples = listOf(HeartRateSampleBody(90.0, 500L))
        )
        val out = BiometricsBuilder.mergeForResume(
            original = original, newSamples = emptyList(),
            weightKg = 70.0, ageYears = 30, sex = Sex.MALE
        )
        assertNotNull(out)
        assertEquals(1, out!!.heartRateSamples.size)
        assertEquals(90.0, out.heartRateSamples[0].bpm, 0.001)
    }
}
