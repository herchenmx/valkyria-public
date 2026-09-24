package com.example.hevycompanion.alternatives

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.recents.SubstitutionMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AltGroupsTest {

    private fun template(id: String, title: String, muscle: String? = null, equipment: String? = null) =
        ExerciseTemplate(id = id, title = title, primaryMuscleGroup = muscle, equipment = equipment)

    // ---- SubstitutionMap.allGroups() shape --------------------------------

    @Test fun `every group has at least two upper-cased members`() {
        SubstitutionMap.allGroups().forEach { group ->
            assertTrue("group too small: $group", group.size >= 2)
            group.forEach { id -> assertEquals(id, id.uppercase()) }
        }
    }

    @Test fun `groups are disjoint - no template in two groups`() {
        val seen = mutableSetOf<String>()
        SubstitutionMap.allGroups().forEach { group ->
            group.forEach { id ->
                assertTrue("duplicate across groups: $id", seen.add(id))
            }
        }
    }

    // ---- AltGroups.build() catalog join -----------------------------------

    @Test fun `build resolves members present in the catalog and drops the rest`() {
        // Chest Fly group: Machine / Pec Deck / Dumbbell. Provide two of three.
        val meta = mapOf(
            "78683336" to template("78683336", "Chest Fly (Machine)", muscle = "chest", equipment = "machine"),
            "9DCE2D64" to template("9DCE2D64", "Chest Fly (Pec Deck)", muscle = "chest", equipment = "machine"),
        )
        val groups = AltGroups.build(meta)
        // Only the chest-fly group has ≥2 resolvable members; all others resolve none.
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals(listOf("Chest Fly (Machine)", "Chest Fly (Pec Deck)"), g.exercises.map { it.title })
        assertEquals("Chest", g.heading)
    }

    @Test fun `build drops a group left with fewer than two catalog members`() {
        val meta = mapOf(
            "78683336" to template("78683336", "Chest Fly (Machine)", muscle = "chest"),
        )
        assertTrue(AltGroups.build(meta).isEmpty())
    }

    @Test fun `build is case-insensitive on catalog ids`() {
        val meta = mapOf(
            "78683336" to template("78683336", "A", muscle = "chest"),
            "9dce2d64" to template("9dce2d64", "B", muscle = "chest"), // lower-cased catalog id
        )
        // VM upper-cases keys before calling build, so simulate that here.
        val upper = meta.mapKeys { it.key.uppercase() }
        assertEquals(1, AltGroups.build(upper).size)
    }

    // ---- headingFor() -----------------------------------------------------

    @Test fun `headingFor picks the most common muscle group`() {
        val members = listOf(
            AltExercise("1", "a", primaryMuscleGroup = "shoulders", equipment = null),
            AltExercise("2", "b", primaryMuscleGroup = "shoulders", equipment = null),
            AltExercise("3", "c", primaryMuscleGroup = "upper_back", equipment = null),
        )
        assertEquals("Shoulders", AltGroups.headingFor(members))
    }

    @Test fun `headingFor falls back to Alternatives when no member carries a muscle`() {
        val members = listOf(
            AltExercise("1", "a", primaryMuscleGroup = null, equipment = null),
            AltExercise("2", "b", primaryMuscleGroup = "", equipment = null),
        )
        assertEquals("Alternatives", AltGroups.headingFor(members))
    }
}
