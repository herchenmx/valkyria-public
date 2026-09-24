package com.example.hevywatch.presentation.workout

import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.data.SimilarExerciseSuggestion
import com.example.hevywatch.data.SubstitutionMap
import com.example.hevywatch.data.SuggestedWeight
import com.example.hevywatch.data.WorkoutDataLoader
import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.SetType
import java.util.UUID

/**
 * R1 — exercise-history fetch + pre-fill + progressive-overload + similar-
 * exercise suggestion + warmup advisor pipeline, extracted from
 * LogWorkoutViewModel. Each step is a pure transform over the working
 * [ActiveWorkout] and the [HevyApp] caches it reads from, so the steps can
 * be tested independently.
 *
 * The pipeline runs once at workout start (or on each VM init for crash-
 * recovered workouts) and produces a [LoadedWorkout] whose components the VM
 * pushes into its own state.
 */
object WorkoutHistoryApplier {

    data class LoadedWorkout(
        val workout: ActiveWorkout,
        val weightIncreasedExercises: Set<String>,
        val exerciseSuggestedWeights: Map<String, SuggestedWeight>,
        /** When non-empty, the VM should surface the warmup-advisor confirm
         *  dialog with these overrides. The workout in [workout] still has
         *  its prescribed warmups untouched; on user confirm, callers swap
         *  per the override list. */
        val pendingWarmupOverrides: Map<Int, List<ActiveSet>>,
    )

    /**
     * Apply previous-session hints to every exercise. For non-PO routines,
     * also pre-fill the working weight from the last logged session.
     *
     * B1 invariant: never overwrite a completed set's data, and never overwrite
     * a weight the user already entered (e.g. crash recovery).
     */
    internal fun applyHistoryHints(
        workout: ActiveWorkout,
        historyMap: Map<String, ExerciseHistoryResponse>,
    ): ActiveWorkout {
        val updated = workout.exercises.map { exercise ->
            val response = historyMap[exercise.exerciseTemplateId] ?: return@map exercise
            val entries = response.exerciseHistory.orEmpty()
            val mostRecentWorkoutId = entries.firstOrNull()?.workoutId ?: return@map exercise
            val prevSets = entries.filter { it.workoutId == mostRecentWorkoutId }
            exercise.copy(
                sets = exercise.sets.mapIndexed { setIdx, activeSet ->
                    val entry = prevSets.getOrNull(setIdx) ?: return@mapIndexed activeSet
                    if (workout.progressiveOverload) {
                        // PO path: previous hint only, weights handled separately
                        activeSet.copy(previous = formatPrevious(entry))
                    } else {
                        if (activeSet.completed || activeSet.weightKg != null) {
                            activeSet.copy(previous = formatPrevious(entry))
                        } else {
                            activeSet.copy(
                                previous = formatPrevious(entry),
                                weightKg = entry.weightKg,
                            )
                        }
                    }
                }
            )
        }
        return workout.copy(exercises = updated)
    }

    /** Apply progressive-overload decisions (PO routines only). Returns the
     *  updated workout and the set of template ids whose weight was raised. */
    internal fun applyProgressiveOverload(
        workout: ActiveWorkout,
        historyMap: Map<String, ExerciseHistoryResponse>,
        bodyweightKg: Float,
    ): Pair<ActiveWorkout, Set<String>> {
        if (!workout.progressiveOverload) return workout to emptySet()
        val (poExercises, increased) = computeProgressiveOverload(
            workout.exercises, historyMap, bodyweightKg = bodyweightKg,
        )
        return workout.copy(exercises = poExercises) to increased
    }

    /** For exercises with no history, pre-populate normal-set weights from
     *  a similar exercise (same equipment + primary muscle group). Returns
     *  the updated workout plus the map of suggestions for the chip badge. */
    internal fun applySimilarSuggestions(
        workout: ActiveWorkout,
        hevyApp: HevyApp,
    ): Pair<ActiveWorkout, Map<String, SuggestedWeight>> {
        val suggestions = mutableMapOf<String, SuggestedWeight>()
        val updated = workout.exercises.map { exercise ->
            if (!exerciseNeedsSimilarSuggestion(exercise)) return@map exercise
            val suggestion = SimilarExerciseSuggestion.find(exercise.exerciseTemplateId, hevyApp)
                ?: return@map exercise
            suggestions[exercise.exerciseTemplateId] = suggestion
            applySimilarSuggestion(exercise, suggestion)
        }
        val nextWorkout = if (suggestions.isNotEmpty()) workout.copy(exercises = updated) else workout
        return nextWorkout to suggestions.toMap()
    }

