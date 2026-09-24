package com.example.hevywatch

import com.example.hevywatch.presentation.workout.SetNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the set-navigation clamping on LogSetScreen's Prev / Next buttons.
 *
 * Behaviour under test: arrow navigation must stay within the current exercise
 * and must NOT flow into the preceding / following exercise. Cross-exercise
 * transitions happen only via completeCurrentSet / the Exercise Done dialog.
 */
class SetNavigationTest {

    // ── nextWithinExercise ────────────────────────────────────────────────────

    @Test
    fun `next advances while there are more sets`() {
        assertEquals(1, SetNavigation.nextWithinExercise(currentSetIndex = 0, totalSets = 3))
        assertEquals(2, SetNavigation.nextWithinExercise(currentSetIndex = 1, totalSets = 3))
    }

    @Test
    fun `next is clamped at the last set of the exercise`() {
        // Already on the last set (idx 2 of 3 sets) — staying put, not hopping to next exercise.
        assertEquals(2, SetNavigation.nextWithinExercise(currentSetIndex = 2, totalSets = 3))
    }

    @Test
    fun `next on a single-set exercise is a no-op`() {
        assertEquals(0, SetNavigation.nextWithinExercise(currentSetIndex = 0, totalSets = 1))
    }

    // ── previousWithinExercise ────────────────────────────────────────────────

    @Test
    fun `previous retreats while there is a set before`() {
        assertEquals(1, SetNavigation.previousWithinExercise(currentSetIndex = 2))
        assertEquals(0, SetNavigation.previousWithinExercise(currentSetIndex = 1))
    }

    @Test
    fun `previous is clamped at the first set of the exercise`() {
        // Already on the first set — staying put, NOT hopping back to the previous exercise.
        assertEquals(0, SetNavigation.previousWithinExercise(currentSetIndex = 0))
    }

    // ── isFirst / isLast boundary flags ───────────────────────────────────────

    @Test
    fun `isFirst only on set index 0`() {
        assertTrue(SetNavigation.isFirst(0))
        assertFalse(SetNavigation.isFirst(1))
        assertFalse(SetNavigation.isFirst(5))
    }

    @Test
    fun `isLast only on the last set of the exercise`() {
        assertFalse(SetNavigation.isLast(currentSetIndex = 0, totalSets = 3))
        assertFalse(SetNavigation.isLast(currentSetIndex = 1, totalSets = 3))
        assertTrue(SetNavigation.isLast(currentSetIndex = 2, totalSets = 3))
    }

    @Test
    fun `isLast on a single-set exercise is always true`() {
        assertTrue(SetNavigation.isLast(currentSetIndex = 0, totalSets = 1))
    }

    @Test
    fun `isLast gracefully handles empty exercise`() {
        // Defensive: empty exercise should be treated as being on the last set
        // so Next arrow disables instead of attempting to advance.
        assertTrue(SetNavigation.isLast(currentSetIndex = 0, totalSets = 0))
    }

    // ── Regression: the old cross-exercise behaviour is gone ──────────────────

    @Test
    fun `next at end of exercise does NOT jump to next exercise index`() {
        // In the pre-fix code, goToNextSet would advance currentExerciseIndex++
        // when at the last set. Under the new contract, the set-index helper
        // simply refuses to advance — the caller (ViewModel) never hops.
        val beforeIdx = 4
        val totalSets = 5
        val afterIdx = SetNavigation.nextWithinExercise(beforeIdx, totalSets)
        assertEquals("must stay on last set of current exercise", beforeIdx, afterIdx)
    }

    @Test
    fun `previous at start of exercise does NOT jump back to previous exercise`() {
        val afterIdx = SetNavigation.previousWithinExercise(currentSetIndex = 0)
        assertEquals("must stay on first set of current exercise", 0, afterIdx)
    }
}
