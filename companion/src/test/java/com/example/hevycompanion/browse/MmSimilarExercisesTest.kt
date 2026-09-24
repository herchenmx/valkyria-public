package com.example.hevycompanion.browse

import com.example.hevycompanion.generate.mm.MmExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmSimilarExercisesTest {

    private val backSquat = ex(
        id = "BSQ", name = "Back Squat", area = "Legs", category = "Compound",
        equipment = listOf("Barbell"), movementPattern = listOf("Squat"),
        targets = listOf("Quadriceps", "Gluteus Maximus"),
        synergists = listOf("Hamstrings"),
    )
    private val frontSquat = ex(
        id = "FSQ", name = "Front Squat", area = "Legs", category = "Compound",
        equipment = listOf("Barbell"), movementPattern = listOf("Squat"),
        targets = listOf("Quadriceps", "Gluteus Maximus"),
        synergists = listOf("Hamstrings"),
    )
    private val gobletSquat = ex(
        id = "GSQ", name = "Goblet Squat", area = "Legs", category = "Compound",
        equipment = listOf("Dumbbell"), movementPattern = listOf("Squat"),
        targets = listOf("Quadriceps", "Gluteus Maximus"),
        synergists = emptyList(),
    )
    private val deadlift = ex(
        id = "DL", name = "Deadlift", area = "Legs", category = "Compound",
        equipment = listOf("Barbell"), movementPattern = listOf("Hinge"),
        targets = listOf("Hamstrings", "Gluteus Maximus"),
        synergists = listOf("Erector Spinae"),
    )
    private val bench = ex(
        id = "BP", name = "Bench Press", area = "Chest", category = "Compound",
        equipment = listOf("Barbell"), movementPattern = listOf("Push"),
        targets = listOf("Pectoralis Major"),
        synergists = listOf("Triceps Brachii"),
    )
    private val plank = ex(
        id = "PL", name = "Plank", area = "Abs", category = "Isolation",
        equipment = listOf("Bodyweight"), movementPattern = emptyList(),
        targets = listOf("Rectus Abdominis"),
        synergists = emptyList(),
    )
    private val plankSideStep = ex(
        id = "PLS", name = "Side-Step Plank", area = "Abs", category = "Isolation",
        equipment = listOf("Bodyweight"), movementPattern = emptyList(),
        targets = listOf("Rectus Abdominis"),
        synergists = listOf("Obliques"),
    )

    private val catalog = listOf(
        backSquat, frontSquat, gobletSquat, deadlift, bench, plank, plankSideStep,
    )

    @Test fun `cross-area rows are excluded by the hard area filter`() {
        // Bench is the only Chest row — must never appear when target is Legs.
        val out = MmSimilarExercises.find(backSquat, catalog)
        assertTrue(bench !in out)
    }

    @Test fun `self is excluded`() {
        assertTrue(backSquat !in MmSimilarExercises.find(backSquat, catalog))
    }

    @Test fun `targetMuscle overlap dominates the ranking`() {
        // Front Squat: shares both targets, both equipment, movement, category, syn.
        // Goblet Squat: shares both targets, no equipment, movement, category.
        // Deadlift: shares only one target, shares equipment, no movement match.
        // Front Squat must rank above Goblet, which must rank above Deadlift.
        val out = MmSimilarExercises.find(backSquat, catalog)
        val frontIdx = out.indexOf(frontSquat)
        val gobletIdx = out.indexOf(gobletSquat)
        val deadliftIdx = out.indexOf(deadlift)
        assertTrue("Front Squat should be in similar list", frontIdx >= 0)
        assertTrue("Goblet Squat should be in similar list", gobletIdx >= 0)
        assertTrue("Deadlift should be in similar list", deadliftIdx >= 0)
        assertTrue(frontIdx < gobletIdx)
        assertTrue(gobletIdx < deadliftIdx)
    }

    @Test fun `equipment is a soft signal not a hard filter`() {
        // Goblet Squat (Dumbbell) is included even though backSquat is Barbell —
        // the user should still see it as a sibling.
        assertTrue(gobletSquat in MmSimilarExercises.find(backSquat, catalog))
    }

    @Test fun `movement pattern bonus skipped when one side is empty`() {
        // Plank has no movementPattern; so does Side-Step Plank. They should
        // still surface as siblings via shared target muscle alone.
        val out = MmSimilarExercises.find(plank, catalog)
        assertEquals(listOf(plankSideStep), out)
    }

    @Test fun `score floor drops rows that only share area`() {
        val lonely = ex(
            id = "X", name = "Mystery Stretch", area = "Legs", category = "Stretching",
            equipment = listOf("Bodyweight"), movementPattern = emptyList(),
            targets = listOf("Some Other Muscle"),
            synergists = emptyList(),
        )
        // No target / equipment / movement / category overlap with backSquat.
        // Score = 0, below floor → must not appear.
        val out = MmSimilarExercises.find(backSquat, catalog + lonely)
        assertTrue(lonely !in out)
    }

    @Test fun `blank target area yields empty list`() {
        val noArea = backSquat.copy(area = "")
        assertTrue(MmSimilarExercises.find(noArea, catalog).isEmpty())
    }

    @Test fun `result respects the cap argument`() {
        val packed = (1..15).map {
            ex(
                id = "C$it", name = "Clone $it", area = "Legs", category = "Compound",
                equipment = listOf("Barbell"), movementPattern = listOf("Squat"),
                targets = listOf("Quadriceps", "Gluteus Maximus"),
                synergists = listOf("Hamstrings"),
            )
        }
        val out = MmSimilarExercises.find(backSquat, catalog + packed, cap = 5)
        assertEquals(5, out.size)
    }

    @Test fun `tiebreak is alphabetical by name`() {
        // Two exact-twin clones with the same score → expected order zebra after anvil.
        val anvil = ex(
            id = "A", name = "Anvil Squat", area = "Legs", category = "Compound",
            equipment = listOf("Barbell"), movementPattern = listOf("Squat"),
            targets = listOf("Quadriceps", "Gluteus Maximus"),
            synergists = listOf("Hamstrings"),
        )
        val zebra = ex(
            id = "Z", name = "Zebra Squat", area = "Legs", category = "Compound",
            equipment = listOf("Barbell"), movementPattern = listOf("Squat"),
            targets = listOf("Quadriceps", "Gluteus Maximus"),
            synergists = listOf("Hamstrings"),
        )
        val out = MmSimilarExercises.find(backSquat, listOf(backSquat, zebra, anvil))
        assertEquals(listOf(anvil, zebra), out)
    }

    private fun ex(
        id: String,
        name: String,
        area: String,
        category: String,
        equipment: List<String>,
        movementPattern: List<String>,
        targets: List<String>,
        synergists: List<String>,
    ) = MmExercise(
        id = id,
        name = name,
        type = "strength",
        isPaid = false,
        category = category,
        area = area,
        subAreas = emptyList(),
        equipment = equipment,
        movementPattern = movementPattern,
        targetMuscles = targets,
        synergistMuscles = synergists,
        stabilizerMuscles = emptyList(),
        defaultWorkoutFields = emptyList(),
        thumbnailUrl = null,
        videoUrl = null,
        videoDurationSec = null,
    )
}
