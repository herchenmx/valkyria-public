package com.example.hevywatch

import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.data.model.RoutineExercise
import com.example.hevywatch.data.model.RoutineSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.data.model.toActiveWorkout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineMapperTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun routineSet(
        type: SetType = SetType.NORMAL,
        weightKg: Float? = 60f,
        reps: Int? = 10
    ) = RoutineSet(
        type = type,
        weightKg = weightKg,
        reps = reps,
        repRangeStart = null,
        repRangeEnd = null,
        distanceMeters = null,
        durationSeconds = null,
    )

    private fun routineExercise(
        templateId: String = "ex1",
        title: String = "Bench Press",
        restSeconds: Int? = 90,
        equipment: String? = "barbell",
        notes: String? = null,
        sets: List<RoutineSet> = listOf(routineSet())
    ) = RoutineExercise(
        exerciseTemplateId = templateId,
        index = 0,
        title = title,
        setCount = sets.size,
        restSeconds = restSeconds,
        sets = sets,
        equipment = equipment,
        notes = notes
    )

    private fun routine(
        id: String = "r1",
        title: String = "Push Day",
        routineId: String = id,
        exercises: List<RoutineExercise> = listOf(routineExercise())
    ) = Routine(
        id = id,
        title = title,
        notes = null,
        folderId = null,
        exercises = exercises,
        updatedAt = "2026-01-01T00:00:00Z"
    )

    // ── routineId mapping ─────────────────────────────────────────────────────

    @Test
    fun `routineId is set from routine id`() {
        val r = routine(id = "abc-123")
        assertEquals("abc-123", r.toActiveWorkout().routineId)
    }

    @Test
    fun `workout name is set from routine title`() {
        val r = routine(title = "Leg Day")
        assertEquals("Leg Day", r.toActiveWorkout().name)
    }

    // ── Exercise mapping ──────────────────────────────────────────────────────

    @Test
    fun `all exercises are mapped`() {
        val r = routine(exercises = listOf(
            routineExercise(templateId = "ex1"),
            routineExercise(templateId = "ex2"),
            routineExercise(templateId = "ex3")
        ))
        assertEquals(3, r.toActiveWorkout().exercises.size)
    }

    @Test
    fun `exercise templateId is preserved`() {
        val r = routine(exercises = listOf(routineExercise(templateId = "t-xyz")))
        assertEquals("t-xyz", r.toActiveWorkout().exercises[0].exerciseTemplateId)
    }

    @Test
    fun `exercise title is preserved`() {
        val r = routine(exercises = listOf(routineExercise(title = "Deadlift")))
        assertEquals("Deadlift", r.toActiveWorkout().exercises[0].title)
    }

    @Test
    fun `exercise restTimerSeconds is mapped from restSeconds`() {
        val r = routine(exercises = listOf(routineExercise(restSeconds = 120)))
        assertEquals(120, r.toActiveWorkout().exercises[0].restTimerSeconds)
    }

    @Test
    fun `null restSeconds is preserved`() {
        val r = routine(exercises = listOf(routineExercise(restSeconds = null)))
        assertNull(r.toActiveWorkout().exercises[0].restTimerSeconds)
    }

    @Test
    fun `exercise equipment is preserved`() {
        val r = routine(exercises = listOf(routineExercise(equipment = "dumbbell")))
        assertEquals("dumbbell", r.toActiveWorkout().exercises[0].equipment)
    }

    @Test
    fun `exercise notes are mapped through to ActiveExercise`() {
        val r = routine(exercises = listOf(routineExercise(notes = "Smith machine base: 20kg")))
        assertEquals("Smith machine base: 20kg", r.toActiveWorkout().exercises[0].notes)
    }

    @Test
    fun `null notes are preserved`() {
        val r = routine(exercises = listOf(routineExercise(notes = null)))
        assertNull(r.toActiveWorkout().exercises[0].notes)
    }

    // ── Set mapping ───────────────────────────────────────────────────────────

    @Test
    fun `all sets are mapped`() {
        val r = routine(exercises = listOf(
            routineExercise(sets = listOf(routineSet(), routineSet(), routineSet()))
        ))
        assertEquals(3, r.toActiveWorkout().exercises[0].sets.size)
    }

    @Test
    fun `set type is preserved`() {
        val r = routine(exercises = listOf(
            routineExercise(sets = listOf(routineSet(type = SetType.WARMUP)))
        ))
        assertEquals(SetType.WARMUP, r.toActiveWorkout().exercises[0].sets[0].setType)
    }

    @Test
    fun `set weightKg and reps are preserved`() {
        val r = routine(exercises = listOf(
            routineExercise(sets = listOf(routineSet(weightKg = 100f, reps = 5)))
        ))
        val set = r.toActiveWorkout().exercises[0].sets[0]
        assertEquals(100f, set.weightKg)
        assertEquals(5, set.reps)
    }

    @Test
    fun `sets start as not completed`() {
        val r = routine()
        assertTrue(r.toActiveWorkout().exercises[0].sets.none { it.completed })
    }

    // ── Progressive overload flag ─────────────────────────────────────────────

    @Test
    fun `progressiveOverload flag is carried through`() {
        val r = Routine(
            id = "r1", title = "Push", notes = null, folderId = null,
            exercises = emptyList(), updatedAt = "2026-01-01T00:00:00Z",
            progressiveOverload = true
        )
        assertTrue(r.toActiveWorkout().progressiveOverload)
    }
}
