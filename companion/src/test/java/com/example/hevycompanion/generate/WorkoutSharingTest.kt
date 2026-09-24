package com.example.hevycompanion.generate

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.muscle.HevyMuscleGroup
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkoutSharingTest {

    private val originalLocale = Locale.getDefault()

    @Before fun before() {
        Locale.setDefault(Locale.US)
    }

    @After fun after() {
        Locale.setDefault(originalLocale)
    }

    // ---- end-to-end format ---------------------------------------------

    @Test fun `full text matches the specified format exactly`() {
        val text = formatWorkoutForSharing(
            workout = workout(
                ex("Bench Press (Barbell)", sets = 3, reps = 8, kg = 40f, equipment = "barbell"),
                ex("Incline Dumbbell Press", sets = 3, reps = 8, kg = 15f, equipment = "dumbbell"),
                ex("Push-Up", sets = 3, reps = 8, kg = 0f, equipment = "none"),
            ),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST, HevyMuscleGroup.SHOULDERS, HevyMuscleGroup.TRICEPS),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell", "dumbbell", "none"),
        )
        // Exact, character-for-character match against the shape Maria agreed to.
        // If the format ever needs to change, update this literal first — it IS
        // the spec.
        val expected = """
            I've put together the following workout for you:

            * Bench Press (Barbell) - 3 sets - 8 reps per set - 40 kg (20kg bar, plus 10kg per side)
            * Incline Dumbbell Press - 3 sets - 8 reps per set - 15 kg (7.5kg per side)
            * Push-Up - 3 sets - 8 reps per set - bodyweight

            This is based on the following filters:

            * target muscles: Chest, Shoulders, Triceps
            * desired duration: 1h
            * weights: Heavy
            * level: Advanced, Beginner, Intermediate
            * category: Compound, Isolation
            * available equipment: Barbell, Bodyweight, Dumbbell

            Let me know if you want me to change anything
        """.trimIndent()
        assertEquals(expected, text)
    }

    // ---- per-side breakdown (dumbbell / barbell) -----------------------

    @Test fun `dumbbell exercises get a halved 'per side' breakdown in brackets`() {
        val text = formatWorkoutForSharing(
            workout = workout(ex("Shrug", sets = 3, reps = 8, kg = 15f, equipment = "dumbbell")),
            selectedMuscles = setOf(HevyMuscleGroup.TRAPS),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("dumbbell"),
        )
        assertTrue("* Shrug - 3 sets - 8 reps per set - 15 kg (7.5kg per side)" in text)
    }

    @Test fun `barbell exercises show the bar weight plus plates per side`() {
        val text = formatWorkoutForSharing(
            workout = workout(ex("Floor Press", sets = 3, reps = 8, kg = 40f, equipment = "barbell")),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell"),
        )
        assertTrue("* Floor Press - 3 sets - 8 reps per set - 40 kg (20kg bar, plus 10kg per side)" in text)
    }

    @Test fun `barbell at bar weight renders zero plates per side rather than hiding the breakdown`() {
        // 20 kg total = just the bar. Keeping the "plus 0kg per side" wording
        // is less surprising than silently dropping the parenthetical: the
        // user still sees "this is a barbell exercise, bar weighs 20 kg".
        val text = formatWorkoutForSharing(
            workout = workout(ex("Beginner Bench", sets = 3, reps = 8, kg = 20f, equipment = "barbell")),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H1,
            weights = Weights.LIGHT,
            selectedLevels = setOf(Level.BEGINNER),
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell"),
        )
        assertTrue("* Beginner Bench - 3 sets - 8 reps per set - 20 kg (20kg bar, plus 0kg per side)" in text)
    }

    @Test fun `non-barbell non-dumbbell equipment does not get a breakdown`() {
        val text = formatWorkoutForSharing(
            workout = workout(
                ex("Cable Row", sets = 3, reps = 8, kg = 25f, equipment = "cable"),
                ex("Leg Press", sets = 3, reps = 8, kg = 30f, equipment = "machine"),
            ),
            selectedMuscles = setOf(HevyMuscleGroup.LATS),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("cable", "machine"),
        )
        // Plain weight only — no "per side" for cable/machine.
        assertTrue("* Cable Row - 3 sets - 8 reps per set - 25 kg" in text)
        assertTrue("* Leg Press - 3 sets - 8 reps per set - 30 kg" in text)
        assertTrue("per side" !in text)
        assertTrue("bar," !in text)
    }

    // ---- filter-summary rendering --------------------------------------

    @Test fun `muscles are alphabetical so reshares produce identical text`() {
        val muscles = setOf(HevyMuscleGroup.TRICEPS, HevyMuscleGroup.CHEST, HevyMuscleGroup.SHOULDERS)
        val text = formatWorkoutForSharing(
            workout = workout(ex("X", 3, 8, 40f)),
            selectedMuscles = muscles,
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell"),
        )
        assertTrue("Chest, Shoulders, Triceps" in text)
    }

    @Test fun `equipment list uses human display labels not raw Hevy tags`() {
        val text = formatWorkoutForSharing(
            workout = workout(ex("X", 3, 8, 40f)),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("ez_bar", "resistance_band", "none"),
        )
        val equipmentLine = text.lineSequence().first { it.startsWith("* available equipment:") }
        assertEquals(
            "* available equipment: Bodyweight, EZ Bar, Resistance Band",
            equipmentLine,
        )
    }

    @Test fun `duration renders as its friendly label, not the enum name`() {
        val text = formatWorkoutForSharing(
            workout = workout(ex("X", 3, 8, 40f)),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H130,        // enum name; Label is "1h 30m"
            weights = Weights.LIGHT,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell"),
        )
        assertTrue("1h 30m" in text)
        assertTrue("H130" !in text)
        assertTrue("Light" in text)
        assertTrue("LIGHT" !in text)
    }

    // ---- level / category lines ----------------------------------------

    @Test fun `level line shows only selected levels, alphabetical`() {
        val text = formatWorkoutForSharing(
            workout = workout(ex("X", 3, 8, 40f)),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = setOf(Level.INTERMEDIATE, Level.BEGINNER),
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell"),
        )
        val levelLine = text.lineSequence().first { it.startsWith("* level:") }
        assertEquals("* level: Beginner, Intermediate", levelLine)
    }

    @Test fun `category line shows only selected categories`() {
        val text = formatWorkoutForSharing(
            workout = workout(ex("X", 3, 8, 40f)),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = setOf(Category.COMPOUND),
            selectedEquipment = setOf("barbell"),
        )
        val categoryLine = text.lineSequence().first { it.startsWith("* category:") }
        assertEquals("* category: Compound", categoryLine)
    }

    @Test fun `empty level set renders (none) rather than blank`() {
        // Shouldn't happen via the UI (the VM defaults to all three and
        // nothing prevents the user from deselecting to zero — but the
        // filter treats empty as "all" at generation time). The share-text
        // still prints (none) so the recipient can tell what was actually
        // on the chip.
        val text = formatWorkoutForSharing(
            workout = workout(ex("X", 3, 8, 40f)),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = emptySet(),
            selectedCategories = emptySet(),
            selectedEquipment = setOf("barbell"),
        )
        assertTrue("* level: (none)" in text)
        assertTrue("* category: (none)" in text)
    }

    @Test fun `weights line renders the friendly display name (Light Medium Heavy)`() {
        for (w in Weights.entries) {
            val text = formatWorkoutForSharing(
                workout = workout(ex("X", 3, 8, 40f)),
                selectedMuscles = setOf(HevyMuscleGroup.CHEST),
                duration = Duration.H1,
                weights = w,
                selectedLevels = Level.DEFAULT,
                selectedCategories = Category.DEFAULT,
                selectedEquipment = setOf("barbell"),
            )
            val weightsLine = text.lineSequence().first { it.startsWith("* weights:") }
            assertEquals("* weights: ${w.displayName}", weightsLine)
        }
    }

    // ---- per-exercise line formatting ----------------------------------

    @Test fun `bodyweight exercises render 'bodyweight' rather than '0 kg'`() {
        val text = formatWorkoutForSharing(
            workout = workout(ex("Push-Up", sets = 3, reps = 8, kg = 0f)),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("none"),
        )
        assertTrue("bodyweight" in text)
        assertTrue("0 kg" !in text)
    }

    @Test fun `decimal weight renders with a dot even on decimal-comma locales`() {
        // Guard against "36,7 kg" leaking into shared text in DE locale.
        Locale.setDefault(Locale.GERMANY)
        val text = formatWorkoutForSharing(
            workout = workout(ex("Bench Press (Barbell)", sets = 3, reps = 8, kg = 36.7f)),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell"),
        )
        assertTrue("36.7 kg" in text)
        assertTrue("36,7" !in text)
    }

    @Test fun `whole-number weight omits the decimal`() {
        val text = formatWorkoutForSharing(
            workout = workout(ex("Squat", sets = 3, reps = 8, kg = 40f)),
            selectedMuscles = setOf(HevyMuscleGroup.QUADRICEPS),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell"),
        )
        assertTrue("40 kg" in text)
    }

    // ---- degenerate inputs ---------------------------------------------

    @Test fun `empty workout yields empty string (no hollow header+filters blob)`() {
        val text = formatWorkoutForSharing(
            workout = GeneratedWorkout(emptyList(), emptyList()),
            selectedMuscles = setOf(HevyMuscleGroup.CHEST),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell"),
        )
        assertEquals("", text)
    }

    @Test fun `empty muscle set shows (none) rather than an empty line`() {
        val text = formatWorkoutForSharing(
            workout = workout(ex("X", 3, 8, 40f)),
            selectedMuscles = emptySet(),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            selectedEquipment = setOf("barbell"),
        )
        assertTrue("* target muscles: (none)" in text)
    }

    // ---- fixture helpers ------------------------------------------------

    private fun ex(
        title: String,
        sets: Int,
        reps: Int,
        kg: Float,
        equipment: String? = null,
    ): GeneratedExercise =
        GeneratedExercise(
            template = ExerciseTemplate(
                id = title.hashCode().toString(),
                title = title,
                equipment = equipment,
            ),
            sets = sets, reps = reps, weightKg = kg,
        )

    private fun workout(vararg exercises: GeneratedExercise): GeneratedWorkout =
        GeneratedWorkout(exercises = exercises.toList(), muscleSplit = emptyList())
}
