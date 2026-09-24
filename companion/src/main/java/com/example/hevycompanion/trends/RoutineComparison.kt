package com.example.hevycompanion.trends

import com.example.hevycompanion.recents.RecentsFormat
import com.example.hevycompanion.recents.SubstitutionMap
import kotlin.math.abs

/**
 * Compares two workouts of the same routine and attributes the difference in
 * volume / sets / reps to the individual exercises that caused it.
 *
 * The hard part is pairing: two sessions of the same routine rarely log the
 * same exercise list, because a machine was busy and something got swapped.
 * A naive template-id join would report the swapped-out exercise as a total
 * loss and the swapped-in one as free volume, which is exactly the noise that
 * makes a routine's numbers unreadable. So pairing runs in passes, mirroring
 * the completeness engine ([com.example.hevycompanion.recents.WorkoutCompletion]):
 *
 *  1. **Exact** — same `exercise_template_id` in both sessions → [Kind.SAME].
 *  2. **Swap** — an unpaired exercise on each side that belong to the same
 *     curated [SubstitutionMap] group are paired as [Kind.SWAPPED]; the row
 *     names both so "−180 kg" reads as "because Cable took Dumbbell's place",
 *     not as vanished work.
 *  3. **Leftovers** — still-unpaired current exercises are [Kind.ADDED]
 *     (all of their volume is new), still-unpaired previous ones are
 *     [Kind.DROPPED] (all of theirs is gone).
 *
 * Pure and fully unit-tested (`RoutineComparisonTest`).
 */
object RoutineComparison {

    enum class Kind { SAME, SWAPPED, ADDED, DROPPED }

    /** One exercise's contribution to the workout-level delta. */
    data class ExerciseDelta(
        val templateId: String,
        /** What was done *this* session — or, for [Kind.DROPPED], last session. */
        val title: String,
        /** The exercise this one stood in for ([Kind.SWAPPED] only). */
        val previousTemplateId: String? = null,
        val previousTitle: String? = null,
        val kind: Kind,
        val current: Totals,
        val previous: Totals,
        /** Set-by-set lines, the evidence behind the numbers. */
        val currentSetLines: List<String> = emptyList(),
        val previousSetLines: List<String> = emptyList(),
    ) {
        val volumeDeltaKg: Double get() = current.volumeKg - previous.volumeKg
        val setsDelta: Int get() = current.sets - previous.sets
        val repsDelta: Int get() = current.reps - previous.reps

        val isUnchanged: Boolean
            get() = abs(volumeDeltaKg) < VOLUME_EPSILON && setsDelta == 0 && repsDelta == 0
    }

    /** A selected workout, its predecessor in the same routine, and why they differ. */
    data class Comparison(
        val current: TrendWorkout,
        /** Null when [current] is the oldest workout in the window — then the
         *  deltas below are simply the workout's own composition (a baseline,
         *  not a change), and callers render them without delta chrome. */
        val previous: TrendWorkout?,
        val currentTotals: Totals,
        val previousTotals: Totals?,
        /** Biggest movers first; unchanged exercises settle at the bottom. */
        val deltas: List<ExerciseDelta>,
    ) {
        val volumeDeltaKg: Double? get() = previousTotals?.let { currentTotals.volumeKg - it.volumeKg }
        val setsDelta: Int? get() = previousTotals?.let { currentTotals.sets - it.sets }
        val repsDelta: Int? get() = previousTotals?.let { currentTotals.reps - it.reps }
    }

    /** Volume deltas below this (kg) are float noise, not a change. */
    private const val VOLUME_EPSILON = 0.001

    /** Average-load changes below this (kg) aren't worth naming as a cause. */
    private const val AVG_LOAD_EPSILON = 0.05

