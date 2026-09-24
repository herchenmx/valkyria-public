package com.example.hevycompanion.trends

import com.example.hevycore.exercise.AssistedBodyweight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Routine Trends totals: what counts as volume / sets / reps, how the
 * warmup scope changes them, the assisted-bodyweight inversion, and the
 * set-line folding the delta breakdown argues from.
 */
class WorkoutTotalsTest {

    private val plain = VolumeRule()

    private fun set(reps: Int, kg: Float, type: String = "normal") =
        TrendSet(type = type, weightKg = kg, reps = reps)

    private fun exercise(id: String = "EX1", title: String = "Exercise", vararg sets: TrendSet) =
        TrendExercise(templateId = id, title = title, sets = sets.toList())

    @Test
    fun `volume is weight times reps summed over sets`() {
        val ex = exercise("EX1", "Squat", set(10, 50f), set(8, 60f))
        val totals = WorkoutTotals.of(ex, SetScope.ALL, plain)
        assertEquals(10 * 50.0 + 8 * 60.0, totals.volumeKg, 0.001)
        assertEquals(2, totals.sets)
        assertEquals(18, totals.reps)
    }

    @Test
    fun `all-sets scope includes warmups, working scope drops them`() {
        val ex = exercise(
            "EX1", "Squat",
            set(10, 20f, type = "warmup"),
            set(8, 60f),
            set(8, 60f),
        )
        val all = WorkoutTotals.of(ex, SetScope.ALL, plain)
        assertEquals(3, all.sets)
        assertEquals(26, all.reps)
        assertEquals(200.0 + 960.0, all.volumeKg, 0.001)

        val working = WorkoutTotals.of(ex, SetScope.WORKING, plain)
        assertEquals(2, working.sets)
        assertEquals(16, working.reps)
        assertEquals(960.0, working.volumeKg, 0.001)
    }

    @Test
    fun `working scope keeps dropsets and failure sets`() {
        val ex = exercise(
            "EX1", "Curl",
            set(10, 10f, type = "dropset"),
            set(5, 12f, type = "failure"),
            set(3, 8f, type = "warmup"),
        )
        val working = WorkoutTotals.of(ex, SetScope.WORKING, plain)
        assertEquals(2, working.sets)
        assertEquals(15, working.reps)
    }

    @Test
    fun `bodyweight sets contribute reps but no volume`() {
        val ex = exercise("EX1", "Push Up", set(20, 0f))
        val totals = WorkoutTotals.of(ex, SetScope.ALL, plain)
        assertEquals(0.0, totals.volumeKg, 0.001)
        assertEquals(20, totals.reps)
        assertEquals(1, totals.sets)
    }

    @Test
    fun `assisted exercises count the bodyweight actually shifted`() {
        val assistedId = AssistedBodyweight.TEMPLATE_IDS.first()
        val ex = exercise(assistedId, "Pull Up (Assisted)", set(10, 20f))
        val rule = VolumeRule.forRoutine(AssistedBodyweight.ROUTINE_ID, bodyweightKg = 57f)
        // 57 kg bodyweight minus 20 kg of stack assistance = 37 kg moved.
        assertEquals(370.0, WorkoutTotals.of(ex, SetScope.ALL, rule).volumeKg, 0.001)
    }

    @Test
    fun `assisted inversion applies only on the assisted routine`() {
        val assistedId = AssistedBodyweight.TEMPLATE_IDS.first()
        val ex = exercise(assistedId, "Pull Up (Assisted)", set(10, 20f))
        val rule = VolumeRule.forRoutine("some-other-routine", bodyweightKg = 57f)
        assertEquals(200.0, WorkoutTotals.of(ex, SetScope.ALL, rule).volumeKg, 0.001)
    }

    @Test
    fun `workout totals sum every exercise`() {
        val workout = TrendWorkout(
            id = "w1",
            title = "POP 1: Upper",
            startTimeIso = "2026-08-01T10:00:00Z",
            startEpochMs = 0L,
            exercises = listOf(
                exercise("A", "A", set(10, 50f)),
                exercise("B", "B", set(5, 20f)),
            ),
        )
        val totals = WorkoutTotals.of(workout, SetScope.ALL, plain)
        assertEquals(600.0, totals.volumeKg, 0.001)
        assertEquals(2, totals.sets)
        assertEquals(15, totals.reps)
    }

    @Test
    fun `avg load per rep is volume over reps`() {
        // The user's 13 July biceps session: 1x15@16 + 2x15@15.
        val ex = exercise("BI", "Biceps Curl", set(15, 16f), set(15, 15f), set(15, 15f))
        val totals = WorkoutTotals.of(ex, SetScope.ALL, plain)
        assertEquals(690.0, totals.volumeKg, 0.001)
        assertEquals(15.333, totals.avgLoadPerRepKg!!, 0.001)
    }

    @Test
    fun `avg load is null when nothing was repped`() {
        val ex = exercise("EX1", "Plank", TrendSet(type = "normal", weightKg = 0f, reps = 0))
        assertNull(WorkoutTotals.of(ex, SetScope.ALL, plain).avgLoadPerRepKg)
    }

    @Test
    fun `set lines fold runs of identical sets`() {
        val ex = exercise("BI", "Biceps Curl", set(15, 16f), set(15, 15f), set(15, 15f))
        assertEquals(
            listOf("1 × 15 @ 16 kg", "2 × 15 @ 15 kg"),
            WorkoutTotals.setLines(ex, SetScope.ALL),
        )
    }

    @Test
    fun `set lines fold runs, not the whole session`() {
        // A→B→A must stay three lines: the order is part of the evidence.
        val ex = exercise("EX1", "Press", set(10, 40f), set(8, 50f), set(10, 40f))
        assertEquals(
            listOf("1 × 10 @ 40 kg", "1 × 8 @ 50 kg", "1 × 10 @ 40 kg"),
            WorkoutTotals.setLines(ex, SetScope.ALL),
        )
    }

    @Test
    fun `set lines label non-normal sets and omit a zero weight`() {
        val ex = exercise(
            "EX1", "Push Up",
            set(10, 25f, type = "warmup"),
            set(20, 0f),
        )
        assertEquals(
            listOf("1 × 10 @ 25 kg  (warmup)", "1 × 20"),
            WorkoutTotals.setLines(ex, SetScope.ALL),
        )
    }

    @Test
    fun `set lines honour the scope`() {
        val ex = exercise("EX1", "Press", set(10, 20f, type = "warmup"), set(8, 60f))
        assertEquals(listOf("1 × 8 @ 60 kg"), WorkoutTotals.setLines(ex, SetScope.WORKING))
    }
}
