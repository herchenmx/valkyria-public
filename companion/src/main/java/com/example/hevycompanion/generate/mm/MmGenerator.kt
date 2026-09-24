package com.example.hevycompanion.generate.mm

import kotlin.random.Random

/**
 * Movement-pattern + role-aware workout generator backed by the M&M catalog.
 *
 * Independent of the Hevy/Liftoff generator (different data shape, different
 * UX). The pick step is a greedy volume-balancer rather than a uniform
 * shuffle: each candidate is scored by how much it covers under-hit target
 * muscles minus how much it piles on already-saturated ones, so a 5-exercise
 * "Push Day" picks variety across chest / shoulders / triceps instead of
 * five bench-press variants.
 */
object MmGenerator {

    fun generate(
        catalog: List<MmExercise>,
        mode: Mode,
        equipment: Set<String>,
        types: Set<ExerciseType>,
        duration: MmDuration,
        addWarmup: Boolean,
        addCooldown: Boolean,
        random: Random = Random.Default,
    ): MmGeneratedWorkout {
        val pool = filterPool(catalog, mode, equipment, types)
        val main = pickGreedy(
            pool = pool,
            count = duration.exerciseCount,
            mode = mode,
            random = random,
        ).map { prescribe(it, duration) }

        val warmup = if (addWarmup) pickAuxiliary(
            catalog = catalog,
            categories = setOf("Warmup", "Mobility"),
            equipment = equipment,
            count = 1,
            random = random,
            excludeIds = main.mapTo(mutableSetOf()) { it.exercise.id },
        ).map { auxiliaryPrescription(it, isWarmup = true) } else emptyList()

        val cooldown = if (addCooldown) pickAuxiliary(
            catalog = catalog,
            categories = setOf("Stretching", "Mobility"),
            equipment = equipment,
            count = 1,
            random = random,
            excludeIds = (main.map { it.exercise.id } + warmup.map { it.exercise.id }).toSet(),
        ).map { auxiliaryPrescription(it, isWarmup = false) } else emptyList()

        val all = warmup + main + cooldown
        return MmGeneratedWorkout(
            blocks = all,
            volumeByMuscle = computeVolume(main.map { it.exercise }),
        )
    }

    fun swap(
        current: MmGeneratedWorkout,
        indexToSwap: Int,
        catalog: List<MmExercise>,
        mode: Mode,
        equipment: Set<String>,
        types: Set<ExerciseType>,
        duration: MmDuration,
        random: Random = Random.Default,
    ): MmGeneratedWorkout {
        require(indexToSwap in current.blocks.indices) { "Index $indexToSwap out of bounds" }
        val target = current.blocks[indexToSwap]

        val replacementPool: List<MmExercise> = when (target.role) {
            BlockRole.WARMUP -> filterAuxiliary(catalog, setOf("Warmup", "Mobility"), equipment)
            BlockRole.COOLDOWN -> filterAuxiliary(catalog, setOf("Stretching", "Mobility"), equipment)
            BlockRole.MAIN -> filterPool(catalog, mode, equipment, types)
        }
        val used = current.blocks.map { it.exercise.id }.toSet()
        val candidates = replacementPool.filter { it.id !in used }
        val replacement = candidates.shuffled(random).firstOrNull() ?: return current

        val newBlock = when (target.role) {
            BlockRole.MAIN -> prescribe(replacement, duration)
            BlockRole.WARMUP -> auxiliaryPrescription(replacement, isWarmup = true)
            BlockRole.COOLDOWN -> auxiliaryPrescription(replacement, isWarmup = false)
        }
        val newBlocks = current.blocks.toMutableList().apply { this[indexToSwap] = newBlock }
        return MmGeneratedWorkout(
            blocks = newBlocks,
            volumeByMuscle = computeVolume(newBlocks.filter { it.role == BlockRole.MAIN }.map { it.exercise }),
        )
    }

    // ---- Filtering ----------------------------------------------------------

    private fun filterPool(
        catalog: List<MmExercise>,
        mode: Mode,
        equipment: Set<String>,
        types: Set<ExerciseType>,
    ): List<MmExercise> {
        val typeStrings = types.mapTo(mutableSetOf()) { it.mmValue }
        return catalog.filter { ex ->
            // Auxiliary categories are picked separately — keep them out of the main pool.
            if (ex.category in AUXILIARY_CATEGORIES) return@filter false
            if ((ex.type ?: "") !in typeStrings) return@filter false
            if (equipment.isNotEmpty() && ex.equipment.none { it in equipment }) return@filter false
            mode.matches(ex)
        }
    }

