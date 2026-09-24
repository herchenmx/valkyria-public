package com.example.hevycompanion.recents

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevycompanion.BuildConfig
import com.example.hevycompanion.data.AuthPrefs
import com.example.hevycompanion.data.BodyweightPrefs
import com.example.hevycompanion.data.DEFAULT_APP_BUILD
import com.example.hevycompanion.data.DEFAULT_APP_VERSION
import com.example.hevycompanion.data.ExerciseTemplateRepo
import com.example.hevycompanion.data.RefreshResult
import com.example.hevycompanion.data.RefreshTokenInteractor
import com.example.hevycompanion.data.RoutineDetail
import com.example.hevycompanion.data.WorkoutDetail
import com.example.hevycompanion.data.WorkoutDetailResponseV2
import com.example.hevycompanion.data.buildHevyPrivateApi
import com.example.hevycompanion.data.buildHevyPublicApi
import com.example.hevycompanion.wear.HevyApiVersionPrefs
import com.example.hevycompanion.wear.WatchTokenSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One editable remaining set in the Resume screen. Observable holders so the
 *  text fields and the "log" toggle drive recomposition directly. */
class ResumeSetUi(
    val type: String,           // "warmup" / "normal"
    weight: String,
    reps: String,
) {
    var weight by mutableStateOf(weight)
    var reps by mutableStateOf(reps)
    // Off by default: every remaining set here is a PRESCRIPTION (PO target
    // weight, advised warmup, routine reps), not a record of what was
    // actually done. Pre-checking it meant hitting "Save workout" without
    // touching anything would silently log sets that were never performed.
    // The user checks off only the sets they actually did.
    var logged by mutableStateOf(false)

    /** Convert to a logged set when logged and the reps parse; weight is
     *  optional (blank → null). Returns null to skip. */
    fun toNewSet(): ResumeNewSet? {
        if (!logged) return null
        val r = reps.trim().toIntOrNull() ?: return null
        val w = weight.trim().toFloatOrNull()
        return ResumeNewSet(type = type, weightKg = w, reps = r)
    }
}

/** One exercise with its remaining (and warmup) sets to log. [sets] is an
 *  observable snapshot list so the "+ Add set" button can append at runtime and
 *  the form recomposes. [templateId] / [title] are state-backed so a mid-resume
 *  swap (replace this exercise) re-renders the header.
 *
 *  [prescribedNormalSets] / [normalReps] are the routine slot's prescription,
 *  retained so a swap can rebuild the substitute's set list against the same
 *  slot. [swappable] gates the Swap affordance (an untouched PO-folder exercise
 *  with substitutes) — mirrors the watch's `canSwap`. */
class ResumeExerciseUi(
    templateId: String,
    title: String,
    val sets: androidx.compose.runtime.snapshots.SnapshotStateList<ResumeSetUi>,
    val prescribedNormalSets: Int,
    val normalReps: List<Int?>,
    val swappable: Boolean,
) {
    var templateId by mutableStateOf(templateId)
    var title by mutableStateOf(title)
}

/** One alternative exercise offered in the swap picker, ordered by recency. */
class SwapOption(val templateId: String, val title: String)

/**
 * Drives the in-companion Resume flow: rebuilds the *remaining* work for an
 * incomplete workout (the watch's "Continue Incomplete Workout", §7 of
 * PRD-WATCH-APP.md), lets the user log it on the phone, and submits.
 *
 * Submission mirrors the watch's `saveResumeWorkout`:
 *  - **Primary** — the private v2 detail (`GET /workout/{id}`) is fetched at
 *    load; on submit a merged `POST /v2/workout` *replaces* the workout (the
 *    original biometrics pass straight through, so the HR chart survives) and
 *    the original is `DELETE`d.
 *  - **Fallback** — if the v2 detail isn't available (not logged in, or the GET
 *    failed) the merged body is `PUT` in place on the public api-key v1 path.
 *
 * Remaining work per still-unfinished routine slot:
 *  - the prescribed-minus-recorded normal sets, pre-filled with the
 *    progressive-overload target weight + the routine's prescribed reps;
 *  - advisor warmup sets, but only for slots with **nothing logged yet** (a
 *    MISSING exercise) — mirroring the watch's "exercises with any completed set
 *    skip warmup" rule.
 */
