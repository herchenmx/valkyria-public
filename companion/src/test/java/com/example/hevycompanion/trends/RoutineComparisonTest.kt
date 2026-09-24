package com.example.hevycompanion.trends

import com.example.hevycompanion.trends.RoutineComparison.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the delta-attribution engine: which exercise is blamed for a routine's
 * change in volume / sets / reps, and how a swapped exercise is paired rather
 * than double-counted.
 */
class RoutineComparisonTest {

    // Two members of the curated "Lateral Raise" substitution group.
    private val lateralMachine = "D5D0354D"
    private val lateralDumbbell = "422B08F1"

    private val plain = VolumeRule()

    private fun set(reps: Int, kg: Float, type: String = "normal") =
        TrendSet(type = type, weightKg = kg, reps = reps)

    private fun exercise(id: String, title: String, vararg sets: TrendSet) =
        TrendExercise(templateId = id, title = title, sets = sets.toList())

    private fun workout(id: String, epochMs: Long, vararg exercises: TrendExercise) =
        TrendWorkout(
            id = id,
            title = "POP 1: Upper",
            startTimeIso = null,
            startEpochMs = epochMs,
            exercises = exercises.toList(),
        )

    private fun compare(current: TrendWorkout, previous: TrendWorkout?, scope: SetScope = SetScope.ALL) =
        RoutineComparison.compare(current, previous, scope, plain)

    // ── The scenario from the feature request ────────────────────────────────

    @Test
    fun `same exercise at a lower average load is blamed for the volume drop`() {
        // 13 July: 1x15@16 + 2x15@15. 1 August: 3x15@15. Same sets, same reps,
        // 15 kg less volume — and the cause is the load, not the work done.
        val july = workout(
            "jul", 1_000L,
            exercise("BI", "Biceps Curl (Dumbbell)", set(15, 16f), set(15, 15f), set(15, 15f)),
        )
        val august = workout(
            "aug", 2_000L,
            exercise("BI", "Biceps Curl (Dumbbell)", set(15, 15f), set(15, 15f), set(15, 15f)),
        )

        val result = compare(august, july)

        assertEquals(675.0, result.currentTotals.volumeKg, 0.001)
        assertEquals(690.0, result.previousTotals!!.volumeKg, 0.001)
        assertEquals(-15.0, result.volumeDeltaKg!!, 0.001)
        assertEquals(0, result.setsDelta)
        assertEquals(0, result.repsDelta)

        val delta = result.deltas.single()
        assertEquals(Kind.SAME, delta.kind)
        assertEquals(-15.0, delta.volumeDeltaKg, 0.001)
        assertEquals(0, delta.setsDelta)
        assertEquals(0, delta.repsDelta)
        assertEquals(listOf("avg load 15.3 kg → 15 kg"), RoutineComparison.causes(delta))
        assertEquals(listOf("3 × 15 @ 15 kg"), delta.currentSetLines)
        assertEquals(listOf("1 × 15 @ 16 kg", "2 × 15 @ 15 kg"), delta.previousSetLines)
    }

    // ── Pairing ──────────────────────────────────────────────────────────────

    @Test
    fun `a swap within a substitution group is paired, not counted twice`() {
        val before = workout(
            "before", 1_000L,
            exercise(lateralMachine, "Lateral Raise (Machine)", set(12, 20f), set(12, 20f)),
        )
        val after = workout(
            "after", 2_000L,
            exercise(lateralDumbbell, "Lateral Raise (Dumbbell)", set(12, 10f), set(12, 10f)),
        )

        val delta = compare(after, before).deltas.single()
        assertEquals(Kind.SWAPPED, delta.kind)
        assertEquals("Lateral Raise (Dumbbell)", delta.title)
        assertEquals("Lateral Raise (Machine)", delta.previousTitle)
        assertEquals(lateralMachine, delta.previousTemplateId)
        assertEquals(-240.0, delta.volumeDeltaKg, 0.001)
        assertTrue(RoutineComparison.causes(delta).first().contains("swapped in for Lateral Raise (Machine)"))
    }

    @Test
    fun `an exercise outside any substitution group is added, not swapped`() {
        val before = workout("before", 1_000L, exercise("AAA", "Squat", set(10, 100f)))
        val after = workout("after", 2_000L, exercise("BBB", "Leg Press", set(10, 100f)))

        val result = compare(after, before)
        assertEquals(
            mapOf("Leg Press" to Kind.ADDED, "Squat" to Kind.DROPPED),
            result.deltas.associate { it.title to it.kind },
        )
        // Volume is unchanged overall, but both halves are named.
        assertEquals(0.0, result.volumeDeltaKg!!, 0.001)
        assertEquals(1000.0, result.deltas.first { it.kind == Kind.ADDED }.volumeDeltaKg, 0.001)
        assertEquals(-1000.0, result.deltas.first { it.kind == Kind.DROPPED }.volumeDeltaKg, 0.001)
    }

