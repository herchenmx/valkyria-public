package com.example.hevycompanion.recents

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevycompanion.BuildConfig
import com.example.hevycompanion.data.BodyweightPrefs
import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.ExerciseTemplateRepo
import com.example.hevycompanion.data.RoutineDetail
import com.example.hevycompanion.data.WorkoutDetail
import com.example.hevycompanion.data.buildHevyPublicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Loads one workout's full detail and compares it against its routine to
 * produce the COMPLETE / INCOMPLETE / MISSING / SUBSTITUTED / EXTRA breakdown —
 * the companion counterpart to the watch's
 * `com.example.hevywatch.presentation.workout.WorkoutDetailViewModel`.
 *
 * On top of the completeness comparison it also computes, per exercise, the
 * **next-session progressive-overload target** and the **advised warmup sets**
 * (the watch's two flagship in-workout features, surfaced read-only here) by
 * fetching each exercise's history on the public api-key — exactly as the
 * Strength Overview does. The advice is shown when a row is expanded.
 *
 * A workout with no `routine_id` (or whose routine can't be fetched) falls back
 * to listing the logged exercises with their set counts, exactly like the watch.
 */
class WorkoutDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val apiKey = BuildConfig.HEVY_PUBLIC_API_KEY
    private val api = buildHevyPublicApi()
    private val templateRepo = ExerciseTemplateRepo(app, api, apiKey)
    private val adviceRepo = RecentsAdviceRepo(api, apiKey, templateRepo)
    private val bodyweightPrefs = BodyweightPrefs(app)

    var isLoading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var workout by mutableStateOf<WorkoutDetail?>(null); private set
    var exerciseStatuses by mutableStateOf<List<ExerciseCompletionStatus>>(emptyList()); private set

    /** Per exercise-template id: the next-session PO target + advised warmups.
     *  Filled in asynchronously after the completeness breakdown renders, so the
     *  screen shows statuses immediately and advice populates a beat later. */
    var advice by mutableStateOf<Map<String, ExerciseAdvice>>(emptyMap()); private set

    /** True when the workout has a routine and is missing normal sets — drives
     *  the Resume affordance (mirrors the watch's `canContinue`). */
    var canContinue by mutableStateOf(false); private set

    /** The resolved routine, kept for the Resume flow's prescription. */
    var routine by mutableStateOf<RoutineDetail?>(null); private set

    private var loadedWorkoutId: String? = null

    /** Catalog metadata keyed by template id. `load()` and `computeAdvice()`
     *  both need it and used to each run `getOrFetch().associateBy { }` —
     *  two full Gson parses + associateBy of ~600 templates per detail open.
     *  Memoised per VM instance; the underlying repo has its own 7-day TTL. */
    private var templateMeta: Map<String, ExerciseTemplate>? = null

    private suspend fun templateMeta(): Map<String, ExerciseTemplate> =
        templateMeta ?: runCatching { templateRepo.getOrFetch().associateBy { it.id } }
            .getOrDefault(emptyMap())
            .also { if (it.isNotEmpty()) templateMeta = it }

    /** Idempotent per workout id so re-composition doesn't re-fetch. */
    fun load(workoutId: String) {
        if (workoutId == loadedWorkoutId) return
        loadedWorkoutId = workoutId
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            error = null
            workout = null
            routine = null
            exerciseStatuses = emptyList()
            advice = emptyMap()
            canContinue = false
            try {
                val detail = api.getWorkout(apiKey = apiKey, workoutId = workoutId)
                workout = detail

                val routineId = detail.routineId
                val resolved = if (routineId != null) resolveRoutine(routineId) else null
                routine = resolved
                if (resolved != null) {
                    // Warmup-aware completeness needs each exercise's catalog
                    // metadata (equipment + muscle group). Cached 7-day TTL, so
                    // usually instant; a cold/failed fetch degrades to no advised
                    // warmups (never a false "incomplete").
                    val meta = templateMeta()
                    val statuses = WorkoutCompletion.buildCompletionStatuses(
                        detail,
                        resolved,
                        ProgressiveOverloadFolders.IDS,
                        meta,
                        bodyweightPrefs.bodyweightKg,
                    )
                    exerciseStatuses = statuses
                    // Resumable when any prescribed/logged slot isn't fully done —
                    // missing normal sets OR missing advised warmups (so you can
                    // go back and log the warmups you skipped). EXTRA is bonus work.
                    canContinue = statuses.any {
                        it.status != ExerciseCompletionStatus.Status.EXTRA && !it.isComplete
                    }
                }
            } catch (e: Exception) {
                error = "Couldn't load this workout: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }

            // Advice is best-effort and non-blocking for the initial render:
            // failures just leave rows without a PO / warmup hint.
            runCatching { computeAdvice(workoutId) }
        }
    }

    private suspend fun resolveRoutine(routineId: String): RoutineDetail? =
        try {
            api.getRoutine(apiKey = apiKey, routineId = routineId).routine
        } catch (_: Exception) {
            // A deleted / inaccessible routine just means no comparison — the
            // screen falls back to the raw exercise list.
            null
        }

    /**
     * Compute the PO target + warmup advice for every exercise on screen,
     * excluding the currently-viewed workout from PO history so the target
     * resolves against prior *completed* sessions — the same filter the watch
     * applies when resuming an in-progress session.
     */
    private suspend fun computeAdvice(workoutId: String) {
        val targets = adviceTargets()
        val computed = adviceRepo.compute(
            targets,
            excludeWorkoutId = workoutId,
            bodyweightKg = bodyweightPrefs.bodyweightKg,
            // Reconstruct the target as of THIS workout — exclude later sessions
            // so a past workout isn't judged against a forward-looking target.
            beforeStartTimeIso = workout?.startTime,
        )
        advice = computed

        // Re-judge warmup completeness against the PO target (the weight the live
        // advisor ramped to, from PRIOR sessions) now that advice has resolved.
        // The initial build used the logged weight, which can sit a bucket above
        // the target after a heavier-than-planned set and read as a phantom
        // missing warmup (the rear-delt-at-30kg case). Excluding the current
        // workout already happened inside adviceRepo.compute.
        val detail = workout
        val resolved = routine
        if (detail != null && resolved != null) {
            val meta = templateMeta()
            val poTargets = computed.mapValues { it.value.po.targetKg }
            val rebuilt = WorkoutCompletion.buildCompletionStatuses(
                detail, resolved, ProgressiveOverloadFolders.IDS, meta, bodyweightPrefs.bodyweightKg, poTargets
            )
            exerciseStatuses = rebuilt
            canContinue = rebuilt.any {
                it.status != ExerciseCompletionStatus.Status.EXTRA && !it.isComplete
            }
        }
    }

    /** Distinct exercises to advise on, derived from the completeness rows when a
     *  routine resolved, else from the raw logged exercises. */
    private fun adviceTargets(): List<RecentsAdviceRepo.Target> {
        val statuses = exerciseStatuses
        if (statuses.isNotEmpty()) {
            return statuses
                .map { s ->
                    val normal = if (s.prescribedNormalSets > 0) s.prescribedNormalSets else s.recordedNormalSets
                    RecentsAdviceRepo.Target(s.exerciseTemplateId, normal)
                }
                .distinctBy { it.templateId }
        }
        return workout?.exercises.orEmpty()
            .map { e -> RecentsAdviceRepo.Target(e.exerciseTemplateId, e.sets.count { it.type == "normal" }) }
            .distinctBy { it.templateId }
    }
}
