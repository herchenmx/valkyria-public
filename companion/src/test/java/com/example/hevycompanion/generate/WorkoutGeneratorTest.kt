package com.example.hevycompanion.generate

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.HevyExerciseAttrs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutGeneratorTest {

    /**
     * Mini fixture catalog that covers the corners we test against:
     * - chest exercises with various equipment
     * - bodyweight exercises (equipment = "none")
     * - exercises with secondary muscles (for %-split)
     * - a resistance-band exercise
     */
    private val fixture: List<ExerciseTemplate> = listOf(
        tpl("c1", "Bench Press", "chest", listOf("triceps", "shoulders"), "barbell"),
        tpl("c2", "Incline Dumbbell Press", "chest", listOf("triceps", "shoulders"), "dumbbell"),
        tpl("c3", "Cable Fly", "chest", listOf("shoulders"), "cable"),
        tpl("c4", "Push-Up", "chest", listOf("triceps"), "none"),
        tpl("c5", "Machine Chest Press", "chest", listOf("triceps"), "machine"),
        tpl("b1", "Barbell Curl", "biceps", emptyList(), "barbell"),
        tpl("b2", "Dumbbell Curl", "biceps", emptyList(), "dumbbell"),
        tpl("b3", "Band Curl", "biceps", emptyList(), "resistance_band"),
        tpl("l1", "Squat", "quadriceps", listOf("glutes"), "barbell"),
    )

    /**
     * Matching attrs side table. Covers every level / category combo we
     * exercise in tests:
     *  - c1 (bench) : beginner+intermediate+advanced, compound
     *  - c2 (incline): intermediate+advanced, assistance-compound
     *  - c3 (fly):   beginner+intermediate, isolation
     *  - c4 (push-up): beginner, compound
     *  - c5 (machine chest press): advanced-only, compound
     *  - b1 (bb curl): intermediate+advanced, isolation
     *  - b2 (db curl): beginner+intermediate+advanced, isolation
     *  - b3 (band curl): beginner, isolation
     *  - l1 (squat):  beginner+intermediate+advanced, compound
     */
    private val attrs: Map<String, HevyExerciseAttrs> = mapOf(
        "c1" to HevyExerciseAttrs(listOf("beginner", "intermediate", "advanced"), emptyList(), "compound"),
        "c2" to HevyExerciseAttrs(listOf("intermediate", "advanced"), emptyList(), "assistance-compound"),
        "c3" to HevyExerciseAttrs(listOf("beginner", "intermediate"), emptyList(), "isolation"),
        "c4" to HevyExerciseAttrs(listOf("beginner"), emptyList(), "compound"),
        "c5" to HevyExerciseAttrs(listOf("advanced"), emptyList(), "compound"),
        "b1" to HevyExerciseAttrs(listOf("intermediate", "advanced"), emptyList(), "isolation"),
        "b2" to HevyExerciseAttrs(listOf("beginner", "intermediate", "advanced"), emptyList(), "isolation"),
        "b3" to HevyExerciseAttrs(listOf("beginner"), emptyList(), "isolation"),
        "l1" to HevyExerciseAttrs(listOf("beginner", "intermediate", "advanced"), emptyList(), "compound"),
    )

    @Test fun `generate respects duration's exercise count`() {
        for (d in Duration.entries) {
            val w = WorkoutGenerator.generate(
                templates = fixture,
                attrsById = attrs,
                selectedHevyMuscles = setOf("chest", "biceps", "quadriceps"),
                selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
                selectedLevels = Level.DEFAULT,
                selectedCategories = Category.DEFAULT,
                duration = d,
                weights = Weights.HEAVY,
                random = Random(0),
            )
            // Pool only has 9 exercises; for 1h30m (8) and 1h15m (6) we cap at pool size.
            val expected = minOf(d.exerciseCount, fixture.size)
            assertEquals("count mismatch for ${d.label}", expected, w.exercises.size)
            // Sets/reps come from the duration table — every row must match.
            for (ex in w.exercises) {
                assertEquals(d.setsPerExercise, ex.sets)
                assertEquals(d.repsPerSet, ex.reps)
            }
        }
    }

    @Test fun `generate filters by selected muscle (primary only)`() {
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("biceps"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            duration = Duration.H1,
            weights = Weights.HEAVY,
            random = Random(1),
        )
        // Even though Bench Press has biceps as a secondary, it must NOT be picked —
        // only primary muscle counts for the filter.
        assertTrue(w.exercises.all { it.template.primaryMuscleGroup == "biceps" })
    }

    @Test fun `strict equipment filter excludes bodyweight when un-ticked`() {
        // Regression for the reverse bug of the earlier soft filter: the user
        // reported that un-ticking "Bodyweight" in the picker still surfaced
        // bird-dog / lateral-leg-raises / push-ups because the old logic
        // force-included the "none" and "other" tags. Behaviour is now strict:
        // whatever the user ticked is exactly what comes through.
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest"),
            selectedEquipment = setOf("barbell"),  // explicitly NO bodyweight
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            duration = Duration.H1,
            weights = Weights.HEAVY,
            random = Random(2),
        )
        // Push-Up (equipment = "none") must NOT be in the result.
        assertTrue(
            "strict filter let a bodyweight exercise through despite un-tick",
            w.exercises.none { it.template.id == "c4" },
        )
        // And all returned exercises must match the ticked tag.
        assertTrue(w.exercises.all { it.template.equipment == "barbell" })
    }

    @Test fun `empty equipment selection yields zero exercises`() {
        // If the user somehow ends up with nothing ticked, the generator
        // must not backfill with soft-filter defaults (the old behaviour).
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest"),
            selectedEquipment = emptySet(),
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            duration = Duration.H1,
            weights = Weights.HEAVY,
            random = Random(2),
        )
        assertTrue(w.exercises.isEmpty())
    }

    @Test fun `level filter narrows pool to exercises that contain a selected level`() {
        // Beginner-only selection: only exercises whose level list contains
        // "beginner" should survive. From the fixture that's c1, c3, c4, b2,
        // b3, l1 — c2, c5, b1 are excluded.
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest", "biceps", "quadriceps"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = setOf(Level.BEGINNER),
            selectedCategories = Category.DEFAULT,
            duration = Duration.H130,  // try to pick 8 — we only have 6 eligible
            weights = Weights.HEAVY,
            random = Random(42),
        )
        val allowed = setOf("c1", "c3", "c4", "b2", "b3", "l1")
        assertTrue(
            "level=beginner let through an id outside the allowlist: ${w.exercises.map { it.template.id }}",
            w.exercises.all { it.template.id in allowed },
        )
    }

    @Test fun `level filter is a union across selected levels`() {
        // Beginner ∪ Advanced: any exercise whose level list contains either
        // value passes. From the fixture that's ALL 9 entries (c3 has beginner,
        // b1 has advanced, every other row has both or one).
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest", "biceps", "quadriceps"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = setOf(Level.BEGINNER, Level.ADVANCED),
            selectedCategories = Category.DEFAULT,
            duration = Duration.H130,
            weights = Weights.HEAVY,
            random = Random(1),
        )
        // Every fixture row is reachable; with N=8 and pool=9, pool size is the cap.
        assertEquals(8, w.exercises.size)
    }

    @Test fun `category filter folds assistance-compound into compound`() {
        // Compound-only: c1 (compound), c2 (assistance-compound), c4 (compound),
        // c5 (compound), l1 (compound) — c3/b1/b2/b3 are isolation and excluded.
        // Specifically asserts that c2 (assistance-compound) is NOT excluded.
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest", "biceps", "quadriceps"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = Level.DEFAULT,
            selectedCategories = setOf(Category.COMPOUND),
            duration = Duration.H130,
            weights = Weights.HEAVY,
            random = Random(7),
        )
        val allowed = setOf("c1", "c2", "c4", "c5", "l1")
        assertTrue(
            "compound filter excluded something valid: ${w.exercises.map { it.template.id }}",
            w.exercises.all { it.template.id in allowed },
        )
        // At least the assistance-compound row must be reachable — we try a
        // small-pool pick to prove it by construction: muscles=chest, equip=dumbbell
        // leaves c2 as the sole eligible exercise under compound+all-levels.
        val w2 = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest"),
            selectedEquipment = setOf("dumbbell"),
            selectedLevels = Level.DEFAULT,
            selectedCategories = setOf(Category.COMPOUND),
            duration = Duration.M15,
            weights = Weights.HEAVY,
            random = Random(0),
        )
        assertEquals(1, w2.exercises.size)
        assertEquals("c2", w2.exercises[0].template.id)
    }

    @Test fun `isolation-only filter excludes all compound and assistance-compound`() {
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest", "biceps"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = Level.DEFAULT,
            selectedCategories = setOf(Category.ISOLATION),
            duration = Duration.H1,
            weights = Weights.HEAVY,
            random = Random(3),
        )
        val isolationIds = setOf("c3", "b1", "b2", "b3")
        assertTrue(
            "isolation filter let through a non-isolation exercise: ${w.exercises.map { it.template.id }}",
            w.exercises.all { it.template.id in isolationIds },
        )
    }

    @Test fun `level and category stack (intersection of filters)`() {
        // Beginner + Isolation: c3 (beg+int, iso), b2 (beg+int+adv, iso), b3 (beg, iso).
        // c1 is compound (excluded), c4 is compound (excluded), b1 is int+adv (no
        // beginner, excluded). Expected survivors: c3, b2, b3.
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest", "biceps"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = setOf(Level.BEGINNER),
            selectedCategories = setOf(Category.ISOLATION),
            duration = Duration.H130,
            weights = Weights.HEAVY,
            random = Random(0),
        )
        assertEquals(setOf("c3", "b2", "b3"), w.exercises.map { it.template.id }.toSet())
    }

    @Test fun `empty attrs map bypasses level+category (graceful asset-missing fallback)`() {
        // If the bundled hevy_exercise_attrs.json fails to load we hand the
        // generator an empty map. It should skip the attrs-based filters
        // entirely and produce a workout as if only muscle+equipment were
        // selected — strictly better than an empty screen.
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = emptyMap(),
            selectedHevyMuscles = setOf("chest"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = setOf(Level.BEGINNER),       // would narrow if attrs loaded
            selectedCategories = setOf(Category.ISOLATION), // would narrow if attrs loaded
            duration = Duration.H1,
            weights = Weights.HEAVY,
            random = Random(0),
        )
        assertEquals(5, w.exercises.size)  // all 5 chest rows are eligible without attrs
    }

    @Test fun `id missing from non-empty attrs map is excluded (strict)`() {
        // Attrs map is populated but doesn't cover c3 — that exercise must
        // be dropped rather than pass through. This is the "deterministic
        // pool" stance: if we can't verify an exercise's attrs, we don't
        // offer it.
        val partialAttrs = attrs.filterKeys { it != "c3" }
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = partialAttrs,
            selectedHevyMuscles = setOf("chest"),
            selectedEquipment = setOf("cable"),  // only c3 uses cable
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            duration = Duration.H1,
            weights = Weights.HEAVY,
            random = Random(0),
        )
        assertEquals(0, w.exercises.size)
    }

    @Test fun `muscle split sums to 100 percent`() {
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            duration = Duration.H1,
            weights = Weights.HEAVY,
            random = Random(3),
        )
        val total = w.muscleSplit.sumOf { it.percent.toDouble() }
        // Allow some FP slack — the exact sum is normalized but float-rounding
        // can leave tiny residuals.
        assertEquals(100.0, total, 0.01)
    }

    @Test fun `muscle split weights primary 1_0 and secondary 0_5`() {
        // Single picked exercise: Bench Press → chest (1.0), triceps (0.5), shoulders (0.5)
        // Total weight = 2.0 → 50% / 25% / 25%
        val shares = WorkoutGenerator.computeMuscleSplit(listOf(fixture[0])) // c1 = Bench
        val byMuscle = shares.associate { it.hevyMuscleGroup to it.percent }
        assertEquals(50f, byMuscle["chest"]!!, 0.01f)
        assertEquals(25f, byMuscle["triceps"]!!, 0.01f)
        assertEquals(25f, byMuscle["shoulders"]!!, 0.01f)
    }

    @Test fun `muscle split is sorted descending by share`() {
        val w = WorkoutGenerator.generate(
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            duration = Duration.H1,
            weights = Weights.HEAVY,
            random = Random(4),
        )
        for (i in 1 until w.muscleSplit.size) {
            assertTrue(
                "split not sorted desc at index $i",
                w.muscleSplit[i - 1].percent >= w.muscleSplit[i].percent,
            )
        }
    }

    @Test fun `regenerate with different seeds yields different sets`() {
        // Randomization is the core of "tap regenerate to get a new workout".
        // With a 9-item pool and N=5, two different seeds should produce
        // different exercise sets the vast majority of the time.
        val a = WorkoutGenerator.generate(
            fixture, attrs,
            setOf("chest", "biceps", "quadriceps"),
            EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            Level.DEFAULT, Category.DEFAULT,
            Duration.H1, Weights.HEAVY, Random(10),
        ).exercises.map { it.template.id }.toSet()
        val b = WorkoutGenerator.generate(
            fixture, attrs,
            setOf("chest", "biceps", "quadriceps"),
            EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            Level.DEFAULT, Category.DEFAULT,
            Duration.H1, Weights.HEAVY, Random(99),
        ).exercises.map { it.template.id }.toSet()
        assertTrue("regenerate produced identical sets — randomization broken", a != b)
    }

    @Test fun `swap replaces only the chosen index`() {
        // Use M30 (3 exercises) against the 5-exercise chest pool so swap has
        // 2 alternatives to choose from (H1 would exhaust the pool and swap
        // would no-op — that's a separate test below).
        val original = WorkoutGenerator.generate(
            fixture, attrs,
            setOf("chest"),
            EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            Level.DEFAULT, Category.DEFAULT,
            Duration.M30, Weights.HEAVY, Random(20),
        )
        val swapped = WorkoutGenerator.swap(
            current = original,
            indexToSwap = 0,
            templates = fixture,
            attrsById = attrs,
            selectedHevyMuscles = setOf("chest"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            weights = Weights.HEAVY,
            random = Random(20),
        )
        // Only index 0 may differ; the rest must be identical.
        for (i in 1 until original.exercises.size) {
            assertEquals(
                "swap touched index $i (should only touch 0)",
                original.exercises[i].template.id,
                swapped.exercises[i].template.id,
            )
        }
        // Index 0 must NOT be the same template as before, AND must not duplicate
        // anything else in the workout.
        assertTrue(swapped.exercises[0].template.id != original.exercises[0].template.id)
        assertEquals(swapped.exercises.size, swapped.exercises.map { it.template.id }.toSet().size)
    }

    @Test fun `weight suggestion scales by weights bias`() {
        val bench = fixture[0]  // barbell
        val light = WeightSuggestion.forExercise(bench, Weights.LIGHT)
        val medium = WeightSuggestion.forExercise(bench, Weights.MEDIUM)
        val heavy = WeightSuggestion.forExercise(bench, Weights.HEAVY)
        assertTrue("weights bias must monotonically increase weight", light < medium && medium < heavy)
    }

    @Test fun `weight suggestion is zero for bodyweight (UI hides label)`() {
        val pushup = fixture[3]  // equipment = "none"
        for (w in Weights.entries) {
            assertEquals(0f, WeightSuggestion.forExercise(pushup, w), 0.001f)
        }
    }

    @Test fun `barbell weight is rounded to a 2_5kg increment (loadable)`() {
        val bench = fixture[0]  // barbell, base 40 kg
        val w = WeightSuggestion.forExercise(bench, Weights.HEAVY)
        // 40 * 1.0 = 40.0 → already on a 2.5 kg grid.
        assertEquals(0f, w % 2.5f, 0.001f)
        // 40 * 0.5 = 20.0 → also on the grid.
        assertEquals(0f, WeightSuggestion.forExercise(bench, Weights.LIGHT) % 2.5f, 0.001f)
    }

    @Test fun `formatSubline shows weight only when non-zero`() {
        val withWeight = GeneratedExercise(
            template = fixture[0],
            sets = 3, reps = 8,
            weightKg = 36.7f,
        )
        val bodyweight = GeneratedExercise(
            template = fixture[3],
            sets = 3, reps = 8,
            weightKg = 0f,
        )
        assertEquals("36.7 kg • 8 reps", formatSubline(withWeight))
        assertEquals("8 reps", formatSubline(bodyweight))
    }

    @Test fun `generate throws when no muscles selected`() {
        val ex = runCatching {
            WorkoutGenerator.generate(
                fixture, attrs, emptySet(),
                EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
                Level.DEFAULT, Category.DEFAULT,
                Duration.H1, Weights.HEAVY, Random(0),
            )
        }.exceptionOrNull()
        assertNotNull("generate must reject empty muscle set", ex)
        assertTrue(ex is IllegalArgumentException)
    }

    @Test fun `swap returns same workout when no alternative exists`() {
        // Pool of size 1 → the lone exercise is already picked → swap finds nothing.
        val singleton = listOf(tpl("only", "Only Curl", "biceps", emptyList(), "dumbbell"))
        val singletonAttrs = mapOf(
            "only" to HevyExerciseAttrs(listOf("beginner", "intermediate", "advanced"), emptyList(), "isolation"),
        )
        val w = WorkoutGenerator.generate(
            singleton, singletonAttrs, setOf("biceps"),
            EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            Level.DEFAULT, Category.DEFAULT,
            Duration.M15, Weights.HEAVY, Random(0),
        )
        assertEquals(1, w.exercises.size)
        val swapped = WorkoutGenerator.swap(
            current = w,
            indexToSwap = 0,
            templates = singleton,
            attrsById = singletonAttrs,
            selectedHevyMuscles = setOf("biceps"),
            selectedEquipment = EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            selectedLevels = Level.DEFAULT,
            selectedCategories = Category.DEFAULT,
            weights = Weights.HEAVY,
            random = Random(0),
        )
        assertEquals(w.exercises[0].template.id, swapped.exercises[0].template.id)
    }

    @Test fun `empty pick list yields empty muscle split (no NaN)`() {
        // Pool that doesn't match any selected muscle → 0 exercises → split must be empty,
        // not a Map<_, NaN> from a divide-by-zero.
        val w = WorkoutGenerator.generate(
            fixture, attrs, setOf("calves"),  // no calf exercises in fixture
            EquipmentMap.KNOWN_HEVY_TAGS.toSet(),
            Level.DEFAULT, Category.DEFAULT,
            Duration.H1, Weights.HEAVY, Random(0),
        )
        assertEquals(0, w.exercises.size)
        assertEquals(emptyList<MuscleShare>(), w.muscleSplit)
    }

    @Test fun `Duration DEFAULT is 1h (the user's preference & Liftoff default)`() {
        assertEquals(Duration.H1, Duration.DEFAULT)
        assertEquals(60, Duration.DEFAULT.minutes)
        assertEquals(5, Duration.DEFAULT.exerciseCount)
    }

    @Test fun `Weights DEFAULT is Heavy (was Advanced before the rename)`() {
        assertEquals(Weights.HEAVY, Weights.DEFAULT)
    }

    @Test fun `Level DEFAULT contains all three levels (permissive)`() {
        assertEquals(Level.entries.toSet(), Level.DEFAULT)
    }

    @Test fun `Category DEFAULT contains both categories (permissive)`() {
        assertEquals(Category.entries.toSet(), Category.DEFAULT)
    }

    @Test fun `Category COMPOUND folds in assistance-compound raw value`() {
        // Key behavioural spec: the user asked us to treat Hevy's two raw
        // compound strings ('compound' and 'assistance-compound') as one
        // category option in the picker. That mapping lives in the enum.
        assertTrue("compound" in Category.COMPOUND.hevyValues)
        assertTrue("assistance-compound" in Category.COMPOUND.hevyValues)
        assertTrue("isolation" !in Category.COMPOUND.hevyValues)
    }

    private fun tpl(
        id: String,
        title: String,
        primary: String,
        secondary: List<String>,
        equipment: String,
    ): ExerciseTemplate = ExerciseTemplate(
        id = id,
        title = title,
        primaryMuscleGroup = primary,
        secondaryMuscleGroups = secondary,
        equipment = equipment,
    )

    @Suppress("unused")
    private fun nonNullSwapResult(r: GeneratedWorkout?) = assertNull(r)  // (unused helper)
}