    private fun filterAuxiliary(
        catalog: List<MmExercise>,
        categories: Set<String>,
        equipment: Set<String>,
    ): List<MmExercise> = catalog.filter { ex ->
        if (ex.category !in categories) return@filter false
        if (equipment.isEmpty()) return@filter true
        // Bodyweight / no-equipment auxiliaries pass even if the user only ticked weighted gear,
        // so a "barbell" workout still gets a stretch suggestion at the end.
        ex.equipment.isEmpty() || ex.equipment.any { it in equipment } ||
                ex.equipment.any { it.equals("Bodyweight", ignoreCase = true) }
    }

    // ---- Greedy volume-balancing pick ---------------------------------------

    private fun pickGreedy(
        pool: List<MmExercise>,
        count: Int,
        mode: Mode,
        random: Random,
    ): List<MmExercise> {
        if (pool.isEmpty()) return emptyList()
        val targetMuscles: Set<String> = mode.targetMuscleHint(pool)
        val hits = mutableMapOf<String, Float>()
        val patternCounts = mutableMapOf<String, Int>()
        val picked = mutableListOf<MmExercise>()
        val remaining = pool.toMutableList().also { it.shuffle(random) }

        while (picked.size < count && remaining.isNotEmpty()) {
            val best = remaining.maxByOrNull { score(it, hits, patternCounts, targetMuscles) }
                ?: break
            remaining.remove(best)
            picked.add(best)
            for (m in best.targetMuscles) hits.merge(m, 1.0f, Float::plus)
            for (m in best.synergistMuscles) hits.merge(m, 0.5f, Float::plus)
            for (m in best.stabilizerMuscles) hits.merge(m, 0.25f, Float::plus)
            for (p in best.movementPattern) patternCounts.merge(p, 1, Int::plus)
        }
        return picked
    }

    private fun score(
        ex: MmExercise,
        hits: Map<String, Float>,
        patternCounts: Map<String, Int>,
        targetMuscles: Set<String>,
    ): Float {
        // Coverage: how many under-hit target muscles this exercise hits.
        var score = 0f
        for (m in ex.targetMuscles) {
            val current = hits[m] ?: 0f
            val onTarget = if (targetMuscles.isEmpty() || m in targetMuscles) 1.0f else 0.4f
            // Diminishing returns: first hit on a muscle is worth full points, second half, etc.
            score += onTarget / (1.0f + current)
        }
        for (m in ex.synergistMuscles) {
            val current = hits[m] ?: 0f
            score += 0.3f / (1.0f + current)
        }
        // Pattern repetition penalty: don't pick three squats in a row.
        for (p in ex.movementPattern) {
            score -= 0.5f * (patternCounts[p] ?: 0)
        }
        return score
    }

    private fun pickAuxiliary(
        catalog: List<MmExercise>,
        categories: Set<String>,
        equipment: Set<String>,
        count: Int,
        random: Random,
        excludeIds: Set<String>,
    ): List<MmExercise> {
        val pool = filterAuxiliary(catalog, categories, equipment)
            .filter { it.id !in excludeIds }
        return pool.shuffled(random).take(count)
    }

    // ---- Sets/reps prescription ---------------------------------------------

    private fun prescribe(ex: MmExercise, duration: MmDuration): MmBlock {
        val cat = ex.category
        val rx: Prescription = when {
            ex.type == "time" || "time" in ex.defaultWorkoutFields ->
                Prescription(sets = duration.setsForTime, repsOrSeconds = 30, isTime = true)
            cat == "Compound" || cat == "Olympic Weightlifting" ->
                Prescription(sets = duration.setsForCompound, repsOrSeconds = 6)
            cat == "Plyometric Training" ->
                Prescription(sets = duration.setsForCompound, repsOrSeconds = 5)
            cat == "Functional Training" ->
                Prescription(sets = duration.setsForIsolation, repsOrSeconds = 12)
            cat == "Cardio" ->
                Prescription(sets = 1, repsOrSeconds = 60, isTime = true)
            cat == "Isolation" ->
                Prescription(sets = duration.setsForIsolation, repsOrSeconds = 10)
            "reps" in ex.defaultWorkoutFields && "weight" !in ex.defaultWorkoutFields ->
                Prescription(sets = duration.setsForIsolation, repsOrSeconds = 12)
            else ->
                Prescription(sets = duration.setsForIsolation, repsOrSeconds = 10)
        }
        return MmBlock(
            exercise = ex,
            role = BlockRole.MAIN,
            sets = rx.sets,
            repsOrSeconds = rx.repsOrSeconds,
            isTime = rx.isTime,
        )
    }

    private fun auxiliaryPrescription(ex: MmExercise, isWarmup: Boolean): MmBlock = MmBlock(
        exercise = ex,
        role = if (isWarmup) BlockRole.WARMUP else BlockRole.COOLDOWN,
        sets = 1,
        repsOrSeconds = if (isWarmup) 30 else 45,
        isTime = true,
    )

