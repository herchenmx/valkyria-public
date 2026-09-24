package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.RoutineDetail
import com.example.hevycompanion.data.WorkoutDetail
import com.example.hevycompanion.data.WorkoutDetailExercise

/**
 * Result of comparing one routine slot (or an unprescribed extra) against what
 * the user actually logged. Mirrors the watch's
 * `com.example.hevywatch.data.model.ExerciseCompletionStatus`.
 */
data class ExerciseCompletionStatus(
    /** Exercise name actually logged (the substitute's name when SUBSTITUTED). */
    val title: String,
    val exerciseTemplateId: String,
    val prescribedNormalSets: Int,
    val recordedNormalSets: Int,
    val recordedWarmupSets: Int,
    val status: Status,
    /** Routine slot's name when this row stood in for it (SUBSTITUTED only). */
    val prescribedTitle: String? = null,
    /** Warmup sets the advisor would prescribe at this session's working weight. */
    val expectedWarmupSets: Int = 0,
    /** Working weight actually logged this session (first normal set), or null
     *  when nothing was logged. Shown on the chip once ≥1 normal set exists;
     *  before that the chip shows the PO target (from advice). */
    val loggedWorkingWeightKg: Float? = null
) {
    enum class Status { COMPLETE, SUBSTITUTED, INCOMPLETE, MISSING, EXTRA }

    /** Fully done iff every prescribed normal set AND every advised warmup set
     *  was logged. Drives the Workout Detail row colour (completion, not swap). */
    val isComplete: Boolean
        get() = recordedNormalSets >= prescribedNormalSets &&
            recordedWarmupSets >= expectedWarmupSets
}

/**
 * Workout-vs-routine completeness, ported from the watch's
 * `WorkoutDetailViewModel.buildCompletionStatuses` so the companion's Workout
 * Detail screen reports the same COMPLETE / INCOMPLETE / MISSING / SUBSTITUTED /
 * EXTRA breakdown.
 */
object WorkoutCompletion {

