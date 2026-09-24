package com.example.hevywatch.presentation.workout

import android.app.Application
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.hevywatch.data.LastSessionStats
import com.example.hevywatch.data.NETWORK_RETRY_MAX_DELAY_MS
import com.example.hevywatch.data.SubstitutionMap
import com.example.hevywatch.data.SuggestedWeight
import com.example.hevywatch.data.api.acceptedByHevy
import com.example.hevywatch.data.api.orThrow
import com.example.hevywatch.data.api.model.ExerciseHistoryEntry
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.data.model.HeartRateSample
import com.example.hevywatch.sensors.BiometricsBuilder
import com.example.hevywatch.sensors.HeartRateAvailability
import com.example.hevywatch.sensors.HeartRateSampler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── ViewModel ─────────────────────────────────────────────────────────────────

class LogWorkoutViewModel(app: Application) : AndroidViewModel(app) {

    private val hevyApp get() = getApplication<HevyApp>()

    private val _workout = mutableStateOf<ActiveWorkout?>(null)
    var workout: ActiveWorkout?
        get() = _workout.value
        private set(value) {
            _workout.value = value
            // Keep HevyApp in sync so the Tile (and other external readers) see
            // live state. In-memory only — persistence is throttled below.
            hevyApp.setActiveWorkoutInMemory(value)
            // B10 — mark dirty so the throttled saver picks it up on its next
            // tick (default every [THROTTLE_INTERVAL_MS]). Replaces the prior
            // per-mutation apply() + 10 s idle flush scheme: that one did a
            // full Gson serialise on every weight-picker scroll tick (dozens
            // per second). The throttled saver caps that at ~30 serialises
            // per minute during active scrolling, zero during idle, and
            // worst-case crash data-loss is ~2 s of scrolling state.
            if (value != null) throttledSaver.touch() else throttledSaver.cancel()
        }

    private val throttledSaver = ThrottledSaver(
        scope = viewModelScope,
        intervalMs = THROTTLE_INTERVAL_MS,
    ) {
        // The throttle loop ticks on viewModelScope (Dispatchers.Main), so this
        // uses async save() (apply) — NOT saveBlocking() — to keep the disk
        // commit off the main thread. A blocking commit() here janked the UI
        // every tick during active scrolling on the Snapdragon Wear 2100. The
        // per-tick Gson serialise is bounded (~1 per interval) and cheap; the
        // two explicit saveBlocking() checkpoints (set completion + pause) still
        // guarantee a synchronous durable flush at the points that matter.
        workout?.let { hevyApp.activeWorkoutStore.save(it) }
    }

    /** Lazy HR sampler — created on first eligible workout start. Lifecycle
     *  managed by [maybeStartHrSampler]/[stopHrSampler] from the workout flow.
     *  Always null on KSW1 (no PPG hardware) and when the user hasn't enabled
     *  HR in Settings. */
    private var hrSampler: HeartRateSampler? = null

    /** One-shot signal to the screen: launch the BODY_SENSORS runtime prompt.
     *  Set by [maybeStartHrSampler] when the user has enabled HR in Settings
     *  + hardware is present + permission is missing. Cleared by
     *  [onBodySensorsResult] regardless of the result. */
    var requestBodySensorsPermission by mutableStateOf(false)
        private set

    var currentExerciseIndex by mutableIntStateOf(0)
        private set
    var currentSetIndex by mutableIntStateOf(0)
        private set

    var isSaving by mutableStateOf(false)
        private set
    /** Live detail behind the Finish spinner: which path is being tried
     *  (private vs the public fallback), the attempt number, and the last
     *  failure reason. Null until a save starts; cleared on success/error. */
    var saveProgress by mutableStateOf<SaveProgress?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    /** True when [saveError] must NOT auto-dismiss. Set for a rejected request
     *  body (400/422): that is a real defect the user needs to see and report,
     *  not a transient hiccup they can retry past, so the 10 s auto-dismiss is
     *  suppressed and it stays until tapped. */
    var saveErrorSticky by mutableStateOf(false)
        private set

    /** Something went wrong on a save that nonetheless **succeeded** — the
     *  resume DELETE failing, say, which leaves a duplicate in Hevy. Distinct
     *  from [saveError], which means the workout was not saved: a notice must
     *  not read as a failure, and a failure must not be downgraded to a notice.
     *  Mirrors the companion's `submitNotice`. */
    var saveNotice by mutableStateOf<String?>(null)
        private set

    fun dismissSaveNotice() { saveNotice = null }

    /** Hevy's own response text from the last failed submit, read once at the
     *  call site (an error body can only be consumed once) and reused when the
     *  error is classified. Null when the failure carried no body. */
    private var lastServerErrorBody: String? = null

    /** U3 — explicit dismiss for the inline save-error banner. The screen
     *  auto-dismisses after 10 s; this hook also lets the user tap to dismiss. */
    fun dismissSaveError() { saveError = null; saveErrorSticky = false }
    var navigateTo by mutableStateOf<String?>(null)
        private set
    var showFallbackPrompt by mutableStateOf(false)
        private set
    var fallbackError by mutableStateOf<String?>(null)
        private set

    /** Phase E — true while [finishWorkout] is retrying the connectivity check
     *  in its 10 s window. Drives a full-screen "Connecting..." spinner so the
     *  user doesn't think the tap was missed. */
    var isAwaitingConnectivity by mutableStateOf(false)
        private set

    /** Phase E — true once the 10 s retry expired without a connection. The
     *  screen surfaces a warning; tapping OK calls [confirmNoConnectivityExit]
     *  which ends the in-memory workout but keeps the recovery file. */
    var showNoConnectivityWarning by mutableStateOf(false)
        private set

    private var pendingEndTimeMs: Long = 0L
    var isPaused by mutableStateOf(false)
        private set

    /** True when the workout has sat paused past [ABANDONMENT_THRESHOLD_MS]
     *  and the user has just come back to it. The screen offers Finish now /
     *  Keep paused / Discard so a forgotten session doesn't linger as an
     *  incomplete workout the user later has to clean up via the
     *  POST+DELETE resume flow. Dismissed for the rest of the pause once
     *  answered — see [dismissAbandonmentNudge]. */
    var showAbandonmentNudge by mutableStateOf(false)
        private set

    /** Set once the nudge has been answered so re-entering the screen during
     *  the same pause doesn't re-prompt. Cleared on resume / discard / finish. */
    private var abandonmentNudgeAnswered = false

    /**
     * Called when LogWorkoutScreen comes to the foreground. Surfaces the
     * nudge exactly once per pause. Pure decision lives in
     * [shouldNudgeAbandonment] so the threshold is unit-testable.
     */
    fun checkAbandonment(nowMs: Long = System.currentTimeMillis()) {
        if (abandonmentNudgeAnswered) return
        showAbandonmentNudge = shouldNudgeAbandonment(
            isPaused = isPaused,
            pausedAtMs = hevyApp.workoutPausedAt,
            nowMs = nowMs,
        )
    }

