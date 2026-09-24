package com.example.hevywatch

import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards ActiveWorkout's Gson serialization — specifically the warmupAdvisorApplied
 * flag that prevents the warmup advisor from re-prompting on app close/reopen.
 *
 * Regression: before this flag existed, closing the app mid-workout would cause
 * the "Prescribed warmup sets detected. Override?" prompt to reappear because
 * the advisor's own previously-injected warmups looked like prescribed ones on
 * the second pass. The flag must:
 *  - default to false on fresh workouts
 *  - round-trip through Gson intact (so disk recovery preserves it)
 *  - default to false when deserializing JSON from a pre-flag build
 */
class ActiveWorkoutPersistenceTest {

    private val gson = Gson()

    private fun sampleWorkout(warmupAdvisorApplied: Boolean = false) = ActiveWorkout(
        name = "Leg Day",
        exercises = listOf(
            ActiveExercise(
                exerciseTemplateId = "abc",
                title = "Squat",
                sets = listOf(ActiveSet())
            )
        ),
        warmupAdvisorApplied = warmupAdvisorApplied
    )

    @Test
    fun `warmupAdvisorApplied defaults to false on fresh workout`() {
        assertFalse(sampleWorkout().warmupAdvisorApplied)
    }

    @Test
    fun `warmupAdvisorApplied true survives gson round-trip`() {
        val before = sampleWorkout(warmupAdvisorApplied = true)
        val json = gson.toJson(before)
        val after = gson.fromJson(json, ActiveWorkout::class.java)
        assertTrue(
            "crash recovery must preserve the advisor-applied flag",
            after.warmupAdvisorApplied
        )
    }

    @Test
    fun `warmupAdvisorApplied false survives gson round-trip`() {
        val before = sampleWorkout(warmupAdvisorApplied = false)
        val json = gson.toJson(before)
        val after = gson.fromJson(json, ActiveWorkout::class.java)
        assertFalse(after.warmupAdvisorApplied)
    }

    @Test
    fun `legacy json without warmupAdvisorApplied deserializes with flag false`() {
        // Simulates JSON from a pre-flag build stored in SharedPreferences.
        // Gson must not crash, and the missing field must default to false
        // so the advisor runs once on the recovered workout (acceptable fallback).
        val legacyJson = """
            {
              "id": "w-1",
              "name": "Legacy Workout",
              "description": "",
              "startTimeMs": 1000,
              "exercises": [],
              "progressiveOverload": false,
              "trackWorkoutAsRoutine": false
            }
        """.trimIndent()
        val recovered = gson.fromJson(legacyJson, ActiveWorkout::class.java)
        assertEquals("Legacy Workout", recovered.name)
        assertFalse(recovered.warmupAdvisorApplied)
    }
}
