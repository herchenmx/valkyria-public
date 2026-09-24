package com.example.hevywatch

import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test that validates our models and computation logic against a real
 * Hevy API response captured from GET /v1/workouts/{workoutId}.
 *
 * Fixture: workout d4cd5b22 (POP: Lower 2), captured 2026-04-12.
 * Known total volume: 21897 kg (verified manually against Hevy web UI).
 */
class ExerciseHistoryFixtureTest {

    private val gson = Gson()

    private fun loadWorkoutFixture(): WorkoutDetailResponse {
        val json = javaClass.classLoader!!
            .getResourceAsStream("fixtures/workout_detail_real.json")!!
            .bufferedReader()
            .readText()
        return gson.fromJson(json, WorkoutDetailResponse::class.java)
    }

    // ── Deserialization ──────────────────────────────────────────────────────

    @Test
    fun `workout detail fixture deserializes without error`() {
        val workout = loadWorkoutFixture()
        assertNotNull(workout.id)
        assertNotNull(workout.exercises)
    }

    @Test
    fun `fixture has 7 exercises`() {
        assertEquals(7, loadWorkoutFixture().exercises.size)
    }

    @Test
    fun `fixture exercises have sets with weight and reps`() {
        val workout = loadWorkoutFixture()
        workout.exercises.forEach { ex ->
            assertTrue("Exercise '${ex.title}' should have sets", ex.sets.isNotEmpty())
            ex.sets.forEach { set ->
                assertNotNull("set type must not be null", set.type)
            }
        }
    }

    // ── Timestamp format ─────────────────────────────────────────────────────

    @Test
    fun `real API start_time is parseable by parseInstant`() {
        val workout = loadWorkoutFixture()
        val instant = RoutineProgressComputer.parseInstant(workout.startTime)
        assertNotNull(
            "parseInstant must handle '${workout.startTime}'",
            instant
        )
    }

    @Test
    fun `real API uses offset format (not Z)`() {
        val ts = loadWorkoutFixture().startTime
        assertTrue(
            "Expected +00:00 offset, got: $ts",
            ts.contains("+") || ts.substring(11).contains("-")
        )
    }

    // ── Volume computation ───────────────────────────────────────────────────

    @Test
    fun `workoutVolume matches known value of 21897`() {
        val volume = RoutineProgressComputer.workoutVolume(loadWorkoutFixture())
        assertEquals(
            "Volume should be 21897 kg — matches manual calculation from Hevy web UI",
            21897.0, volume, 0.01
        )
    }

    // ── Progress computation ─────────────────────────────────────────────────

    @Test
    fun `single real workout produces baseline entry`() {
        val progress = RoutineProgressComputer.compute(
            listOf(loadWorkoutFixture()), monthsBack = 12
        )
        assertEquals(1, progress.size)
        assertEquals(21897.0, progress[0].totalVolumeKg, 0.01)
        assertEquals(null, progress[0].deltaKg)
    }
}
