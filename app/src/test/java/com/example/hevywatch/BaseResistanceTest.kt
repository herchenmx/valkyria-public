package com.example.hevywatch

import com.example.hevycore.workout.WarmupConstants
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.applyBaseResistance
import com.example.hevywatch.presentation.workout.defaultBaseFor
import com.example.hevywatch.presentation.workout.suggestWarmupSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Base-resistance lens: stored weights stay true-total (base + plates); the base
 * only re-ramps the warmup ladder onto the plate portion and drives the log
 * screen's display offset. These tests pin the warmup-ladder maths and the
 * [applyBaseResistance] session mutation.
 */
class BaseResistanceTest {

    private val eps = 0.001f

    // ── Equipment eligibility ─────────────────────────────────────────────────

    @Test
    fun `bar smith and machine have a base, dumbbell and none do not`() {
        assertTrue(WarmupConstants.equipmentHasBaseResistance("barbell"))
        assertTrue(WarmupConstants.equipmentHasBaseResistance("machine"))   // incl. Smith
        assertTrue(WarmupConstants.equipmentHasBaseResistance("plate"))
        assertFalse(WarmupConstants.equipmentHasBaseResistance("dumbbell"))
        assertFalse(WarmupConstants.equipmentHasBaseResistance("kettlebell"))
        assertFalse(WarmupConstants.equipmentHasBaseResistance("none"))
        assertFalse(WarmupConstants.equipmentHasBaseResistance(null))
    }

    @Test
    fun `default base is bare bar for barbell, zero otherwise`() {
        assertEquals(20f, defaultBaseFor("barbell"), eps)
        assertEquals(0f, defaultBaseFor("machine"), eps)
        assertEquals(0f, defaultBaseFor(null), eps)
    }

    // ── Warmup ladder: base 0 == old behaviour ────────────────────────────────

    @Test
    fun `base 0 is identical to the no-base ladder`() {
        val noArg = suggestWarmupSets("quadriceps", "machine", 117f, normalSetCount = 3)
        val zero = suggestWarmupSets("quadriceps", "machine", 117f, normalSetCount = 3, baseResistanceKg = 0f)
        assertEquals(noArg.map { it.weightKg }, zero.map { it.weightKg })
    }

    // ── Warmup ladder: base > 0 ramps on the plate portion ────────────────────

    @Test
    fun `leg press count uses total but ladder ramps on plates`() {
        // 117 kg total, 50 kg sled → plate portion 67. LARGE ≥80kg → 4 warmups
        // (count on the TOTAL). Ladder = base + floor(plate * pct); machine incr 1kg.
        val sets = suggestWarmupSets(
            primaryMuscleGroup = "quadriceps",
            equipment = "machine",
            workingWeightKg = 117f,
            normalSetCount = 3,
            baseResistanceKg = 50f,
        )
        assertEquals(4, sets.size)
        // pct 0.30/0.50/0.70/0.85 of 67 → 20/33/46/56 → +50 base
        assertEquals(listOf(70f, 83f, 96f, 106f), sets.map { it.weightKg })
        // Every warmup total sits at or above the empty sled.
        assertTrue(sets.all { (it.weightKg ?: 0f) >= 50f })
    }

    @Test
    fun `warmups never fall below the base (no negative plates)`() {
        // Squat machine 89 kg total, 30 kg base → plates 59. LARGE ≥80 → 4 warmups.
        val sets = suggestWarmupSets("quadriceps", "machine", 89f, normalSetCount = 3, baseResistanceKg = 30f)
        assertEquals(4, sets.size)
        assertTrue(sets.all { (it.weightKg ?: 0f) >= 30f })
        // Lowest warmup is base + floor(59 * 0.30) = 30 + 17 = 47.
        assertEquals(47f, sets.first().weightKg!!, eps)
    }

    // ── applyBaseResistance: session mutation ─────────────────────────────────

    private fun legPress(): ActiveExercise = ActiveExercise(
        exerciseTemplateId = "C7973E0E",
        title = "Leg Press (Machine)",
        equipment = "machine",
        primaryMuscleGroup = "quadriceps",
        sets = listOf(
            ActiveSet(setType = SetType.WARMUP, weightKg = 35f, reps = 12),
            ActiveSet(setType = SetType.WARMUP, weightKg = 58f, reps = 8),
            ActiveSet(setType = SetType.WARMUP, weightKg = 81f, reps = 4),
            ActiveSet(setType = SetType.WARMUP, weightKg = 99f, reps = 2),
            ActiveSet(setType = SetType.NORMAL, weightKg = 117f, reps = 15),
            ActiveSet(setType = SetType.NORMAL, weightKg = 117f, reps = 15),
            ActiveSet(setType = SetType.NORMAL, weightKg = 117f, reps = 15),
        ),
    )

    @Test
    fun `applying base records it and re-ramps warmups onto plates`() {
        val out = applyBaseResistance(legPress(), 50f)
        assertEquals(50f, out.baseResistanceKg!!, eps)
        val warmups = out.sets.filter { it.setType == SetType.WARMUP }.map { it.weightKg }
        assertEquals(listOf(70f, 83f, 96f, 106f), warmups)
        // Normal sets are untouched — they were always true-total.
        assertTrue(out.sets.filter { it.setType == SetType.NORMAL }.all { it.weightKg == 117f })
    }

    @Test
    fun `applying base preserves already-completed and locked warmups`() {
        val ex = legPress().let { e ->
            e.copy(sets = e.sets.mapIndexed { i, s ->
                when (i) {
                    0 -> s.copy(completed = true)          // logged this session
                    1 -> s.copy(locked = true)             // carried from a resume
                    else -> s
                }
            })
        }
        val out = applyBaseResistance(ex, 50f)
        val warmups = out.sets.filter { it.setType == SetType.WARMUP }
        assertEquals(35f, warmups[0].weightKg!!, eps)   // completed → kept verbatim
        assertEquals(58f, warmups[1].weightKg!!, eps)   // locked → kept verbatim
        assertEquals(96f, warmups[2].weightKg!!, eps)   // free → re-ramped (slot 2)
        assertEquals(106f, warmups[3].weightKg!!, eps)  // free → re-ramped (slot 3)
    }

    @Test
    fun `base 0 only stamps the exercise and leaves total-ramp warmups`() {
        val out = applyBaseResistance(legPress(), 0f)
        assertEquals(0f, out.baseResistanceKg!!, eps)
        // base 0 → the ladder equals the total ramp; slot weights recompute to the
        // canonical advisor values for 117kg (not necessarily the fixture's).
        val expected = suggestWarmupSets("quadriceps", "machine", 117f, normalSetCount = 3)
            .map { it.weightKg }
        assertEquals(expected, out.sets.filter { it.setType == SetType.WARMUP }.map { it.weightKg })
    }

    @Test
    fun `no working weight yet only stamps the base`() {
        val ex = ActiveExercise(
            exerciseTemplateId = "X", title = "New", equipment = "machine",
            primaryMuscleGroup = "quadriceps",
            sets = listOf(ActiveSet(setType = SetType.NORMAL, weightKg = null, reps = 15)),
        )
        val out = applyBaseResistance(ex, 40f)
        assertEquals(40f, out.baseResistanceKg!!, eps)
        assertNull(out.sets.single().weightKg)
    }
}
