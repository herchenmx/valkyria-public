package com.example.hevycompanion.muscle

import org.junit.Assert.assertEquals
import org.junit.Test

class LiftoffSlugTest {

    @Test fun `normalize strips parentheticals`() {
        assertEquals("bench_press", LiftoffSlug.normalize("Bench Press (Barbell)"))
        assertEquals("bench_press", LiftoffSlug.normalize("Bench Press"))
    }

    @Test fun `normalize lowercases and underscore-joins`() {
        assertEquals("dumbbell_curl", LiftoffSlug.normalize("Dumbbell Curl"))
        assertEquals("romanian_deadlift", LiftoffSlug.normalize("Romanian Deadlift"))
    }

    @Test fun `normalize collapses consecutive non-alphanumerics`() {
        assertEquals("ab_wheel_rollout", LiftoffSlug.normalize("Ab-Wheel Rollout"))
        assertEquals("face_pull", LiftoffSlug.normalize("Face  Pull"))
    }

    @Test fun `normalize trims leading and trailing underscores`() {
        assertEquals("squat", LiftoffSlug.normalize(" squat "))
        assertEquals("squat", LiftoffSlug.normalize("-squat-"))
    }

    @Test fun `candidateSlugs returns bare slug when no parenthetical`() {
        val c = LiftoffSlug.candidateSlugs("Dumbbell Curl")
        assertEquals(listOf("dumbbell_curl"), c)
    }

    @Test fun `candidateSlugs puts equipment-qualified variants first and drops the bare-base fallback`() {
        // Regression for the bug where "Bench Press (Cable)" resolved to the
        // generic `bench_press` avatar (which is the barbell version), making
        // every variant of bench press look identical in the UI. The bare
        // base must NOT be tried when the title carries equipment, so the
        // user sees an honest first-letter placeholder if no exact-equipment
        // avatar exists rather than a misleading wrong-equipment image.
        val c = LiftoffSlug.candidateSlugs("Bench Press (Barbell)")
        assertEquals(listOf("barbell_bench_press", "bench_press_barbell"), c)
    }

    @Test fun `candidateSlugs for cable bench press does not fall back to bare base`() {
        // The user-visible bug: "Bench Press (Cable)" was rendering the
        // barbell bench press avatar because Liftoff has no cable variant
        // and the old slug ranking tried the bare `bench_press` first.
        val c = LiftoffSlug.candidateSlugs("Bench Press (Cable)")
        assertEquals(listOf("cable_bench_press", "bench_press_cable"), c)
        // Critical: bare base must NOT appear anywhere in the candidate list.
        assert("bench_press" !in c) {
            "bare 'bench_press' must not be a candidate when equipment is specified"
        }
    }

    @Test fun `candidateSlugs for multi-word equipment like Smith Machine`() {
        // "Bench Press (Smith Machine)" → both word-orderings, neither
        // contains the bare base. Liftoff actually ships
        // liftoff_ex_smith_machine_bench_press.png so the first candidate
        // resolves at runtime.
        assertEquals(
            listOf("smith_machine_bench_press", "bench_press_smith_machine"),
            LiftoffSlug.candidateSlugs("Bench Press (Smith Machine)"),
        )
    }
}