class ResumeWorkoutViewModel(app: Application) : AndroidViewModel(app) {

    private val apiKey = BuildConfig.HEVY_PUBLIC_API_KEY
    private val api = buildHevyPublicApi()
    // serializeNulls = false so optional set fields are omitted, not sent as
    // null — matches the watch's known-good v1 PUT fallback shape.
    private val putApi = buildHevyPublicApi(serializeNulls = false)
    private val templateRepo = ExerciseTemplateRepo(app, api, apiKey)
    private val adviceRepo = RecentsAdviceRepo(api, apiKey, templateRepo)
    private val bodyweightPrefs = BodyweightPrefs(app)
    private val authPrefs = AuthPrefs(app)
    private val apiVersionPrefs = HevyApiVersionPrefs(app)

    var isLoading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var workoutTitle by mutableStateOf("Workout"); private set
    var exercises by mutableStateOf<List<ResumeExerciseUi>>(emptyList()); private set

    /** Index of the exercise whose swap picker is open, or null. */
    var swapForIndex by mutableStateOf<Int?>(null); private set
    /** Substitute options for the open picker, ordered by recency of use. */
    var swapCandidates by mutableStateOf<List<SwapOption>>(emptyList()); private set
    /** True while the picker's candidates are loading or a swap is applying. */
    var swapLoading by mutableStateOf(false); private set

    var submitting by mutableStateOf(false); private set
    var submitError by mutableStateOf<String?>(null); private set
    /** Live detail while submitting: which path is being tried (private vs the
     *  public PUT fallback), the attempt number, and the last failure reason.
     *  Null when not submitting. */
    var submitProgress by mutableStateOf<SubmitProgress?>(null); private set
    /** Flips true after a successful submit — the host closes the screen. */
    var done by mutableStateOf(false); private set

    /** Why an earlier attempt failed, when a later one then succeeded. Without
     *  this a working fallback hides a broken primary path indefinitely: the
     *  workout saves, nothing is reported, and the fault goes unnoticed. The
     *  screen holds itself open on this rather than closing silently. */
    var submitNotice by mutableStateOf<String?>(null); private set

    fun dismissNotice() { submitNotice = null }

    /** Set by [recordAttemptFailure] on any failed attempt, cleared per submit. */
    private var lastAttemptFailure: String? = null

    private var original: WorkoutDetail? = null
    /** Private v2 detail, fetched best-effort at load. Null → PUT fallback. */
    private var originalV2: WorkoutDetailResponseV2? = null
    /** The same workout unparsed, so resume can carry fields the DTO does not
     *  model into the replacement instead of dropping them. */
    private var originalRawV2: com.google.gson.JsonObject? = null
    private var loadedId: String? = null

