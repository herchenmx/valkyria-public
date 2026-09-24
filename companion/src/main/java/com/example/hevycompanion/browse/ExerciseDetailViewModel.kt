package com.example.hevycompanion.browse

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevycompanion.BuildConfig
import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.ExerciseTemplateRepo
import com.example.hevycompanion.data.HevyExerciseAttrMap
import com.example.hevycompanion.data.HevyExerciseAttrs
import com.example.hevycompanion.generate.mm.MmCatalog
import com.example.hevycompanion.generate.mm.MmExercise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Global navigation host for the Hevy / M&M exercise detail page.
 *
 * Every feature surface that surfaces an exercise (the unified Browser, both
 * workout generators) talks to a single
 * instance of this VM, so:
 *
 *  - There is one source of truth for "what detail page is showing right
 *    now" — easy to render at the top of [com.example.hevycompanion.MainActivity]
 *    so the detail screen takes over from any feature.
 *  - Tapping a similar-exercise row inside the detail screen pushes a new
 *    selection onto a back-stack instead of replacing in place — predictive
 *    back / system-back / the explicit "← Back" button all pop one step,
 *    matching what users expect from any other Android navigation.
 *
 * The catalogs are loaded lazily on first selection so any feature can
 * launch the detail page even if the user never opened the searchable
 * list. [ExerciseTemplateRepo] is the same disk-cached instance every
 * other Hevy feature uses, so no double-fetch.
 */
class ExerciseDetailViewModel(app: Application) : AndroidViewModel(app) {

    sealed class Selection {
        data class Hevy(val templateId: String) : Selection()
        data class Mm(val exerciseId: String) : Selection()
    }

    private val repo = ExerciseTemplateRepo(
        context = app,
        apiKey = BuildConfig.HEVY_PUBLIC_API_KEY,
    )

    /** Bundled `{id → level/goal/category}` side table. Lazy because the
     *  asset (~53 KB) is wasted memory if the user never opens a Hevy detail. */
    private val attrsById: Map<String, HevyExerciseAttrs> by lazy {
        HevyExerciseAttrMap.all(app)
    }

    /**
     * Detail navigation back-stack. Each push corresponds to one detail
     * screen the user can return to via swipe-back. Replacing-in-place
     * (the previous behavior) made re-targeting feel snappy but lost the
     * navigation breadcrumb — opening A → tap-similar B → tap-similar C
     * left no way to step back to B.
     */
    var stack by mutableStateOf<List<Selection>>(emptyList())
        private set

    /** Snapshot of the most-recent Hevy catalog fetch. Empty until the
     *  first Hevy detail is opened. */
    var hevyCatalog by mutableStateOf<List<ExerciseTemplate>>(emptyList())
        private set

    /** Bundled M&M catalog. Lazy so the JSON parse is deferred until needed,
     *  but [MmCatalog] memoizes for the process so peer features share it. */
    val mmCatalog: List<MmExercise> by lazy { MmCatalog.all(getApplication()) }

    /** Top of the stack — the detail page currently visible, if any. */
    val current: Selection? by derivedStateOf { stack.lastOrNull() }

    /** Convenience: did anything push a detail onto the stack? */
    val isOpen: Boolean by derivedStateOf { stack.isNotEmpty() }

    /** Resolve the active Hevy selection against the loaded catalog. */
    val hevyTarget: ExerciseTemplate? by derivedStateOf {
        val sel = current as? Selection.Hevy ?: return@derivedStateOf null
        hevyCatalog.firstOrNull { it.id == sel.templateId }
    }

    /** Resolve the active M&M selection against the bundled catalog. */
    val mmTarget: MmExercise? by derivedStateOf {
        val sel = current as? Selection.Mm ?: return@derivedStateOf null
        mmCatalog.firstOrNull { it.id == sel.exerciseId }
    }

    fun openHevy(templateId: String) {
        ensureHevyLoaded()
        stack = stack + Selection.Hevy(templateId)
    }

    fun openMm(exerciseId: String) {
        stack = stack + Selection.Mm(exerciseId)
    }

    /** Pop one step. If the stack empties, the detail screen disappears
     *  and the spawning feature surface becomes visible again. */
    fun goBack() {
        if (stack.isEmpty()) return
        stack = stack.dropLast(1)
    }

    /** Force-close every detail level. Currently only used for "→ Back"
     *  symmetry on configuration churn; in normal flow [goBack] is the
     *  one-step pop tied to swipe-back / explicit "← Back". */
    fun closeAll() { stack = emptyList() }

    /** Bundled level/category attrs for a Hevy exercise; null if unknown. */
    fun attrsFor(exerciseId: String): HevyExerciseAttrs? = attrsById[exerciseId]

    private fun ensureHevyLoaded() {
        if (hevyCatalog.isNotEmpty()) return
        repo.cached()?.let { hevyCatalog = it; return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                hevyCatalog = repo.refresh()
            } catch (_: Exception) {
                // Detail screen will render with an empty catalog → "No
                // similar exercises" placeholder instead of crashing.
            }
        }
    }
}