    // ---- Volume rollup ------------------------------------------------------

    internal fun computeVolume(picked: List<MmExercise>): List<MmMuscleShare> {
        if (picked.isEmpty()) return emptyList()
        val hits = linkedMapOf<String, Float>()
        for (ex in picked) {
            for (m in ex.targetMuscles) hits.merge(m, 1.0f, Float::plus)
            for (m in ex.synergistMuscles) hits.merge(m, 0.5f, Float::plus)
        }
        val total = hits.values.sum()
        if (total == 0f) return emptyList()
        return hits.map { (m, h) -> MmMuscleShare(muscle = m, percent = (h / total) * 100f) }
            .sortedByDescending { it.percent }
    }

    private val AUXILIARY_CATEGORIES = setOf("Warmup", "Stretching", "Mobility")

    private data class Prescription(val sets: Int, val repsOrSeconds: Int, val isTime: Boolean = false)
}

// ---- Public types -----------------------------------------------------------

/**
 * What the user asked the generator to build.
 *
 *  - [Split]: picks across a curated set of movement patterns (e.g. Push Day
 *    = Push pattern only).
 *  - [SubAreas]: picks exercises whose `area` or `sub_areas` intersect the
 *    user's selection. Free-form muscle targeting.
 */
sealed class Mode {
    /** Returns true if [ex] is eligible for this mode. */
    abstract fun matches(ex: MmExercise): Boolean
    /**
     * Optional hint for the volume-balancer about which muscles should be
     * prioritised. Empty set = "treat every target as equal".
     */
    open fun targetMuscleHint(pool: List<MmExercise>): Set<String> = emptySet()

    data class Split(val day: SplitDay) : Mode() {
        override fun matches(ex: MmExercise): Boolean =
            ex.movementPattern.any { it in day.allowedPatterns }
    }

    data class SubAreas(val selected: Set<String>) : Mode() {
        override fun matches(ex: MmExercise): Boolean {
            if (selected.isEmpty()) return true
            if (ex.area in selected) return true
            return ex.subAreas.any { it in selected }
        }
    }
}

enum class SplitDay(val displayName: String, val allowedPatterns: Set<String>) {
    PUSH("Push", setOf("Push")),
    PULL("Pull", setOf("Pull")),
    LEGS("Legs", setOf("Squat", "Hinge", "Lunge")),
    UPPER("Upper", setOf("Push", "Pull")),
    LOWER("Lower", setOf("Squat", "Hinge", "Lunge")),
    FULL_BODY("Full Body", setOf("Push", "Pull", "Squat", "Hinge", "Lunge"));

    companion object { val DEFAULT: SplitDay = FULL_BODY }
}

enum class ExerciseType(val displayName: String, val mmValue: String) {
    STRENGTH("Strength", "strength"),
    BODYWEIGHT("Bodyweight", "bodyweight"),
    TIME("Time-based", "time");

    companion object {
        /** Default excludes time-based — most users want a lifting session by default. */
        val DEFAULT: Set<ExerciseType> = setOf(STRENGTH, BODYWEIGHT)
    }
}

enum class MmDuration(
    val minutes: Int,
    val label: String,
    val exerciseCount: Int,
    val setsForCompound: Int,
    val setsForIsolation: Int,
    val setsForTime: Int,
) {
    M30(30, "30m", exerciseCount = 4, setsForCompound = 3, setsForIsolation = 3, setsForTime = 2),
    M45(45, "45m", exerciseCount = 5, setsForCompound = 4, setsForIsolation = 3, setsForTime = 3),
    H1(60, "1h", exerciseCount = 6, setsForCompound = 4, setsForIsolation = 3, setsForTime = 3),
    H115(75, "1h 15m", exerciseCount = 7, setsForCompound = 4, setsForIsolation = 4, setsForTime = 3),
    H130(90, "1h 30m", exerciseCount = 8, setsForCompound = 5, setsForIsolation = 4, setsForTime = 3);

    companion object { val DEFAULT: MmDuration = H1 }
}

enum class BlockRole { WARMUP, MAIN, COOLDOWN }

data class MmBlock(
    val exercise: MmExercise,
    val role: BlockRole,
    val sets: Int,
    /** Reps for normal sets, seconds for time-based sets. */
    val repsOrSeconds: Int,
    val isTime: Boolean,
)

data class MmGeneratedWorkout(
    val blocks: List<MmBlock>,
    /** %-per-muscle bar over the MAIN blocks only (warmup/cooldown excluded). */
    val volumeByMuscle: List<MmMuscleShare>,
)

data class MmMuscleShare(val muscle: String, val percent: Float)