    fun load(workoutId: String) {
        if (workoutId == loadedId) return
        loadedId = workoutId
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            error = null
            try {
                val detail = api.getWorkout(apiKey = apiKey, workoutId = workoutId)
                original = detail
                workoutTitle = detail.title ?: "Workout"

                val routineId = detail.routineId
                val routine = if (routineId != null) {
                    runCatching { api.getRoutine(apiKey = apiKey, routineId = routineId).routine }.getOrNull()
                } else null
                if (routine == null) {
                    error = "This workout has no routine to resume from."
                    return@launch
                }

                val statuses = WorkoutCompletion.buildCompletionStatuses(
                    detail, routine, ProgressiveOverloadFolders.IDS,
                )
                // Any prescribed/logged slot (not EXTRA) may still need work —
                // missing normal sets OR missing advised warmups (the latter even
                // when every normal set is done). buildModel drops the ones with
                // nothing left, so a "nothing to do" workout falls through to the
                // empty-model guard below.
                val candidates = statuses.filter {
                    it.status != ExerciseCompletionStatus.Status.EXTRA
                }
                val targets = candidates
                    .map {
                        RecentsAdviceRepo.Target(
                            it.exerciseTemplateId,
                            it.prescribedNormalSets.takeIf { n -> n > 0 } ?: it.recordedNormalSets,
                        )
                    }
                    .distinctBy { it.templateId }
                val advice = adviceRepo.compute(
                    targets, excludeWorkoutId = workoutId, bodyweightKg = bodyweightPrefs.bodyweightKg,
                )
                val model = buildModel(candidates, routine, advice)
                if (model.isEmpty()) {
                    error = "Nothing left to log — this workout is complete."
                    return@launch
                }
                exercises = model

                // Best-effort private v2 fetch for the primary POST+DELETE path
                // (mirrors the watch fetching the v2 detail on Continue, which
                // it guards with refreshTokenIfNeeded). Failure (not logged in,
                // route gated) just leaves us on the v1 PUT fallback at submit.
                refreshTokenIfNeeded()
                privateApiOrNull()?.let { pApi ->
                    originalRawV2 = runCatching { pApi.getWorkoutRaw(workoutId) }.getOrNull()
                    originalV2 = originalRawV2?.let { raw ->
                        runCatching {
                            com.example.hevycompanion.util.GsonHolder.gson
                                .fromJson(raw, WorkoutDetailResponseV2::class.java)
                        }.getOrNull()
                    }
                    if (originalRawV2 != null) {
                        Log.w(TAG, "v2 GET keys=${originalRawV2!!.keySet().sorted()}")
                    }
                }
            } catch (e: Exception) {
                error = "Couldn't load this workout: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }

    private fun buildModel(
        candidates: List<ExerciseCompletionStatus>,
        routine: RoutineDetail,
        advice: Map<String, ExerciseAdvice>,
    ): List<ResumeExerciseUi> {
        val inPoFolder = routine.folderId?.toString() in ProgressiveOverloadFolders.IDS
        return candidates.mapNotNull { status ->
            val a = advice[status.exerciseTemplateId]
            val remainingNormal = (status.prescribedNormalSets - status.recordedNormalSets).coerceAtLeast(0)

            // Advised warmups still owed: the advisor's list (computed at the PO
            // target — the same basis as the chip's warmup-aware completion) minus
            // the ones already logged. So an exercise offers warmups to log iff its
            // chip reads warmup-short — including one whose normal sets are all done.
            val missingWarmups = missingWarmups(a?.warmups.orEmpty(), status.recordedWarmupSets)

            // Nothing left for this exercise → drop it.
            if (remainingNormal == 0 && missingWarmups.isEmpty()) return@mapNotNull null

            val weightText = a?.po?.targetKg?.let { trimKg(it) } ?: ""
            val routineNormalReps = routine.exercises
                .firstOrNull { it.exerciseTemplateId == status.exerciseTemplateId }
                ?.sets?.filter { it.type == "normal" }?.mapNotNull { it.reps }
                .orEmpty()

            val sets = androidx.compose.runtime.mutableStateListOf<ResumeSetUi>()
            missingWarmups.forEach { w ->
                sets += ResumeSetUi(type = "warmup", weight = trimKg(w.weightKg), reps = w.reps.toString())
            }
            for (k in 0 until remainingNormal) {
                val repsIdx = status.recordedNormalSets + k
                val reps = routineNormalReps.getOrNull(repsIdx) ?: routineNormalReps.lastOrNull()
                sets += ResumeSetUi(type = "normal", weight = weightText, reps = reps?.toString() ?: "")
            }

            // Swap-eligible like the watch's canSwap: untouched, in a PO folder,
            // and the exercise has curated substitutes.
            val nothingLoggedYet = status.recordedNormalSets == 0 && status.recordedWarmupSets == 0
            val swappable = nothingLoggedYet && inPoFolder &&
                SubstitutionMap.substitutesFor(status.exerciseTemplateId).isNotEmpty()

            ResumeExerciseUi(
                templateId = status.exerciseTemplateId,
                title = status.title,
                sets = sets,
                prescribedNormalSets = status.prescribedNormalSets,
                normalReps = routineNormalReps,
                swappable = swappable,
            )
        }
    }

    /** Append one more normal set to [exerciseIndex], pre-filled from the last
     *  normal set's weight/reps — the companion counterpart to the watch's
     *  "+1 Set" prompt. */
    fun addSet(exerciseIndex: Int) {
        val ex = exercises.getOrNull(exerciseIndex) ?: return
        val lastNormal = ex.sets.lastOrNull { it.type == "normal" }
        ex.sets.add(
            ResumeSetUi(
                type = "normal",
                weight = lastNormal?.weight.orEmpty(),
                reps = lastNormal?.reps.orEmpty(),
            )
        )
    }

    /**
     * Open the swap picker for an untouched exercise, loading its substitutes
     * **ordered by recency of use** (most-recently-logged first, never-used last)
     * — mirrors the watch's swap flow. Titles come from the exercise catalog.
     */
    fun openSwap(exerciseIndex: Int) {
        val ex = exercises.getOrNull(exerciseIndex) ?: return
        if (!ex.swappable) return
        swapForIndex = exerciseIndex
        swapCandidates = emptyList()
        swapLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            val subIds = SubstitutionMap.substitutesFor(ex.templateId)
            val meta = runCatching { templateRepo.getOrFetch().associateBy { it.id } }.getOrDefault(emptyMap())
            val ordered = subIds.map { id ->
                val startTimes = runCatching {
                    api.getExerciseHistory(apiKey = apiKey, exerciseTemplateId = id).exerciseHistory
                }.getOrDefault(emptyList()).map { it.workoutStartTime }
                Triple(id, meta[id]?.title ?: id, lastUsedEpochMsOf(startTimes))
            }.sortedByDescending { it.third }
            swapCandidates = ordered.map { SwapOption(it.first, it.second) }
            swapLoading = false
        }
    }

    /** Dismiss the swap picker without changing anything. */
    fun closeSwap() {
        swapForIndex = null
        swapCandidates = emptyList()
        swapLoading = false
    }

    /**
     * Replace the picker's exercise with [option]: rebuild its set list against
     * the same routine-slot prescription but at the substitute's own PO target +
     * advised warmups (fetched fresh, excluding this workout). Mirrors the watch's
     * applySwap (substitute logged against its own template id).
     */
    fun applySwap(option: SwapOption) {
        val idx = swapForIndex ?: return
        val ex = exercises.getOrNull(idx) ?: return
        val wid = loadedId ?: return
        swapLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            val advice = adviceRepo.compute(
                listOf(RecentsAdviceRepo.Target(option.templateId, ex.prescribedNormalSets)),
                excludeWorkoutId = wid,
                bodyweightKg = bodyweightPrefs.bodyweightKg,
            )[option.templateId]
            val weightText = advice?.po?.targetKg?.let { trimKg(it) } ?: ""
            val rebuilt = mutableListOf<ResumeSetUi>()
            advice?.warmups?.forEach { w ->
                rebuilt += ResumeSetUi(type = "warmup", weight = trimKg(w.weightKg), reps = w.reps.toString())
            }
            for (k in 0 until ex.prescribedNormalSets) {
                val reps = ex.normalReps.getOrNull(k) ?: ex.normalReps.lastOrNull()
                rebuilt += ResumeSetUi(type = "normal", weight = weightText, reps = reps?.toString() ?: "")
            }
            ex.templateId = option.templateId
            ex.title = option.title
            ex.sets.clear()
            ex.sets.addAll(rebuilt)
            closeSwap()
        }
    }


