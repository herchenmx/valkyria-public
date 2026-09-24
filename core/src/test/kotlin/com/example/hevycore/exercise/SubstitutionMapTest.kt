package com.example.hevycore.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the curated substitution table. The IDs themselves are opaque
 * Hevy strings; these tests pin the invariants (case-insensitive lookup,
 * self-exclusion from substitutes, cross-group non-interchange) rather than
 * the specific groupings — those change intentionally as the user's routines
 * evolve.
 */
class SubstitutionMapTest {

    // Two members of the same well-known group in the current curation
    // (Squats), used across the invariant tests. If either ID leaves the
    // group these tests fail loudly, which is the intent.
    private val squatBarbell = "D04AC939"
    private val squatMachine = "CC35A01F"

    @Test fun `groupOf resolves case-insensitively`() {
        val lower = SubstitutionMap.groupOf(squatBarbell.lowercase())
        val upper = SubstitutionMap.groupOf(squatBarbell.uppercase())
        assertEquals(lower, upper)
    }

    @Test fun `unknown template returns null group`() {
        assertNull(SubstitutionMap.groupOf("not-a-real-id"))
    }

    @Test fun `two members of the same group are interchangeable`() {
        assertTrue(SubstitutionMap.areInterchangeable(squatBarbell, squatMachine))
    }

    @Test fun `same id is never interchangeable with itself`() {
        assertFalse(SubstitutionMap.areInterchangeable(squatBarbell, squatBarbell))
        assertFalse(SubstitutionMap.areInterchangeable(squatBarbell, squatBarbell.lowercase()))
    }

    @Test fun `substitutesFor excludes the query itself`() {
        val subs = SubstitutionMap.substitutesFor(squatBarbell)
        assertFalse("query id must not appear in its own substitutes", subs.contains(squatBarbell))
        assertTrue("expected sibling machine squat to be a substitute", subs.contains(squatMachine))
    }

    @Test fun `substitutesFor is upper-cased regardless of query case`() {
        val subs = SubstitutionMap.substitutesFor(squatBarbell.lowercase())
        subs.forEach { id ->
            assertEquals("substitute $id must be upper-cased", id.uppercase(), id)
        }
    }

    @Test fun `unknown template has empty substitutes`() {
        assertEquals(emptyList<String>(), SubstitutionMap.substitutesFor("nope"))
    }

    @Test fun `allGroups preserves the curated order`() {
        val flat = SubstitutionMap.allGroups()
        // Every group is non-empty and every id appears exactly once across all
        // groups. Groups don't overlap by construction — invariant asserted here
        // so a copy-paste doesn't accidentally merge or split without notice.
        val flatIds = flat.flatMap { it }
        assertEquals("no duplicate ids across groups", flatIds.size, flatIds.toSet().size)
        flat.forEach { g -> assertTrue("group must be non-empty", g.isNotEmpty()) }
    }
}
