package com.example.hevywatch

import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.api.model.WorkoutExerciseResponse
import com.example.hevywatch.data.api.model.WorkoutSetResponse
import com.example.hevywatch.data.model.RoutineWorkoutVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RoutineProgressComputerTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun set(weightKg: Float? = 50f, reps: Int? = 10, type: String = "normal") =
        WorkoutSetResponse(index = 0, type = type, weightKg = weightKg, reps = reps,
            distanceMeters = null, durationSeconds = null)

    private fun exercise(vararg sets: WorkoutSetResponse) =
        WorkoutExerciseResponse(index = 0, title = "Test", exerciseTemplateId = "t1",
            sets = sets.toList())

    private fun workout(
        id: String,
        startTime: String = "2026-04-01T10:00:00+00:00",
        exercises: List<WorkoutExerciseResponse> = listOf(exercise(set()))
    ) = WorkoutDetailResponse(id = id, title = "Test", description = null, routineId = "r1",
        startTime = startTime, endTime = null, exercises = exercises)

    // ── workoutVolume ────────────────────────────────────────────────────────

    @Test
    fun `workoutVolume sums weight x reps across all exercises and sets`() {
        val w = workout("w1", exercises = listOf(
            exercise(set(50f, 10), set(60f, 8)),   // 500 + 480
            exercise(set(30f, 15))                   // 450
        ))
        assertEquals(1430.0, RoutineProgressComputer.workoutVolume(w), 0.01)
    }

    @Test
    fun `workoutVolume handles null weight as zero`() {
        val w = workout("w1", exercises = listOf(exercise(set(null, 10))))
        assertEquals(0.0, RoutineProgressComputer.workoutVolume(w), 0.01)
    }

    @Test
    fun `workoutVolume handles null reps as zero`() {
        val w = workout("w1", exercises = listOf(exercise(set(50f, null))))
        assertEquals(0.0, RoutineProgressComputer.workoutVolume(w), 0.01)
    }

    @Test
    fun `workoutVolume includes warmup sets in total`() {
        val w = workout("w1", exercises = listOf(
            exercise(
                set(20f, 10, "warmup"),  // 200
                set(50f, 10, "normal")   // 500
            )
        ))
        assertEquals(700.0, RoutineProgressComputer.workoutVolume(w), 0.01)
    }

    // ── compute: empty/single ────────────────────────────────────────────────

    @Test
    fun `empty list returns empty progress`() {
        assertTrue(RoutineProgressComputer.compute(emptyList()).isEmpty())
    }

    @Test
    fun `single workout is baseline (null delta)`() {
        val result = RoutineProgressComputer.compute(listOf(
            workout("w1", exercises = listOf(exercise(set(50f, 10))))
        ), monthsBack = 12)

        assertEquals(1, result.size)
        assertEquals(500.0, result[0].totalVolumeKg, 0.01)
        assertNull(result[0].deltaKg)
    }

    // ── compute: two workouts with delta ─────────────────────────────────────

    @Test
    fun `two workouts compute correct positive delta`() {
        val result = RoutineProgressComputer.compute(listOf(
            workout("w2", startTime = "2026-04-05T10:00:00+00:00",
                exercises = listOf(exercise(set(60f, 10)))),
            workout("w1", startTime = "2026-04-01T10:00:00+00:00",
                exercises = listOf(exercise(set(50f, 10))))
        ), monthsBack = 12)

        assertEquals(2, result.size)
        assertEquals("w2", result[0].workoutId)  // newest first
        assertEquals("w1", result[1].workoutId)
        assertEquals(100.0, result[0].deltaKg!!, 0.01)  // 600 - 500
        assertNull(result[1].deltaKg)
    }

    @Test
    fun `negative delta when volume decreases`() {
        val result = RoutineProgressComputer.compute(listOf(
            workout("w2", startTime = "2026-04-05T10:00:00+00:00",
                exercises = listOf(exercise(set(40f, 10)))),
            workout("w1", startTime = "2026-04-01T10:00:00+00:00",
                exercises = listOf(exercise(set(50f, 10))))
        ), monthsBack = 12)

        assertEquals(-100.0, result[0].deltaKg!!, 0.01)
    }

    // ── compute: three workouts with chained deltas ──────────────────────────

    @Test
    fun `three workouts produce correct chained deltas`() {
        val result = RoutineProgressComputer.compute(listOf(
            workout("w3", startTime = "2026-04-10T10:00:00+00:00",
                exercises = listOf(exercise(set(70f, 10)))),
            workout("w2", startTime = "2026-04-05T10:00:00+00:00",
                exercises = listOf(exercise(set(60f, 10)))),
            workout("w1", startTime = "2026-04-01T10:00:00+00:00",
                exercises = listOf(exercise(set(50f, 10))))
        ), monthsBack = 12)

        assertEquals(3, result.size)
        assertEquals(100.0, result[0].deltaKg!!, 0.01)  // 700-600
        assertEquals(100.0, result[1].deltaKg!!, 0.01)  // 600-500
        assertNull(result[2].deltaKg)
    }

    // ── compute: date filtering ──────────────────────────────────────────────

    @Test
    fun `old workouts are filtered out by monthsBack`() {
        // Dates are relative to "now" so the test doesn't age out: the cutoff is
        // ~3 months back, so a 10-day-old workout survives and a ~5-month-old one
        // is filtered.
        val recent = Instant.now().minusSeconds(10L * 86_400).toString()
        val old = Instant.now().minusSeconds(150L * 86_400).toString()
        val result = RoutineProgressComputer.compute(listOf(
            workout("w2", startTime = recent,
                exercises = listOf(exercise(set(60f, 10)))),
            workout("w1", startTime = old,
                exercises = listOf(exercise(set(50f, 10))))
        ), monthsBack = 3)

        assertEquals(1, result.size)
        assertEquals("w2", result[0].workoutId)
    }

    // ── averageDelta ─────────────────────────────────────────────────────────

    @Test
    fun `averageDelta returns null for empty list`() {
        assertNull(RoutineProgressComputer.averageDelta(emptyList()))
    }

    @Test
    fun `averageDelta returns null when all baseline`() {
        assertNull(RoutineProgressComputer.averageDelta(listOf(
            RoutineWorkoutVolume("w1", "2026-04-01T10:00:00+00:00", 1000.0, null)
        )))
    }

    @Test
    fun `averageDelta computes mean of non-null deltas`() {
        val avg = RoutineProgressComputer.averageDelta(listOf(
            RoutineWorkoutVolume("w3", "d", 1500.0, 200.0),
            RoutineWorkoutVolume("w2", "d", 1300.0, 300.0),
            RoutineWorkoutVolume("w1", "d", 1000.0, null)
        ))!!
        assertEquals(250.0, avg, 0.01)
    }

    // ── parseInstant format tolerance ────────────────────────────────────────

    @Test
    fun `parseInstant handles Z suffix`() {
        assertNotNull(RoutineProgressComputer.parseInstant("2026-04-12T10:00:00Z"))
    }

    @Test
    fun `parseInstant handles offset +00 00`() {
        val result = RoutineProgressComputer.parseInstant("2026-04-12T10:00:00+00:00")
        assertEquals(Instant.parse("2026-04-12T10:00:00Z"), result)
    }

    @Test
    fun `parseInstant handles positive offset`() {
        val result = RoutineProgressComputer.parseInstant("2026-04-12T10:00:00+02:00")
        assertEquals(Instant.parse("2026-04-12T08:00:00Z"), result)
    }

    @Test
    fun `parseInstant handles no timezone`() {
        val result = RoutineProgressComputer.parseInstant("2026-04-12T10:00:00")
        assertEquals(Instant.parse("2026-04-12T10:00:00Z"), result)
    }

    @Test
    fun `parseInstant handles space-separated`() {
        val result = RoutineProgressComputer.parseInstant("2026-04-12 10:00:00")
        assertEquals(Instant.parse("2026-04-12T10:00:00Z"), result)
    }

    @Test
    fun `parseInstant returns null for garbage`() {
        assertNull(RoutineProgressComputer.parseInstant("not-a-date"))
    }
}
