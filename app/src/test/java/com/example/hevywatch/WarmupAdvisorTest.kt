package com.example.hevywatch

import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.suggestWarmupSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupAdvisorTest {

    // ── Skip cases ────────────────────────────────────────────────────────────

    @Test
    fun `no warmup for bodyweight equipment`() {
        val sets = suggestWarmupSets("quadriceps", "none", 50f)
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `no warmup for resistance band`() {
        val sets = suggestWarmupSets("quadriceps", "resistance_band", 20f)
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `no warmup for abdominals`() {
        val sets = suggestWarmupSets("abdominals", "machine", 50f)
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `no warmup for cardio`() {
        val sets = suggestWarmupSets("cardio", "machine", 50f)
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `no warmup for null muscle group`() {
        val sets = suggestWarmupSets(null, "barbell", 50f)
        assertTrue(sets.isEmpty())
    }

    // ── Set count by muscle group + weight ────────────────────────────────────

    @Test
    fun `large muscle group under 30kg gets 1 warmup set`() {
        val sets = suggestWarmupSets("quadriceps", "machine", 25f)
        assertEquals(1, sets.size)
    }

    @Test
    fun `large muscle group 30-50kg gets 2 warmup sets`() {
        val sets = suggestWarmupSets("hamstrings", "barbell", 40f)
        assertEquals(2, sets.size)
    }

    @Test
    fun `large muscle group 50-80kg gets 3 warmup sets`() {
        val sets = suggestWarmupSets("glutes", "machine", 60f)
        assertEquals(3, sets.size)
    }

    @Test
    fun `large muscle group over 80kg gets 4 warmup sets`() {
        val sets = suggestWarmupSets("quadriceps", "machine", 100f)
        assertEquals(4, sets.size)
    }

    @Test
    fun `medium muscle group under 30kg gets 0 warmup sets`() {
        val sets = suggestWarmupSets("chest", "dumbbell", 20f)
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `medium muscle group 30-50kg gets 1 warmup set`() {
        val sets = suggestWarmupSets("shoulders", "barbell", 35f)
        assertEquals(1, sets.size)
    }

    @Test
    fun `small muscle group 50-80kg gets 2 warmup sets`() {
        val sets = suggestWarmupSets("biceps", "dumbbell", 60f)
        assertEquals(2, sets.size)
    }

    // ── Protocol percentages ──────────────────────────────────────────────────

    @Test
    fun `3-set protocol uses 40-60-80 percent`() {
        // Large group + 50-80kg = 3 warmup sets
        val sets = suggestWarmupSets("quadriceps", "machine", 60f)
        assertEquals(3, sets.size)
        // 40% of 60 = 24, 60% = 36, 80% = 48 (machine rounds to 1kg)
        assertEquals(24f, sets[0].weightKg)
        assertEquals(36f, sets[1].weightKg)
        assertEquals(48f, sets[2].weightKg)
    }

    @Test
    fun `2-set protocol uses 50-70 percent`() {
        val sets = suggestWarmupSets("lats", "machine", 40f)
        assertEquals(2, sets.size)
        // 50% of 40 = 20, 70% of 40 = 28
        assertEquals(20f, sets[0].weightKg)
        assertEquals(28f, sets[1].weightKg)
    }

    @Test
    fun `1-set protocol uses 60 percent`() {
        val sets = suggestWarmupSets("quadriceps", "machine", 25f)
        assertEquals(1, sets.size)
        // 60% of 25 = 15
        assertEquals(15f, sets[0].weightKg)
    }

    // ── Rounding ──────────────────────────────────────────────────────────────

    @Test
    fun `barbell rounds down to 2_5kg`() {
        // Large group + 30-50kg = 2 sets. Protocol: 50%, 70%.
        // 50% of 45 = 22.5 → floor(22.5/2.5)*2.5 = 22.5
        val sets = suggestWarmupSets("hamstrings", "barbell", 45f)
        assertEquals(22.5f, sets[0].weightKg)
    }

    @Test
    fun `dumbbell rounds down to 2kg`() {
        // 50% of 35 = 17.5 → floor(17.5/2)*2 = 16
        val sets = suggestWarmupSets("lats", "dumbbell", 35f)
        assertEquals(16f, sets[0].weightKg)
    }

    @Test
    fun `kettlebell rounds down to 4kg`() {
        // 60% of 25 = 15 → floor(15/4)*4 = 12
        val sets = suggestWarmupSets("quadriceps", "kettlebell", 25f)
        assertEquals(12f, sets[0].weightKg)
    }

    // ── All sets are WARMUP type ──────────────────────────────────────────────

    @Test
    fun `all suggested sets have warmup type`() {
        val sets = suggestWarmupSets("quadriceps", "machine", 100f)
        assertTrue(sets.all { it.setType == SetType.WARMUP })
    }

    // ── Bilateral doubling ────────────────────────────────────────────────────

    @Test
    fun `6 normal sets doubles warmup sets`() {
        val sets = suggestWarmupSets("quadriceps", "machine", 40f, normalSetCount = 6)
        // Base: 2 warmup sets for large+30-50kg → doubled to 4
        assertEquals(4, sets.size)
        // Pairs should have same weight
        assertEquals(sets[0].weightKg, sets[1].weightKg)
        assertEquals(sets[2].weightKg, sets[3].weightKg)
    }

    @Test
    fun `8 normal sets doubles warmup sets`() {
        val sets = suggestWarmupSets("quadriceps", "machine", 60f, normalSetCount = 8)
        // Base: 3 warmup sets → doubled to 6
        assertEquals(6, sets.size)
    }

    @Test
    fun `5 normal sets does NOT double (odd count)`() {
        val sets = suggestWarmupSets("quadriceps", "machine", 40f, normalSetCount = 5)
        assertEquals(2, sets.size)
    }

    @Test
    fun `3 normal sets does NOT double (below 6)`() {
        val sets = suggestWarmupSets("quadriceps", "machine", 40f, normalSetCount = 3)
        assertEquals(2, sets.size)
    }

    // ── Barbell 20kg floor ────────────────────────────────────────────────────
    // The Olympic bar weighs 20kg, so no barbell warmup set can physically go below that.

    @Test
    fun `barbell warmup floors at 20kg when percentage is below 20kg`() {
        // Large group + barbell + 30kg working weight → 2 sets (large 30-50kg range)
        // Protocol: 50% of 30 = 15kg, 70% of 30 = 21kg
        // The 15kg warmup must be floored to 20kg; the 21kg set stays as 21 (but rounds
        // down to 20kg on the 2.5kg increment — floor(21/2.5)*2.5 = 20).
        val sets = suggestWarmupSets("quadriceps", "barbell", 30f)
        assertEquals(2, sets.size)
        assertEquals(20f, sets[0].weightKg)
        assertEquals(20f, sets[1].weightKg)
    }

    @Test
    fun `barbell warmup floors at 20kg for small muscle group`() {
        // Small group + barbell + 50-80kg → 2 warmup sets at 50% and 70%
        // 50% of 50 = 25kg, 70% = 35kg — both above 20, unaffected by floor.
        val sets = suggestWarmupSets("biceps", "barbell", 50f)
        assertEquals(2, sets.size)
        assertEquals(25f, sets[0].weightKg)
        assertEquals(35f, sets[1].weightKg)
    }

    @Test
    fun `barbell warmup floors at 20kg when first set would be 17_5kg`() {
        // Small/Medium group + barbell + 35kg → 1 set, 50% of 35 = 17.5kg → floored to 20kg.
        // (shoulders is in SMALL_GROUPS but SMALL and MEDIUM share the same
        // warmup-count table, so the set count is identical either way.)
        val sets = suggestWarmupSets("shoulders", "barbell", 35f)
        assertEquals(1, sets.size)
        assertEquals(20f, sets[0].weightKg)
    }

    // ── Muscle re-classification regression (warmup unaffected) ──────────────
    //
    // shoulders + abdominals moved into SMALL_GROUPS; abductors + adductors
    // moved out of SMALL_GROUPS into MEDIUM. These tests pin that warmup
    // output is unchanged — MEDIUM and SMALL share the same warmup-count
    // table, and abdominals is still suppressed by NO_WARMUP_GROUPS.

    @Test
    fun `shoulders warmup unchanged after move to SMALL`() {
        // Pre-shuffle: MEDIUM 30-50kg → 1 set. Post-shuffle: SMALL 30-50kg → still 1.
        val sets = suggestWarmupSets("shoulders", "machine", 45f)
        assertEquals(1, sets.size)
    }

    @Test
    fun `abdominals stays warmup-free after move to SMALL`() {
        // NO_WARMUP_GROUPS short-circuits before LARGE/MEDIUM/SMALL classification.
        val sets = suggestWarmupSets("abdominals", "machine", 50f)
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `abductors warmup unchanged after move to MEDIUM`() {
        // Pre-shuffle: SMALL 30-50kg → 1 set. Post-shuffle: MEDIUM 30-50kg → still 1.
        val sets = suggestWarmupSets("abductors", "machine", 40f)
        assertEquals(1, sets.size)
    }

    @Test
    fun `adductors warmup unchanged after move to MEDIUM`() {
        val sets = suggestWarmupSets("adductors", "machine", 60f)
        // 50-80kg → 2 warmup sets, same under SMALL or MEDIUM.
        assertEquals(2, sets.size)
    }

    @Test
    fun `dumbbell warmup is NOT floored at 20kg`() {
        // Large group + dumbbell + 25kg → 1 set, 60% of 25 = 15 → floor(15/2)*2 = 14kg.
        // The 20kg floor only applies to barbell.
        val sets = suggestWarmupSets("quadriceps", "dumbbell", 25f)
        assertEquals(1, sets.size)
        assertEquals(14f, sets[0].weightKg)
    }

    @Test
    fun `machine warmup is NOT floored at 20kg`() {
        // Large + machine + 25kg → 1 set, 60% of 25 = 15kg (no 20kg floor for machine).
        val sets = suggestWarmupSets("quadriceps", "machine", 25f)
        assertEquals(1, sets.size)
        assertEquals(15f, sets[0].weightKg)
    }
}
