package com.example.hevycompanion.generate

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.HevyExerciseAttrs
import kotlin.random.Random

/**
 * Deterministic port of Liftoff's STATIC (non-LLM) "Generate workout" flow.
 *
 * Given:
 *  - a set of target Hevy muscle groups (from the multi-select grid)
 *  - a set of available Hevy equipment tags
 *  - a set of permitted exercise levels (beginner / intermediate / advanced)
 *  - a set of permitted exercise categories (compound / isolation)
 *  - a duration (→ exercise count + sets + reps, per Liftoff's fixed table)
 *  - a [Weights] bias (→ suggested weight multiplier — UI calls this "Light /
 *    Medium / Heavy" and it's independent of the level filter)
 *  - the cached Hevy exercise-template catalog
 *  - the bundled attrs side table ({id → level/goal/category}), because the
 *    REST catalog does not return those fields
 *
 * …produces a [GeneratedWorkout] by filtering the catalog, shuffling, picking
 * N exercises, and prescribing sets / reps / suggested weight.
 *
 * Liftoff's real weight formula is unknown (we only have screenshots, not the
 * algorithm) so we use a per-equipment baseline × weight-bias multiplier. The
 * numbers are a starting point — tweak [WeightSuggestion.baseKg] later when
 * we collect more data or wire in the user's Hevy workout history.
 */
object WorkoutGenerator {

    /**
     * @param attrsById Joined-by-id side table from [HevyExerciseAttrMap.all].
     *   An empty map disables the level/category filters (every exercise
     *   passes), which is the right fallback if the asset ever fails to
     *   load — better to show an unfiltered workout than none at all.
     * @param random Injected so tests can pin the shuffle.
     * @throws IllegalArgumentException if [selectedHevyMuscles] is empty —
     *   the caller's "Confirm" button should already guard this.
     */
    fun generate(
        templates: List<ExerciseTemplate>,
        attrsById: Map<String, HevyExerciseAttrs>,
        selectedHevyMuscles: Set<String>,
        selectedEquipment: Set<String>,
        selectedLevels: Set<Level>,
        selectedCategories: Set<Category>,
        duration: Duration,
        weights: Weights,
        random: Random = Random.Default,
    ): GeneratedWorkout {
        require(selectedHevyMuscles.isNotEmpty()) {
            "At least one muscle must be selected before generating."
        }

        val filter = Filter(
            muscles = selectedHevyMuscles.map { it.lowercase() }.toSet(),
            equipment = selectedEquipment.map { it.lowercase() }.toSet(),
            levels = selectedLevels,
            categories = selectedCategories,
            attrsById = attrsById,
        )

        val candidates = templates.filter { filter.matches(it) }
        val picked = candidates.shuffled(random).take(duration.exerciseCount)

        return GeneratedWorkout(
            exercises = picked.map { tpl ->
                GeneratedExercise(
                    template = tpl,
                    sets = duration.setsPerExercise,
                    reps = duration.repsPerSet,
                    weightKg = WeightSuggestion.forExercise(tpl, weights),
                )
            },
            muscleSplit = computeMuscleSplit(picked),
        )
    }

    /**
     * Replace [indexToSwap] in [current] with a different exercise from the same
     * filtered pool. Returns [current] unchanged if there's no alternative.
     * Used by the per-row "..." menu in [GenerateWorkoutScreen].
     */
    fun swap(
        current: GeneratedWorkout,
        indexToSwap: Int,
        templates: List<ExerciseTemplate>,
        attrsById: Map<String, HevyExerciseAttrs>,
        selectedHevyMuscles: Set<String>,
        selectedEquipment: Set<String>,
        selectedLevels: Set<Level>,
        selectedCategories: Set<Category>,
        weights: Weights,
        random: Random = Random.Default,
    ): GeneratedWorkout {
        require(indexToSwap in current.exercises.indices) { "Index $indexToSwap out of bounds" }

        val filter = Filter(
            muscles = selectedHevyMuscles.map { it.lowercase() }.toSet(),
            equipment = selectedEquipment.map { it.lowercase() }.toSet(),
            levels = selectedLevels,
            categories = selectedCategories,
            attrsById = attrsById,
        )
        val used = current.exercises.map { it.template.id }.toSet()

        val alternatives = templates.filter { tpl ->
            tpl.id !in used && filter.matches(tpl)
        }
        val replacement = alternatives.shuffled(random).firstOrNull() ?: return current

        val oldExercise = current.exercises[indexToSwap]
        val newExercise = GeneratedExercise(
            template = replacement,
            sets = oldExercise.sets,
            reps = oldExercise.reps,
            weightKg = WeightSuggestion.forExercise(replacement, weights),
        )
        val newList = current.exercises.toMutableList().apply { this[indexToSwap] = newExercise }
        return GeneratedWorkout(
            exercises = newList,
            muscleSplit = computeMuscleSplit(newList.map { it.template }),
        )
    }

