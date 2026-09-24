package com.example.hevywatch

import com.example.hevywatch.data.SubstitutionMap
import com.example.hevywatch.data.api.model.RoutineExerciseResponse
import com.example.hevywatch.data.api.model.RoutineResponse
import com.example.hevywatch.data.api.model.RoutineSetResponse
import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.api.model.WorkoutExerciseResponse
import com.example.hevywatch.data.api.model.WorkoutSetResponse
import com.example.hevywatch.data.model.ExerciseCompletionStatus.Status
import com.example.hevywatch.presentation.workout.WorkoutDetailViewModel.Companion.buildCompletionStatuses
import com.example.hevywatch.presentation.workout.suggestWarmupSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the substitution + extras matching in [buildCompletionStatuses].
 *
 * Real template IDs from [SubstitutionMap]:
 *  - Lateral Raise group: D5D0354D (Machine), 422B08F1 (Dumbbell), BE289E45 (Cable)
 *  - Triceps group:       93A552C6 (Pushdown), 3765684D (Extension DB)
 *  - 37FCC2BB (Bicep Curl) is intentionally ungrouped.
 */
class SubstitutionCompletionTest {

    private val PO = setOf("2525049")

    // ── fixture builders ─────────────────────────────────────────────────────

    private fun rSet(type: String = "normal") =
        RoutineSetResponse(type, null, null, null, null, null, null, null)

    private fun routineEx(id: String, title: String, normalSets: Int) =
        RoutineExerciseResponse(
            exerciseTemplateId = id, index = 0, title = title, notes = null,
            supersetsId = null, restSeconds = null, equipment = null,
            sets = List(normalSets) { rSet() }
        )

    private fun routine(folderId: String?, vararg exercises: RoutineExerciseResponse) =
        RoutineResponse(
            id = "r1", title = "Test Routine", notes = null, folderId = folderId,
            createdAt = "", updatedAt = "", exercises = exercises.toList()
        )

    private fun wSet(type: String = "normal") =
        WorkoutSetResponse(0, type, null, null, null, null, null, null)

    private fun workoutEx(id: String, title: String, normal: Int = 0, warmup: Int = 0) =
        WorkoutExerciseResponse(
            index = 0, title = title, exerciseTemplateId = id, supersetId = null,
            notes = null, sets = List(warmup) { wSet("warmup") } + List(normal) { wSet("normal") }
        )

    private fun workout(vararg exercises: WorkoutExerciseResponse) =
        WorkoutDetailResponse(
            id = "w1", title = "W", description = null, routineId = "r1",
            startTime = "", endTime = null, isPrivate = null, exercises = exercises.toList()
        )

    /** Workout exercise whose normal sets carry a weight, for the warmup-advisor
     *  expected-count math (warmups: warmup-type sets, normal: weighted normal). */
    private fun weightedEx(id: String, title: String, normalKg: Float, normal: Int, warmup: Int = 0) =
        WorkoutExerciseResponse(
            index = 0, title = title, exerciseTemplateId = id, supersetId = null, notes = null,
            sets = List(warmup) { WorkoutSetResponse(0, "warmup", normalKg * 0.5f, 8, null, null, null, null) } +
                List(normal) { WorkoutSetResponse(0, "normal", normalKg, 8, null, null, null, null) }
        )

    // ── map sanity ───────────────────────────────────────────────────────────

    @Test
    fun `map groups lateral raise machine and dumbbell together`() {
        assertTrue(SubstitutionMap.areInterchangeable("D5D0354D", "422B08F1"))
        assertEquals(SubstitutionMap.groupOf("D5D0354D"), SubstitutionMap.groupOf("BE289E45"))
    }

    @Test
    fun `map lookup is case insensitive and an exercise is not interchangeable with itself`() {
        assertEquals(SubstitutionMap.groupOf("d5d0354d"), SubstitutionMap.groupOf("D5D0354D"))
        assertTrue(!SubstitutionMap.areInterchangeable("D5D0354D", "D5D0354D"))
    }

    @Test
    fun `ungrouped exercise has no group`() {
        assertNull(SubstitutionMap.groupOf("37FCC2BB")) // Bicep Curl (Dumbbell)
    }

    // ── substitution ─────────────────────────────────────────────────────────

    @Test
    fun `swapped exercise satisfies the slot as SUBSTITUTED`() {
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(workoutEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 3))