    /** Inject warmup advisor sets where the exercise has no warmup yet, and
     *  collect overrides for exercises that already had prescribed warmups
     *  (the VM surfaces a confirm prompt for those). */
    internal fun applyWarmupAdvisor(
        workout: ActiveWorkout,
        bodyweightKg: Float,
    ): Pair<ActiveWorkout, Map<Int, List<ActiveSet>>> {
        if (workout.warmupAdvisorApplied) return workout to emptyMap()

        val advisedExercises = workout.exercises.toMutableList()
        val overrides = mutableMapOf<Int, List<ActiveSet>>()
        advisedExercises.forEachIndexed { idx, exercise ->
            val hasCompleted = exercise.sets.any { it.completed }
            val workingWeight = exercise.sets
                .firstOrNull { it.setType == SetType.NORMAL }?.weightKg
                ?: return@forEachIndexed
            val normalCount = exercise.sets.count { it.setType == SetType.NORMAL }
            val suggested = suggestWarmupSets(
                primaryMuscleGroup = exercise.primaryMuscleGroup,
                equipment = exercise.equipment,
                workingWeightKg = workingWeight,
                normalSetCount = normalCount,
                exerciseTemplateId = exercise.exerciseTemplateId,
                bodyweightKg = bodyweightKg,
            )
            if (suggested.isEmpty()) return@forEachIndexed
            val existingWarmups = exercise.sets.filter { it.setType == SetType.WARMUP }
            when {
                // Resumed/continued exercise (carries locked, completed sets):
                // don't re-prescribe — just APPEND the still-missing advised
                // warmups as fresh editable sets so the user can log the ones they
                // skipped. Completed data stays untouched; any warmup left
                // unlogged is dropped at Finish like any other uncompleted set.
                // New sets go at the end to preserve the resume merge's "new sets
                // follow the originals" invariant (see WorkoutRequestBuilder).
                hasCompleted -> {
                    val missing = suggested.drop(existingWarmups.size)
                    if (missing.isNotEmpty()) {
                        advisedExercises[idx] = exercise.copy(sets = exercise.sets + missing)
                    }
                }
                // Fresh exercise, no warmups yet → prepend the advised warmups.
                existingWarmups.isEmpty() -> advisedExercises[idx] = exercise.copy(sets = suggested + exercise.sets)
                // Fresh exercise that already had prescribed warmups → offer the
                // advisor's set via a confirm prompt instead of forcing it.
                else -> overrides[idx] = suggested
            }
        }
        // If no override prompt is needed, the advisor's decision is final now;
        // otherwise the VM defers marking-applied until the user decides.
        val hasPendingPrompt = overrides.isNotEmpty()
        val nextWorkout = workout.copy(
            exercises = advisedExercises,
            warmupAdvisorApplied = !hasPendingPrompt,
        )
        return nextWorkout to overrides.toMap()
    }

