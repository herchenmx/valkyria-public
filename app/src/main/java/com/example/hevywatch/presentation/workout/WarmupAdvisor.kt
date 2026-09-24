package com.example.hevywatch.presentation.workout

import com.example.hevycore.exercise.AssistedBodyweight
import com.example.hevycore.workout.WarmupConstants
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import kotlin.math.floor

/**
 * For assisted-bodyweight exercises (chinup/dip machine, etc.), the work performed scales
 * with `bodyweight − loggedKg`. We compute the warmup percentages on that effective work
 * and then convert back to logged kg, which makes the warmup logged kg HIGHER than the
 * working set's logged kg (more assistance = easier warmup) instead of lower.
 */
internal fun suggestWarmupSets(
    primaryMuscleGroup: String?,
    equipment: String?,
    workingWeightKg: Float,
    normalSetCount: Int = 0,
    exerciseTemplateId: String? = null,
    bodyweightKg: Float = 0f,
    // Fixed base resistance (empty bar / Smith carriage / machine sled) baked into
    // the logged working weight. The warmup COUNT is still decided on the full
    // total (base + plates) — a 117 kg-total leg press is a 4-warmup lift whether
    // 50 kg of that is the sled — but the ladder RAMPS on the plate portion
    // (`working − base`), then adds the base back, so the percentages describe the
    // plates you actually add. Without a base (0, the default), the plate portion
    // equals the total and behaviour is byte-for-byte identical to before.
    // Ignored for assisted-bodyweight (stack machines have no add-on base).
    baseResistanceKg: Float = 0f
): List<ActiveSet> {
    if (equipment?.lowercase() !in WarmupConstants.WEIGHTED_EQUIPMENT) return emptyList()
    if (primaryMuscleGroup?.lowercase() in WarmupConstants.NO_WARMUP_GROUPS) return emptyList()

    val category = WarmupConstants.categorize(primaryMuscleGroup) ?: return emptyList()

    val isAssisted = AssistedBodyweight.isAssisted(exerciseTemplateId)

    // Work in "effort" space: what the lifter is actually moving. For normal exercises
    // this equals the logged kg; for assisted exercises it's bodyweight minus logged kg.
    val effortWeightKg = if (isAssisted) {
        (bodyweightKg - workingWeightKg).coerceAtLeast(0f)
    } else workingWeightKg
    if (effortWeightKg <= 0f) return emptyList()

    // Warmup COUNT is decided on the total effort — base stays in the effort.
    val setCount = WarmupConstants.warmupSetCount(category, effortWeightKg)
    if (setCount == 0) return emptyList()

    val increment = WarmupConstants.incrementFor(equipment)
    // Barbell warmups can't go below the bar itself (20kg Olympic bar). N/A for assisted
    // (always machine equipment), so this only affects the conventional path.
    val minWeight = WarmupConstants.minWarmupWeight(equipment)

    // The plate portion the ladder scales. Assisted stack machines have no add-on
    // base, so their ramp stays in pure effort space (base forced to 0).
    val base = if (isAssisted) 0f else baseResistanceKg.coerceAtLeast(0f)

    val protocol = WarmupConstants.PROTOCOLS[setCount] ?: return emptyList()
    val single = protocol.map { (pct, reps) ->
        val loggedKg = if (isAssisted) {
            val raw = effortWeightKg * pct
            val effortRounded = (floor(raw / increment) * increment).coerceAtLeast(minWeight)
            // Convert back to logged kg. Lower effort warmup → higher logged kg
            // (more stack assistance), capped at bodyweight − one increment so the
            // warmup is strictly easier than fully-assisted.
            (bodyweightKg - effortRounded).coerceAtLeast(0f)
        } else {
            // Ramp the plate portion, then add the fixed base back so the stored
            // weight is the true total moved (never below the base — an empty
            // bar/machine is the floor). base = 0 → identical to the old
            // `floor(working * pct)` path.
            val movable = (effortWeightKg - base).coerceAtLeast(0f)
            val plateRounded = floor(movable * pct / increment) * increment
            (plateRounded + base).coerceAtLeast(minWeight)
        }
        ActiveSet(setType = SetType.WARMUP, weightKg = loggedKg, reps = reps)
    }

    // Bilateral exercises (≥6 normal sets, even count → e.g. 3 per leg):
    // double every warmup set so each leg gets one at each weight level.
    return if (normalSetCount >= 6 && normalSetCount % 2 == 0) {
        single.flatMap { set -> listOf(set, set.copy()) }
    } else {
        single
    }
}

/**
 * Record a machine/bar [baseResistanceKg] on [exercise] and re-ramp its
 * not-yet-logged warmup sets onto the plate portion (see [suggestWarmupSets]).
 *
 * Called when the user enters (or corrects) the base for an exercise mid-session.
 * Warmup weights were first injected at workout load with base 0 (total ramp), so
 * on a machine with a real base the low warmups would otherwise sit below the
 * base (negative plates on the log screen). We regenerate the ideal ladder at the
 * new base and overwrite each warmup slot's weight positionally — but only for
 * slots that are neither completed nor locked, so an already-logged warmup (or a
 * carried-over resume set) keeps the exact weight the user recorded.
 *
 * Normal sets are untouched: their stored weight is the true total either way;
 * the log screen just displays `total − base`. If the exercise has no working
 * weight yet (nothing to scale a ladder from), only the base is stored.
 *
 * Pure / unit-testable.
 */
internal fun applyBaseResistance(
    exercise: ActiveExercise,
    baseResistanceKg: Float,
    bodyweightKg: Float = 0f
): ActiveExercise {
    val base = baseResistanceKg.coerceAtLeast(0f)
    val withBase = exercise.copy(baseResistanceKg = base)

    val workingTotal = exercise.sets
        .firstOrNull { it.setType == SetType.NORMAL }?.weightKg
        ?.takeIf { it > 0f }
        ?: return withBase

    val ladder = suggestWarmupSets(
        primaryMuscleGroup = exercise.primaryMuscleGroup,
        equipment = exercise.equipment,
        workingWeightKg = workingTotal,
        normalSetCount = exercise.sets.count { it.setType == SetType.NORMAL },
        exerciseTemplateId = exercise.exerciseTemplateId,
        bodyweightKg = bodyweightKg,
        baseResistanceKg = base,
    )
    if (ladder.isEmpty()) return withBase

    var warmupSlot = 0
    val resets = withBase.sets.map { s ->
        if (s.setType != SetType.WARMUP) return@map s
        val target = ladder.getOrNull(warmupSlot)?.weightKg
        warmupSlot++
        if (s.completed || s.locked || target == null) s else s.copy(weightKg = target)
    }
    return withBase.copy(sets = resets)
}