    fun submit() {
        // Previously `original ?: return` -- the one path in submit() that left
        // no trace at all: no error, no progress, no navigation, so the button
        // simply appeared dead. Surface it instead.
        val orig = original
        if (orig == null) {
            Log.w(TAG, "submit: no original workout loaded — nothing to submit")
            submitError = "This workout hasn't finished loading. Go back and try again."
            return
        }
        if (submitting) {
            Log.w(TAG, "submit: ignored, a submit is already in flight")
            return
        }
        submitting = true
        submitError = null
        submitProgress = null
        submitNotice = null
        lastAttemptFailure = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newExercises = exercises
                    .map { ex -> ResumeNewExercise(ex.templateId, ex.title, ex.sets.mapNotNull { it.toNewSet() }) }
                    .filter { it.sets.isNotEmpty() }
                if (newExercises.isEmpty()) {
                    submitError = "Log at least one set (with reps) first."
                    return@launch
                }
                val nowIso = ISO_MILLIS_UTC.format(Instant.now())

                // Primary: POST a merged replacement on the private v2 API, then
                // DELETE the original (best-effort) — preserving its biometrics.
                // Refresh first if the token is expiring, like the watch guards
                // its v2 POST with refreshTokenIfNeeded. Retried with visible
                // attempts before the public fallback.
                val v2 = originalV2
                if (v2 != null) refreshTokenIfNeeded()
                val pApi = privateApiOrNull()
                // Which branch runs is the single most useful fact when a
                // submit goes nowhere: no v2 detail or no access token silently
                // downgrades this to the public PUT, which cannot carry
                // biometrics and behaves differently.
                Log.w(TAG, "submit: exercises=${newExercises.size} " +
                        "originalV2=${if (v2 != null) "present" else "NULL"} " +
                        "privateApi=${if (pApi != null) "present" else "NULL (no access token)"}")
                if (v2 != null && pApi != null) {
                    val typedBody = ResumeRequestBuilder.buildPostV2(v2, orig.routineId, newExercises, nowIso)
                    // Carry across workout-level fields Hevy returned that the
                    // DTO does not model. Additive onto the known-good body:
                    // ours wins, server-owned identity fields are excluded, and
                    // no raw capture makes it a no-op.
                    val gson = com.example.hevycompanion.util.GsonHolder.gson
                    val body = com.example.hevycore.workout.ResumeBodyMerge.carryUnmodelledFields(
                        gson.toJsonTree(typedBody).asJsonObject,
                        originalRawV2,
                    )
                    Log.w(TAG, "carried=" + com.example.hevycore.workout.ResumeBodyMerge.carriedKeys(
                        gson.toJsonTree(typedBody).asJsonObject, originalRawV2))
                    Log.w(TAG, "withheld=" +
                            com.example.hevycore.workout.ResumeBodyMerge.withheldKeys(originalRawV2))
                    val privateOk = runCatching {
                        runSubmitAttempts(SubmitProgress.Phase.PRIVATE, PRIVATE_SUBMIT_ATTEMPTS) {
                            val resp = pApi.postWorkoutJson(body)
                            if (!resp.isSuccessful) {
                                // Hevy's own complaint is the only thing that
                                // identifies a newly-required field; the status
                                // code alone cannot.
                                val err = runCatching { resp.errorBody()?.string() }.getOrNull()
                                Log.w(TAG, "private v2 POST HTTP ${resp.code()}: ${err?.take(500)}")
                                // What we sent, so an echoed-back value that Hevy
                                // now rejects is visible next to its complaint.
                                // Workout data only -- the token is a header.
                                Log.w(TAG, "sent=" + runCatching {
                                    com.example.hevycompanion.util.GsonHolder.gson.toJson(body).take(3000)
                                }.getOrDefault("<unserializable>"))
                                throw HttpException(resp)
                            }
                        }
                    }
                    if (privateOk.isSuccess) {
                        // runCatching alone was not enough to call this "ok":
                        // deleteWorkout returns Response<Unit>, so a 404 or 500
                        // returns normally rather than throwing, and isSuccess
                        // was true for every HTTP status Hevy could give. The
                        // status has to be read explicitly.
                        val del = runCatching { pApi.deleteWorkout(orig.id) } // best-effort; dup is recoverable
                        val delCode = del.getOrNull()?.code()
                        val delOk = del.getOrNull()?.isSuccessful == true
                        Log.w(TAG, "submit: private v2 POST ok; delete of ${orig.id} " +
                                when {
                                    delOk -> "ok"
                                    del.isFailure -> "FAILED (${del.exceptionOrNull()})"
                                    else -> "FAILED (HTTP $delCode)"
                                })
                        // Collected rather than assigned in turn: a retried
                        // attempt AND a failed delete can both have happened,
                        // and whichever was written last would otherwise erase
                        // the other. The user asked to be told why something
                        // failed even when the operation ultimately succeeded.
                        val notices = buildList {
                            lastAttemptFailure?.let { add(it) }
                            if (!delOk) {
                                add("the original couldn't be removed" +
                                    (delCode?.let { " ($it)" } ?: "") +
                                    ", so you'll have a duplicate in Hevy")
                            }
                        }
                        if (notices.isNotEmpty()) {
                            submitNotice = "Saved, but: " + notices.joinToString("; ")
                        }
                        done = true
                        return@launch
                    }
                    Log.w(TAG, "submit: private v2 POST failed, falling back to public PUT",
                            privateOk.exceptionOrNull())
                    // Fall through to the v1 PUT fallback on any v2 failure.
                }

                // Fallback: in-place PUT on the public api-key (no biometrics),
                // also retried with visible attempts.
                val putBody = ResumeRequestBuilder.buildPutV1(orig, newExercises, nowIso)
                val fallbackResult = runCatching {
                    runSubmitAttempts(SubmitProgress.Phase.FALLBACK, FALLBACK_SUBMIT_ATTEMPTS) {
                        val putResp = putApi.updateWorkout(apiKey = apiKey, workoutId = orig.id, body = putBody)
                        if (!putResp.isSuccessful) {
                            val err = runCatching { putResp.errorBody()?.string() }.getOrNull()
                            Log.w(TAG, "public PUT HTTP ${putResp.code()}: ${err?.take(500)}")
                            Log.w(TAG, "PUT sent=" + runCatching {
                                com.example.hevycompanion.util.GsonHolder.gson.toJson(putBody).take(3000)
                            }.getOrDefault("<unserializable>"))
                            throw HttpException(putResp)
                        }
                    }
                }
                if (fallbackResult.isSuccess) {
                    Log.w(TAG, "submit: public PUT fallback ok")
                    lastAttemptFailure?.let { submitNotice = "Saved via the public API, but: $it" }
                    done = true
                } else {
                    Log.w(TAG, "submit: public PUT fallback failed",
                            fallbackResult.exceptionOrNull())
                    submitError = fallbackResult.exceptionOrNull()
                        ?.let { submitErrorReason(it) }
                        ?: "Couldn't submit."
                }
            } catch (e: Exception) {
                Log.w(TAG, "submit: threw", e)
                submitError = "Couldn't submit: ${e.message ?: "unknown error"}"
            } finally {
                submitting = false
                submitProgress = null
            }
        }
    }

    /**
     * Run a single submit call [block] up to [maxAttempts] times, publishing
     * [submitProgress] before each attempt (so the screen shows "attempt x/y"
     * for [phase]) and the failure reason between retries. Only
     * [isRetryableSubmitError] failures are retried, with exponential backoff;
     * a non-retryable failure or the final attempt rethrows so the caller can
     * fall back or surface the error. Mirrors the watch's `runSaveAttempts`.
     */
    /** Note why an attempt failed, whether or not the submit later succeeds. */
    private fun recordAttemptFailure(phase: SubmitProgress.Phase, t: Throwable) {
        val where = if (phase == SubmitProgress.Phase.PRIVATE) "Private API" else "Public API"
        val full = "$where: ${submitErrorReason(t)}"
        lastAttemptFailure = full
        Log.w(TAG, "attempt failed: $full", t)
    }

    private suspend fun runSubmitAttempts(
        phase: SubmitProgress.Phase,
        maxAttempts: Int,
        block: suspend () -> Unit,
    ) {
        var delayMs = SUBMIT_RETRY_INITIAL_DELAY_MS
        var lastError: Throwable? = null
        repeat(maxAttempts) { index ->
            submitProgress = SubmitProgress(
                phase = phase,
                attempt = index + 1,
                maxAttempts = maxAttempts,
                lastError = lastError?.let { submitErrorReason(it) },
            )
            try {
                block()
                return
            } catch (t: Throwable) {
                lastError = t
                // runSubmitAttempts wraps every attempt on both paths, so this
                // is the one place that sees private and public failures alike.
                recordAttemptFailure(phase, t)
                val isLast = index == maxAttempts - 1
                if (isLast || !isRetryableSubmitError(t)) throw t
                submitProgress = SubmitProgress(
                    phase = phase,
                    attempt = index + 1,
                    maxAttempts = maxAttempts,
                    lastError = submitErrorReason(t),
                )
                delay(delayMs)
                delayMs = (delayMs * SUBMIT_RETRY_FACTOR).toLong().coerceAtMost(SUBMIT_RETRY_MAX_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("runSubmitAttempts exhausted without error")
    }

    /**
     * Proactively refresh the Bearer token when it's expired or within
     * [TOKEN_REFRESH_PROACTIVE_S] of expiring, mirroring the watch's
     * `HevyApp.refreshTokenIfNeeded` guard before its private v2 calls. Shares
     * the process-wide refresh mutex (so it can't race the hourly worker / a
     * widget tap), and on success pushes the rotated tokens to the watch —
     * keeping the dual-refresh bridge healthy, the way the watch's refresh
     * pushes back to the companion. Best-effort: a failure just leaves the v2
     * call to 401 and fall back to the v1 PUT path.
     */
    private suspend fun refreshTokenIfNeeded() {
        if (!isTokenExpiringSoon(authPrefs.expiresAt)) return
        if (authPrefs.refreshToken == null) return // not logged in — nothing to refresh
        val result = runCatching { RefreshTokenInteractor().refresh(authPrefs) }.getOrNull()
        if (result is RefreshResult.Success) {
            runCatching { WatchTokenSender.push(getApplication(), authPrefs) }
        }
    }

    /** A private v2 client built from the stored Bearer token + spoofed version,
     *  or null when the user isn't logged in (→ PUT fallback). */
    private fun privateApiOrNull() = authPrefs.accessToken?.let { token ->
        buildHevyPrivateApi(
            accessToken = token,
            versionName = apiVersionPrefs.versionName ?: DEFAULT_APP_VERSION,
            versionCode = apiVersionPrefs.versionCode ?: DEFAULT_APP_BUILD,
        )
    }

    private fun trimKg(kg: Float): String =
        if (kg == kg.toLong().toFloat()) kg.toLong().toString() else kg.toString()

    companion object {
        private const val TAG = "ResumeWorkout"

        /** Refresh if the token expires within this window — matches the watch's
         *  `TOKEN_REFRESH_PROACTIVE_S`. */
        private const val TOKEN_REFRESH_PROACTIVE_S = 60L

        /** Max private-path (POST) attempts before the public PUT fallback. */
        const val PRIVATE_SUBMIT_ATTEMPTS = 3
        /** Max public fallback (PUT) attempts before surfacing the error. */
        const val FALLBACK_SUBMIT_ATTEMPTS = 2
        private const val SUBMIT_RETRY_INITIAL_DELAY_MS = 800L
        private const val SUBMIT_RETRY_FACTOR = 3.0
        private const val SUBMIT_RETRY_MAX_DELAY_MS = 30_000L

        private val ISO_MILLIS_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** True if [expiresAt] is null, unparseable, or within
         *  [TOKEN_REFRESH_PROACTIVE_S] of now. Ported from the watch's
         *  `isTokenExpiringSoon`; internal so it's unit-testable. */
        internal fun isTokenExpiringSoon(expiresAt: String?, now: Instant = Instant.now()): Boolean {
            if (expiresAt == null) return true
            return try {
                !Instant.parse(expiresAt).isAfter(now.plusSeconds(TOKEN_REFRESH_PROACTIVE_S))
            } catch (_: Exception) {
                true
            }
        }

        /** Advised warmups still owed: the advisor's list (computed at the PO
         *  target, matching the chip's warmup-aware completion) minus the ones
         *  already logged. Empty when all advised warmups are done — so an
         *  exercise offers warmups to log iff its chip reads warmup-short. */
        internal fun missingWarmups(adviceWarmups: List<WarmupSet>, recordedWarmups: Int): List<WarmupSet> =
            if (adviceWarmups.size > recordedWarmups) adviceWarmups.drop(recordedWarmups) else emptyList()

        /** Lenient ISO-8601 parse for workout start times ("…Z" or "…+00:00"). */
        internal fun parseInstantOrNull(iso: String?): Instant? {
            if (iso.isNullOrBlank()) return null
            return try {
                OffsetDateTime.parse(iso).toInstant()
            } catch (_: Exception) {
                try { Instant.parse(iso) } catch (_: Exception) { null }
            }
        }

        /** Recency sort key: max session start across [startTimes] as epoch ms,
         *  or [Long.MIN_VALUE] when none parse (never-used → sorts last). Mirrors
         *  the watch's `WorkoutHistoryApplier.lastUsedEpochMsOf`. */
        internal fun lastUsedEpochMsOf(startTimes: List<String?>): Long =
            startTimes.mapNotNull { parseInstantOrNull(it)?.toEpochMilli() }
                .maxOrNull() ?: Long.MIN_VALUE
    }
}
