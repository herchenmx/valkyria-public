package com.example.hevycompanion.trends

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevycompanion.BuildConfig
import com.example.hevycompanion.data.BodyweightPrefs
import com.example.hevycompanion.data.ExerciseTemplateRepo
import com.example.hevycompanion.data.buildHevyPublicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Drives **Routine Trends** — per-routine totals over the last 12 months, and
 * the exercise-level cause of the change between any two consecutive workouts
 * of that routine.
 *
 * Same shape as the other feature VMs ([com.example.hevycompanion.recents.RecentsViewModel],
 * [com.example.hevycompanion.overview.ExerciseMaxViewModel]): an `isOpen` flag
 * the host `when`-navigation switches on, plus loading / error / data state.
 * Public api-key, so it works regardless of the watch-token login state.
 *
 * Two levels of selection:
 *  - [selectedRoutineId] — which routine's chart is showing (null = routine list);
 *  - [selectedWorkoutId] — which point on that chart is expanded below it.
 *
 * The year of workouts is fetched **once** per open and held in memory, so
 * moving between routines and between points costs nothing. Pull-to-refresh on
 * the routine list re-runs the fetch.
 */
class RoutineTrendsViewModel(app: Application) : AndroidViewModel(app) {

    private val apiKey = BuildConfig.HEVY_PUBLIC_API_KEY
    private val api = buildHevyPublicApi()
    private val templateRepo = ExerciseTemplateRepo(context = app, apiKey = apiKey)
    private val repo = RoutineTrendRepo(
        api = api,
        apiKey = apiKey,
        templates = templateRepo::getOrFetch,
    )
    private val bodyweightPrefs = BodyweightPrefs(app)

    var isOpen by mutableStateOf(false); private set
    var isLoading by mutableStateOf(false); private set
    var isRefreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var trends by mutableStateOf<List<RoutineTrend>>(emptyList()); private set

    var selectedRoutineId by mutableStateOf<String?>(null); private set
    var selectedWorkoutId by mutableStateOf<String?>(null); private set

    /** Whether warmups count towards the totals. Defaults to [SetScope.ALL] so
     *  the volume shown reconciles with the figure Hevy puts on the workout. */
    var scope by mutableStateOf(SetScope.ALL); private set

    /** Which total the chart plots. The panel always shows all three. */
    var metric by mutableStateOf(TrendMetric.VOLUME); private set

    val selectedTrend: RoutineTrend?
        get() = trends.firstOrNull { it.routineId == selectedRoutineId }

    /** The chart's series for the selected routine, oldest → newest. */
    val series: List<TrendPoint>
        get() {
            val trend = selectedTrend ?: return emptyList()
            val rule = VolumeRule.forRoutine(trend.routineId, bodyweightPrefs.bodyweightKg)
            return trend.workouts.map { w ->
                TrendPoint(
                    workoutId = w.id,
                    epochMs = w.startEpochMs,
                    totals = WorkoutTotals.of(w, scope, rule),
                )
            }
        }

    /** The selected workout compared against its chronological predecessor in
     *  the same routine. Null until a routine + point are selected. */
    val comparison: RoutineComparison.Comparison?
        get() {
            val trend = selectedTrend ?: return null
            val index = trend.workouts.indexOfFirst { it.id == selectedWorkoutId }
            if (index < 0) return null
            return RoutineComparison.compare(
                current = trend.workouts[index],
                previous = trend.workouts.getOrNull(index - 1),
                scope = scope,
                rule = VolumeRule.forRoutine(trend.routineId, bodyweightPrefs.bodyweightKg),
            )
        }

    fun open() {
        isOpen = true
        if (trends.isEmpty() && !isLoading) load()
    }

    fun close() {
        isOpen = false
        selectedRoutineId = null
        selectedWorkoutId = null
    }

    fun retry() = load()

    /** Pull-to-refresh: re-fetch in place, leaving the current list on screen. */
    fun refresh() {
        if (isRefreshing) return
        viewModelScope.launch(Dispatchers.IO) {
            isRefreshing = true
            error = null
            try {
                trends = repo.load()
                // The refreshed list is a different set of objects, so a
                // selection made against the old one can dangle — re-point it
                // at the newest workout rather than leaving an empty panel.
                selectedRoutineId?.let { routineId ->
                    val workouts = trends.firstOrNull { it.routineId == routineId }?.workouts.orEmpty()
                    if (workouts.none { it.id == selectedWorkoutId }) {
                        selectedWorkoutId = workouts.lastOrNull()?.id
                    }
                    hydrate(routineId)
                }
            } catch (e: Exception) {
                error = "Couldn't load routine trends: ${e.message ?: "unknown error"}"
            } finally {
                isRefreshing = false
            }
        }
    }

    fun openRoutine(routineId: String) {
        selectedRoutineId = routineId
        // Newest workout first — the question is almost always "what happened
        // last time", and it's the rightmost point on the chart.
        selectedWorkoutId = trends.firstOrNull { it.routineId == routineId }
            ?.workouts?.lastOrNull()?.id
        hydrate(routineId)
    }

    fun closeRoutine() {
        selectedRoutineId = null
        selectedWorkoutId = null
    }

    fun selectWorkout(workoutId: String) { selectedWorkoutId = workoutId }

    // Named `select*` rather than `set*`: `var scope` / `var metric` already
    // generate private `setScope` / `setMetric` JVM setters, and a public
    // function of the same name would clash with them on the JVM.
    fun selectScope(value: SetScope) { scope = value }

    fun selectMetric(value: TrendMetric) { metric = value }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            error = null
            try {
                trends = repo.load()
            } catch (e: Exception) {
                error = "Couldn't load routine trends: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Top up any workout in [routineId] whose list payload arrived without
     * sets. Normally a no-op (the list endpoint sends them), so it runs
     * silently in the background rather than behind a spinner.
     */
    private fun hydrate(routineId: String) {
        val trend = trends.firstOrNull { it.routineId == routineId } ?: return
        if (trend.isHydrated) return
        viewModelScope.launch(Dispatchers.IO) {
            val filled = runCatching { repo.hydrate(trend) }.getOrNull() ?: return@launch
            trends = trends.map { if (it.routineId == routineId) filled else it }
        }
    }
}