    /** Full pipeline: history fetch → equipment stamp → hints/pre-fill → PO
     *  → similar suggestions → warmup advisor. Suspending because the steps
     *  hit the network through [WorkoutDataLoader]. */
    suspend fun load(workout: ActiveWorkout, hevyApp: HevyApp): LoadedWorkout {
        val templateIds = workout.exercises.map { it.exerciseTemplateId }
        val rawHistoryMap = WorkoutDataLoader.fetchExerciseHistory(templateIds, hevyApp)
        WorkoutDataLoader.fetchExerciseTemplates(templateIds, hevyApp)

        // On a resumed workout, the in-progress session is already on the
        // server (it was POST'd as incomplete before the user closed it), so
        // /exercise_history returns it as the *most recent* entry for every
        // exercise it touched. For an exercise where the user hadn't yet
        // logged a normal set, those entries are either empty or warmup-only —
        // and computeProgressiveOverload bails out when historyNormal of the
        // most-recent workout is empty, leaving the remaining sets with no
        // suggested weight. Strip the in-progress workout out of the map so
        // PO + previous-hint resolve against the prior *completed* session.
        val historyMap = filterOutContinuingWorkout(rawHistoryMap, workout.continuingWorkoutId)

        // Stamp equipment + muscle group onto each exercise from the session cache.
        val stamped = workout.copy(
            exercises = workout.exercises.map { exercise ->
                exercise.copy(
                    equipment = hevyApp.exerciseEquipment[exercise.exerciseTemplateId],
                    primaryMuscleGroup = hevyApp.exerciseMuscleGroup[exercise.exerciseTemplateId],
                )
            }
        )

        // 1. previous hints + non-PO pre-fill
        val withHints = applyHistoryHints(stamped, historyMap)

        // 2. progressive overload (PO only)
        val (afterPo, increased) = applyProgressiveOverload(
            withHints, historyMap, hevyApp.bodyweightKg,
        )

        // 3. similar-exercise suggestions for never-worked exercises. MUST
        //    run before the warmup advisor so percentages are derived from
        //    the suggested working weight.
        WorkoutDataLoader.prefetchSimilarExerciseHistory(
            afterPo.exercises.map { it.exerciseTemplateId }, hevyApp,
        )
        val (afterSimilar, suggestions) = applySimilarSuggestions(afterPo, hevyApp)

        // 4. warmup advisor
        val (afterAdvisor, overrides) = applyWarmupAdvisor(afterSimilar, hevyApp.bodyweightKg)

        return LoadedWorkout(
            workout = afterAdvisor,
            weightIncreasedExercises = increased,
            exerciseSuggestedWeights = suggestions,
            pendingWarmupOverrides = overrides,
        )
    }

    /** Strip entries whose `workoutId` matches [continuingWorkoutId] from every
     *  history response in [map]. When [continuingWorkoutId] is null, returns
     *  the input unchanged. Responses that become empty after filtering still
     *  appear in the result map (with `exerciseHistory = emptyList()`) so the
     *  downstream "no history" code paths trigger correctly. */
    internal fun filterOutContinuingWorkout(
        map: Map<String, ExerciseHistoryResponse>,
        continuingWorkoutId: String?,
    ): Map<String, ExerciseHistoryResponse> {
        if (continuingWorkoutId == null) return map
        return map.mapValues { (_, response) ->
            val entries = response.exerciseHistory.orEmpty()
            val filtered = entries.filter { it.workoutId != continuingWorkoutId }
            if (filtered.size == entries.size) response
            else response.copy(exerciseHistory = filtered)
        }
    }

    private fun formatPrevious(entry: ExerciseHistoryEntry): String? {
        val parts = mutableListOf<String>()
        entry.weightKg?.let { if (it > 0f) parts.add("%.1fkg".format(it)) }
        entry.reps?.let { if (it > 0) parts.add("$it") }
        entry.durationSeconds?.let { if (it > 0) parts.add("${it}s") }
        entry.distanceMeters?.let { if (it > 0f) parts.add("%.0fm".format(it)) }
        return parts.ifEmpty { null }?.joinToString(" × ")
    }

    // ── In-workout substitution ─────────────────────────────────────────────

    /** A swap option for the in-workout Swap Exercise screen: a substitute
     *  exercise with its own PO target already computed, plus a ready-to-splice
     *  [ActiveExercise] (warmup-advisor sets included) so selecting it is a
     *  pure, synchronous replace. */
    data class SwapCandidate(
        val templateId: String,
        val title: String,
        /** First normal-set target weight, or null if no data could be derived. */
        val targetWeightKg: Float?,
        val source: Source,
        /** Non-null only when [source] is [Source.SIMILAR] — carries the badge. */
        val suggestedWeight: SuggestedWeight?,
        /** Last-session line for the substitute, e.g. "8.0kg × 15", or null. */
        val previous: String?,
        val preparedExercise: ActiveExercise,
    ) {
        enum class Source { PROGRESSIVE_OVERLOAD, LAST_SESSION, SIMILAR, NONE }
    }

