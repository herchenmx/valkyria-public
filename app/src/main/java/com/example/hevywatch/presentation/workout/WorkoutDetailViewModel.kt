package com.example.hevywatch.presentation.workout

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import com.example.hevywatch.data.api.model.RoutineResponse
import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.api.model.WorkoutExerciseResponse
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.ExerciseCompletionStatus
import com.example.hevywatch.data.model.ExerciseCompletionStatus.Status
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.data.SubstitutionMap
import com.example.hevywatch.data.WorkoutDataLoader
import com.example.hevywatch.data.model.toActiveWorkout
import com.example.hevywatch.data.model.toDomain
import com.example.hevywatch.data.withNetworkRetry
import com.example.hevywatch.presentation.navigation.Screen
import kotlinx.coroutines.launch
import java.time.Instant

class WorkoutDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val hevyApp get() = getApplication<HevyApp>()

    var isLoading by mutableStateOf(true)
        private set
    /** True while a user-tapped Refresh chip is in flight. Distinct from
     *  [isLoading] (the initial-load spinner). */
    var isRefreshing by mutableStateOf(false)
        private set
    var workout by mutableStateOf<WorkoutDetailResponse?>(null)
        private set
    var exerciseStatuses by mutableStateOf<List<ExerciseCompletionStatus>>(emptyList())
        private set
    /** True if this workout has a routine and is missing normal sets. */
    var canContinue by mutableStateOf(false)
        private set
    var navigateTo by mutableStateOf<String?>(null)
        private set

    private var routineResponse: RoutineResponse? = null
    private var currentWorkoutId: String? = null

    fun load(workoutId: String) {
        currentWorkoutId = workoutId
        isLoading = true
        viewModelScope.launch {
            fetchData(workoutId)
            isLoading = false
        }
    }

    /**
     * User-triggered refresh. Drops cached exercise history for the workout's
     * exercises so [WorkoutDataLoader.fetchExerciseHistory] re-fetches, then
     * re-runs [fetchData] to refresh the workout detail itself. The screen
     * overlays a full-screen spinner over the still-mounted column while
     * [isRefreshing] is true, mirroring RoutineDetailScreen's refresh pattern.
     */
    fun refresh() {
        val workoutId = currentWorkoutId ?: return
        viewModelScope.launch {
            isRefreshing = true
            // fetchData already drops + re-fetches per-exercise history (it needs
            // fresh data for the PO target), so an explicit invalidation here
            // would be redundant.
            fetchData(workoutId)
            isRefreshing = false
        }
    }

    private suspend fun fetchData(workoutId: String) {
        try {
            val service = hevyApp.requireApiService()
            val detail = withNetworkRetry { service.getWorkoutDetail(workoutId) }
            workout = detail

            // Resolve the routine for completeness comparison
            val routineId = detail.routineId
            val routine = if (routineId != null) resolveRoutine(routineId) else null
            routineResponse = routine

            // Top up catalog metadata (equipment + muscle group, for warmup-aware
            // completeness) and exercise history up front. History is needed both
            // for tap-to-expand last-session data AND for the PO target weights
            // the chip shows on still-missing slots. Best-effort — the loaders
            // swallow failures; absent metadata just means no advised warmups.
            val ids = templateIdsForHistoryFetch()
            WorkoutDataLoader.fetchExerciseTemplates(ids, hevyApp)
            // Force-fresh history so the PO target — and thus warmup-aware
            // completeness — is computed from the SAME data the companion sees.
            // fetchExerciseHistory short-circuits on populated cache entries, so
            // viewing an older workout while newer sessions aren't cached would
            // otherwise compute a stale PO target and read a phantom
            // missing-warmup the phone (which always fetches fresh) never shows.
            ids.forEach { hevyApp.exerciseHistoryCache.remove(it) }
            WorkoutDataLoader.fetchExerciseHistory(ids, hevyApp)

            if (routine != null) {
                val statuses = buildCompletionStatuses(
                    detail,
                    routine,
                    hevyApp.progressiveOverloadStore.enabledFolderIds,
                    hevyApp.exerciseEquipment,
                    hevyApp.exerciseMuscleGroup,
                    hevyApp.bodyweightStore.bodyweightKg,
                    computePoTargets(
                        routine,
                        excludeWorkoutId = detail.id,
                        beforeStartTimeIso = detail.startTime
                    )
                )
                exerciseStatuses = statuses
                // Resumable when any prescribed/logged slot isn't fully done —
                // missing normal sets OR missing advised warmups, so you can go
                // back and log warmups you skipped. EXTRA rows are bonus work.
                canContinue = statuses.any { it.status != Status.EXTRA && !it.isComplete }
            }
            hevyApp.workoutDetailRefreshedAtMs = System.currentTimeMillis()
        } catch (_: Exception) { /* best-effort */ }
    }

    /** Template IDs for the exercises currently shown on the screen — the
     *  union covers both the "compared against routine" and "raw workout"
     *  rendering paths so either branch has cached history. */
    private fun templateIdsForHistoryFetch(): List<String> {
        val routineIds = routineResponse?.exercises?.map { it.exerciseTemplateId }.orEmpty()
        val workoutIds = workout?.exercises?.map { it.exerciseTemplateId }.orEmpty()
        return (routineIds + workoutIds).distinct()
    }

    /**
     * Progressive-overload target weight per routine exercise, computed from the
     * (already-fetched) exercise history via the same [computeProgressiveOverload]
     * the live workout uses. Returns templateId → (targetKg, isBump): the bumped
     * target when a recent session qualified, otherwise the last session's weight
     * carried forward (isBump = false). Drives the chip weight shown on slots
     * with nothing logged yet. Best-effort — exercises without history/equipment
     * simply don't appear in the map.
     */
    private fun computePoTargets(
        routine: RoutineResponse,
        excludeWorkoutId: String?,
        // Start time (ISO) of the workout being viewed. History sessions logged
        // AT OR AFTER this instant are excluded so the PO target reconstructs the
        // weight the live advisor used *when this workout was logged*, not a
        // forward-looking target inflated by LATER sessions. Null → time filter
        // skipped (id-only exclusion, the old behaviour).
        beforeStartTimeIso: String? = null
    ): Map<String, Pair<Float?, Boolean>> {
        val active = routine.toDomain(hevyApp.progressiveOverloadStore.enabledFolderIds).toActiveWorkout()
        // toActiveWorkout doesn't carry catalog metadata; the PO algorithm needs
        // equipment (its hasEquipment gate + increment) and muscle group.
        val withMeta = active.exercises.map { ex ->
            ex.copy(
                equipment = ex.equipment ?: hevyApp.exerciseEquipment[ex.exerciseTemplateId],
                primaryMuscleGroup = ex.primaryMuscleGroup ?: hevyApp.exerciseMuscleGroup[ex.exerciseTemplateId]
            )
        }
        // Reconstruct the target the live advisor used WHEN THIS WORKOUT WAS
        // LOGGED: keep only sessions strictly BEFORE the viewed workout's start,
        // and drop the viewed workout itself by id. Excluding only by id (the old
        // behaviour) left LATER, heavier sessions in the recency-weighted base, so
        // a past workout was judged against a forward-looking target — e.g. Leg
        // Press advised 3 warmups on 6 Jul but later sessions pushed the target
        // over the 80kg boundary → 4 expected → the completed row read incomplete.
        // (Excluding the viewed workout also stops an unusually heavy first set
        // this session inflating the target — the rear-delt-at-30kg case.)
        val historyMap = hevyApp.exerciseHistoryCache.mapValues { (_, resp) ->
            resp.copy(exerciseHistory = historyBefore(resp.exerciseHistory.orEmpty(), excludeWorkoutId, beforeStartTimeIso))
        }
        val (updated, increasedIds) = computeProgressiveOverload(
            withMeta, historyMap, hevyApp.bodyweightStore.bodyweightKg
        )
        return updated.associate { ex ->
            // Mirror the companion's ProgressiveOverload.compute NONE guards so
            // the two apps agree on the target: with no logged history — or a
            // most-recent session that logged no normal sets —
            // computeProgressiveOverload leaves the routine's placeholder weight
            // on the set, but the companion resolves NONE (null). Null it here
            // too so warmup completeness falls back to the logged weight on BOTH
            // apps instead of the watch judging against a phantom routine weight.
            val recentNormal = historyMap[ex.exerciseTemplateId]?.exerciseHistory.orEmpty()
                .groupBy { it.workoutId }.values.firstOrNull()
                ?.count { it.setType.equals("normal", ignoreCase = true) } ?: 0
            val target = if (recentNormal == 0) null
                else ex.sets.firstOrNull { it.setType == SetType.NORMAL }?.weightKg?.takeIf { it > 0f }
            ex.exerciseTemplateId to (target to (ex.exerciseTemplateId in increasedIds))
        }
    }

    /** Find the routine in cache, or fetch it from the API. */
    private suspend fun resolveRoutine(routineId: String): RoutineResponse? {
        // Check cached routines first
        val cached = hevyApp.cachedRoutines.firstOrNull { it.id == routineId }
        if (cached != null) {
            // We need the RoutineResponse (API model), not the domain Routine.
            // Fetch from API since we only cache domain models.
            return try {
                withNetworkRetry { hevyApp.requireApiService().getRoutineDetail(routineId).routine }
            } catch (_: Exception) { null }
        }
        return try {
            hevyApp.requireApiService().getRoutineDetail(routineId).routine
        } catch (_: Exception) { null }
    }

    /** Continue this workout: rebuild ActiveWorkout preserving ALL recorded sets (including warmups),
     *  then append remaining prescribed normal sets for incomplete exercises. */
    fun continueWorkout() {
        val workoutDetail = workout ?: return
        val routine = routineResponse ?: return

        if (hevyApp.activeWorkout != null) {
            navigateTo = Screen.LOG_WORKOUT
            return
        }

        val domainRoutine = routine.toDomain(hevyApp.progressiveOverloadStore.enabledFolderIds)
        val activeWorkout = domainRoutine.toActiveWorkout()

        // Substitution-aware reconstruction: a swapped-in exercise that already
        // has recorded sets resumes as *itself*, not the prescribed routine
        // exercise it stood in for. Mirrors buildCompletionStatuses so Resume
        // matches what Workout Detail showed. (See WorkoutDetailViewModel
        // companion buildResumeExercises.)
        val preFilledExercises = buildResumeExercises(
            workoutDetail = workoutDetail,
            activeWorkout = activeWorkout,
            poFolderIds = hevyApp.progressiveOverloadStore.enabledFolderIds,
            routineFolderId = routine.folderId,
            nowMs = System.currentTimeMillis()
        )

        // Resume-timer adjustment: slide the virtual start back by the
        // original workout's duration so WorkoutAwareTimeText reads e.g. 45:00
        // immediately on resume and ticks forward from there (instead of 00:00).
        // Both submission builders (`buildResumePostRequestV2` for the primary
        // POST+DELETE flow, `buildWorkoutPutRequest` for the v1 fallback)
        // later use the same offset to compute a new end_time
        // = original_start + (endTimeMs − adjustedStartMs).
        val adjustedStartMs = computeAdjustedStartMs(
            workoutDetail.startTime,
            workoutDetail.endTime,
            nowMs = System.currentTimeMillis()
        )
        val continued = activeWorkout.copy(
            exercises = preFilledExercises,
            continuingWorkoutId = workoutDetail.id,
            startTimeMs = adjustedStartMs
        )
        // Store the original GET response — used for screen rendering + the
        // resume-merge body builder.
        hevyApp.continuingWorkoutDetail = workoutDetail
        // Kick off the private v2 GET in the background; it carries the
        // original biometrics + unix-seconds timestamps the merged POST body
        // needs at Finish time. Best-effort: if it fails, the resume POST
        // proceeds with empty original biometrics (no HR chart loss on the
        // server because the original workout survives until DELETE — and
        // DELETE is conditional on the POST succeeding).
        // launchAppScoped, NOT viewModelScope. This request is started on the
        // detail screen but its result is needed at Finish on the log screen,
        // and the two lines below navigate away immediately. viewModelScope is
        // cancelled when this ViewModel is cleared, so the GET was being killed
        // in flight every time — and CancellationException, being an Exception,
        // was caught by the handler below and turned into "no v2 detail". The
        // resume then skipped the private POST entirely and silently fell back
        // to the public PUT, on every single resume.
        hevyApp.launchAppScoped {
            try {
                hevyApp.refreshTokenIfNeeded()
                val raw = withNetworkRetry {
                    hevyApp.requirePrivateApiService().getWorkoutPrivate(workoutDetail.id)
                }
                hevyApp.continuingWorkoutRawV2 = raw
                hevyApp.continuingWorkoutV2Error = null
                hevyApp.continuingWorkoutDetailV2 = com.example.hevywatch.util.GsonHolder.gson
                    .fromJson(raw, com.example.hevywatch.data.api.model.WorkoutDetailResponseV2::class.java)
                // Names any workout-level field Hevy returns that our DTO does
                // not model. Those are exactly the fields the old rebuild-from-
                // typed-object approach silently dropped.
                val modelled = setOf(
                    "id", "short_id", "name", "description", "start_time", "end_time",
                    "is_private", "is_biometrics_public", "wearos_watch", "apple_watch",
                    "biometrics", "exercises",
                )
                val unmodelled = raw.keySet().filterNot { it in modelled }
                android.util.Log.w(
                    "HevyResume",
                    "v2 GET keys=${raw.keySet().sorted()} unmodelled=$unmodelled"
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Distinguished rather than folded in with the rest: a
                // cancellation here means the scope was wrong, not that Hevy
                // refused anything, and the two need different fixes. Rethrown
                // so the scope's own bookkeeping stays correct.
                android.util.Log.w("HevyResume", "v2 GET CANCELLED — scope died before it finished", ce)
                hevyApp.continuingWorkoutDetailV2 = null
                hevyApp.continuingWorkoutRawV2 = null
                hevyApp.continuingWorkoutV2Error = "the fetch was cancelled"
                throw ce
            } catch (e: Exception) {
                android.util.Log.w("HevyResume", "v2 GET failed — resume will use the v1 PUT path", e)
                hevyApp.continuingWorkoutDetailV2 = null
                hevyApp.continuingWorkoutRawV2 = null
                hevyApp.continuingWorkoutV2Error =
                    (e as? retrofit2.HttpException)?.let { "Hevy returned ${it.code()}" }
                        ?: (e.message?.take(60) ?: e.javaClass.simpleName)
            }
        }
        hevyApp.startWorkout(domainRoutine)
        hevyApp.updateActiveWorkout(continued)
        navigateTo = Screen.LOG_WORKOUT
    }

    fun onNavigated() { navigateTo = null }

    companion object {
        /**
         * Compute the virtual workout-start timestamp for a resume so the on-watch
         * timer immediately reflects the original session's duration.
         *
         *   adjusted = now − (originalEnd − originalStart)
         *
         * If either timestamp is missing or unparseable (paranoid — saved
         * workouts always carry both), falls back to [nowMs] so the timer just
         * starts at 0:00 instead of crashing.
         */
        internal fun computeAdjustedStartMs(
            originalStartIso: String?,
            originalEndIso: String?,
            nowMs: Long
        ): Long {
            if (originalStartIso == null || originalEndIso == null) return nowMs
            return try {
                val startMs = Instant.parse(originalStartIso).toEpochMilli()
                val endMs = Instant.parse(originalEndIso).toEpochMilli()
                val durationMs = (endMs - startMs).coerceAtLeast(0L)
                nowMs - durationMs
            } catch (_: Exception) {
                nowMs
            }
        }

        /**
         * Build completion statuses by comparing workout exercises against the
         * routine prescription.
         *
         * Matching runs in passes so exact matches always win over substitutions:
         *  1. **Exact** — each routine slot claims the workout exercise with the
         *     same template ID (status COMPLETE / INCOMPLETE / MISSING as before).
         *  2. **Substitution** (PO folders only) — a still-MISSING slot whose
         *     exercise is in a [SubstitutionMap] group claims any *unclaimed*
         *     workout exercise from the same group (preferring one with logged
         *     normal sets, but falling back to a warmup-only in-progress swap). The
         *     row then shows the exercise actually done, status SUBSTITUTED, with
         *     [ExerciseCompletionStatus.prescribedTitle] naming the slot it filled.
         *  3. **Extras** (PO folders only) — workout exercises left unclaimed and
         *     not themselves a prescribed slot render as status EXTRA so nothing
         *     the user logged is invisible.
         *
         * @param poFolderIds folder IDs for which substitution + extras apply
         *        (Progressive-Overload folders). Outside them behaviour is the
         *        plain exact-match comparison — pass empty to disable entirely.
         */
        fun buildCompletionStatuses(
            workout: WorkoutDetailResponse,
            routine: RoutineResponse,
            poFolderIds: Set<String> = emptySet(),
            equipmentOf: Map<String, String?> = emptyMap(),
            muscleGroupOf: Map<String, String?> = emptyMap(),
            bodyweightKg: Float = 0f,
            // templateId → (PO target weight, isBump). Shown on the chip for slots
            // with nothing logged yet (see ExerciseCompletionStatus.poTargetKg).
            poByTemplate: Map<String, Pair<Float?, Boolean>> = emptyMap()
        ): List<ExerciseCompletionStatus> {
            val inScope = routine.folderId != null && routine.folderId in poFolderIds
            val workoutByTemplate = workout.exercises.associateBy { it.exerciseTemplateId }
            val prescribedIds = routine.exercises.map { it.exerciseTemplateId }.toSet()
            // Workout-exercise template IDs already used to satisfy a slot (exact
            // or substitute), so one logged exercise can't fill two slots.
            val claimed = mutableSetOf<String>()

            // ── Pass 1: exact matches ────────────────────────────────────────
            // Computed eagerly so every exact match is claimed before any
            // substitution lookup runs (otherwise a substitute could steal an
            // exercise that is itself an exact match for a later slot).
            val exactStatuses = routine.exercises.map { routineEx ->
                val templateId = routineEx.exerciseTemplateId
                val prescribedNormal = routineEx.sets.count { it.type == "normal" }
                val workoutEx = workoutByTemplate[templateId]?.takeIf { templateId !in claimed }
                if (workoutEx != null) claimed += templateId
                val recordedNormal = workoutEx?.sets?.count { it.type == "normal" } ?: 0
                val recordedWarmup = workoutEx?.sets?.count { it.type == "warmup" } ?: 0
                val status = when {
                    workoutEx == null -> Status.MISSING
                    recordedNormal >= prescribedNormal -> Status.COMPLETE
                    recordedNormal > 0 -> Status.INCOMPLETE
                    else -> Status.MISSING
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
                        poByTemplate[templateId]?.first, recordedWarmup,
                        equipmentOf, muscleGroupOf, bodyweightKg
                    ),
                    loggedWorkingWeightKg = firstNormalWeight(workoutEx),
                    poTargetKg = poByTemplate[templateId]?.first,
                    poIncreased = poByTemplate[templateId]?.second == true
                )
            }

            // ── Pass 2: substitutions for still-MISSING slots ────────────────
            val slotStatuses = exactStatuses.mapIndexed { i, slot ->
                if (!inScope || slot.status != Status.MISSING) return@mapIndexed slot
                val group = SubstitutionMap.groupOf(slot.exerciseTemplateId) ?: return@mapIndexed slot
                val candidates = workout.exercises.filter { we ->
                    we.exerciseTemplateId !in claimed &&
                        SubstitutionMap.groupOf(we.exerciseTemplateId) == group
                }
                // Prefer a substitute with logged work, but still claim an
                // in-progress swap that only has warmups so far. Otherwise a
                // swapped-in exercise with no normal sets yet would leave its slot
                // MISSING and surface itself as EXTRA in Pass 3 (the "warmups-only
                // swap reads as extra" bug); here it fills the slot as an
                // INCOMPLETE SUBSTITUTED row.
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
                    status = Status.SUBSTITUTED,
                    prescribedTitle = routine.exercises[i].title ?: slot.exerciseTemplateId,
                    // No PO target is computed for off-routine substitutes, so
                    // there's no planned weight to judge warmups against — hold the
                    // swap to exactly the warmups it recorded rather than
                    // recomputing from the logged weight (which would retroactively
                    // demand more after a heavy set; see
                    // expectedWarmupSetsForCompletion).
                    expectedWarmupSets = expectedWarmupSetsForCompletion(
                        sub.exerciseTemplateId, slot.prescribedNormalSets,
                        poByTemplate[sub.exerciseTemplateId]?.first, subRecordedWarmup,
                        equipmentOf, muscleGroupOf, bodyweightKg
                    ),
                    loggedWorkingWeightKg = firstNormalWeight(sub),
                    poTargetKg = poByTemplate[sub.exerciseTemplateId]?.first,
                    poIncreased = poByTemplate[sub.exerciseTemplateId]?.second == true
                )
            }

            // ── Pass 3: extras (logged, not a prescribed slot, never claimed) ─
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
                        status = Status.EXTRA,
                        expectedWarmupSets = expectedWarmupSetsForCompletion(
                            we.exerciseTemplateId, recordedNormal,
                            poByTemplate[we.exerciseTemplateId]?.first, recordedWarmup,
                            equipmentOf, muscleGroupOf, bodyweightKg
                        ),
                        loggedWorkingWeightKg = firstNormalWeight(we)
                    )
                }

            // COMPLETE → SUBSTITUTED → INCOMPLETE → MISSING → EXTRA (enum order)
            return (slotStatuses + extras).sortedWith(compareBy { it.status.ordinal })
        }

        /** First logged normal set's weight for [exercise], or null. */
        private fun firstNormalWeight(exercise: WorkoutExerciseResponse?): Float? =
            exercise?.sets?.firstOrNull { it.type == "normal" }?.weightKg

        /**
         * History entries kept when reconstructing a viewed workout's PO target:
         * drop the viewed workout itself ([excludeWorkoutId]) and every session
         * logged AT OR AFTER [cutoffIso] (the viewed workout's start), so the
         * target reflects only what the live advisor could see when the workout
         * was logged. Entries with an unparseable stamp are kept (never worse than
         * the id-only fallback). [cutoffIso] null → id-only exclusion.
         */
        internal fun historyBefore(
            entries: List<ExerciseHistoryEntry>,
            excludeWorkoutId: String?,
            cutoffIso: String?
        ): List<ExerciseHistoryEntry> {
            val cutoff = cutoffIso?.let { RoutineProgressComputer.parseInstant(it) }
            return entries.filter { entry ->
                if (entry.workoutId == excludeWorkoutId) return@filter false
                if (cutoff == null) return@filter true
                val t = RoutineProgressComputer.parseInstant(entry.workoutStartTime) ?: return@filter true
                t.isBefore(cutoff)
            }
        }

        /**
         * How many warmup sets the [WarmupAdvisor] would prescribe for [templateId]
         * at [workingWeightKg] — the **planned target** weight (the PO target the
         * live advisor ramped to), NOT the weight actually lifted. Passing the
         * logged weight would let a heavier-than-planned first set this session tip
         * the exercise over a warmup-bucket boundary and read falsely "incomplete"
         * (e.g. rear-delt logged at 30kg against a ~24kg rolling PO target —
         * 0 advised warmups become a phantom 1). Returns 0 when there's no working
         * weight or the catalog metadata is unavailable, so a row is never falsely
         * marked incomplete for warmups we can't assess. Assisted-bodyweight is
         * handled inside the advisor.
         */
        private fun expectedWarmupsFor(
            templateId: String,
            normalSetCount: Int,
            workingWeightKg: Float?,
            equipmentOf: Map<String, String?>,
            muscleGroupOf: Map<String, String?>,
            bodyweightKg: Float
        ): Int {
            val workingWeight = workingWeightKg ?: return 0
            return suggestWarmupSets(
                primaryMuscleGroup = muscleGroupOf[templateId],
                equipment = equipmentOf[templateId],
                workingWeightKg = workingWeight,
                normalSetCount = normalSetCount,
                exerciseTemplateId = templateId,
                bodyweightKg = bodyweightKg
            ).size
        }

        /**
         * Warmup count an already-logged exercise is held to for [isComplete].
         *
         * With a prior-session [poTargetKg] (non-null) we judge warmups against
         * that **planned** weight — a heavier-than-planned working set can't tip
         * the exercise over a warmup-bucket boundary (the rear-delt fix).
         *
         * With NO PO target the only weight we have is the one the user just
         * lifted. Recomputing the advisor from it would retroactively demand more
         * warmups than were advised when the set was logged — the adductor-at-50kg
         * false-"incomplete" bug: a working set heavier than the plan only raises
         * *next* session's warmup target, it must not un-complete this instance. So
         * with no plan to judge against we hold the exercise to exactly the warmups
         * it recorded — the gate can't fail on a weight we never planned for.
         */
        private fun expectedWarmupSetsForCompletion(
            templateId: String,
            normalSetCount: Int,
            poTargetKg: Float?,
            recordedWarmupSets: Int,
            equipmentOf: Map<String, String?>,
            muscleGroupOf: Map<String, String?>,
            bodyweightKg: Float
        ): Int =
            if (poTargetKg != null)
                expectedWarmupsFor(templateId, normalSetCount, poTargetKg, equipmentOf, muscleGroupOf, bodyweightKg)
            else
                recordedWarmupSets

        /**
         * Rebuild the resume exercise list, matching what was actually logged —
         * including swapped-in substitutes — against the routine prescription.
         * Mirrors [buildCompletionStatuses] so Resume stays consistent with
         * Workout Detail (which already shows substitutions).
         *
         * Per routine slot, in passes (exact wins over substitution):
         *  1. **Exact** — the logged exercise with the same template ID. Claimed
         *     eagerly so a substitute can't steal a later slot's exact match.
         *  2. **Substitution** (PO folders only) — a slot with nothing logged
         *     claims an *unclaimed* logged exercise from the same
         *     [SubstitutionMap] group that has ≥1 normal set (the swapped-in
         *     exercise). The slot then resumes as the SWAPPED exercise (its id /
         *     title / recorded sets), so e.g. a Deadlift slot the user swapped to
         *     Romanian Deadlift resumes RDL — not Deadlift from scratch.
         *  3. Slots with nothing logged stay the prescribed routine exercise
         *     (empty, still swap-eligible at resume, fed to the warmup advisor).
         *
         * Recorded sets carry in verbatim (locked + completed); remaining normal
         * sets toward the prescribed count are appended from the routine template
         * (editable). Extra logged exercises that aren't a slot or a substitute
         * are not surfaced here — the submission builders
         * ([buildResumePostRequestV2] / [buildWorkoutPutRequest]) already preserve
         * them from the original GET response.
         *
         * @param poFolderIds folder IDs for which substitution applies; pass
         *        empty to disable (then unmatched slots stay prescribed).
         */
        internal fun buildResumeExercises(
            workoutDetail: WorkoutDetailResponse,
            activeWorkout: ActiveWorkout,
            poFolderIds: Set<String>,
            routineFolderId: String?,
            nowMs: Long
        ): List<ActiveExercise> {
            val inScope = routineFolderId != null && routineFolderId in poFolderIds
            val workoutByTemplate = workoutDetail.exercises.associateBy { it.exerciseTemplateId }
            // Logged-exercise template IDs already claimed by a slot (exact or
            // substitute) so one logged exercise can't fill two slots.
            val claimed = mutableSetOf<String>()

            // ── Pass 1: exact matches, claimed eagerly ───────────────────────
            val exactMatch: List<WorkoutExerciseResponse?> = activeWorkout.exercises.map { activeEx ->
                val we = workoutByTemplate[activeEx.exerciseTemplateId]
                    ?.takeIf { activeEx.exerciseTemplateId !in claimed }
                if (we != null) claimed += activeEx.exerciseTemplateId
                we
            }

            // ── Pass 2: substitution for still-unmatched slots ───────────────
            return activeWorkout.exercises.mapIndexed { i, activeEx ->
                val matched: WorkoutExerciseResponse? = exactMatch[i] ?: run {
                    if (!inScope) return@run null
                    val group = SubstitutionMap.groupOf(activeEx.exerciseTemplateId) ?: return@run null
                    val candidates = workoutDetail.exercises.filter { cand ->
                        cand.exerciseTemplateId !in claimed &&
                            SubstitutionMap.groupOf(cand.exerciseTemplateId) == group
                    }
                    // Prefer a substitute with logged normal sets, but fall back to
                    // a warmup-only in-progress swap so resuming preserves the
                    // swapped-in exercise (and its logged warmups) instead of
                    // dropping them and resuming the prescribed slot. Keeps Resume
                    // in agreement with buildCompletionStatuses.
                    val sub = candidates.firstOrNull { it.sets.any { s -> s.type == "normal" } }
                        ?: candidates.firstOrNull()
                    if (sub != null) claimed += sub.exerciseTemplateId
                    sub
                }
                if (matched == null) activeEx // nothing logged → prescribed slot
                else resumeExerciseFrom(activeEx, matched, nowMs)
            }
        }

        /**
         * Build a resumed [ActiveExercise] from a routine slot [activeEx] and the
         * [logged] exercise that filled it (exact or substitute): recorded sets
         * locked + completed, then the routine's remaining normal sets appended.
         * When [logged] is a substitute its id/title win, so the user resumes the
         * swapped-in exercise.
         */
        private fun resumeExerciseFrom(
            activeEx: ActiveExercise,
            logged: WorkoutExerciseResponse,
            nowMs: Long
        ): ActiveExercise {
            // Rebuild sets from the WORKOUT DETAIL (preserves warmups, dropsets,
            // etc.); all recorded sets are completed + locked (not editable).
            val recordedSets = logged.sets.map { ws ->
                ActiveSet(
                    setType = SetType.fromApiValue(ws.type),
                    weightKg = ws.weightKg,
                    reps = ws.reps,
                    distanceMeters = ws.distanceMeters,
                    durationSeconds = ws.durationSeconds,
                    completed = true,
                    completedAtMs = nowMs,
                    locked = true
                )
            }

            val recordedNormalCount = recordedSets.count { it.setType == SetType.NORMAL }
            val prescribedNormalCount = activeEx.sets.count { it.setType == SetType.NORMAL }
            val remainingNormal = prescribedNormalCount - recordedNormalCount

            // Append empty normal sets for the ones not yet done, from the
            // routine's normal-set template.
            val remainingSets = if (remainingNormal > 0) {
                activeEx.sets
                    .filter { it.setType == SetType.NORMAL }
                    .takeLast(remainingNormal)
                    .map { it.copy(completed = false, completedAtMs = null) }
            } else emptyList()

            val isSwap = logged.exerciseTemplateId != activeEx.exerciseTemplateId
            return activeEx.copy(
                // Resume the swapped-in exercise's identity, not the prescribed
                // slot's, so the right exercise shows up.
                exerciseTemplateId = logged.exerciseTemplateId,
                title = if (isSwap) (logged.title ?: activeEx.title) else activeEx.title,
                sets = recordedSets + remainingSets,
                // Preserve the workout's notes (may differ from routine notes).
                notes = logged.notes?.takeIf { it.isNotBlank() } ?: activeEx.notes,
                // A resumed substitute keeps the swap tag on the chip.
                wasSwapped = isSwap || activeEx.wasSwapped
            )
        }
    }
}