    fun compare(
        current: TrendWorkout,
        previous: TrendWorkout?,
        scope: SetScope,
        rule: VolumeRule,
    ): Comparison {
        val cur = mergeDuplicates(current.exercises)
        val prev = mergeDuplicates(previous?.exercises.orEmpty())

        val prevByTemplate = prev.associateBy { it.templateId }
        val pairedPrev = mutableSetOf<String>()
        val out = mutableListOf<ExerciseDelta>()

        // ── Pass 1: exact template matches ───────────────────────────────────
        val unpairedCurrent = mutableListOf<TrendExercise>()
        cur.forEach { c ->
            val p = prevByTemplate[c.templateId]
            if (p == null) {
                unpairedCurrent += c
            } else {
                pairedPrev += p.templateId
                out += delta(c, p, Kind.SAME, scope, rule)
            }
        }

        // ── Pass 2: swaps within a curated substitution group ────────────────
        val remainingPrev = prev.filter { it.templateId !in pairedPrev }.toMutableList()
        unpairedCurrent.forEach { c ->
            val group = SubstitutionMap.groupOf(c.templateId)
            val match = group?.let { g ->
                remainingPrev.firstOrNull { SubstitutionMap.groupOf(it.templateId) == g }
            }
            if (match != null) {
                remainingPrev -= match
                out += delta(c, match, Kind.SWAPPED, scope, rule)
            } else {
                out += delta(c, null, Kind.ADDED, scope, rule)
            }
        }

        // ── Pass 3: exercises that only the previous session had ─────────────
        remainingPrev.forEach { p ->
            out += ExerciseDelta(
                templateId = p.templateId,
                title = p.title,
                kind = Kind.DROPPED,
                current = Totals.ZERO,
                previous = WorkoutTotals.of(p, scope, rule),
                currentSetLines = emptyList(),
                previousSetLines = WorkoutTotals.setLines(p, scope),
            )
        }

        return Comparison(
            current = current,
            previous = previous,
            currentTotals = WorkoutTotals.of(current, scope, rule),
            previousTotals = previous?.let { WorkoutTotals.of(it, scope, rule) },
            deltas = out.sortedWith(BY_IMPACT),
        )
    }

    /**
     * Why this exercise's numbers moved, as short phrases. Deliberately
     * descriptive rather than a synthetic attribution split: volume is
     * `Σ weight × reps`, which doesn't decompose into independent "caused by
     * weight" / "caused by reps" terms, and a made-up split would read
     * authoritative while being arbitrary. Naming which inputs moved — plus
     * the set lines on the row — lets the numbers be checked by hand.
     */
    fun causes(delta: ExerciseDelta): List<String> {
        val out = mutableListOf<String>()
        when (delta.kind) {
            Kind.ADDED -> out += "not in the previous session"
            Kind.DROPPED -> out += "not done this session"
            Kind.SWAPPED -> out += "swapped in for ${delta.previousTitle ?: "another exercise"}"
            Kind.SAME -> Unit
        }
        if (delta.kind == Kind.ADDED || delta.kind == Kind.DROPPED) return out

        if (delta.setsDelta != 0) {
            out += "sets ${delta.previous.sets} → ${delta.current.sets}"
        }
        if (delta.repsDelta != 0) {
            out += "reps ${delta.previous.reps} → ${delta.current.reps}"
        }
        val before = delta.previous.avgLoadPerRepKg
        val after = delta.current.avgLoadPerRepKg
        if (before != null && after != null && abs(after - before) >= AVG_LOAD_EPSILON) {
            out += "avg load ${RecentsFormat.kg(round1(before))} → ${RecentsFormat.kg(round1(after))}"
        }
        if (out.isEmpty()) out += "unchanged"
        return out
    }

    /** Biggest absolute volume move first, then reps, then sets, then title —
     *  so the exercises that explain the workout delta are read first, and
     *  a purely structural change (a dropped bodyweight exercise) still
     *  outranks the untouched ones. */
    private val BY_IMPACT: Comparator<ExerciseDelta> =
        compareByDescending<ExerciseDelta> { abs(it.volumeDeltaKg) }
            .thenByDescending { abs(it.repsDelta) }
            .thenByDescending { abs(it.setsDelta) }
            .thenBy { it.title }

    private fun delta(
        current: TrendExercise,
        previous: TrendExercise?,
        kind: Kind,
        scope: SetScope,
        rule: VolumeRule,
    ) = ExerciseDelta(
        templateId = current.templateId,
        title = current.title,
        previousTemplateId = previous?.templateId?.takeIf { kind == Kind.SWAPPED },
        previousTitle = previous?.title?.takeIf { kind == Kind.SWAPPED },
        kind = kind,
        current = WorkoutTotals.of(current, scope, rule),
        previous = previous?.let { WorkoutTotals.of(it, scope, rule) } ?: Totals.ZERO,
        currentSetLines = WorkoutTotals.setLines(current, scope),
        previousSetLines = previous?.let { WorkoutTotals.setLines(it, scope) }.orEmpty(),
    )

    /**
     * Hevy allows the same exercise to appear twice in one workout (a slot
     * revisited after a superset). Fold those into one entry first, otherwise
     * the second occurrence would look like an unpaired ADDED exercise and
     * inflate both sides of the comparison.
     */
    internal fun mergeDuplicates(exercises: List<TrendExercise>): List<TrendExercise> {
        if (exercises.size < 2) return exercises
        val merged = LinkedHashMap<String, TrendExercise>()
        exercises.forEach { e ->
            val existing = merged[e.templateId]
            merged[e.templateId] = if (existing == null) e
            else existing.copy(sets = existing.sets + e.sets)
        }
        return merged.values.toList()
    }

    private fun round1(v: Double): Float = (Math.round(v * 10.0) / 10.0).toFloat()
}