        val statuses = buildCompletionStatuses(w, r, PO)
        assertEquals(1, statuses.size)
        val s = statuses.first()
        assertEquals(Status.SUBSTITUTED, s.status)
        assertEquals("Lateral Raise (Dumbbell)", s.title)            // shows what was done
        assertEquals("422B08F1", s.exerciseTemplateId)
        assertEquals("Lateral Raise (Machine)", s.prescribedTitle)   // the slot it filled
        assertEquals(3, s.recordedNormalSets)
        assertEquals(3, s.prescribedNormalSets)
    }

    @Test
    fun `substitution is disabled outside a PO folder`() {
        val r = routine("999", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(workoutEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 3))

        val statuses = buildCompletionStatuses(w, r, PO)
        assertEquals(1, statuses.size)
        assertEquals(Status.MISSING, statuses.first().status) // unchanged legacy behaviour
        assertEquals("D5D0354D", statuses.first().exerciseTemplateId)
    }

    @Test
    fun `warmup-only swap fills the slot as SUBSTITUTED, not EXTRA`() {
        // In-progress swap: the substitute has only warmups logged so far. It must
        // still claim the routine slot (incomplete SUBSTITUTED) rather than leaving
        // the slot MISSING while surfacing itself as EXTRA.
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(workoutEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 0, warmup = 2))

        val statuses = buildCompletionStatuses(w, r, PO)
        val s = statuses.single()
        assertEquals(Status.SUBSTITUTED, s.status)
        assertEquals("422B08F1", s.exerciseTemplateId)
        assertEquals("Lateral Raise (Machine)", s.prescribedTitle)
        assertEquals(0, s.recordedNormalSets)
        assertEquals(2, s.recordedWarmupSets)
        assertTrue(!s.isComplete)                      // no normal sets yet
        assertTrue(statuses.none { it.status == Status.EXTRA })
    }

    @Test
    fun `substitute with normal sets is preferred over a warmup-only group member`() {
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(
            workoutEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 0, warmup = 2),
            workoutEx("BE289E45", "Lateral Raise (Cable)", normal = 3)
        )
        val statuses = buildCompletionStatuses(w, r, PO)
        val slot = statuses.first { it.prescribedTitle == "Lateral Raise (Machine)" }
        assertEquals(Status.SUBSTITUTED, slot.status)
        assertEquals("BE289E45", slot.exerciseTemplateId)   // the one with real work
        assertEquals(Status.EXTRA, statuses.first { it.exerciseTemplateId == "422B08F1" }.status)
    }

    @Test
    fun `exact match wins over substitution for its own slot`() {
        // Routine prescribes both Machine and Dumbbell; only Dumbbell was logged.
        val r = routine(
            "2525049",
            routineEx("D5D0354D", "Lateral Raise (Machine)", 3),
            routineEx("422B08F1", "Lateral Raise (Dumbbell)", 3)
        )
        val w = workout(workoutEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 3))

        val statuses = buildCompletionStatuses(w, r, PO)
        // Dumbbell satisfies its OWN slot exactly, Machine is left missing (not substituted).
        assertEquals(Status.COMPLETE, statuses.first { it.exerciseTemplateId == "422B08F1" }.status)
        assertEquals(Status.MISSING, statuses.first { it.exerciseTemplateId == "D5D0354D" }.status)
    }

    @Test
    fun `one logged exercise cannot satisfy two slots - second group member is an extra`() {
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(
            workoutEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 3),
            workoutEx("BE289E45", "Lateral Raise (Cable)", normal = 3)
        )

        val statuses = buildCompletionStatuses(w, r, PO)
        val subs = statuses.filter { it.status == Status.SUBSTITUTED }
        val extras = statuses.filter { it.status == Status.EXTRA }
        assertEquals(1, subs.size) // only one fills the single slot
        assertEquals(1, extras.size) // the other is surfaced as extra, not dropped
    }

    // ── extras ───────────────────────────────────────────────────────────────

    @Test
    fun `unrelated logged exercise renders as EXTRA`() {
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(
            workoutEx("D5D0354D", "Lateral Raise (Machine)", normal = 3),       // exact → complete
            workoutEx("37FCC2BB", "Bicep Curl (Dumbbell)", normal = 4)           // ungrouped → extra
        )

        val statuses = buildCompletionStatuses(w, r, PO)
        assertEquals(Status.COMPLETE, statuses.first { it.exerciseTemplateId == "D5D0354D" }.status)
        val extra = statuses.first { it.exerciseTemplateId == "37FCC2BB" }
        assertEquals(Status.EXTRA, extra.status)
        assertEquals(4, extra.recordedNormalSets)
        assertEquals(0, extra.prescribedNormalSets)
    }

    @Test
    fun `extras are not shown outside a PO folder`() {
        val r = routine("999", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(
            workoutEx("D5D0354D", "Lateral Raise (Machine)", normal = 3),
            workoutEx("37FCC2BB", "Bicep Curl (Dumbbell)", normal = 4)
        )

        val statuses = buildCompletionStatuses(w, r, PO)
        assertEquals(1, statuses.size) // only the prescribed slot, extra suppressed
        assertNull(statuses.firstOrNull { it.exerciseTemplateId == "37FCC2BB" })
    }

    // ── warmup-aware completeness ──────────────────────────────────────────────
    // SMALL group (shoulders) + dumbbell @ 60 kg → advisor prescribes 2 warmups
    // (50 ≤ effort < 80 bucket). Metadata maps supply equipment + muscle group.

    private val SHOULDER_DB_EQUIP = mapOf("422B08F1" to "dumbbell", "D5D0354D" to "dumbbell")
    private val SHOULDER_DB_MUSCLE = mapOf("422B08F1" to "shoulders", "D5D0354D" to "shoulders")

    @Test
    fun `swap missing warmups is complete when there is no PO target to judge against`() {
        // A swap is off-routine, so no PO target is ever computed for it. Without
        // a planned weight we can't assert the user under-warmed — recomputing
        // from the logged 60kg would demand 2 phantom warmups and falsely read
        // incomplete (the adductor-at-50kg bug, sub variant). Hold the swap to the
        // warmups it recorded instead → complete.
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("422B08F1", "Lateral Raise (Dumbbell)", normalKg = 60f, normal = 3, warmup = 0))

        val s = buildCompletionStatuses(w, r, PO, SHOULDER_DB_EQUIP, SHOULDER_DB_MUSCLE, 70f).first()
        assertEquals(Status.SUBSTITUTED, s.status)
        assertEquals(0, s.expectedWarmupSets)   // no plan → held to recorded (0)
        assertEquals(0, s.recordedWarmupSets)
        assertTrue(s.isComplete)                // normal done, warmups not demanded
    }

    @Test
    fun `swap with logged warmups is complete without a PO target`() {
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("422B08F1", "Lateral Raise (Dumbbell)", normalKg = 60f, normal = 3, warmup = 2))

        val s = buildCompletionStatuses(w, r, PO, SHOULDER_DB_EQUIP, SHOULDER_DB_MUSCLE, 70f).first()
        assertEquals(2, s.expectedWarmupSets)   // no plan → held to recorded (2)
        assertEquals(2, s.recordedWarmupSets)
        assertTrue(s.isComplete)
    }

    @Test
    fun `exact match with no warmups is complete when there is no PO target`() {
        // No prior-session PO target (empty poByTemplate) → we have no planned
        // weight, so the logged 60kg must not retroactively demand warmups. The
        // exercise is held to what it recorded (0 warmups) → complete.
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("D5D0354D", "Lateral Raise (Machine)", normalKg = 60f, normal = 3, warmup = 0))

        val s = buildCompletionStatuses(w, r, PO, SHOULDER_DB_EQUIP, SHOULDER_DB_MUSCLE, 70f).first()
        assertEquals(Status.COMPLETE, s.status)
        assertEquals(0, s.expectedWarmupSets)
        assertTrue(s.isComplete)
    }

    @Test
    fun `exact match with a PO target still gates on advised warmups`() {
        // The gate is NOT gone — with a genuine planned target the exercise is
        // still held to the warmups that target warrants.
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("D5D0354D", "Lateral Raise (Machine)", normalKg = 60f, normal = 3, warmup = 0))

        val s = buildCompletionStatuses(
            w, r, PO, SHOULDER_DB_EQUIP, SHOULDER_DB_MUSCLE, 70f,
            mapOf("D5D0354D" to (60f to false))   // planned 60kg → 2 advised warmups
        ).first()
        assertEquals(2, s.expectedWarmupSets)
        assertTrue(!s.isComplete)                 // warmups genuinely short of the plan
    }

    @Test
    fun `warmups are judged against the PO target, not the heavier weight lifted this session`() {
        // The rear-delt-at-30kg case: shoulders (SMALL) + machine.
        //   logged 30kg  → 30≤effort<50 bucket → 1 advised warmup
        //   PO target 24 → effort<30           → 0 advised warmups
        val equip = mapOf("D8281C62" to "machine")
        val muscle = mapOf("D8281C62" to "shoulders")
        val r = routine("2525049", routineEx("D8281C62", "Rear Delt Reverse Fly (Machine)", 3))
        val w = workout(weightedEx("D8281C62", "Rear Delt Reverse Fly (Machine)", normalKg = 30f, normal = 4, warmup = 0))

        // No PO target → held to recorded warmups (0), NOT the phantom 1 that
        // recomputing from the logged 30kg used to produce → complete.
        val loggedOnly = buildCompletionStatuses(w, r, PO, equip, muscle, 70f).first()
        assertEquals(0, loggedOnly.expectedWarmupSets)
        assertTrue(loggedOnly.isComplete)

        // With the prior-sessions PO target (24kg) → 0 advised warmups → green,
        // even though the first set this session was logged at 30kg.
        val withTarget = buildCompletionStatuses(
            w, r, PO, equip, muscle, 70f, mapOf("D8281C62" to (24f to false))
        ).first()
        assertEquals(0, withTarget.expectedWarmupSets)
        assertTrue(withTarget.isComplete)
    }

    @Test
    fun `adductor completed all suggested sets but lifted above plan stays complete`() {
        // The reported bug. Adductors are a MEDIUM group: the warmup bucket steps
        // 1 → 2 across the 50kg boundary. The user's plan was ~40kg (1 advised
        // warmup, which they did), but they pushed the working sets to 55kg. With
        // no prior-session PO target the completion recompute used to feed 55kg
        // back through the advisor → 2 expected warmups → recorded 1 < 2 → the
        // completed exercise falsely read incomplete. The heavier lift must only
        // raise NEXT session's target, not un-complete this instance.
        val equip = mapOf("ADDUCT01" to "machine")
        val muscle = mapOf("ADDUCT01" to "adductors")
        val r = routine("2525049", routineEx("ADDUCT01", "Hip Adduction (Machine)", 3))
        val w = workout(weightedEx("ADDUCT01", "Hip Adduction (Machine)", normalKg = 55f, normal = 3, warmup = 1))

        // No PO target (first time / no usable history) → held to its 1 recorded
        // warmup, so the 55kg lift can't manufacture a 2nd required warmup.
        val s = buildCompletionStatuses(w, r, PO, equip, muscle, 70f).first()
        assertEquals(Status.COMPLETE, s.status)
        assertEquals(1, s.expectedWarmupSets)
        assertEquals(1, s.recordedWarmupSets)
        assertTrue("adductor completed at above-plan weight must read complete", s.isComplete)

        // Sanity: the advisor genuinely would want 2 warmups at 55kg — so the old
        // logged-weight fallback is what produced the false incomplete.
        val at55 = suggestWarmupSets(
            primaryMuscleGroup = "adductors", equipment = "machine",
            workingWeightKg = 55f, normalSetCount = 3, exerciseTemplateId = "ADDUCT01"
        ).size
        assertEquals(2, at55)
    }

    @Test
    fun `without catalog metadata no warmups are expected and completion ignores them`() {
        val r = routine("2525049", routineEx("D5D0354D", "Lateral Raise (Machine)", 3))
        val w = workout(weightedEx("D5D0354D", "Lateral Raise (Machine)", normalKg = 60f, normal = 3, warmup = 0))

        // No equipment/muscle maps passed → advisor can't qualify it.
        val s = buildCompletionStatuses(w, r, PO).first()
        assertEquals(0, s.expectedWarmupSets)
        assertTrue(s.isComplete)
    }

    // ── ordering ─────────────────────────────────────────────────────────────

    @Test
    fun `rows sort COMPLETE then SUBSTITUTED then MISSING then EXTRA`() {
        val r = routine(
            "2525049",
            routineEx("93A552C6", "Triceps Pushdown", 3),              // exact complete
            routineEx("D5D0354D", "Lateral Raise (Machine)", 3),       // substituted
            routineEx("68CE0B9B", "Hip Thrust (Machine)", 3)           // missing
        )
        val w = workout(
            workoutEx("93A552C6", "Triceps Pushdown", normal = 3),
            workoutEx("422B08F1", "Lateral Raise (Dumbbell)", normal = 3),
            workoutEx("37FCC2BB", "Bicep Curl (Dumbbell)", normal = 4) // extra
        )

        val order = buildCompletionStatuses(w, r, PO).map { it.status }
        assertEquals(
            listOf(Status.COMPLETE, Status.SUBSTITUTED, Status.MISSING, Status.EXTRA),
            order
        )
    }
}