    @Test
    fun `exact matches win over swaps`() {
        // Both sessions did the machine variant; the dumbbell variant is extra
        // work, so the machine rows must pair with each other.
        val before = workout(
            "before", 1_000L,
            exercise(lateralMachine, "Lateral Raise (Machine)", set(12, 20f)),
        )
        val after = workout(
            "after", 2_000L,
            exercise(lateralMachine, "Lateral Raise (Machine)", set(12, 20f)),
            exercise(lateralDumbbell, "Lateral Raise (Dumbbell)", set(12, 10f)),
        )

        val result = compare(after, before)
        val byTitle = result.deltas.associateBy { it.title }
        assertEquals(Kind.SAME, byTitle.getValue("Lateral Raise (Machine)").kind)
        assertEquals(Kind.ADDED, byTitle.getValue("Lateral Raise (Dumbbell)").kind)
    }

    @Test
    fun `one previous exercise can only fill one swap`() {
        val before = workout(
            "before", 1_000L,
            exercise(lateralMachine, "Lateral Raise (Machine)", set(12, 20f)),
        )
        val after = workout(
            "after", 2_000L,
            exercise(lateralDumbbell, "Lateral Raise (Dumbbell)", set(12, 10f)),
            exercise("BE289E45", "Lateral Raise (Cable)", set(12, 10f)),
        )

        val kinds = compare(after, before).deltas.map { it.kind }
        assertEquals(1, kinds.count { it == Kind.SWAPPED })
        assertEquals(1, kinds.count { it == Kind.ADDED })
    }

    @Test
    fun `the same exercise logged twice is folded into one row`() {
        val before = workout("before", 1_000L, exercise("EX", "Row", set(10, 50f)))
        val after = workout(
            "after", 2_000L,
            exercise("EX", "Row", set(10, 50f)),
            exercise("EX", "Row", set(10, 50f)),
        )

        val delta = compare(after, before).deltas.single()
        assertEquals(Kind.SAME, delta.kind)
        assertEquals(2, delta.current.sets)
        assertEquals(500.0, delta.volumeDeltaKg, 0.001)
    }

    // ── Ordering + presentation ──────────────────────────────────────────────

    @Test
    fun `deltas are ordered by how much of the change they explain`() {
        val before = workout(
            "before", 1_000L,
            exercise("BIG", "Squat", set(10, 100f)),
            exercise("MID", "Row", set(10, 50f)),
            exercise("FLAT", "Curl", set(10, 10f)),
        )
        val after = workout(
            "after", 2_000L,
            exercise("BIG", "Squat", set(10, 120f)),   // +200
            exercise("MID", "Row", set(10, 45f)),      // −50
            exercise("FLAT", "Curl", set(10, 10f)),    // 0
        )

        assertEquals(
            listOf("Squat", "Row", "Curl"),
            compare(after, before).deltas.map { it.title },
        )
    }

    @Test
    fun `an unchanged exercise reports no cause`() {
        val before = workout("before", 1_000L, exercise("EX", "Row", set(10, 50f)))
        val after = workout("after", 2_000L, exercise("EX", "Row", set(10, 50f)))

        val delta = compare(after, before).deltas.single()
        assertTrue(delta.isUnchanged)
        assertEquals(listOf("unchanged"), RoutineComparison.causes(delta))
    }

    @Test
    fun `set and rep changes are named alongside the load`() {
        val before = workout("before", 1_000L, exercise("EX", "Row", set(10, 50f), set(10, 50f)))
        val after = workout(
            "after", 2_000L,
            exercise("EX", "Row", set(12, 55f), set(12, 55f), set(12, 55f)),
        )

        assertEquals(
            listOf("sets 2 → 3", "reps 20 → 36", "avg load 50 kg → 55 kg"),
            RoutineComparison.causes(compare(after, before).deltas.single()),
        )
    }

    // ── Edges ────────────────────────────────────────────────────────────────

    @Test
    fun `the oldest workout has no previous and reports its own composition`() {
        val only = workout(
            "only", 1_000L,
            exercise("A", "Squat", set(10, 100f)),
            exercise("B", "Curl", set(10, 10f)),
        )

        val result = compare(only, null)
        assertNull(result.previous)
        assertNull(result.previousTotals)
        assertNull(result.volumeDeltaKg)
        assertEquals(1100.0, result.currentTotals.volumeKg, 0.001)
        // Biggest exercise first, so the panel reads as the workout's makeup.
        assertEquals(listOf("Squat", "Curl"), result.deltas.map { it.title })
    }

    @Test
    fun `the scope carries into the per-exercise breakdown`() {
        val before = workout(
            "before", 1_000L,
            exercise("EX", "Press", set(10, 20f, type = "warmup"), set(8, 60f)),
        )
        val after = workout(
            "after", 2_000L,
            exercise("EX", "Press", set(8, 60f)),
        )

        // All sets: the dropped warmup is the whole difference.
        assertEquals(-200.0, compare(after, before).deltas.single().volumeDeltaKg, 0.001)
        // Working sets only: nothing changed.
        assertTrue(compare(after, before, SetScope.WORKING).deltas.single().isUnchanged)
    }
}
