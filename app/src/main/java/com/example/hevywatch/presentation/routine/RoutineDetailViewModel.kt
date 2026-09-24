package com.example.hevywatch.presentation.routine

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.RoutineProgressComputer
import com.example.hevywatch.data.WorkoutDataLoader
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.data.model.RoutineWorkoutVolume
import com.example.hevywatch.data.model.toDomain
import com.example.hevywatch.data.withNetworkRetry
import com.example.hevywatch.presentation.navigation.Screen
import kotlinx.coroutines.launch

class RoutineDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val hevyApp get() = getApplication<HevyApp>()

    private var currentRoutineId: String? = null

    var isLoading by mutableStateOf(true)
        private set
    /** True while a user-tapped Refresh chip is in flight. Distinct from
     *  [isLoading] (the initial-load spinner). */
    var isRefreshing by mutableStateOf(false)
        private set
    var routine by mutableStateOf<Routine?>(null)
        private set
    var navigateTo by mutableStateOf<String?>(null)
        private set
    /** Routine-level volume progress entries (newest first). Only populated for PO routines. */
    var routineProgress by mutableStateOf<List<RoutineWorkoutVolume>>(emptyList())
        private set
    /** Average volume delta across all progress entries, or null if insufficient data. */
    var averageDeltaKg by mutableStateOf<Double?>(null)
        private set
    /** Whether this routine should show the progress page (only PO routines). */
    var showProgress by mutableStateOf(false)
        private set

    fun load(routineId: String) {
        currentRoutineId = routineId
        isLoading = true
        routine = hevyApp.cachedRoutines.firstOrNull { it.id == routineId }
        showProgress = routine?.progressiveOverload == true
        fetchData()
    }

    /**
     * User-triggered refresh. Clears the per-exercise history cache for this
     * routine, drops cached progress data, force-refetches the routine list
     * (so a renamed exercise / changed prescription shows up), then re-runs
     * [fetchData]. Drives the page-level full-screen spinner via
     * [isRefreshing] for the entire operation.
     */
    fun refresh() {
        val routineId = currentRoutineId ?: return
        viewModelScope.launch {
            isRefreshing = true
            // Drop per-exercise history so [fetchData] actually re-fetches
            // (otherwise WorkoutDataLoader.fetchExerciseHistory short-circuits
            // when the cache is populated). The screen overlays a full-screen
            // spinner over the still-mounted column while isRefreshing is
            // true, so the user never sees the resulting "no last session"
            // flash; both the routine list and `routineProgress` are
            // overwritten atomically on success.
            routine?.exercises?.forEach { ex ->
                hevyApp.exerciseHistoryCache.remove(ex.exerciseTemplateId)
            }
            try {
                val service = hevyApp.requireApiService()
                val all = mutableListOf<Routine>()
                var page = 1; var pageCount: Int
                do {
                    val response = withNetworkRetry { service.getRoutines(page) }
                    all += response.routines.map { it.toDomain(hevyApp.progressiveOverloadStore.enabledFolderIds) }
                    pageCount = response.pageCount; page++
                } while (page <= pageCount)
                val sorted = all.sortedBy { it.title.lowercase() }
                hevyApp.cachedRoutines = sorted
                hevyApp.cachedRoutinesAtMs = System.currentTimeMillis()
                routine = sorted.firstOrNull { it.id == routineId }
                showProgress = routine?.progressiveOverload == true
            } catch (_: Exception) {
                /* keep existing routine data if network fails */
            }
            // fetchData() re-fetches history + progress and stamps the
            // detail-refreshed timestamp on its successful exit (see below).
            fetchData()
            isRefreshing = false
        }
    }

    /**
     * Fetches data needed by this screen only:
     * - Exercise history (for long-press expand on each exercise)
     * - Workout details for progress (PO routines only, via GET /v1/workouts/{id})
     *
     * Also pre-warms similar-exercise history in the background so LogWorkoutScreen's
     * similar-exercise suggestion has a reference for any never-worked exercise in this
     * routine by the time the user taps Start. Does NOT fetch exercise_templates or PO
     * detection — those remain LogWorkoutScreen-local.
     */
    private fun fetchData() {
        val exercises = routine?.exercises
        if (exercises.isNullOrEmpty()) {
            isLoading = false
            return
        }
        val templateIds = exercises.map { it.exerciseTemplateId }
        viewModelScope.launch {
            try {
                // 1. Fetch exercise history (for long-press expand)
                WorkoutDataLoader.fetchExerciseHistory(templateIds, hevyApp)

                // 2. Fetch full workout details for progress (PO routines only)
                val currentRoutine = routine
                if (currentRoutine?.progressiveOverload == true) {
                    val workoutIds = currentRoutineId
                        ?.let { hevyApp.routineWorkoutIds[it] }
                        ?: emptySet()

                    if (workoutIds.isNotEmpty()) {
                        val service = hevyApp.requireApiService()
                        val details = workoutIds.mapNotNull { wId ->
                            try {
                                withNetworkRetry { service.getWorkoutDetail(wId) }
                            } catch (_: Exception) { null }
                        }
                        routineProgress = RoutineProgressComputer.compute(
                            details,
                            routineId = currentRoutineId,
                            bodyweightKg = hevyApp.bodyweightKg
                        )
                        averageDeltaKg = RoutineProgressComputer.averageDelta(routineProgress)
                    }
                }
                hevyApp.routineDetailRefreshedAtMs = System.currentTimeMillis()
            } finally {
                isLoading = false
            }
            // Pre-warm similar-exercise history in the background after the main fetch.
            // Runs after isLoading=false so it never delays the detail screen rendering;
            // a no-op if every candidate is already cached (cheap on return visits).
            WorkoutDataLoader.prefetchSimilarExerciseHistory(templateIds, hevyApp)
        }
    }

    fun onStartWorkout() {
        val r = routine ?: return
        // If a workout is already active, resume it instead of starting a new one
        if (hevyApp.activeWorkout != null) {
            navigateTo = Screen.LOG_WORKOUT
            return
        }
        hevyApp.startWorkout(r)
        navigateTo = Screen.LOG_WORKOUT
    }

    /** True if there's an active workout in progress (controls button label). */
    val hasActiveWorkout: Boolean
        get() = hevyApp.activeWorkout != null

    fun onNavigated() { navigateTo = null }
}