    /**
     * All filter state rolled up into one object so [matches] has the full
     * picture in one place. Instances are cheap (just references) but the
     * constructor normalizes muscle/equipment sets once instead of re-doing
     * it per exercise.
     */
    private data class Filter(
        val muscles: Set<String>,
        val equipment: Set<String>,
        val levels: Set<Level>,
        val categories: Set<Category>,
        val attrsById: Map<String, HevyExerciseAttrs>,
    ) {
        // Only an empty map (i.e. asset failed to load) bypasses the attrs
        // checks. When the map is populated but a specific id is missing, we
        // treat that exercise as unfilterable and exclude it — matches the
        // user's "deterministic pool" stance: if we can't verify level /
        // category for an exercise, don't offer it.
        private val attrsBypass: Boolean = attrsById.isEmpty()
        // Flattened allowlists so the per-exercise hot path is a single
        // Set-membership check rather than a nested any/any.
        private val levelAllowlist: Set<String> = levels.mapTo(mutableSetOf()) { it.hevyValue }
        private val categoryAllowlist: Set<String> = categories.flatMapTo(mutableSetOf()) { it.hevyValues }

        fun matches(tpl: ExerciseTemplate): Boolean {
            val muscleOk = tpl.primaryMuscleGroup?.lowercase() in muscles
            // Strict equipment filter: the exercise's equipment tag must be in
            // the user's selection. Bodyweight ("none") and catch-all ("other")
            // are no longer force-included — earlier versions did that to match
            // Liftoff's observed soft-filter, but the user reported it as surprising
            // (bird-dog / lateral-leg-raises appearing after she un-ticked
            // "Bodyweight"). User's explicit selection wins.
            //
            // Exercises with a null equipment tag are excluded — we have no
            // basis to decide whether the user wanted them.
            val equipmentOk = tpl.equipment?.lowercase() in equipment
            if (!muscleOk || !equipmentOk) return false
            if (attrsBypass) return true

            val attrs = attrsById[tpl.id] ?: return false
            val levelOk = levelAllowlist.isEmpty() || attrs.level.any { it in levelAllowlist }
            val categoryOk = categoryAllowlist.isEmpty() || attrs.category in categoryAllowlist
            return levelOk && categoryOk
        }
    }

    /**
     * Computes Liftoff's %-per-muscle bar from the picked exercises.
     * Primary muscle counts 1.0, each secondary muscle 0.5, normalized to 100%.
     * Returns muscles sorted by descending share.
     */
    internal fun computeMuscleSplit(picked: List<ExerciseTemplate>): List<MuscleShare> {
        if (picked.isEmpty()) return emptyList()
        val hits = linkedMapOf<String, Float>()
        for (ex in picked) {
            ex.primaryMuscleGroup?.lowercase()?.let { m ->
                hits[m] = (hits[m] ?: 0f) + 1.0f
            }
            ex.secondaryMuscleGroups?.forEach { raw ->
                val m = raw.lowercase()
                hits[m] = (hits[m] ?: 0f) + 0.5f
            }
        }
        val total = hits.values.sum()
        if (total == 0f) return emptyList()
        return hits
            .map { (m, h) -> MuscleShare(hevyMuscleGroup = m, percent = (h / total) * 100f) }
            .sortedByDescending { it.percent }
    }
}

/**
 * Liftoff's fixed duration → (exercise count, sets, reps) table, observed
 * by the user by running each setting in the real app and counting.
 *
 * 15m →  3 ex × 1 set × 8 reps
 * 30m →  3 ex × 3 sets × 8 reps
 * 45m →  4 ex × 3 sets × 8 reps
 * 1h  →  5 ex × 3 sets × 8 reps
 * 1h 15m → 6 ex × 3 sets × 8 reps
 * 1h 30m → 8 ex × 3 sets × 8 reps
 *
 * (Reps stay at 8 across every duration; only count + sets vary.)
 */
enum class Duration(
    val minutes: Int,
    val label: String,
    val exerciseCount: Int,
    val setsPerExercise: Int,
    val repsPerSet: Int,
) {
    M15(15, "15m", exerciseCount = 3, setsPerExercise = 1, repsPerSet = 8),
    M30(30, "30m", exerciseCount = 3, setsPerExercise = 3, repsPerSet = 8),
    M45(45, "45m", exerciseCount = 4, setsPerExercise = 3, repsPerSet = 8),
    H1(60, "1h", exerciseCount = 5, setsPerExercise = 3, repsPerSet = 8),
    H115(75, "1h 15m", exerciseCount = 6, setsPerExercise = 3, repsPerSet = 8),
    H130(90, "1h 30m", exerciseCount = 8, setsPerExercise = 3, repsPerSet = 8);

    companion object {
        /** Default matching the user's personal preference + Liftoff's default selection. */
        val DEFAULT: Duration = H1
    }
}

