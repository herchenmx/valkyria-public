package com.example.hevycompanion.overview

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevycompanion.BuildConfig
import com.example.hevycompanion.data.ExerciseTemplateRepo
import com.example.hevycompanion.data.buildHevyPublicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the Strength Overview screen. Mirrors [BrowserViewModel]'s shape: an
 * `isOpen` flag the host `when`-navigation switches on, plus loading / error /
 * data state written from a background coroutine.
 *
 * Uses the public api-key (compile-time), which is account-scoped, so the
 * overview works regardless of the watch-token login state — same as the
 * Browser and the Generator.
 */
class ExerciseMaxViewModel(app: Application) : AndroidViewModel(app) {

    private val apiKey = BuildConfig.HEVY_PUBLIC_API_KEY
    private val templateRepo = ExerciseTemplateRepo(context = app, apiKey = apiKey)
    private val repo = ExerciseMaxRepo(
        api = buildHevyPublicApi(),
        apiKey = apiKey,
        templates = templateRepo::getOrFetch,
    )

    var isOpen by mutableStateOf(false); private set
    var isLoading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var rows by mutableStateOf<List<ExerciseMaxRow>>(emptyList()); private set

    /** History-fetch progress for the loading UI ("Scanning 23 / 101…"). */
    var scanned by mutableStateOf(0); private set
    var total by mutableStateOf(0); private set

    // Sort / search / equipment-filter. Each is a private backing field so the
    // generated setter doesn't clash with the explicit setter fun (same idiom
    // as BrowserViewModel's `_source`).
    private var _sort by mutableStateOf(OverviewSort.NAME_ASC)
    val sort: OverviewSort get() = _sort

    private var _query by mutableStateOf("")
    val query: String get() = _query

    /** Selected equipment keys; empty = no equipment filter (show all). */
    private var _equipmentFilter by mutableStateOf<Set<String>>(emptySet())
    val equipmentFilter: Set<String> get() = _equipmentFilter

    /** Distinct equipment present in the loaded data, for the filter chips. */
    val knownEquipment: List<String> by derivedStateOf {
        rows.map { it.equipment }.distinct().sortedBy { it.lowercase() }
    }

    /** True while a search or equipment filter is narrowing the list — the
     *  screen uses this to auto-expand muscle groups so matches are visible. */
    val isFiltering: Boolean get() = _query.isNotBlank() || _equipmentFilter.isNotEmpty()

    private fun filteredRows(): List<ExerciseMaxRow> {
        val q = _query.trim()
        val equip = _equipmentFilter
        return rows.filter { row ->
            (q.isEmpty() || row.title.contains(q, ignoreCase = true)) &&
                (equip.isEmpty() || row.equipment in equip)
        }
    }

    val sections: List<MuscleSection> by derivedStateOf { groupByMuscle(filteredRows(), _sort) }

    // Muscle-group expand/collapse. Default collapsed (a tidy overview you
    // drill into); held here so it survives leaving and re-entering the screen.
    private val expandedMuscles = mutableStateMapOf<String, Boolean>()

    fun isMuscleExpanded(muscleGroup: String): Boolean =
        expandedMuscles[muscleGroup] ?: false

    fun toggleMuscle(muscleGroup: String) {
        expandedMuscles[muscleGroup] = !isMuscleExpanded(muscleGroup)
    }

    fun setSort(next: OverviewSort) {
        _sort = next
    }

    fun setQuery(next: String) {
        _query = next
    }

    fun toggleEquipmentFilter(equipment: String) {
        _equipmentFilter =
            if (equipment in _equipmentFilter) _equipmentFilter - equipment
            else _equipmentFilter + equipment
    }

    fun clearEquipmentFilter() {
        _equipmentFilter = emptySet()
    }

    fun open() {
        isOpen = true
        if (rows.isEmpty() && !isLoading) load()
    }

    fun close() {
        isOpen = false
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            error = null
            scanned = 0
            total = 0
            try {
                rows = repo.load { d, t -> scanned = d; total = t }
            } catch (e: Exception) {
                error = "Couldn't load your history: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Writes the current overview to a CSV in the cache dir and returns a
     * share [Intent] (or null if there's nothing to export). The caller fires
     * it through a chooser. Uses [FileProvider] (`${applicationId}.fileprovider`)
     * so the temp file is readable by the receiving app.
     */
    fun exportCsvIntent(context: Context): Intent? {
        // Export what's currently visible (search + equipment filter applied),
        // in the active sort order — matches the on-screen table.
        val visible = filteredRows()
        if (visible.isEmpty()) return null
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "strength-overview.csv")
        file.writeText(ExerciseMaxCsv.toCsv(visible, sort))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Strength Overview")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
