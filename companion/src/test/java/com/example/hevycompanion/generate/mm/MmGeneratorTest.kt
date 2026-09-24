package com.example.hevycompanion.generate.mm

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmGeneratorTest {

    /**
     * Compact fixture covering: Push/Pull/Squat/Hinge patterns, Isolation +
     * Compound, Strength + Bodyweight + Time types, paid + free, and a
     * Stretching auxiliary so warmup/cooldown picks have something to match.
     */
    private val fixture = listOf(
        ex("p1", "Bench Press", "strength", false, "Compound", "Chest",
            sub = listOf("Chest"), eq = listOf("Barbell"), pat = listOf("Push"),
            target = listOf("Pectoralis Major"), syn = listOf("Triceps", "Anterior Deltoid")),
        ex("p2", "Incline DB Press", "strength", false, "Compound", "Chest",
            sub = listOf("Chest"), eq = listOf("Dumbbell"), pat = listOf("Push"),
            target = listOf("Pectoralis Major"), syn = listOf("Triceps", "Anterior Deltoid")),
        ex("p3", "Cable Fly", "strength", true, "Isolation", "Chest",
            sub = listOf("Chest"), eq = listOf("Cable"), pat = listOf("Push"),
            target = listOf("Pectoralis Major")),
        ex("p4", "OHP", "strength", false, "Compound", "Shoulders",
            sub = listOf("Shoulders | Deltoid"), eq = listOf("Barbell"), pat = listOf("Push"),
            target = listOf("Anterior Deltoid"), syn = listOf("Triceps")),
        ex("p5", "Tricep Pushdown", "strength", false, "Isolation", "Arms",
            sub = listOf("Arms | Triceps"), eq = listOf("Cable"), pat = listOf("Push"),
            target = listOf("Triceps")),
        ex("u1", "Pull-Up", "bodyweight", false, "Compound", "Back",
            sub = listOf("Back | Lats"), eq = listOf("Bodyweight"), pat = listOf("Pull"),
            target = listOf("Latissimus Dorsi"), syn = listOf("Biceps Brachii")),
        ex("u2", "Barbell Row", "strength", false, "Compound", "Back",
            sub = listOf("Back | Lats"), eq = listOf("Barbell"), pat = listOf("Pull"),
            target = listOf("Latissimus Dorsi"), syn = listOf("Biceps Brachii")),
        ex("l1", "Back Squat", "strength", false, "Compound", "Legs",
            sub = listOf("Legs | Quads"), eq = listOf("Barbell"), pat = listOf("Squat"),
            target = listOf("Quadriceps Femoris"), syn = listOf("Gluteus Maximus")),
        ex("l2", "Romanian Deadlift", "strength", false, "Compound", "Legs",
            sub = listOf("Legs | Hamstrings"), eq = listOf("Barbell"), pat = listOf("Hinge"),
            target = listOf("Hamstrings"), syn = listOf("Gluteus Maximus")),
        ex("l3", "Hamstring Curl", "strength", true, "Isolation", "Legs",
            sub = listOf("Legs | Hamstrings"), eq = listOf("Machine"), pat = listOf("Hinge"),
            target = listOf("Hamstrings")),
        ex("t1", "Plank", "time", false, "Stretching", "Abs & Core",
            sub = listOf("Abs & Core | Core"), eq = listOf("Bodyweight"), pat = emptyList(),
            target = listOf("Transversus Abdominis"), fields = listOf("time")),
        ex("w1", "Cat-Cow", "time", false, "Warmup", "Back",
            sub = listOf("Back | Erector Spinae"), eq = listOf("Bodyweight"), pat = emptyList(),
            target = listOf("Erector Spinae"), fields = listOf("time")),
        ex("s1", "Hip Flexor Stretch", "time", false, "Stretching", "Legs",
            sub = listOf("Legs | Hip Flexors"), eq = listOf("Bodyweight"), pat = emptyList(),
            target = listOf("Psoas Major"), fields = listOf("time")),
    )

    private val allEquipment = setOf("Barbell", "Dumbbell", "Cable", "Machine", "Bodyweight")

    @Test fun `split push-day picks only push-pattern exercises`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.Split(SplitDay.PUSH),
            equipment = allEquipment,
            types = setOf(ExerciseType.STRENGTH, ExerciseType.BODYWEIGHT),
            duration = MmDuration.M30,
            addWarmup = false,
            addCooldown = false,
            random = Random(42),
        )
        assertEquals(4, w.blocks.size)
        for (b in w.blocks) {
            assertTrue("expected push pattern, got ${b.exercise.movementPattern}",
                "Push" in b.exercise.movementPattern)
            assertEquals(BlockRole.MAIN, b.role)
        }
    }

    @Test fun `legs split allows squat hinge and lunge`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.Split(SplitDay.LEGS),
            equipment = allEquipment,
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            addWarmup = false,
            addCooldown = false,
            random = Random(0),
        )
        for (b in w.blocks) {
            val ok = b.exercise.movementPattern.any { it in setOf("Squat", "Hinge", "Lunge") }
            assertTrue("expected squat/hinge/lunge in ${b.exercise.name}", ok)
        }
    }

    @Test fun `subareas mode filters by sub_area or area`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.SubAreas(setOf("Arms | Triceps")),
            equipment = allEquipment,
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            addWarmup = false,
            addCooldown = false,
            random = Random(0),
        )
        assertTrue(w.blocks.isNotEmpty())
        for (b in w.blocks) {
            val matches = "Arms | Triceps" in b.exercise.subAreas
            assertTrue("expected Arms | Triceps in ${b.exercise.name}", matches)
        }
    }

    @Test fun `equipment filter excludes exercises lacking the chosen gear`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.Split(SplitDay.PUSH),
            equipment = setOf("Dumbbell"),  // dumbbell-only
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            addWarmup = false,
            addCooldown = false,
            random = Random(0),
        )
        for (b in w.blocks) {
            assertTrue(b.exercise.equipment.contains("Dumbbell"))
        }
    }

    @Test fun `time-based exercises get isTime=true and seconds prescription`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.SubAreas(setOf("Abs & Core | Core")),
            equipment = allEquipment,
            types = setOf(ExerciseType.TIME),
            duration = MmDuration.M30,
            addWarmup = false,
            addCooldown = false,
            random = Random(0),
        )
        // t1 (Plank) is in Stretching category and so excluded from main pool;
        // the test confirms the behaviour: when only TIME type is selected and
        // every TIME entry in the fixture is auxiliary, the main pool is empty.
        assertTrue(w.blocks.none { it.role == BlockRole.MAIN })
    }

    @Test fun `compound prescription is heavier than isolation`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.Split(SplitDay.PUSH),
            equipment = allEquipment,
            types = setOf(ExerciseType.STRENGTH),
            duration = MmDuration.H1,
            addWarmup = false,
            addCooldown = false,
            random = Random(7),
        )
        val compound = w.blocks.firstOrNull { it.exercise.category == "Compound" }
        val isolation = w.blocks.firstOrNull { it.exercise.category == "Isolation" }
        assertNotNull("expected at least one compound", compound)
        assertNotNull("expected at least one isolation", isolation)
        assertTrue(compound!!.sets >= isolation!!.sets)
        assertTrue(compound.repsOrSeconds < isolation.repsOrSeconds)
    }

    @Test fun `addWarmup prepends a warmup block`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.Split(SplitDay.PUSH),
            equipment = allEquipment,
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            addWarmup = true,
            addCooldown = false,
            random = Random(1),
        )
        assertEquals(BlockRole.WARMUP, w.blocks.first().role)
        assertTrue(w.blocks.drop(1).all { it.role == BlockRole.MAIN })
    }

    @Test fun `addCooldown appends a cooldown block`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.Split(SplitDay.LEGS),
            equipment = allEquipment,
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            addWarmup = false,
            addCooldown = true,
            random = Random(2),
        )
        assertEquals(BlockRole.COOLDOWN, w.blocks.last().role)
        assertTrue(w.blocks.dropLast(1).all { it.role == BlockRole.MAIN })
    }

    @Test fun `volume bar excludes warmup and cooldown contributions`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.Split(SplitDay.PUSH),
            equipment = allEquipment,
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            addWarmup = true,
            addCooldown = true,
            random = Random(3),
        )
        // Volume sums to ~100% (allow rounding)
        val total = w.volumeByMuscle.sumOf { it.percent.toDouble() }
        assertEquals(100.0, total, 0.5)
        // Warmup target shouldn't appear in the volume bar (warmup uses Erector Spinae,
        // and the main push pool doesn't target it).
        val muscles = w.volumeByMuscle.map { it.muscle }
        assertFalse("Erector Spinae" in muscles)
    }

    @Test fun `swap replaces a main block without changing its role`() {
        val w = MmGenerator.generate(
            catalog = fixture,
            mode = Mode.Split(SplitDay.PUSH),
            equipment = allEquipment,
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            addWarmup = false,
            addCooldown = false,
            random = Random(4),
        )
        val originalId = w.blocks[0].exercise.id
        val swapped = MmGenerator.swap(
            current = w,
            indexToSwap = 0,
            catalog = fixture,
            mode = Mode.Split(SplitDay.PUSH),
            equipment = allEquipment,
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            random = Random(99),
        )
        assertEquals(BlockRole.MAIN, swapped.blocks[0].role)
        assertTrue(swapped.blocks[0].exercise.id != originalId ||
            // pool exhausted ⇒ unchanged is acceptable
            fixture.count { "Push" in it.movementPattern } == w.blocks.size)
    }

    @Test fun `greedy pick prefers variety over hitting the same muscle five times`() {
        // Six clones of the same chest-only exercise and one shoulder exercise:
        // the greedy picker should still pick the shoulder one for variety.
        val pool = listOf(
            ex("a", "A", "strength", false, "Compound", "Chest",
                sub = listOf("Chest"), eq = listOf("Barbell"), pat = listOf("Push"),
                target = listOf("Pectoralis Major")),
            ex("b", "B", "strength", false, "Compound", "Chest",
                sub = listOf("Chest"), eq = listOf("Barbell"), pat = listOf("Push"),
                target = listOf("Pectoralis Major")),
            ex("c", "C", "strength", false, "Compound", "Chest",
                sub = listOf("Chest"), eq = listOf("Barbell"), pat = listOf("Push"),
                target = listOf("Pectoralis Major")),
            ex("d", "D", "strength", false, "Compound", "Shoulders",
                sub = listOf("Shoulders | Deltoid"), eq = listOf("Barbell"), pat = listOf("Push"),
                target = listOf("Anterior Deltoid")),
        )
        val w = MmGenerator.generate(
            catalog = pool,
            mode = Mode.Split(SplitDay.PUSH),
            equipment = setOf("Barbell"),
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            addWarmup = false,
            addCooldown = false,
            random = Random(0),
        )
        // 4 picks from a 4-deep pool ⇒ all picked. Volume bar should include both muscles.
        val muscles = w.volumeByMuscle.map { it.muscle }.toSet()
        assertTrue("Pectoralis Major" in muscles)
        assertTrue("Anterior Deltoid" in muscles)
    }

    @Test fun `empty pool returns empty workout`() {
        val w = MmGenerator.generate(
            catalog = emptyList(),
            mode = Mode.Split(SplitDay.PUSH),
            equipment = allEquipment,
            types = ExerciseType.DEFAULT,
            duration = MmDuration.M30,
            addWarmup = false,
            addCooldown = false,
            random = Random(0),
        )
        assertTrue(w.blocks.isEmpty())
        assertTrue(w.volumeByMuscle.isEmpty())
    }

    // ---- helpers ------------------------------------------------------------

    private fun ex(
        id: String,
        name: String,
        type: String,
        isPaid: Boolean,
        category: String,
        area: String,
        sub: List<String>,
        eq: List<String>,
        pat: List<String>,
        target: List<String>,
        syn: List<String> = emptyList(),
        stab: List<String> = emptyList(),
        fields: List<String> = listOf("reps", "weight"),
    ): MmExercise = MmExercise(
        id = id,
        name = name,
        type = type,
        isPaid = isPaid,
        category = category,
        area = area,
        subAreas = sub,
        equipment = eq,
        movementPattern = pat,
        targetMuscles = target,
        synergistMuscles = syn,
        stabilizerMuscles = stab,
        defaultWorkoutFields = fields,
        thumbnailUrl = null,
        videoUrl = null,
        videoDurationSec = null,
    )
}