/**
 * Suggested-weight bias. Independent of [Level]: a heavy-weights intermediate
 * lifter is free to pick `levels=[INTERMEDIATE]` alongside `weights=HEAVY`,
 * and an advanced lifter on a deload week can pair `levels=[ADVANCED]` with
 * `weights=LIGHT`. The share-message text uses [displayName].
 */
enum class Weights(val displayName: String) {
    LIGHT("Light"),
    MEDIUM("Medium"),
    HEAVY("Heavy");

    companion object {
        /** Default matches the previous Difficulty=Advanced default. */
        val DEFAULT: Weights = HEAVY
    }
}

/**
 * Exercise-level filter — multi-select. Values map 1:1 to the `level` entries
 * Hevy's catalog ships (beginner / intermediate / advanced), which can be a
 * list of 1–3 on any given exercise. An exercise passes the filter if its
 * level list intersects the user's selection.
 */
enum class Level(val displayName: String, val hevyValue: String) {
    BEGINNER("Beginner", "beginner"),
    INTERMEDIATE("Intermediate", "intermediate"),
    ADVANCED("Advanced", "advanced");

    companion object {
        /** Permissive default — don't narrow the pool until the user asks to. */
        val DEFAULT: Set<Level> = entries.toSet()
    }
}

/**
 * Exercise-category filter — multi-select. Hevy publishes three raw category
 * strings (`compound`, `isolation`, `assistance-compound`); per spec we treat
 * `assistance-compound` as a variant of compound so the two collapse into
 * one filter option.
 */
enum class Category(val displayName: String, val hevyValues: Set<String>) {
    COMPOUND("Compound", setOf("compound", "assistance-compound")),
    ISOLATION("Isolation", setOf("isolation"));

    companion object {
        /** Permissive default — don't narrow the pool until the user asks to. */
        val DEFAULT: Set<Category> = entries.toSet()
    }
}

data class GeneratedExercise(
    val template: ExerciseTemplate,
    val sets: Int,
    val reps: Int,
    /**
     * Suggested starting weight in kg. 0f means "bodyweight / no weight" —
     * the UI should render it as "8 reps" without a weight prefix.
     */
    val weightKg: Float,
)

data class GeneratedWorkout(
    val exercises: List<GeneratedExercise>,
    /** %-per-muscle bar shown above the exercise list; sorted desc by share. */
    val muscleSplit: List<MuscleShare>,
)

data class MuscleShare(
    val hevyMuscleGroup: String,
    val percent: Float,
)

/**
 * Suggested weight per exercise, derived from its Hevy equipment tag and the
 * selected [Weights] bias.
 *
 * This is an intentional simplification: Liftoff uses a per-exercise baseline
 * we don't have visibility into, so equipment-level baselines are the best
 * approximation until we either (a) observe enough Liftoff outputs to reverse-
 * engineer a table, or (b) read the user's own Hevy history and scale from
 * their 1RM. Tuning [baseKg] over time is expected.
 *
 * Weight is rounded down to the nearest increment expected on that equipment
 * (2.5 kg for barbells, 1 kg for everything else) so the numbers don't look
 * silly (e.g. "24.5 kg" on a dumbbell that only comes in whole kilograms).
 */
object WeightSuggestion {

    /**
     * Ballpark starting weight for a "Heavy" bias. Adjust here (not per-
     * exercise) when tuning.
     */
    internal fun baseKg(equipment: String?): Float = when (equipment?.lowercase()) {
        "barbell" -> 40f
        "dumbbell" -> 15f
        "kettlebell" -> 16f
        "cable" -> 25f
        "machine" -> 30f
        "plate" -> 10f
        "ez_bar" -> 20f
        // Everything else (none / resistance_band / suspension / other / null)
        // → bodyweight / not prescribed → 0 → UI hides the weight label.
        else -> 0f
    }

    internal fun multiplier(w: Weights): Float = when (w) {
        Weights.LIGHT -> 0.50f
        Weights.MEDIUM -> 0.75f
        Weights.HEAVY -> 1.00f
    }

    fun forExercise(tpl: ExerciseTemplate, w: Weights): Float {
        val raw = baseKg(tpl.equipment) * multiplier(w)
        if (raw == 0f) return 0f
        val step = if (tpl.equipment?.lowercase() == "barbell") 2.5f else 1.0f
        // floor to increment so the suggestion is always a plate/weight the user
        // can actually load.
        return (raw / step).toInt() * step
    }
}