    /**
     * Build the swap options for [prescribed] — one per acceptable substitute in
     * its [SubstitutionMap] group. Each candidate's weights/warmups are computed
     * by running the *same* full pipeline ([load]) the substitute would get if it
     * were prescribed, so the weight shown on the swap screen is exactly what the
     * exercise receives when selected.
     *
     * Histories/templates for all candidates are batch-fetched up front; the
     * per-candidate [load] calls then resolve from cache.
     */
    suspend fun prepareSwapCandidates(
        prescribed: ActiveExercise,
        progressiveOverload: Boolean,
        continuingWorkoutId: String?,
        hevyApp: HevyApp,
    ): List<SwapCandidate> {
        val subIds = SubstitutionMap.substitutesFor(prescribed.exerciseTemplateId)
        if (subIds.isEmpty()) return emptyList()

        // Batch-fetch so each per-candidate load() below hits the cache.
        WorkoutDataLoader.fetchExerciseHistory(subIds, hevyApp)
        WorkoutDataLoader.fetchExerciseTemplates(subIds, hevyApp)

        // Carry the prescribed normal-set structure (count, reps, rep range,
        // target RPE) but blank the weights + recorded state so the pipeline
        // fills them for the substitute.
        val baseSets = prescribed.sets.filter { it.setType == SetType.NORMAL }.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                weightKg = null,
                completed = false,
                completedAtMs = null,
                previous = null,
                poBaseWeightKg = null,
                isSimilarSuggestion = false,
            )
        }

        return subIds.map { subId ->
            val candidateEx = ActiveExercise(
                exerciseTemplateId = subId,
                title = SubstitutionMap.nameOf(subId) ?: subId,
                exerciseType = prescribed.exerciseType,
                sets = baseSets.map { it.copy(id = UUID.randomUUID().toString()) },
                restTimerSeconds = prescribed.restTimerSeconds,
                notes = prescribed.notes,
                equipment = hevyApp.exerciseEquipment[subId],
                primaryMuscleGroup = hevyApp.exerciseMuscleGroup[subId],
            )
            val temp = ActiveWorkout(
                name = "",
                exercises = listOf(candidateEx),
                progressiveOverload = progressiveOverload,
                continuingWorkoutId = continuingWorkoutId,
                warmupAdvisorApplied = false,
            )
            val loaded = load(temp, hevyApp)
            val ex = loaded.workout.exercises.first()
            val firstNormal = ex.sets.firstOrNull { it.setType == SetType.NORMAL }
            val target = firstNormal?.weightKg
            val suggested = loaded.exerciseSuggestedWeights[subId]
            val source = when {
                subId in loaded.weightIncreasedExercises -> SwapCandidate.Source.PROGRESSIVE_OVERLOAD
                suggested != null -> SwapCandidate.Source.SIMILAR
                target != null -> SwapCandidate.Source.LAST_SESSION
                else -> SwapCandidate.Source.NONE
            }
            SwapCandidate(
                templateId = subId,
                title = ex.title,
                targetWeightKg = target,
                source = source,
                suggestedWeight = suggested,
                previous = firstNormal?.previous,
                preparedExercise = ex,
            )
        }
            // Order by recency of use: the substitutes you've done most recently
            // come first, never-done ones last (Long.MIN_VALUE). The batch
            // fetchExerciseHistory above populated the cache these timestamps read.
            // sortedByDescending is stable, so same-recency ties keep the curated
            // group order.
            .sortedByDescending { lastUsedEpochMs(it.templateId, hevyApp) }
    }

    /** Most recent session timestamp (epoch ms) this exercise was logged, from
     *  the history cache; [Long.MIN_VALUE] when never logged (sorts last). */
    private fun lastUsedEpochMs(templateId: String, hevyApp: HevyApp): Long =
        lastUsedEpochMsOf(hevyApp.exerciseHistoryCache[templateId]?.exerciseHistory.orEmpty())

    /** Max session timestamp (epoch ms) across [entries], or [Long.MIN_VALUE]
     *  when none parse — the recency sort key for swap candidates. */
    internal fun lastUsedEpochMsOf(entries: List<ExerciseHistoryEntry>): Long =
        entries.mapNotNull { RoutineProgressComputer.parseInstant(it.workoutStartTime)?.toEpochMilli() }
            .maxOrNull() ?: Long.MIN_VALUE
}
