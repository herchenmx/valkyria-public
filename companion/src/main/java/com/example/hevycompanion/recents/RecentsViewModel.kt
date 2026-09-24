package com.example.hevycompanion.recents

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevycompanion.BuildConfig
import com.example.hevycompanion.data.buildHevyPublicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One row in the Recents list — the fields the chip renders + the id/routine
 *  id the detail screen needs. */
data class RecentWorkout(
    val id: String,
    val title: String,
    val startTime: String?,
    val routineId: String?
)

/**
 * Drives the Recents screen — the user's recently logged workouts, newest
 * first, mirroring the watch app's "Recent" page. Same shape as
 * [com.example.hevycompanion.overview.ExerciseMaxViewModel]: an `isOpen` flag
 * the host `when`-navigation switches on, plus loading / error / data state.
 *
 * Selecting a row sets [selectedWorkoutId]; the host then renders the Workout
 * Detail screen over the list (back clears the selection). Uses the public
 * api-key, so it works regardless of the watch-token login state.
 *
 * The list is scoped to Progressive-Overload workouts only: a fetched workout
 * is shown only when its `routine_id` resolves to a routine in a PO folder
 * (see [ProgressiveOverloadFolders]). Workouts with no routine, or from a
 * non-PO routine, are hidden.
 */
class RecentsViewModel(app: Application) : AndroidViewModel(app) {

    private val apiKey = BuildConfig.HEVY_PUBLIC_API_KEY
    private val api = buildHevyPublicApi()

    var isOpen by mutableStateOf(false); private set
    var isLoading by mutableStateOf(false); private set
    var isRefreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var workouts by mutableStateOf<List<RecentWorkout>>(emptyList()); private set

    /** When non-null, the host shows the Workout Detail screen for this id. */
    var selectedWorkoutId by mutableStateOf<String?>(null); private set

    fun open() {
        isOpen = true
        if (workouts.isEmpty() && !isLoading) load()
    }

    /**
     * Pull-to-refresh: re-fetch in place. Unlike [load] this leaves the existing
     * list on screen (no full-screen spinner) and drives the pull indicator via
     * [isRefreshing] instead. A no-op while a refresh is already running.
     */
    fun refresh() {
        if (isRefreshing) return
        viewModelScope.launch(Dispatchers.IO) {
            isRefreshing = true
            error = null
            try {
                workouts = fetchRecents()
            } catch (e: Exception) {
                error = "Couldn't load recent workouts: ${e.message ?: "unknown error"}"
            } finally {
                isRefreshing = false
            }
        }
    }

    fun close() {
        isOpen = false
        selectedWorkoutId = null
    }

    fun openWorkout(id: String) { selectedWorkoutId = id }
    fun closeWorkout() { selectedWorkoutId = null }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            error = null
            try {
                workouts = fetchRecents()
            } catch (e: Exception) {
                error = "Couldn't load recent workouts: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Page through `/v1/workouts` (newest first already) up to [LIMIT] rows,
     * then keep only the workouts whose routine is in a PO folder.
     *
     * There's no list-routines endpoint on the public api, so we resolve each
     * distinct `routine_id` via the single-routine endpoint (the same call the
     * Workout Detail screen already makes) and check its `folder_id` against
     * [ProgressiveOverloadFolders]. The lookups run concurrently; a routine
     * that fails to resolve is treated as non-PO and its workouts are hidden.
     */
    private suspend fun fetchRecents(): List<RecentWorkout> {
        val out = mutableListOf<RecentWorkout>()
        var page = 1
        while (out.size < LIMIT) {
            val resp = api.getWorkouts(apiKey = apiKey, page = page, pageSize = PAGE_SIZE)
            resp.workouts.forEach { w ->
                out += RecentWorkout(
                    id = w.id,
                    title = w.title ?: "Workout",
                    startTime = w.startTime,
                    routineId = w.routineId
                )
            }
            if (page >= resp.pageCount) break
            page++
        }
        val poIds = poRoutineIds(out.mapNotNull { it.routineId }.toSet())
        // The API returns newest-first, but sort defensively on start_time so
        // the visible order always matches the dates shown.
        return filterToPoRoutines(out, poIds)
            .sortedByDescending { it.startTime ?: "" }
            .take(LIMIT)
    }

    /** Resolve [routineIds] concurrently to the subset that lives in a PO
     *  folder. A routine whose lookup fails is omitted (treated as non-PO). */
    private suspend fun poRoutineIds(routineIds: Set<String>): Set<String> = coroutineScope {
        routineIds.map { id ->
            async {
                val folderId = try {
                    api.getRoutine(apiKey = apiKey, routineId = id).routine.folderId
                } catch (_: Exception) {
                    null
                }
                id.takeIf { folderId != null && folderId.toString() in ProgressiveOverloadFolders.IDS }
            }
        }.awaitAll().filterNotNull().toSet()
    }

    companion object {
        private const val LIMIT = 30
        private const val PAGE_SIZE = 10

        /** Keep only workouts logged from a PO-folder routine. A workout with no
         *  `routine_id`, or one whose routine isn't in [poRoutineIds], is
         *  hidden. Pure — exercised directly by unit tests. */
        fun filterToPoRoutines(
            workouts: List<RecentWorkout>,
            poRoutineIds: Set<String>,
        ): List<RecentWorkout> =
            workouts.filter { it.routineId != null && it.routineId in poRoutineIds }
    }
}
