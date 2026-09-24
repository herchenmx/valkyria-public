package com.example.hevywatch

import com.example.hevywatch.data.SubstitutionMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubstitutionMapTest {

    // One representative template id per group (15 groups).
    private val oneIdPerGroup = listOf(
        "D5D0354D", // Lateral Raise
        "93A552C6", // Triceps
        "FBB62888", // Rotation
        "937292AB", // Unilateral Glutes
        "D57C2EC7", // Thrust
        "D04AC939", // Squats
        "2B4B7310", // Glutes
        "9237BAD1", // Shoulder
        "7EB3F7C3", // Chest Press
        "78683336", // Chest Fly
        "B582299E", // Reverse Fly
        "6A6C31A5", // Lats
        "F1E57334", // Row
        "EB43ADD4", // Crunch
        "C7973E0E", // Leg Press
    )

    @Test
    fun `substitutesFor returns the other group members and excludes self`() {
        val subs = SubstitutionMap.substitutesFor("D5D0354D") // Lateral Raise (Machine)
        // Group is {Machine, Dumbbell, Cable, Upright Row Barbell, Upright Row Cable}
        assertEquals(4, subs.size)
        assertFalse("must not contain itself", subs.contains("D5D0354D"))
        assertTrue(subs.contains("422B08F1")) // Dumbbell
        assertTrue(subs.contains("BE289E45")) // Cable
    }

    @Test
    fun `substitutesFor is case insensitive`() {
        assertEquals(
            SubstitutionMap.substitutesFor("D5D0354D").toSet(),
            SubstitutionMap.substitutesFor("d5d0354d").toSet()
        )
    }

    @Test
    fun `substitutesFor is empty for an unmapped exercise`() {
        assertTrue(SubstitutionMap.substitutesFor("37FCC2BB").isEmpty()) // Bicep Curl — ungrouped
    }

    @Test
    fun `nameOf returns curated names case-insensitively`() {
        assertEquals("Lateral Raise (Machine)", SubstitutionMap.nameOf("D5D0354D"))
        assertEquals("Lateral Raise (Dumbbell)", SubstitutionMap.nameOf("422b08f1"))
        assertNull(SubstitutionMap.nameOf("37FCC2BB"))
    }

    @Test
    fun `every mapped exercise has a curated display name`() {
        // For each group, the representative + all its substitutes must resolve
        // to a name — guards against a future group edit forgetting NAMES.
        for (rep in oneIdPerGroup) {
            assertNotNull("rep $rep needs a name", SubstitutionMap.nameOf(rep))
            for (sub in SubstitutionMap.substitutesFor(rep)) {
                assertNotNull("substitute $sub needs a name", SubstitutionMap.nameOf(sub))
            }
        }
    }

    @Test
    fun `chest press group is flat across machine, barbell, dumbbell, smith and offers iso-lateral`() {
        // The Chest/Bench-Press group is intentionally flat: every horizontal/
        // incline/decline chest-press variant substitutes for every other.
        assertTrue(
            "Iso-Lateral Chest Press must be a Chest Press (Machine) alternative",
            SubstitutionMap.areInterchangeable("7EB3F7C3", "24706DCD")
        )
        // Spot-check a few cross-equipment pairs in the same flat group.
        assertTrue(SubstitutionMap.areInterchangeable("7EB3F7C3", "79D0BB3A")) // Machine ↔ Barbell
        assertTrue(SubstitutionMap.areInterchangeable("FBF92739", "0FBF7195")) // Incline Machine ↔ Smith Bench
        assertTrue(SubstitutionMap.areInterchangeable("24706DCD", "EAC7D9C5")) // Iso-Lateral ↔ Band
    }

    @Test
    fun `close-grip bench is excluded from the chest press group`() {
        // Close-Grip Bench is triceps-primary, deliberately left out.
        assertNull(SubstitutionMap.groupOf("35B51B87"))
        assertFalse(SubstitutionMap.areInterchangeable("7EB3F7C3", "35B51B87"))
    }

    @Test
    fun `substitutes are mutually consistent within a group`() {
        // Picking any member and asking for substitutes yields a set that, when
        // unioned with that member, equals the same full group for every member.
        for (rep in oneIdPerGroup) {
            val fullGroup = (SubstitutionMap.substitutesFor(rep) + rep).toSet()
            for (member in fullGroup) {
                val viaMember = (SubstitutionMap.substitutesFor(member) + member).toSet()
                assertEquals("group seen from $member must match group from $rep", fullGroup, viaMember)
            }
        }
    }
}