    /** User answered the nudge (any option). Don't re-prompt this pause. */
    fun dismissAbandonmentNudge() {
        showAbandonmentNudge = false
        abandonmentNudgeAnswered = true
    }
    var showExerciseDonePrompt by mutableStateOf(false)
        private set
    /** True once fetchExerciseHistory has completed (success or partial). */
    var isHistoryLoaded by mutableStateOf(false)
        private set
    private var pendingExerciseRestSeconds: Int = 0

    // Template IDs where progressive overload increased the weight (Scenario 1)
    var weightIncreasedExercises by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Set type of the just-completed set, used by Phase C brightness coordinator
     *  to decide whether the rest-timer screen following a set should stay
     *  bright (warmup → next set) or be allowed to dim (normal → normal rest). */
    var lastCompletedSetType: SetType? by mutableStateOf(null)
        private set

    /** Suggested weights for exercises with no history, based on similar exercises. */
    var exerciseSuggestedWeights by mutableStateOf<Map<String, SuggestedWeight>>(emptyMap())
        private set

    var showWarmupAdvisorPrompt by mutableStateOf(false)
        private set
    private var pendingWarmupOverrides: Map<Int, List<ActiveSet>> = emptyMap()

    private var historyFetchJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init() {
        if (workout != null) return  // already initialised
        val active = hevyApp.activeWorkout ?: return
        workout = active
        // P1 — no per-second VM tick. The visible workout duration is rendered
        // by WorkoutAwareTimeText, which has its own scoped LaunchedEffect and
        // reads hevyApp.activeWorkout.startTimeMs directly. A second VM-side
        // ticker would just burn battery without anyone observing its state.
        fetchExerciseHistory(active)
        maybeStartHrSampler(active)
    }

    /**
     * Start per-minute HR sampling iff:
     *  - user enabled "Heart rate" in Settings
     *  - device has the sensor (KSW2 yes, KSW1 no)
     *  - BODY_SENSORS granted
     *
     * Resumed workouts sample HR too — at Finish, the resume POST+DELETE flow
     * merges the new samples with the original workout's biometrics into a
     * single combined POST. (Earlier we skipped sampling on resume because
     * the v1 PUT path couldn't persist them; that path is now a fallback.)
     *
     * Idempotent. Samples land in [ActiveWorkout.heartRateSamples] via the
     * VM's existing throttled-save path so they survive a crash.
     */
    private fun maybeStartHrSampler(active: ActiveWorkout) {
        if (!hevyApp.userProfileStore.heartRateEnabled) return
        val ctx = getApplication<HevyApp>()
        if (!HeartRateAvailability.hasHardware(ctx)) return
        if (!HeartRateAvailability.hasPermission(ctx)) {
            // User opted in but the runtime grant is missing — surface a
            // one-shot prompt request to the screen. The actual launcher lives
            // in the Compose layer (needs an Activity ResultRegistry).
            requestBodySensorsPermission = true
            return
        }
        if (hrSampler != null) return
        hrSampler = HeartRateSampler(ctx) { sample -> appendHrSample(sample) }
        hrSampler?.start()
    }

    /** Called by the screen after the BODY_SENSORS prompt resolves. If granted,
     *  we try to start the sampler again (the workout might already be running
     *  by the time the user taps Allow). If denied, the toggle stays on but
     *  no samples are collected for this workout. */
    fun onBodySensorsResult(granted: Boolean) {
        requestBodySensorsPermission = false
        if (!granted) return
        val w = workout ?: return
        maybeStartHrSampler(w)
    }

    private fun stopHrSampler() {
        hrSampler?.stop()
        hrSampler = null
    }

    /** Append a fresh HR sample to the active workout. Mutates through the
     *  workout setter so the throttled saver persists it eventually. */
    private fun appendHrSample(sample: HeartRateSample) {
        val w = workout ?: return
        workout = w.copy(heartRateSamples = w.heartRateSamples + sample)
    }

    // ── Exercise history (previous hints + PR bests + progressive overload) ──────

