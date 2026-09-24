package com.example.hevycompanion.muscle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftoffCuratedPicksTest {

    @Test fun `biceps returns the 3 Liftoff picks`() {
        assertEquals(
            listOf("dumbbell_curl", "barbell_curl", "hammer_curl"),
            LiftoffCuratedPicks.picksForHevyGroup(HevyMuscleGroup.BICEPS),
        )
    }

    @Test fun `triceps returns the 3 Liftoff picks`() {
        assertEquals(
            listOf("tricep_pushdown", "tricep_extension", "dumbbell_push_press"),
            LiftoffCuratedPicks.picksForHevyGroup(HevyMuscleGroup.TRICEPS),
        )
    }

    @Test fun `chest unions upper and lower (dedup preserves order)`() {
        // Liftoff's "Upper Chest" and "Lower Chest" both map to
        // (bench_press, dumbbell_fly, cable_fly). Union should dedupe to
        // three entries, not six.
        val picks = LiftoffCuratedPicks.picksForHevyGroup(HevyMuscleGroup.CHEST)
        assertEquals(listOf("bench_press", "dumbbell_fly", "cable_fly"), picks)
    }

    @Test fun `shoulders unions front-middle-rear delts`() {
        val picks = LiftoffCuratedPicks.picksForHevyGroup(HevyMuscleGroup.SHOULDERS)
        // Distinct picks across the 3 delts; push_press appears for front AND middle
        // so it should dedupe.
        assertEquals(
            listOf(
                "shoulder_press", "push_press", "arnold_press",
                "dumbbell_lateral_raise", "cable_lateral_raise",
                "face_pull", "dumbbell_reverse_fly", "cable_reverse_fly",
            ),
            picks,
        )
    }

    @Test fun `abdominals unions abdominals and obliques`() {
        val picks = LiftoffCuratedPicks.picksForHevyGroup(HevyMuscleGroup.ABDOMINALS)
        assertTrue(picks.contains("cable_crunch"))
        assertTrue(picks.contains("dumbbell_side_bend"))
    }

    @Test fun `unmapped group returns empty`() {
        assertEquals(emptyList<String>(), LiftoffCuratedPicks.picksForHevyGroup(HevyMuscleGroup.CARDIO))
        assertEquals(emptyList<String>(), LiftoffCuratedPicks.picksForHevyGroup(HevyMuscleGroup.NECK))
    }
}