    /**
     * Build completion statuses by comparing workout exercises against the
     * routine prescription, in passes so exact matches always win over
     * substitutions:
     *  1. **Exact** — each routine slot claims the workout exercise with the
     *     same template ID (COMPLETE / INCOMPLETE / MISSING).
     *  2. **Substitution** (PO folders only) — a still-MISSING slot whose
     *     exercise is in a [SubstitutionMap] group claims any *unclaimed*
     *     workout exercise from the same group (preferring one with logged normal
     *     sets, but falling back to a warmup-only in-progress swap), marked
     *     SUBSTITUTED with [ExerciseCompletionStatus.prescribedTitle] naming the
     *     slot it filled.
     *  3. **Extras** (PO folders only) — workout exercises left unclaimed and
     *     not themselves a prescribed slot render as EXTRA.
     *
     * @param poFolderIds folder IDs (as strings) for which substitution + extras
     *        apply. Pass empty to disable both and get the plain exact-match
     *        comparison.
     */
    fun buildCompletionStatuses(
        workout: WorkoutDetail,
        routine: RoutineDetail,
        poFolderIds: Set<String> = emptySet(),
        meta: Map<String, ExerciseTemplate> = emptyMap(),
        bodyweightKg: Float = 0f,
        // templateId → planned PO target weight (prior sessions, the weight the
        // live warmup advisor ramped to). Drives the warmup expectation so it's
        // judged against the target, not the heavier-than-planned weight lifted
        // this session. Empty → falls back to the logged weight (initial render,
        // before the async advice lands).
        poTargetByTemplate: Map<String, Float?> = emptyMap()
    ): List<ExerciseCompletionStatus> {
        val inScope = routine.folderId != null && routine.folderId.toString() in poFolderIds
        val workoutByTemplate = workout.exercises.associateBy { it.exerciseTemplateId }
        val prescribedIds = routine.exercises.map { it.exerciseTemplateId }.toSet()
        // Workout-exercise template IDs already used to satisfy a slot (exact or
        // substitute), so one logged exercise can't fill two slots.
        val claimed = mutableSetOf<String>()

        // ── Pass 1: exact matches ────────────────────────────────────────────
        val exactStatuses = routine.exercises.map { routineEx ->
            val templateId = routineEx.exerciseTemplateId
            val prescribedNormal = routineEx.sets.count { it.type == "normal" }
            val workoutEx = workoutByTemplate[templateId]?.takeIf { templateId !in claimed }
            if (workoutEx != null) claimed += templateId
            val recordedNormal = workoutEx?.sets?.count { it.type == "normal" } ?: 0
            val recordedWarmup = workoutEx?.sets?.count { it.type == "warmup" } ?: 0
            val status = when {
                workoutEx == null -> ExerciseCompletionStatus.Status.MISSING
                recordedNormal >= prescribedNormal -> ExerciseCompletionStatus.Status.COMPLETE
                recordedNormal > 0 -> ExerciseCompletionStatus.Status.INCOMPLETE
                else -> ExerciseCompletionStatus.Status.MISSING
            }
            ExerciseCompletionStatus(
                title = routineEx.title ?: templateId,
                exerciseTemplateId = templateId,
                prescribedNormalSets = prescribedNormal,
                recordedNormalSets = recordedNormal,
                recordedWarmupSets = recordedWarmup,
                status = status,
                expectedWarmupSets = expectedWarmupSetsForCompletion(
                    templateId, prescribedNormal,
                    poTargetByTemplate[templateId], recordedWarmup,
                    meta, bodyweightKg
                ),
                loggedWorkingWeightKg = firstNormalWeight(workoutEx)
            )
        }

        // ── Pass 2: substitutions for still-MISSING slots ────────────────────
        val slotStatuses = exactStatuses.mapIndexed { i, slot ->
            if (!inScope || slot.status != ExerciseCompletionStatus.Status.MISSING) return@mapIndexed slot
            val group = SubstitutionMap.groupOf(slot.exerciseTemplateId) ?: return@mapIndexed slot
            val candidates = workout.exercises.filter { we ->
                we.exerciseTemplateId !in claimed &&
                    SubstitutionMap.groupOf(we.exerciseTemplateId) == group
            }
            // Prefer a substitute with logged work, but still claim an in-progress
            // swap that only has warmups so far. Otherwise a swapped-in exercise
            // with no normal sets yet would leave its slot MISSING and surface
            // itself as EXTRA in Pass 3 (the "warmups-only swap reads as extra"
            // bug); here it fills the slot as an INCOMPLETE SUBSTITUTED row.
            val sub = candidates.firstOrNull { it.sets.any { s -> s.type == "normal" } }
                ?: candidates.firstOrNull()
                ?: return@mapIndexed slot
            claimed += sub.exerciseTemplateId
            val subRecordedWarmup = sub.sets.count { it.type == "warmup" }
            ExerciseCompletionStatus(
                title = sub.title ?: sub.exerciseTemplateId,
                exerciseTemplateId = sub.exerciseTemplateId,
                prescribedNormalSets = slot.prescribedNormalSets,
                recordedNormalSets = sub.sets.count { it.type == "normal" },
                recordedWarmupSets = subRecordedWarmup,
                status = ExerciseCompletionStatus.Status.SUBSTITUTED,
                prescribedTitle = routine.exercises[i].title ?: slot.exerciseTemplateId,
                // No PO target for off-routine substitutes → no planned weight to
                // judge warmups against. Hold the swap to the warmups it recorded
                // rather than recomputing from the logged weight (which would
                // retroactively demand more after a heavy set).
                expectedWarmupSets = expectedWarmupSetsForCompletion(
                    sub.exerciseTemplateId, slot.prescribedNormalSets,
                    poTargetByTemplate[sub.exerciseTemplateId], subRecordedWarmup,
                    meta, bodyweightKg
                ),
                loggedWorkingWeightKg = firstNormalWeight(sub)
            )
        }

        // ── Pass 3: extras (logged, not a prescribed slot, never claimed) ─────
        val extras = if (!inScope) emptyList() else workout.exercises
            .filter { it.exerciseTemplateId !in claimed && it.exerciseTemplateId !in prescribedIds }
            .map { we ->
                val recordedNormal = we.sets.count { it.type == "normal" }
                val recordedWarmup = we.sets.count { it.type == "warmup" }
                ExerciseCompletionStatus(
                    title = we.title ?: we.exerciseTemplateId,
                    exerciseTemplateId = we.exerciseTemplateId,
                    prescribedNormalSets = 0,
                    recordedNormalSets = recordedNormal,
                    recordedWarmupSets = recordedWarmup,
                    status = ExerciseCompletionStatus.Status.EXTRA,
                    expectedWarmupSets = expectedWarmupSetsForCompletion(
                        we.exerciseTemplateId, recordedNormal,
                        poTargetByTemplate[we.exerciseTemplateId], recordedWarmup,
                        meta, bodyweightKg
                    ),
                    loggedWorkingWeightKg = firstNormalWeight(we)
                )
            }

        // COMPLETE → SUBSTITUTED → INCOMPLETE → MISSING → EXTRA (enum order)
        return (slotStatuses + extras).sortedWith(compareBy { it.status.ordinal })
    }

