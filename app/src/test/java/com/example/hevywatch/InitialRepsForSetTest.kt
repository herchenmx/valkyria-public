package com.example.hevywatch

import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.initialRepsForSet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LogSetScreen reps picker initial value.
 *
 * In a PO routine the normal-set rep target is the PO "qualifying" rep count
 * (15). A routine prescribing `rep_range: { start: 10, end: 15 }` populates
 * `set.reps = 10` via the active-workout mapper, but the user should still
 * land on 15 in the picker — that's the rep count PO needs to count this
 * session as qualifying. Hence the floor.
 */
class InitialRepsForSetTest {

    private fun normalSet(reps: Int? = null, repRangeStart: Int? = null, repRangeEnd: Int? = null) =
        ActiveSet(
            setType = SetType.NORMAL,
            reps = reps,
            repRangeStart = repRangeStart,
            repRangeEnd = repRangeEnd,
        )

    private fun warmupSet(reps: Int? = null, repRangeStart: Int? = null) =
        ActiveSet(
            setType = SetType.WARMUP,
            reps = reps,
            repRangeStart = repRangeStart,
        )

    // ── PO normal sets: floor at 15 ───────────────────────────────────────────

    @Test
    fun `PO normal set with prescribed 10 reps floors to 15`() {
        // Routine prescribes rep_range 10-15; mapper picks reps=10. PO should
        // start the picker at 15 so logging 15 reps qualifies for overload.
        assertEquals(15, initialRepsForSet(normalSet(reps = 10), isPoRoutine = true))
    }

    @Test
    fun `PO normal set with prescribed 12 reps floors to 15`() {
        assertEquals(15, initialRepsForSet(normalSet(reps = 12), isPoRoutine = true))
    }

    @Test
    fun `PO normal set with prescribed 15 reps stays at 15`() {
        assertEquals(15, initialRepsForSet(normalSet(reps = 15), isPoRoutine = true))
    }

    @Test
    fun `PO normal set with prescribed 20 reps keeps the higher prescription`() {
        // Floor only — if the routine asks for more than 15, respect it.
        assertEquals(20, initialRepsForSet(normalSet(reps = 20), isPoRoutine = true))
    }

    @Test
    fun `PO normal set with null reps defaults to 15`() {
        assertEquals(15, initialRepsForSet(normalSet(reps = null), isPoRoutine = true))
    }

    // ── PO non-normal sets: untouched ─────────────────────────────────────────

    @Test
    fun `PO warmup set keeps prescribed reps even if below 15`() {
        // Warmups intentionally use lower reps — flooring would be wrong.
        assertEquals(8, initialRepsForSet(warmupSet(reps = 8), isPoRoutine = true))
    }

    @Test
    fun `PO warmup with null reps falls back to repRangeStart`() {
        assertEquals(
            6,
            initialRepsForSet(warmupSet(reps = null, repRangeStart = 6), isPoRoutine = true)
        )
    }

    // ── Non-PO routines: no floor ─────────────────────────────────────────────

    @Test
    fun `non-PO normal set with prescribed 10 reps stays at 10`() {
        assertEquals(10, initialRepsForSet(normalSet(reps = 10), isPoRoutine = false))
    }

    @Test
    fun `non-PO normal set with null reps defaults to 15`() {
        // Preserves the prior behaviour: normal sets default to 15 when the
        // routine prescribes nothing, regardless of PO.
        assertEquals(15, initialRepsForSet(normalSet(reps = null), isPoRoutine = false))
    }

    @Test
    fun `non-PO warmup with prescribed 8 reps stays at 8`() {
        assertEquals(8, initialRepsForSet(warmupSet(reps = 8), isPoRoutine = false))
    }

    // ── Rest-timer next-set preview parity ────────────────────────────────────
    // The rest-timer screen reads the next-set rep preview through this same
    // helper so the "X reps" caption under the timer ring matches the value
    // the LogSet picker lands on when the timer ends. These cases pin that
    // contract — they are scenarios the user saw the rest-timer get wrong
    // before the fix (first normal set showed 10; subsequent normal sets
    // showed the previously-logged rep count).

    @Test
    fun `rest-timer first PO normal set previews 15 not the prescribed 10`() {
        // Pre-fix: rest-timer displayed `set.reps` raw → 10.
        // Post-fix: shares initialRepsForSet → floored to 15.
        assertEquals(15, initialRepsForSet(normalSet(reps = 10), isPoRoutine = true))
    }

    @Test
    fun `rest-timer subsequent PO normal set previews 15 not the carried-forward 12`() {
        // Pre-fix: after a user logged 12 reps the active-workout mapper
        // carried 12 forward to the next set and the rest-timer rendered "12".
        // Post-fix: still floored to 15 — same as the LogSet picker.
        assertEquals(15, initialRepsForSet(normalSet(reps = 12), isPoRoutine = true))
    }
}
