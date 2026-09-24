package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.RoutineDetail
import com.example.hevycompanion.data.RoutineDetailExercise
import com.example.hevycompanion.data.RoutineDetailSet
import com.example.hevycompanion.data.RoutineDetailWrapper
import com.example.hevycompanion.data.WorkoutDetail
import com.example.hevycompanion.data.WorkoutDetailExercise
import com.example.hevycompanion.data.WorkoutDetailSet
import com.example.hevycompanion.recents.ExerciseCompletionStatus.Status
import com.example.hevycompanion.recents.WorkoutCompletion.buildCompletionStatuses
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the watch app's `WorkoutCompletionTest` + `SubstitutionCompletionTest`
 * against the companion's ported [WorkoutCompletion]. Uses the same real API
 * fixtures (copied into this module's test resources) for the exact-match path
 * plus synthetic fixtures for the substitution / extras passes.
 */
class WorkoutCompletionTest {

    private val gson = Gson()
    private val PO = ProgressiveOverloadFolders.IDS // {"2525049"} — the fixture's folder

    private fun loadWorkout(): WorkoutDetail =
        gson.fromJson(
            javaClass.classLoader!!.getResourceAsStream("fixtures/workout_incomplete_real.json")!!
                .bufferedReader().readText(),
            WorkoutDetail::class.java
        )

    private fun loadRoutine(): RoutineDetail =
        gson.fromJson(
            javaClass.classLoader!!.getResourceAsStream("fixtures/routine_lower2_real.json")!!
                .bufferedReader().readText(),
            RoutineDetailWrapper::class.java
        ).routine

    // ── fixture deserialization ──────────────────────────────────────────────

    @Test
    fun `fixtures deserialize correctly`() {
        val workout = loadWorkout()
        assertEquals(5, workout.exercises.size)
        assertEquals("a1fbe803-74ab-4071-9571-59e27adb43b5", workout.id)

        val routine = loadRoutine()
        assertEquals(7, routine.exercises.size)
        assertEquals(2525049L, routine.folderId)
    }

    // ── exact-match path (real fixtures) ─────────────────────────────────────

    @Test
    fun `returns one entry per routine exercise`() {
        assertEquals(7, buildCompletionStatuses(loadWorkout(), loadRoutine(), PO).size)
    }

    @Test
    fun `Squat is INCOMPLETE - 1 of 3 normal sets`() {
        val squat = buildCompletionStatuses(loadWorkout(), loadRoutine(), PO)
            .first { it.exerciseTemplateId == "CC35A01F" }
        assertEquals(Status.INCOMPLETE, squat.status)
        assertEquals(1, squat.recordedNormalSets)
        assertEquals(3, squat.prescribedNormalSets)
    }

    @Test
    fun `Romanian Deadlift is COMPLETE - 3 of 3`() {
        val rdl = buildCompletionStatuses(loadWorkout(), loadRoutine(), PO)
            .first { it.exerciseTemplateId == "2B4B7310" }
        assertEquals(Status.COMPLETE, rdl.status)
        assertEquals(3, rdl.recordedNormalSets)
    }

    @Test
    fun `Single Leg Press is COMPLETE - 6 of 6`() {
        val slp = buildCompletionStatuses(loadWorkout(), loadRoutine(), PO)
            .first { it.exerciseTemplateId == "3FD83744" }
        assertEquals(Status.COMPLETE, slp.status)
        assertEquals(6, slp.recordedNormalSets)
        assertEquals(6, slp.prescribedNormalSets)
    }

    @Test
    fun `Hip Thrust and Cable Twist are MISSING - not in workout`() {
        val statuses = buildCompletionStatuses(loadWorkout(), loadRoutine(), PO)
        assertEquals(Status.MISSING, statuses.first { it.exerciseTemplateId == "68CE0B9B" }.status)
        assertEquals(Status.MISSING, statuses.first { it.exerciseTemplateId == "A2D838BD" }.status)
    }

    @Test
    fun `no spurious substitutions or extras when nothing matches the missing slots`() {
        // The logged exercises are all exact-prescribed, so PO mode must not
        // invent SUBSTITUTED rows or EXTRA rows — result equals exact-match.
        val poStatuses = buildCompletionStatuses(loadWorkout(), loadRoutine(), PO)
        val exactStatuses = buildCompletionStatuses(loadWorkout(), loadRoutine(), emptySet())
        assertEquals(exactStatuses.map { it.exerciseTemplateId to it.status }, poStatuses.map { it.exerciseTemplateId to it.status })
        assertTrue(poStatuses.none { it.status == Status.SUBSTITUTED || it.status == Status.EXTRA })
    }

    @Test
    fun `statuses are sorted COMPLETE before INCOMPLETE before MISSING`() {
        val order = buildCompletionStatuses(loadWorkout(), loadRoutine(), PO).map { it.status }
        val lastComplete = order.lastIndexOf(Status.COMPLETE)
        val firstIncomplete = order.indexOf(Status.INCOMPLETE)
        val firstMissing = order.indexOf(Status.MISSING)
        if (firstIncomplete >= 0) assertTrue(lastComplete < firstIncomplete)
        if (firstMissing >= 0 && firstIncomplete >= 0) assertTrue(firstIncomplete < firstMissing)
    }

    // ── synthetic substitution / extras ──────────────────────────────────────

    private fun rEx(id: String, title: String, normalSets: Int) =
        RoutineDetailExercise(exerciseTemplateId = id, title = title, sets = List(normalSets) { RoutineDetailSet(type = "normal") })

    private fun routine(folderId: Long?, vararg exercises: RoutineDetailExercise) =
        RoutineDetail(id = "r1", title = "Test", folderId = folderId, exercises = exercises.toList())

    private fun wEx(id: String, title: String, normal: Int = 0, warmup: Int = 0) =
        WorkoutDetailExercise(
            exerciseTemplateId = id, title = title,
            sets = List(warmup) { WorkoutDetailSet(type = "warmup") } + List(normal) { WorkoutDetailSet(type = "normal") }
        )

    private fun workout(vararg exercises: WorkoutDetailExercise) =
        WorkoutDetail(id = "w1", routineId = "r1", exercises = exercises.toList())

    @Test
    fun `swapped exercise satisfies the slot as SUBSTITUTED`() {
        val r = routine(2525049L, rEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(wEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 3))

        val s = buildCompletionStatuses(w, r, PO).single()
        assertEquals(Status.SUBSTITUTED, s.status)
        assertEquals("Lateral Raise (Dumbbell)", s.title)
        assertEquals("422B08F1", s.exerciseTemplateId)
        assertEquals("Lateral Raise (Machine)", s.prescribedTitle)
    }

    @Test
    fun `warmup-only swap fills the slot as SUBSTITUTED, not EXTRA`() {
        // Repro of the Squat (Smith Machine) -> Squat (Machine) swap where only
        // the 3 warmups were logged and no normal sets. The swapped-in exercise
        // must claim the routine slot (incomplete SUBSTITUTED), NOT leave the
        // slot MISSING while itself surfacing as EXTRA.
        val r = routine(2525049L, rEx("DDCC3821", "Squat (Smith Machine)", 3))
        val w = workout(wEx("CC35A01F", "Squat (Machine)", normal = 0, warmup = 3))

        val statuses = buildCompletionStatuses(w, r, PO)
        val s = statuses.single()
        assertEquals(Status.SUBSTITUTED, s.status)
        assertEquals("Squat (Machine)", s.title)
        assertEquals("CC35A01F", s.exerciseTemplateId)
        assertEquals("Squat (Smith Machine)", s.prescribedTitle)
        assertEquals(0, s.recordedNormalSets)
        assertEquals(3, s.recordedWarmupSets)
        // Incomplete: no normal sets logged yet.
        assertFalse(s.isComplete)
        // Nothing left over as EXTRA.
        assertTrue(statuses.none { it.status == Status.EXTRA })
    }

    @Test
    fun `substitute with normal sets is preferred over a warmup-only group member`() {
        // Both Squat (Machine) [warmups only] and Squat (Barbell) [normal sets]
        // are in the squats group. The slot should claim the one with real work.
        val r = routine(2525049L, rEx("DDCC3821", "Squat (Smith Machine)", 3))
        val w = workout(
            wEx("CC35A01F", "Squat (Machine)", normal = 0, warmup = 3),
            wEx("D04AC939", "Squat (Barbell)", normal = 3),
        )
        val statuses = buildCompletionStatuses(w, r, PO)
        val slot = statuses.first { it.prescribedTitle == "Squat (Smith Machine)" }
        assertEquals(Status.SUBSTITUTED, slot.status)
        assertEquals("D04AC939", slot.exerciseTemplateId)
        // The warmup-only one is then a genuine leftover -> EXTRA.
        assertEquals(Status.EXTRA, statuses.first { it.exerciseTemplateId == "CC35A01F" }.status)
    }

    @Test
    fun `substitution and extras are disabled outside a PO folder`() {
        val r = routine(999L, rEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(
            wEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 3),
            wEx("37FCC2BB", "Bicep Curl (Dumbbell)", normal = 4),
        )
        val statuses = buildCompletionStatuses(w, r, PO)
        assertEquals(1, statuses.size) // only the prescribed slot
        assertEquals(Status.MISSING, statuses.single().status)
    }

    @Test
    fun `unrelated logged exercise renders as EXTRA in a PO folder`() {
        val r = routine(2525049L, rEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(
            wEx("D5D0354D", "Lateral Raise (Machine)", normal = 3),
            wEx("37FCC2BB", "Bicep Curl (Dumbbell)", normal = 4),
        )
        val statuses = buildCompletionStatuses(w, r, PO)
        assertEquals(Status.COMPLETE, statuses.first { it.exerciseTemplateId == "D5D0354D" }.status)
        assertEquals(Status.EXTRA, statuses.first { it.exerciseTemplateId == "37FCC2BB" }.status)
    }

    @Test
    fun `exact match wins over substitution for its own slot`() {
        val r = routine(
            2525049L,
            rEx("D5D0354D", "Lateral Raise (Machine)", 3),
            rEx("422B08F1", "Lateral Raise (Dumbbell)", 3),
        )
        val w = workout(wEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 3))
        val statuses = buildCompletionStatuses(w, r, PO)
        assertEquals(Status.COMPLETE, statuses.first { it.exerciseTemplateId == "422B08F1" }.status)
        assertEquals(Status.MISSING, statuses.first { it.exerciseTemplateId == "D5D0354D" }.status)
    }

    @Test
    fun `rows sort COMPLETE then SUBSTITUTED then MISSING then EXTRA`() {
        val r = routine(
            2525049L,
            rEx("93A552C6", "Triceps Pushdown", 3),
            rEx("D5D0354D", "Lateral Raise (Machine)", 3),
            rEx("68CE0B9B", "Hip Thrust (Machine)", 3),
        )
        val w = workout(
            wEx("93A552C6", "Triceps Pushdown", normal = 3),
            wEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 3),
            wEx("37FCC2BB", "Bicep Curl (Dumbbell)", normal = 4),
        )
        assertEquals(
            listOf(Status.COMPLETE, Status.SUBSTITUTED, Status.MISSING, Status.EXTRA),
            buildCompletionStatuses(w, r, PO).map { it.status },
        )
    }

    // ── warmup-aware completeness ──────────────────────────────────────────────
    // SMALL group (shoulders) + dumbbell @ 60 kg → advisor prescribes 2 warmups.

    private fun weightedEx(id: String, title: String, normalKg: Float, normal: Int, warmup: Int = 0) =
        WorkoutDetailExercise(
            exerciseTemplateId = id, title = title,
            sets = List(warmup) { WorkoutDetailSet(type = "warmup", weightKg = normalKg * 0.5f) } +
                List(normal) { WorkoutDetailSet(type = "normal", weightKg = normalKg) },
        )

    private fun tmpl(id: String, muscle: String, equip: String) =
        ExerciseTemplate(id = id, title = id, primaryMuscleGroup = muscle, equipment = equip)

    private val SHOULDER_DB = mapOf(
        "422B08F1" to tmpl("422B08F1", "shoulders", "dumbbell"),
        "D5D0354D" to tmpl("D5D0354D", "shoulders", "dumbbell"),
    )

    @Test
    fun `swap missing warmups is complete without a PO target to judge against`() {
        // A swap is off-routine → no PO target. Without a planned weight the
        // logged 60kg must not manufacture 2 phantom warmups (the adductor-at-50kg
        // bug, sub variant); hold the swap to its recorded warmups → complete.
        val r = routine(2525049L, rEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("422B08F1", "Lateral Raise (Dumbbell)", normalKg = 60f, normal = 3, warmup = 0))

        val s = buildCompletionStatuses(w, r, PO, SHOULDER_DB, 70f).single()
        assertEquals(Status.SUBSTITUTED, s.status)
        assertEquals(0, s.expectedWarmupSets)
        assertTrue(s.isComplete)
    }

    @Test
    fun `swap with logged warmups is complete without a PO target`() {
        val r = routine(2525049L, rEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("422B08F1", "Lateral Raise (Dumbbell)", normalKg = 60f, normal = 3, warmup = 2))

        val s = buildCompletionStatuses(w, r, PO, SHOULDER_DB, 70f).single()
        assertEquals(2, s.expectedWarmupSets)
        assertTrue(s.isComplete)
    }

    @Test
    fun `exact match with no warmups is complete when there is no PO target`() {
        val r = routine(2525049L, rEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("D5D0354D", "Lateral Raise (Machine)", normalKg = 60f, normal = 3, warmup = 0))

        val s = buildCompletionStatuses(w, r, PO, SHOULDER_DB, 70f).single()
        assertEquals(Status.COMPLETE, s.status)
        assertEquals(0, s.expectedWarmupSets)
        assertTrue(s.isComplete)
    }

    @Test
    fun `exact match with a PO target still gates on advised warmups`() {
        val r = routine(2525049L, rEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("D5D0354D", "Lateral Raise (Machine)", normalKg = 60f, normal = 3, warmup = 0))

        val s = buildCompletionStatuses(w, r, PO, SHOULDER_DB, 70f, mapOf("D5D0354D" to 60f)).single()
        assertEquals(2, s.expectedWarmupSets)
        assertTrue(!s.isComplete)
    }

    @Test
    fun `without catalog metadata no warmups are expected`() {
        val r = routine(2525049L, rEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("D5D0354D", "Lateral Raise (Machine)", normalKg = 60f, normal = 3, warmup = 0))

        val s = buildCompletionStatuses(w, r, PO).single()
        assertEquals(0, s.expectedWarmupSets)
        assertTrue(s.isComplete)
    }

    @Test
    fun `warmups are judged against the PO target, not the heavier weight lifted`() {
        // rear-delt-at-30kg: shoulders (SMALL) + machine. logged 30 → 1 warmup;
        // PO target 24 (prior sessions) → 0 warmups.
        val meta = mapOf("D8281C62" to tmpl("D8281C62", "shoulders", "machine"))
        val r = routine(2525049L, rEx("D8281C62", "Rear Delt Reverse Fly (Machine)", 3))
        val w = workout(weightedEx("D8281C62", "Rear Delt Reverse Fly (Machine)", normalKg = 30f, normal = 4, warmup = 0))

        // No PO target → held to recorded warmups (0), not the phantom 1 the
        // logged 30kg used to produce → complete.
        val loggedOnly = buildCompletionStatuses(w, r, PO, meta, 70f).single()
        assertEquals(0, loggedOnly.expectedWarmupSets)
        assertTrue(loggedOnly.isComplete)

        // With the prior-sessions PO target (24kg) → 0 warmups → green.
        val withTarget = buildCompletionStatuses(w, r, PO, meta, 70f, mapOf("D8281C62" to 24f)).single()
        assertEquals(0, withTarget.expectedWarmupSets)
        assertTrue(withTarget.isComplete)
    }

    @Test
    fun `adductor completed all suggested sets but lifted above plan stays complete`() {
        // Companion mirror of the reported bug. Adductors are MEDIUM: warmup
        // bucket steps 1 → 2 across 50kg. Plan ~40kg (1 warmup, which the user
        // did); working sets pushed to 55kg. With no PO target the completion
        // recompute fed 55kg back through the advisor → 2 expected → falsely
        // incomplete. Now held to the 1 recorded warmup → complete.
        val meta = mapOf("ADDUCT01" to tmpl("ADDUCT01", "adductors", "machine"))
        val r = routine(2525049L, rEx("ADDUCT01", "Hip Adduction (Machine)", 3))
        val w = workout(weightedEx("ADDUCT01", "Hip Adduction (Machine)", normalKg = 55f, normal = 3, warmup = 1))

        val s = buildCompletionStatuses(w, r, PO, meta, 70f).single()
        assertEquals(Status.COMPLETE, s.status)
        assertEquals(1, s.expectedWarmupSets)
        assertEquals(1, s.recordedWarmupSets)
        assertTrue(s.isComplete)
    }

    @Test
    fun `map groups lateral raise variants together and leaves bicep curl ungrouped`() {
        assertEquals(SubstitutionMap.groupOf("D5D0354D"), SubstitutionMap.groupOf("422B08F1"))
        assertEquals(SubstitutionMap.groupOf("d5d0354d"), SubstitutionMap.groupOf("D5D0354D"))
        assertNull(SubstitutionMap.groupOf("37FCC2BB"))
    }
}