    /**
     * How many warmup sets the [WarmupAdvisor] would prescribe for [exercise] at
     * this session's logged working weight (the first logged normal set's weight;
     * the advisor converts via bodyweight for assisted-bodyweight moves). Returns
     * 0 when there's no logged normal set, no working weight, or no catalog
     * metadata — so a row is never falsely marked incomplete for warmups we can't
     * assess.
     */
    /** First logged normal set's weight for [exercise], or null. */
    private fun firstNormalWeight(exercise: WorkoutDetailExercise?): Float? =
        exercise?.sets?.firstOrNull { it.type == "normal" }?.weightKg

    /**
     * How many warmup sets the advisor would prescribe for [templateId] at
     * [workingWeightKg] — the **planned target** weight, not the weight lifted
     * (see `poTargetByTemplate`). Returns 0 when there's no working weight or
     * metadata, so a row is never falsely marked incomplete.
     */
    private fun expectedWarmupsFor(
        templateId: String,
        normalSetCount: Int,
        workingWeightKg: Float?,
        meta: Map<String, ExerciseTemplate>,
        bodyweightKg: Float
    ): Int {
        val workingWeight = workingWeightKg ?: return 0
        val m = meta[templateId]
        return WarmupAdvisor.suggest(
            primaryMuscleGroup = m?.primaryMuscleGroup,
            equipment = m?.equipment,
            workingWeightKg = workingWeight,
            normalSetCount = normalSetCount,
            exerciseTemplateId = templateId,
            bodyweightKg = bodyweightKg
        ).size
    }

    /**
     * Warmup count an already-logged exercise is held to for [isComplete].
     * Ported from the watch's `WorkoutDetailViewModel.expectedWarmupSetsForCompletion`.
     *
     * With a prior-session [poTargetKg] (non-null) warmups are judged against the
     * **planned** weight, so a heavier-than-planned working set can't inflate the
     * requirement. With NO PO target the only weight available is the one just
     * lifted; recomputing from it would retroactively demand more warmups than
     * were advised when the set was logged (the adductor-at-50kg false-"incomplete"
     * bug — the heavier weight only raises *next* session's target). So without a
     * plan we hold the exercise to exactly the warmups it recorded.
     *
     * NOTE: [poTargetByTemplate] is empty on the companion's initial render
     * (before the async advice lands), so this also stops a transient false
     * "incomplete" flash while advice is loading.
     */
    private fun expectedWarmupSetsForCompletion(
        templateId: String,
        normalSetCount: Int,
        poTargetKg: Float?,
        recordedWarmupSets: Int,
        meta: Map<String, ExerciseTemplate>,
        bodyweightKg: Float
    ): Int =
        if (poTargetKg != null)
            expectedWarmupsFor(templateId, normalSetCount, poTargetKg, meta, bodyweightKg)
        else
            recordedWarmupSets
}