    private fun fetchExerciseHistory(active: ActiveWorkout) {
        historyFetchJob?.cancel()
        historyFetchJob = viewModelScope.launch {
            // R1 — the heavy lifting (history fetch + equipment stamp + previous
            // hints + PO + similar-exercise suggestions + warmup advisor) now
            // lives in [WorkoutHistoryApplier.load], which returns a single
            // [WorkoutHistoryApplier.LoadedWorkout] the VM pushes into its
            // own state. The applier is testable in isolation.
            val loaded = WorkoutHistoryApplier.load(active, hevyApp)
            workout = loaded.workout
            weightIncreasedExercises = loaded.weightIncreasedExercises
            exerciseSuggestedWeights = loaded.exerciseSuggestedWeights
            if (loaded.pendingWarmupOverrides.isNotEmpty()) {
                pendingWarmupOverrides = loaded.pendingWarmupOverrides
                showWarmupAdvisorPrompt = true
            }
            isHistoryLoaded = true
        }
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    fun openSet(exerciseIndex: Int, setIndex: Int) {
        currentExerciseIndex = exerciseIndex
        currentSetIndex = setIndex
        navigateTo = Screen.LOG_SET
    }

    fun onNavigated() { navigateTo = null }

    // ── In-workout exercise substitution ────────────────────────────────────

    /** Exercise index awaiting the "Swap exercise?" Y/N prompt, or null. */
    var swapPromptExerciseIndex by mutableStateOf<Int?>(null)
        private set
    /** Exercise index the Swap Exercise screen is operating on, or null. */
    var swapExerciseIndex by mutableStateOf<Int?>(null)
        private set
    var swapCandidates by mutableStateOf<List<WorkoutHistoryApplier.SwapCandidate>>(emptyList())
        private set
    var isSwapLoading by mutableStateOf(false)
        private set

    /**
     * A swap is offered only when the routine is in a PO folder
     * ([ActiveWorkout.progressiveOverload] is set via `toDomain(enabledFolderIds)`),
     * the exercise has acceptable substitutes, and it hasn't been started yet
     * (no completed sets) — so we never nag on un-swappable exercises or when
     * returning to finish remaining sets.
     */
    private fun canSwap(exerciseIndex: Int): Boolean {
        val w = workout ?: return false
        if (!w.progressiveOverload) return false
        val ex = w.exercises.getOrNull(exerciseIndex) ?: return false
        if (ex.sets.any { it.completed }) return false
        return SubstitutionMap.substitutesFor(ex.exerciseTemplateId).isNotEmpty()
    }

    /** Entry point from an exercise chip tap. Surfaces the swap prompt when a
     *  swap is possible, otherwise opens the first set directly (legacy path). */
    fun onExerciseChipTap(exerciseIndex: Int) {
        if (canSwap(exerciseIndex)) {
            swapPromptExerciseIndex = exerciseIndex
        } else {
            openSet(exerciseIndex, 0)
        }
    }

    /** User chose "Y" on the swap prompt → arm the Swap Exercise screen. The
     *  screen handles navigation + candidate loading. */
    fun confirmSwapPrompt() {
        swapExerciseIndex = swapPromptExerciseIndex
        swapPromptExerciseIndex = null
        swapCandidates = emptyList()
    }

    /** User chose "N" → proceed to log the prescribed exercise unchanged. */
    fun declineSwapPrompt() {
        val idx = swapPromptExerciseIndex
        swapPromptExerciseIndex = null
        if (idx != null) openSet(idx, 0)
    }

    /** Compute the substitute options for the armed exercise. Called by the
     *  Swap Exercise screen on entry. */
    fun loadSwapCandidates() {
        val idx = swapExerciseIndex ?: return
        val w = workout ?: return
        val prescribed = w.exercises.getOrNull(idx) ?: return
        viewModelScope.launch {
            isSwapLoading = true
            swapCandidates = WorkoutHistoryApplier.prepareSwapCandidates(
                prescribed = prescribed,
                progressiveOverload = w.progressiveOverload,
                continuingWorkoutId = w.continuingWorkoutId,
                hevyApp = hevyApp,
            )
            isSwapLoading = false
        }
    }

    /** Replace the armed exercise with [candidate] and point the logger at its
     *  first set. Pure synchronous splice — the candidate's weights/warmups were
     *  computed in [loadSwapCandidates]. The screen navigates to LOG_SET after. */
    fun applySwap(candidate: WorkoutHistoryApplier.SwapCandidate) {
        val idx = swapExerciseIndex ?: return
        val w = workout ?: return
        val list = w.exercises.toMutableList()
        if (idx !in list.indices) return
        val oldId = list[idx].exerciseTemplateId
        val newId = candidate.templateId
        // Tag the spliced-in exercise so its chip shows the live `swap` marker.
        list[idx] = candidate.preparedExercise.copy(wasSwapped = true)
        workout = w.copy(exercises = list)
        // Re-key the chip-badge maps onto the substitute.
        weightIncreasedExercises = (weightIncreasedExercises - oldId).let {
            if (candidate.source == WorkoutHistoryApplier.SwapCandidate.Source.PROGRESSIVE_OVERLOAD) it + newId else it
        }
        exerciseSuggestedWeights = (exerciseSuggestedWeights - oldId).let {
            if (candidate.suggestedWeight != null) it + (newId to candidate.suggestedWeight) else it
        }
        currentExerciseIndex = idx
        currentSetIndex = 0
        clearSwapState()
    }

    /** "Keep prescribed" on the Swap screen → log the original exercise. */
    fun keepPrescribedFromSwap() {
        val idx = swapExerciseIndex
        clearSwapState()
        if (idx != null) {
            currentExerciseIndex = idx
            currentSetIndex = 0
        }
    }

    /** Back out of the swap flow entirely (e.g. swipe-dismiss). */
    fun cancelSwap() = clearSwapState()

    private fun clearSwapState() {
        swapExerciseIndex = null
        swapCandidates = emptyList()
        isSwapLoading = false
    }

    val currentExercise: ActiveExercise?
        get() = workout?.exercises?.getOrNull(currentExerciseIndex)

    val currentSet: ActiveSet?
        get() = currentExercise?.sets?.getOrNull(currentSetIndex)

    /** Sets logged in the most recent session for the current exercise; empty if no history. */
    val currentExerciseLastSessionEntries: List<ExerciseHistoryEntry>
        get() {
            val templateId = currentExercise?.exerciseTemplateId ?: return emptyList()
            return LastSessionStats.latestSessionEntries(
                hevyApp.exerciseHistoryCache[templateId]?.exerciseHistory.orEmpty()
            )
        }

    /** Prev / Next on LogSetScreen only move within the current exercise —
     *  jumping across exercise boundaries happens implicitly via completeCurrentSet. */
    fun goToNextSet() {
        val exercise = workout?.exercises?.getOrNull(currentExerciseIndex) ?: return
        currentSetIndex = SetNavigation.nextWithinExercise(currentSetIndex, exercise.sets.size)
    }

    fun goToPreviousSet() {
        currentSetIndex = SetNavigation.previousWithinExercise(currentSetIndex)
    }

    val isFirstSet: Boolean
        get() = SetNavigation.isFirst(currentSetIndex)

    val isLastSet: Boolean
        get() {
            val exercise = workout?.exercises?.getOrNull(currentExerciseIndex) ?: return true
            return SetNavigation.isLast(currentSetIndex, exercise.sets.size)
        }

    // ── Set value updates ─────────────────────────────────────────────────────

    fun updateCurrentSetWeight(weightKg: Float) {
        updateCurrentSet { it.copy(weightKg = weightKg.coerceAtLeast(0f)) }
    }

    fun updateCurrentSetReps(reps: Int) {
        updateCurrentSet { it.copy(reps = reps.coerceAtLeast(0)) }
    }

    fun changeCurrentSetType(type: SetType) {
        updateCurrentSet { it.copy(setType = type) }
    }

    // ── Base resistance (bar / Smith carriage / machine sled) ─────────────────

    /** Last base the user entered for [templateId] (prompt seed), or null. */
    fun lastBaseResistance(templateId: String): Float? =
        hevyApp.baseResistanceStore.lastBase(templateId)

    /**
     * Record [kg] as the base resistance for the exercise at [exerciseIndex] and
     * re-ramp its not-yet-logged warmups onto the plate portion (see
     * [applyBaseResistance]). Stored set weights stay true-total; only the log
     * screen's display and the warmup ladder change. Persists last-used per
     * template (prompt seed) and blocking-flushes the session so the base
     * survives a crash before the throttled saver would fire.
     */
    fun setBaseResistance(exerciseIndex: Int, kg: Float) {
        val w = workout ?: return
        val exercises = w.exercises.toMutableList()
        val exercise = exercises.getOrNull(exerciseIndex) ?: return
        val base = kg.coerceAtLeast(0f)
        exercises[exerciseIndex] = applyBaseResistance(exercise, base, hevyApp.bodyweightKg)
        workout = w.copy(exercises = exercises)
        hevyApp.baseResistanceStore.setLastBase(exercise.exerciseTemplateId, base)
        workout?.let { hevyApp.activeWorkoutStore.saveBlocking(it) }
    }

    // ── Add / Remove sets ─────────────────────────────────────────────────────

    /** Adds a new set to the current exercise (copies type + values from last set) and
     *  moves the cursor to it. */
    fun addSetToCurrentExercise() {
        val w = workout ?: return
        val exIdx = currentExerciseIndex
        val exercises = w.exercises.toMutableList()
        val exercise = exercises.getOrNull(exIdx) ?: return
        val last = exercise.sets.lastOrNull()
        val newSet = ActiveSet(
            setType = last?.setType ?: SetType.NORMAL,
            weightKg = last?.weightKg,
            reps = last?.reps
        )
        val newSets = exercise.sets + newSet
        exercises[exIdx] = exercise.copy(sets = newSets)
        workout = w.copy(exercises = exercises)
        currentSetIndex = newSets.size - 1
    }

    /** Removes the current set (minimum 1 set enforced). */
    fun removeCurrentSet() {
        val w = workout ?: return
        val exIdx = currentExerciseIndex
        val setIdx = currentSetIndex
        val exercises = w.exercises.toMutableList()
        val exercise = exercises.getOrNull(exIdx) ?: return
        if (exercise.sets.size <= 1) return
        val newSets = exercise.sets.toMutableList().also { it.removeAt(setIdx) }
        exercises[exIdx] = exercise.copy(sets = newSets)
        workout = w.copy(exercises = exercises)
        if (currentSetIndex >= newSets.size) currentSetIndex = newSets.size - 1
    }

    // ── Rest timer duration logic ───────────────────────────────────────────

    private fun computeRestSeconds(exercise: ActiveExercise, completedSetIndex: Int): Int =
        computeRestSecondsForSet(exercise, completedSetIndex)

    // ── Complete set + PR check ───────────────────────────────────────────────

    fun completeCurrentSet() {
        val exercise = currentExercise ?: return
        val setSnapshot = currentSet ?: return   // weight/reps already written by caller
        val wasAlreadyCompleted = setSnapshot.completed
        val restSeconds = computeRestSeconds(exercise, currentSetIndex)

        // Phase C — record which type of set the user just finished so the
        // brightness coordinator can decide whether the upcoming rest-timer
        // screen should stay bright (warmup → anything) or allow dim.
        lastCompletedSetType = setSnapshot.setType

        updateCurrentSet { it.copy(completed = true, completedAtMs = System.currentTimeMillis()) }

        // Durable checkpoint: blocking flush so a process kill (low memory,
        // OS reaping during a long workout) immediately after this point can
        // never lose the just-completed set. The throttled saver stays running
        // — subsequent mutations will still flow through it.
        workout?.let { hevyApp.activeWorkoutStore.saveBlocking(it) }

        // Set-completion haptic intentionally suppressed: the rest-timer
        // countdown haptic is the one the user actually reacts to, and the
        // small motor on the Scallop 2 isn't free to fire. The visible Compose
        // state change (set marked complete, cursor advance, rest timer
        // opening) is the confirmation now.

        // If re-completing an already-done set (user went back to edit), advance the
        // cursor to the next set so the button still feels responsive, but skip the
        // rest timer and the carry-forward of values.
        if (wasAlreadyCompleted) {
            val w0 = workout
            if (w0 != null) {
                val nextSetIdx = currentSetIndex + 1
                val currEx0 = w0.exercises.getOrNull(currentExerciseIndex)
                if (currEx0 != null && nextSetIdx < currEx0.sets.size) {
                    currentSetIndex = nextSetIdx
                } else if (currentExerciseIndex + 1 < w0.exercises.size) {
                    currentExerciseIndex++
                    currentSetIndex = 0
                }
            }
            return
        }

        // Advance to next set, or show "done with exercise?" prompt on last set
        val w = workout
        val isLastSetOfExercise: Boolean
        if (w != null) {
            val nextSetIdx = currentSetIndex + 1
            val currEx = w.exercises.getOrNull(currentExerciseIndex)
            if (currEx != null && nextSetIdx < currEx.sets.size) {
                currentSetIndex = nextSetIdx
                val nextSet = currEx.sets[nextSetIdx]
                // Only carry forward if the next set is NOT already completed
                if (!nextSet.completed) {
                    if (setSnapshot.setType == SetType.NORMAL && nextSet.setType == SetType.NORMAL) {
                        // Normal → Normal: carry forward weight AND reps from completed set
                        updateCurrentSet { it.copy(weightKg = setSnapshot.weightKg, reps = setSnapshot.reps) }
                    } else {
                        // Warmup transitions: keep prescribed weight
                        updateCurrentSet { it.copy(weightKg = it.weightKg ?: setSnapshot.weightKg) }
                    }
                }
                isLastSetOfExercise = false
            } else {
                // Last set — save rest seconds for use if user dismisses the prompt
                pendingExerciseRestSeconds = exercise.restTimerSeconds ?: 0
                showExerciseDonePrompt = true
                isLastSetOfExercise = true
            }
        } else {
            isLastSetOfExercise = false
        }

        // Skip rest timer now if prompt is showing — it fires on dismiss if user wants more sets
        val postSetRoute = if (isLastSetOfExercise) null else when {
            restSeconds > 0 -> Screen.restTimer(restSeconds)
            else -> null
        }

        navigateTo = postSetRoute
    }

    /** User confirmed they are done with the current exercise. Advance to the next exercise
     *  and navigate to LogSetScreen. If it was the last exercise, go to LogWorkoutScreen. */
    fun confirmExerciseDone() {
        showExerciseDonePrompt = false
        val w = workout ?: return
        val nextExIdx = currentExerciseIndex + 1
        if (nextExIdx < w.exercises.size) {
            currentExerciseIndex = nextExIdx
            currentSetIndex = 0
        }
        // Always return to overview — user picks the next exercise from there
        navigateTo = Screen.LOG_WORKOUT
    }

    /** User wants another set — add one (prepopulated from last), then fire the rest timer. */
    fun dismissExerciseDone() {
        showExerciseDonePrompt = false
        addSetToCurrentExercise()   // copies weight + reps from last set, advances cursor
        navigateTo = when {
            pendingExerciseRestSeconds > 0 -> Screen.restTimer(pendingExerciseRestSeconds)
            else -> null
        }
        pendingExerciseRestSeconds = 0
    }

    // ── Pause / Resume ────────────────────────────────────────────────────────

    fun pauseWorkout() {
        if (isPaused) return
        isPaused = true
        hevyApp.workoutPausedAt = System.currentTimeMillis()
        // Stop the PPG while the user is paused — no point burning the sensor
        // on samples we'd later flag as "not actually working out".
        stopHrSampler()
        // B4 — durable flush at the pause boundary. Mutations through the
        // workout setter use apply() (async, write-coalesced), which is fine
        // for normal navigation; but a process kill while paused could lose
        // the last few state writes. Pause is rare and user-initiated, so the
        // 1-2 ms commit() cost here is invisible and worth the durability.
        workout?.let { hevyApp.activeWorkoutStore.saveBlocking(it) }
    }

    fun resumeWorkout() {
        if (!isPaused) return
        showAbandonmentNudge = false
        abandonmentNudgeAnswered = false
        val pausedAt = hevyApp.workoutPausedAt ?: return
        val pausedMs = System.currentTimeMillis() - pausedAt
        val w = workout ?: return
        val adjustedStartMs = w.startTimeMs + pausedMs
        workout = w.copy(startTimeMs = adjustedStartMs)
        // Keep hevyApp.activeWorkout in sync so WorkoutAwareTimeText shows the correct elapsed time
        hevyApp.adjustWorkoutStartTime(adjustedStartMs)
        hevyApp.workoutPausedAt = null
        isPaused = false
        maybeStartHrSampler(workout!!)
    }

    /** Resets all ViewModel-local workout state. Does NOT touch HevyApp. */
    private fun resetVmState() {
        historyFetchJob?.cancel()
        stopHrSampler()
        workout = null
        isPaused = false
        showAbandonmentNudge = false
        abandonmentNudgeAnswered = false
        isHistoryLoaded = false
        currentExerciseIndex = 0
        currentSetIndex = 0
        weightIncreasedExercises = emptySet()
        showWarmupAdvisorPrompt = false
        pendingWarmupOverrides = emptyMap()
        exerciseSuggestedWeights = emptyMap()
        saveError = null
        saveNotice = null
    }

    /** Clears workout state without navigating — callers handle routing. */
    fun clearWorkout() {
        resetVmState()
        hevyApp.clearActiveWorkout()
    }

    // ── Finish / Discard ──────────────────────────────────────────────────────

    private fun isConnected(): Boolean {
        val cm = hevyApp.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun finishWorkout() {
        val w = workout ?: return
        // Guard: an empty workout (zero completed sets across all exercises)
        // produces a useless POST and pollutes Hevy history. The "Discard"
        // path is the right exit when nothing was logged.
        val totalCompleted = w.exercises.sumOf { ex -> ex.sets.count { it.completed } }
        if (totalCompleted == 0) {
            saveError = "No completed sets — log at least one set or discard the workout."
            return
        }
        viewModelScope.launch {
            // Phase E — Wi-Fi is suppressed during workouts (Bluetooth-tether
            // still provides INTERNET in most cases). On Finish, retry the
            // connectivity check for up to 10 s so a slow BT-tether bring-up
            // doesn't make the user re-tap; if still no connection after the
            // window, surface a dialog and let the user exit while keeping
            // the recovery file so they can retry on the next launch.
            if (!isConnected()) {
                isAwaitingConnectivity = true
                val deadline = System.currentTimeMillis() + FINISH_CONNECTIVITY_WINDOW_MS
                while (!isConnected() && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(FINISH_CONNECTIVITY_POLL_MS)
                }
                isAwaitingConnectivity = false
                if (!isConnected()) {
                    showNoConnectivityWarning = true
                    return@launch
                }
            }
            isSaving = true
            saveError = null
            saveNotice = null
            pendingEndTimeMs = System.currentTimeMillis()
            val isContinuing = w.continuingWorkoutId != null
            try {
                saveWorkout(w, pendingEndTimeMs)
            } catch (e: Throwable) {
                isSaving = false
                saveProgress = null
                when (val kind = classifyFinishError(e)) {
                    is FinishError.InvalidRequest -> {
                        val detail = lastServerErrorBody?.take(120)
                        if (isContinuing) {
                            // Resume has already tried its own v1 PUT fallback
                            // internally, so there is nothing left to offer.
                            // Sticky and explicit: a rejected body is a real
                            // defect to report, and "tap Finish to retry" would
                            // be actively misleading — the same body will be
                            // rejected again.
                            saveError = "Hevy rejected the request (${kind.code}) — the API " +
                                    "shape has changed. Your workout is safe on the watch; " +
                                    "retrying won't help." +
                                    (detail?.let { " Hevy said: $it" } ?: "")
                            saveErrorSticky = true
                        } else {
                            // A normal workout hasn't tried the public path yet,
                            // and v1 takes a different body shape — so it may
                            // well succeed where v2 refused. Keep offering it,
                            // but name the actual problem.
                            fallbackError = "Hevy rejected the request body (${kind.code}). " +
                                    "Save via public API instead?" +
                                    (detail?.let { " ($it)" } ?: "")
                            showFallbackPrompt = true
                        }
                    }
                    is FinishError.AuthFailed -> {
                        saveError = if (isContinuing)
                            "Authentication failed — check your API key and try again."
                        else
                            "Authentication failed — open the companion app to refresh tokens, then try again."
                    }
                    is FinishError.NetworkError -> {
                        saveError = "Network error — check your connection and tap Finish again."
                    }
                    is FinishError.ServerError -> {
                        if (isContinuing) {
                            // Resume already has an internal v1-PUT fallback inside
                            // saveResumeWorkout (used when the v2 GET on Continue
                            // failed). If the primary POST+DELETE path also fails
                            // server-side, there's nothing further to offer — just
                            // surface the error and let the user retry.
                            saveError = "Save failed (${kind.code}). Tap Finish to retry."
                        } else {
                            fallbackError = "Private API failed (${kind.code}). Save via public API instead?"
                            showFallbackPrompt = true
                        }
                    }
                    is FinishError.Unknown -> {
                        if (isContinuing) {
                            saveError = "Save failed. Tap Finish to retry."
                        } else {
                            fallbackError = "Private API error. Save via public API instead?"
                            showFallbackPrompt = true
                        }
                    }
                }
                return@launch
            }
            recordAndNavigateCongrats(w, pendingEndTimeMs)
        }
    }

    /** User confirmed they want to fall back to public API after private API failure. */
    fun confirmFallbackToPublic() {
        val w = workout ?: return
        if (!isConnected()) {
            saveError = "No connection — connect to phone or WiFi before saving."
            showFallbackPrompt = false
            fallbackError = null
            isSaving = false
            return
        }
        showFallbackPrompt = false
        fallbackError = null
        viewModelScope.launch {
            isSaving = true
            saveError = null
            saveNotice = null
            try {
                val continuingId = w.continuingWorkoutId
                val originalDetail = hevyApp.continuingWorkoutDetail
                if (continuingId != null && originalDetail != null) {
                    val request = buildWorkoutPutRequest(originalDetail, w, pendingEndTimeMs)
                    hevyApp.pendingRequestStore.save("PUT", "v1/workouts/$continuingId", request)
                    runSaveAttempts(SaveProgress.Phase.FALLBACK, FALLBACK_SAVE_ATTEMPTS) {
                        hevyApp.requireApiService().putWorkout(continuingId, request).orThrow()
                    }
                    hevyApp.pendingRequestStore.clear()
                } else {
                    val request = buildPublicPostRequest(w, pendingEndTimeMs)
                    hevyApp.pendingRequestStore.save("POST", "v1/workouts", request)
                    runSaveAttempts(SaveProgress.Phase.FALLBACK, FALLBACK_SAVE_ATTEMPTS) {
                        hevyApp.requireApiService().postWorkout(request).orThrow()
                    }
                    hevyApp.pendingRequestStore.clear()
                }
            } catch (e: Throwable) {
                saveProgress = null
                saveError = when (val kind = classifyFinishError(e)) {
                    is FinishError.AuthFailed -> "Authentication failed on public API — your api-key may be invalid."
                    is FinishError.NetworkError -> "Network error — check your connection and try again."
                    // Both APIs rejecting the body is the end of the line: there
                    // is no third path to try, so say what happened rather than
                    // implying a retry would help.
                    is FinishError.InvalidRequest -> {
                        saveErrorSticky = true
                        "Public API rejected the request body (${kind.code}) — API shape changed." +
                            (lastServerErrorBody?.take(100)?.let { " Hevy said: $it" } ?: "")
                    }
                    is FinishError.ServerError -> "Public API failed (${kind.code})."
                    is FinishError.Unknown -> "Public API error: ${kind.message ?: "unknown"}"
                }
                isSaving = false
                return@launch
            }
            recordAndNavigateCongrats(w, pendingEndTimeMs)
        }
    }

    fun dismissFallbackPrompt() {
        showFallbackPrompt = false
        fallbackError = null
    }

    /** Phase E — user tapped OK on the no-connectivity warning after the 10 s
     *  retry timed out. End the in-memory workout flow but preserve the
     *  recovery file so the next launch can offer "Resume workout?". Wi-Fi
     *  is restored as part of [HevyApp.endWorkoutKeepingRecovery]. */
    fun confirmNoConnectivityExit() {
        showNoConnectivityWarning = false
        resetVmState()
        hevyApp.endWorkoutKeepingRecovery()
        navigateTo = Screen.MODE_SELECTION
    }

    fun confirmWarmupOverride() {
        val w = workout ?: return
        val exercises = w.exercises.toMutableList()
        pendingWarmupOverrides.forEach { (idx, suggested) ->
            val ex = exercises.getOrNull(idx) ?: return@forEach
            val nonWarmup = ex.sets.filter { it.setType != SetType.WARMUP }
            exercises[idx] = ex.copy(sets = suggested + nonWarmup)
        }
        workout = w.copy(exercises = exercises, warmupAdvisorApplied = true)
        pendingWarmupOverrides = emptyMap()
        showWarmupAdvisorPrompt = false
    }

    fun keepRoutineWarmups() {
        workout = workout?.copy(warmupAdvisorApplied = true)
        pendingWarmupOverrides = emptyMap()
        showWarmupAdvisorPrompt = false
    }

    private suspend fun saveWorkout(w: ActiveWorkout, endTimeMs: Long) {
        val continuingId = w.continuingWorkoutId
        if (continuingId != null) {
            saveResumeWorkout(continuingId, w, endTimeMs)
        } else {
            // New workout → POST via private API, with visible bounded retries.
            val request = buildPostRequest(w, endTimeMs)
            hevyApp.pendingRequestStore.save("POST", "v2/workout", request)
            runSaveAttempts(SaveProgress.Phase.PRIVATE, PRIVATE_SAVE_ATTEMPTS) {
                hevyApp.refreshTokenIfNeeded()
                hevyApp.requirePrivateApiService().postWorkoutPrivate(request).orThrow()
            }
            hevyApp.pendingRequestStore.clear()
        }
    }

    /**
     * Run a single save call [block] up to [maxAttempts] times, publishing
     * [saveProgress] before each attempt (so the spinner shows "attempt x/y"
     * for [phase]) and the failure reason between retries. Only
     * [isRetryableSaveError] failures are retried, with exponential backoff
     * (capped at [NETWORK_RETRY_MAX_DELAY_MS]); a non-retryable failure (auth /
     * 4xx) or the final attempt rethrows so the caller can fall back to the
     * public path or surface the error. [CancellationException] always
     * propagates so coroutine cancellation still works.
     */
    /**
     * Note why an attempt failed, whether or not the submit later succeeds.
     * A rejected request body (400/422) is called out as such: it means the API
     * shape no longer matches, which a retry cannot fix and a successful
     * fallback would otherwise conceal entirely.
     */
    private fun recordAttemptFailure(phase: SaveProgress.Phase, t: Throwable) {
        val where = if (phase == SaveProgress.Phase.PRIVATE) "Private API" else "Public API"
        val detail = (t as? retrofit2.HttpException)
            ?.response()?.errorBody()
            ?.let { runCatching { it.string() }.getOrNull() }
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (detail != null) lastServerErrorBody = detail
        val summary = when (val kind = classifyFinishError(t)) {
            is FinishError.InvalidRequest ->
                "$where rejected the request body (${kind.code}) — API shape changed"
            is FinishError.AuthFailed -> "$where auth failed"
            is FinishError.NetworkError -> "$where unreachable"
            is FinishError.ServerError -> "$where error (${kind.code})"
            is FinishError.Unknown -> "$where failed: ${kind.message?.take(60) ?: "unknown"}"
        }
        val full = summary + (detail?.take(100)?.let { " — $it" } ?: "")
        hevyApp.lastSubmitDiagnostic = full
        Log.w(RESUME_TAG, "attempt failed: $full", t)
    }

    private suspend fun runSaveAttempts(
        phase: SaveProgress.Phase,
        maxAttempts: Int,
        block: suspend () -> Unit,
    ) {
        var delayMs = SAVE_RETRY_INITIAL_DELAY_MS
        var lastError: Throwable? = null
        repeat(maxAttempts) { index ->
            saveProgress = SaveProgress(
                phase = phase,
                attempt = index + 1,
                maxAttempts = maxAttempts,
                lastError = lastError?.let { saveErrorReason(it) },
            )
            try {
                block()
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                // Record before deciding what to do with it. runSaveAttempts
                // wraps every attempt on every path -- new workout and resume,
                // private and public -- so this is the single point that sees
                // all four combinations.
                recordAttemptFailure(phase, t)
                val isLast = index == maxAttempts - 1
                if (isLast || !isRetryableSaveError(t)) throw t
                // Keep the reason on screen while we back off before retrying.
                saveProgress = SaveProgress(
                    phase = phase,
                    attempt = index + 1,
                    maxAttempts = maxAttempts,
                    lastError = saveErrorReason(t),
                )
                delay(delayMs)
                delayMs = (delayMs * SAVE_RETRY_FACTOR).toLong()
                    .coerceAtMost(NETWORK_RETRY_MAX_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("runSaveAttempts exhausted without error")
    }

    /**
     * Resume save. Two paths:
     *   1. **Preferred (POST + DELETE).** If the private v2 GET succeeded on
     *      Continue, we have the original biometrics. Build a merged POST
     *      body (original exercises + new exercises + combined HR samples +
     *      recomputed total_calories + new end_time), POST it as a fresh
     *      workout, then best-effort DELETE the original by id. Comments /
     *      likes / followers on the original are lost server-side — accepted
     *      tradeoff for getting biometrics to surface on resumed sessions.
     *   2. **Fallback (v1 PUT).** If the v2 detail isn't available (fetch
     *      failed on Continue, or pre-feature workout), we PUT v1 like the
     *      old path. Resumed-segment biometrics are dropped on the floor
     *      (v1 PUT whitelist rejects them) but the workout data isn't lost.
     */
    private suspend fun saveResumeWorkout(continuingId: String, w: ActiveWorkout, endTimeMs: Long) {
        val originalV2 = hevyApp.continuingWorkoutDetailV2
        val originalV1 = hevyApp.continuingWorkoutDetail
        // Log.w survives R8 here (proguard-rules.pro strips only d/v/i), so this
        // is readable from a release build via `adb logcat -s HevyResume`.
        // Which detail is present decides the whole path: no v2 means the
        // private POST is skipped entirely and the public PUT runs instead.
        Log.w(RESUME_TAG, "saveResume: id=$continuingId " +
                "v2=${if (originalV2 != null) "present" else "NULL"} " +
                "v1=${if (originalV1 != null) "present" else "NULL"}")
        // A missing v2 detail silently downgrades the whole save: the private
        // POST is skipped, biometrics are dropped, and what runs instead is the
        // public PUT — a different endpoint with different failure modes. That
        // is far too consequential to leave as a logcat line, so say it.
        if (originalV2 == null) {
            saveNotice = "Saved without heart-rate data — couldn't load the original workout" +
                (hevyApp.continuingWorkoutV2Error?.let { " ($it)" } ?: "") + "."
        }

        // ── Primary: private v2 POST + DELETE (needs the v2 detail for the
        //    biometrics merge). Retried a few times with visible attempts. ────
        if (originalV2 != null) {
            val combinedBiometrics = BiometricsBuilder.mergeForResume(
                original = originalV2.biometrics,
                newSamples = w.heartRateSamples,
                weightKg = hevyApp.bodyweightKg.toDouble(),
                ageYears = hevyApp.userProfileStore.ageAt(),
                sex = hevyApp.userProfileStore.sex
            )
            val typedRequest = buildResumePostRequestV2(originalV2, w, endTimeMs, combinedBiometrics)
            // Carry across any workout-level field Hevy returned that our DTO
            // does not model — a location, say — which the typed rebuild would
            // otherwise drop. Additive onto the known-good body: our values win,
            // server-owned identity fields are excluded, and a missing raw
            // capture makes this a no-op.
            val gson = com.example.hevywatch.util.GsonHolder.gson
            val rawOriginal = hevyApp.continuingWorkoutRawV2
            val request = com.example.hevycore.workout.ResumeBodyMerge.carryUnmodelledFields(
                gson.toJsonTree(typedRequest).asJsonObject,
                rawOriginal,
            )
            Log.w(RESUME_TAG, "saveResume: carried=" +
                    com.example.hevycore.workout.ResumeBodyMerge.carriedKeys(
                        gson.toJsonTree(typedRequest).asJsonObject, rawOriginal))
            Log.w(RESUME_TAG, "saveResume: withheld=" +
                    com.example.hevycore.workout.ResumeBodyMerge.withheldKeys(rawOriginal))
            hevyApp.pendingRequestStore.save("POST", "v2/workout", request)
            try {
                runSaveAttempts(SaveProgress.Phase.PRIVATE, PRIVATE_SAVE_ATTEMPTS) {
                    hevyApp.refreshTokenIfNeeded()
                    hevyApp.requirePrivateApiService().postWorkoutPrivateJson(request).orThrow()
                }
                hevyApp.pendingRequestStore.clear()
                // Reached only on a real 2xx now that the POST is checked, so
                // the original is never deleted for a create that did not
                // happen. Still best-effort: a failed delete leaves a duplicate
                // the user can remove in the official app, and the merged
                // workout is already stored.
                Log.w(RESUME_TAG, "saveResume: private v2 POST ok")

                // Never delete the original when the replacement carries less
                // than it did. A resume can only ever add to a workout, so
                // fewer exercises or fewer sets means something upstream — a
                // partial detail fetch, a bad merge — has already lost data,
                // and the DELETE would make that loss permanent and
                // unrecoverable. Keeping both leaves a duplicate to tidy up by
                // hand, which is a far cheaper mistake than a deleted session.
                val origExercises = originalV2.exercises.size
                val origSets = originalV2.exercises.sumOf { it.sets.size }
                val newExercises = typedRequest.workout.exercises.size
                val newSets = typedRequest.workout.exercises.sumOf { it.sets.size }
                if (newExercises < origExercises || newSets < origSets) {
                    Log.w(RESUME_TAG, "saveResume: REFUSING to delete the original — " +
                        "posted $newExercises exercises / $newSets sets vs the original's " +
                        "$origExercises / $origSets")
                    saveNotice = "Saved, but the copy has fewer exercises than the original " +
                        "($newExercises vs $origExercises), so the original was KEPT. " +
                        "You have a duplicate in Hevy — delete the wrong one yourself."
                    return
                }

                try {
                    val del = hevyApp.requirePrivateApiService().deleteWorkoutPrivate(continuingId)
                    if (!del.acceptedByHevy) {
                        // Previously invisible twice over: a non-2xx DELETE
                        // does not throw, so the catch below never ran and the
                        // log claimed success regardless of what Hevy said.
                        Log.w(RESUME_TAG, "saveResume: delete of original returned HTTP ${del.code()}")
                        saveNotice = "Saved, but the original workout couldn't be removed " +
                            "(${del.code()}) — you'll have a duplicate in Hevy."
                    }
                } catch (t: Throwable) {
                    Log.w(RESUME_TAG, "saveResume: delete of original failed (duplicate expected)", t)
                    saveNotice = "Saved, but the original workout couldn't be removed " +
                        "— you'll have a duplicate in Hevy."
                }
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (primaryError: Throwable) {
                // Hevy's own response text is the only thing that names a
                // rejected or newly-required field; the status alone cannot.
                val httpBody = (primaryError as? retrofit2.HttpException)
                    ?.response()?.errorBody()
                    ?.let { runCatching { it.string() }.getOrNull() }
                // The request we sent, next to Hevy's complaint. Resume echoes
                // server values (set indicator, rpe, custom_metric, the original
                // times) back into the request, unlike the normal POST which only
                // sends literals it builds itself -- so an unknown value arriving
                // in a response becomes an invalid value in our request. Workout
                // data only; the bearer token lives in a header, not the body.
                Log.w(RESUME_TAG, "saveResume: sent=" +
                        runCatching {
                            com.example.hevywatch.util.GsonHolder.gson.toJson(request).take(3000)
                        }.getOrDefault("<unserializable>"))
                lastServerErrorBody = httpBody?.trim()?.takeIf { it.isNotEmpty() }
                Log.w(RESUME_TAG, "saveResume: private v2 POST failed" +
                        ((primaryError as? retrofit2.HttpException)?.let { " HTTP ${it.code()}" } ?: "") +
                        (httpBody?.take(500)?.let { ": $it" } ?: ""), primaryError)
                // Private path exhausted. If a v1 detail is available, fall
                // through to the public PUT fallback (the user sees the phase
                // flip to FALLBACK). Otherwise there's nothing more to try —
                // surface the error so finishWorkout() can classify it.
                if (originalV1 == null) throw primaryError
            }
        }

        // ── Fallback: v1 PUT on the public api-key (no biometrics). Reached
        //    when the v2 detail was unavailable at Continue, OR the private
        //    attempts above were exhausted. Retried too. ────────────────────
        val v1 = originalV1
            ?: throw IllegalStateException("continuing workout has no original detail")
        val request = buildWorkoutPutRequest(v1, w, endTimeMs)
        hevyApp.pendingRequestStore.save("PUT", "v1/workouts/$continuingId", request)
        Log.w(RESUME_TAG, "saveResume: falling back to public v1 PUT")
        try {
            runSaveAttempts(SaveProgress.Phase.FALLBACK, FALLBACK_SAVE_ATTEMPTS) {
                hevyApp.requireApiService().putWorkout(continuingId, request).orThrow()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (putError: Throwable) {
            // The public v1 endpoint failing too is the important signal: it is
            // the documented, stable API, so a rejection here points at the body
            // our merge built rather than at anything on the private v2 side.
            val httpBody = (putError as? retrofit2.HttpException)
                ?.response()?.errorBody()
                ?.let { runCatching { it.string() }.getOrNull() }
            lastServerErrorBody = httpBody?.trim()?.takeIf { it.isNotEmpty() }
            Log.w(RESUME_TAG, "saveResume: public v1 PUT failed" +
                    ((putError as? retrofit2.HttpException)?.let { " HTTP ${it.code()}" } ?: "") +
                    (httpBody?.take(500)?.let { ": $it" } ?: ""), putError)
            Log.w(RESUME_TAG, "saveResume: PUT sent=" +
                    runCatching {
                        com.example.hevywatch.util.GsonHolder.gson.toJson(request).take(3000)
                    }.getOrDefault("<unserializable>"))
            throw putError
        }
        Log.w(RESUME_TAG, "saveResume: public v1 PUT ok")
        hevyApp.pendingRequestStore.clear()
    }

    private fun recordAndNavigateCongrats(w: ActiveWorkout, endTimeMs: Long) {
        WorkoutCompletionRecorder.record(hevyApp, w, endTimeMs)
        resetVmState()
        isSaving = false
        saveProgress = null
        navigateTo = Screen.CONGRATS
    }

    fun discardWorkout() {
        val routineId = workout?.routineId
        clearWorkout()
        navigateTo = if (routineId != null) {
            Screen.routineDetail(routineId)
        } else {
            Screen.ROUTINE_FOLDERS
        }
    }

    val hasAnyCompletedSets: Boolean
        get() = workout?.exercises?.any { ex -> ex.sets.any { it.completed } } == true

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateCurrentSet(transform: (ActiveSet) -> ActiveSet) {
        val w = workout ?: return
        val exercises = w.exercises.toMutableList()
        val exercise = exercises.getOrNull(currentExerciseIndex) ?: return
        val sets = exercise.sets.toMutableList()
        val set = sets.getOrNull(currentSetIndex) ?: return
        sets[currentSetIndex] = transform(set)
        exercises[currentExerciseIndex] = exercise.copy(sets = sets)
        workout = w.copy(exercises = exercises)
    }

    private fun buildPostRequest(w: ActiveWorkout, endTimeMs: Long) =
        buildWorkoutPostRequestV2(w, endTimeMs, biometricsFor(w))

    /** Compute the biometrics blob for [w] using stored demographics. Returns
     *  null when there are no HR samples — the builder then omits the field
     *  entirely (we'd rather send no biometrics than zeros). */
    private fun biometricsFor(w: ActiveWorkout) = BiometricsBuilder.fromActiveWorkout(
        workout = w,
        weightKg = hevyApp.bodyweightKg.toDouble(),
        ageYears = hevyApp.userProfileStore.ageAt(),
        sex = hevyApp.userProfileStore.sex
    )

    override fun onCleared() {
        super.onCleared()
        historyFetchJob?.cancel()
        throttledSaver.cancel()
        stopHrSampler()
    }

    companion object {
        /** logcat tag for the resume submit path. Log.w survives R8 (only
         *  d/v/i are stripped), so this is readable from a release build. */
        private const val RESUME_TAG = "HevyResume"

        /** Min interval between throttled saveBlocking() calls during a workout. */
        const val THROTTLE_INTERVAL_MS: Long = 2_000L
        /** Phase E — total window for "wait for connectivity" on Finish tap. */
        const val FINISH_CONNECTIVITY_WINDOW_MS: Long = 10_000L
        /** Phase E — poll interval inside the window. */
        const val FINISH_CONNECTIVITY_POLL_MS: Long = 500L

        /** Max private-path (POST) attempts before the public fallback. */
        const val PRIVATE_SAVE_ATTEMPTS: Int = 3
        /** Max public fallback (PUT/POST) attempts before surfacing the error. */
        const val FALLBACK_SAVE_ATTEMPTS: Int = 2
        /** First retry backoff; tripled each retry, capped at
         *  [NETWORK_RETRY_MAX_DELAY_MS]. */
        const val SAVE_RETRY_INITIAL_DELAY_MS: Long = 800L
        private const val SAVE_RETRY_FACTOR: Double = 3.0

    }
}

/**
 * Pure set-navigation helpers used by LogSetScreen's Prev / Next buttons.
 * Navigation is clamped to the current exercise — moving past the first or last
 * set of the exercise is a no-op, so the user never accidentally crosses into
 * a neighbouring exercise while arrow-scrubbing through sets.
 *
 * Extracted as a stateless object so the boundary logic can be unit-tested
 * without instantiating the full AndroidViewModel.
 */
object SetNavigation {
    fun nextWithinExercise(currentSetIndex: Int, totalSets: Int): Int =
        if (currentSetIndex + 1 < totalSets) currentSetIndex + 1 else currentSetIndex

    fun previousWithinExercise(currentSetIndex: Int): Int =
        if (currentSetIndex > 0) currentSetIndex - 1 else currentSetIndex

    fun isFirst(currentSetIndex: Int): Boolean = currentSetIndex <= 0

    fun isLast(currentSetIndex: Int, totalSets: Int): Boolean =
        totalSets <= 0 || currentSetIndex >= totalSets - 1
}

/**
 * True when the exercise has no normal set with a logged working weight. Used to
 * gate the similar-exercise weight suggestion — a routine that explicitly
 * prescribes a working weight on any normal set keeps that prescription
 * untouched, even if other normal sets are blank.
 *
 * `weight_kg: 0` from the API counts the same as null here: the user has not
 * given the watch a real working weight, so suggestion is welcome.
 */
internal fun exerciseNeedsSimilarSuggestion(exercise: ActiveExercise): Boolean =
    exercise.sets.none { it.setType == SetType.NORMAL && (it.weightKg ?: 0f) > 0f }

/**
 * Overwrite every normal set's weight with the similar-exercise suggestion and
 * flag them with `isSimilarSuggestion = true`. Callers are expected to gate
 * with [exerciseNeedsSimilarSuggestion] first, so we don't trample a
 * user-prescribed routine weight.
 */
internal fun applySimilarSuggestion(
    exercise: ActiveExercise,
    suggestion: SuggestedWeight,
): ActiveExercise = exercise.copy(
    sets = exercise.sets.map { s ->
        if (s.setType == SetType.NORMAL)
            s.copy(weightKg = suggestion.weightKg, isSimilarSuggestion = true)
        else s
    }
)

/**
 * How long a workout may sit paused before the user gets a "still training?"
 * prompt when they come back to it. 30 minutes is well past any real rest
 * period (the longest configured rest timer is minutes, not tens of minutes)
 * but short enough that a genuinely-forgotten session gets caught the same
 * day rather than becoming a stale incomplete workout the user later has to
 * clean up through the resume POST+DELETE flow.
 */
internal const val ABANDONMENT_THRESHOLD_MS: Long = 30L * 60 * 1000

/**
 * Pure decision for the post-pause abandonment nudge. Extracted so the
 * threshold behaviour is unit-testable without a ViewModel or a clock.
 *
 * Only fires for a workout that is *currently paused* and whose pause began
 * more than [ABANDONMENT_THRESHOLD_MS] ago. A null [pausedAtMs] means the
 * pause timestamp was never recorded (shouldn't happen while `isPaused`, but
 * treat it as "don't nag" rather than guessing).
 */
internal fun shouldNudgeAbandonment(
    isPaused: Boolean,
    pausedAtMs: Long?,
    nowMs: Long,
): Boolean {
    if (!isPaused) return false
    val since = pausedAtMs ?: return false
    return nowMs - since > ABANDONMENT_THRESHOLD_MS
}
