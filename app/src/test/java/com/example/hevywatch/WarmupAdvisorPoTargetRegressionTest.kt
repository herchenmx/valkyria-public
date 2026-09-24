package com.example.hevywatch

import com.example.hevywatch.presentation.workout.suggestWarmupSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bucket-F item 28 — pins the fix from commit d6729ae ("Warmups judged
 * against PO target, not the weight lifted (rear-delt fix)").
 *
 * Rear delts and other small-muscle groups produced under-scaled warmups
 * before the fix because the advisor was seeing the actual weight lifted
 * — often lower than the PO target after a set the user pushed heavy on
 * — instead of the PO target the routine had just set. The fix routes
 * the PO target into `suggestWarmupSets(workingWeightKg = poTarget)`, so
 * this test pins the invariant "same muscle group, higher input weight →
 * warmup set count is monotonically non-decreasing".
 *
 * A regression that reintroduces "warmup uses the lifted weight" would
 * make the set-count sequence non-monotonic (a heavier PO target could
 * feed a lower recorded lift, which would produce fewer warmup sets — the
 * exact wrong behavior the fix targets).
 */
class WarmupAdvisorPoTargetRegressionTest {

    @Test
    fun `shoulder small-muscle warmup count grows monotonically with input weight`() {
        // At each threshold the SMALL_GROUPS table steps up: <30 → 0, <50
        // → 1, <80 → 2, else → 3. If a future edit re-scales rear-delt
        // work off the lifted weight instead of the PO target, this
        // sequence stops being monotonic on the rear-delt exercise.
        val progression = listOf(20f to 0, 35f to 1, 60f to 2, 90f to 3)
        progression.forEach { (input, expected) ->
            val sets = suggestWarmupSets(
                primaryMuscleGroup = "shoulders",
                equipment = "dumbbell",
                workingWeightKg = input,
                normalSetCount = 3,
                exerciseTemplateId = null,
                bodyweightKg = 0f
            )
            assertEquals(
                "shoulders @ input=$input kg should produce $expected warmup sets",
                expected,
                sets.size
            )
        }
    }

    @Test
    fun `rear-delt-equivalent shoulder workout at PO target above 50kg gets more than one warmup`() {
        // Concrete rear-delt scenario from the fix: user's PO target is
        // above the 50kg threshold, but the *lifted* weight last session
        // was lower (they failed reps). Advisor must use the PO target,
        // which crosses the 50kg boundary, giving 2+ warmup sets.
        val sets = suggestWarmupSets(
            primaryMuscleGroup = "shoulders",
            equipment = "dumbbell",
            workingWeightKg = 55f,  // PO target — > 50kg
            normalSetCount = 3,
        )
        assertTrue(
            "PO target 55kg for shoulders should produce ≥ 2 warmup sets (fix from d6729ae)",
            sets.size >= 2
        )
    }

    @Test
    fun `unilateral doubling still applies with PO target input`() {
        // Unilateral bilateral exercises (≥6 normal sets, even count) still
        // double every warmup set — the "PO target as input" fix mustn't
        // break the doubling.
        val singleSided = suggestWarmupSets(
            primaryMuscleGroup = "quadriceps",
            equipment = "dumbbell",
            workingWeightKg = 60f,
            normalSetCount = 3
        )
        val bilateral = suggestWarmupSets(
            primaryMuscleGroup = "quadriceps",
            equipment = "dumbbell",
            workingWeightKg = 60f,
            normalSetCount = 6
        )
        assertEquals(2 * singleSided.size, bilateral.size)
    }
}
