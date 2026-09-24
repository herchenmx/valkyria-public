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
import com.example.hevycompanion.muscle.HevyMuscleGroup
import com.example.hevycompanion.muscle.LiftoffCuratedPicks
import com.example.hevycompanion.muscle.LiftoffMuscleCard
import com.example.hevycompanion.muscle.LiftoffSlug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Unified Browser view-model. Keeps `(source, viewMode)` plus an independent
 * filter slot per source (Liftoff card grid / per-muscle list, searchable
 * Hevy list, searchable M&M list). Flipping the source restores its persisted
 * chip selection rather than carrying the previous source's state across.
 *
 * Hevy state requires a network fetch on first open (with disk fallback);
 * M&M is a bundled JSON asset, so it's effectively immediate after the
 * `MmCatalog.preload` startup call.
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ExerciseTemplateRepo(
        context = app,
        apiKey = BuildConfig.HEVY_PUBLIC_API_KEY,
    )
    private val browsePrefs = BrowsePrefs(app)

    /** Bundled `{id → level/goal/category}` side table for the level + category
     *  Hevy filters. Lazy because the asset (~53 KB) is wasted memory if the
     *  user only ever browses M&M. */
    private val attrsById: Map<String, HevyExerciseAttrs> by lazy {
        HevyExerciseAttrMap.all(app)
    }

    var isOpen by mutableStateOf(false); private set
    // Backing state for `source` / `viewMode` is kept private so the public
    // surface is read-only — mutations must go through `setSource` /
    // `setViewMode`, which also persist to prefs and trigger Hevy load. A
    // public `var ... private set` would collide on the JVM with the public
    // `setSource(...)` setter signature.
    private var _source by mutableStateOf(browsePrefs.lastSource)
    val source: Source get() = _source
    private var _viewMode by mutableStateOf(browsePrefs.lastViewMode)
    val viewMode: ViewMode get() = _viewMode

    // Hevy state ----------------------------------------------------------------
    var hevyTemplates by mutableStateOf<List<ExerciseTemplate>>(emptyList()); private set
    var isLoadingHevy by mutableStateOf(false); private set
    var hevyError by mutableStateOf<String?>(null); private set
    var hevyFilter by mutableStateOf(browsePrefs.hevyFilter); private set

    // M&M state -----------------------------------------------------------------
    val mmCatalog: List<MmExercise> by lazy { MmCatalog.all(app) }
    var mmFilter by mutableStateOf(browsePrefs.mmFilter); private set

    // Derived: distinct values for each filter dropdown. Hevy values come from
    // the loaded catalog; M&M values come from the bundled asset.
    val knownHevyMuscleGroups: List<String> by derivedStateOf {
        hevyTemplates
            .mapNotNull { it.primaryMuscleGroup?.lowercase()?.takeIf(String::isNotBlank) }
            .toSortedSet().toList()
    }
    val knownHevyEquipment: List<String> by derivedStateOf {
        hevyTemplates
            .mapNotNull { it.equipment?.lowercase()?.takeIf(String::isNotBlank) }
            .toSortedSet().toList()
    }
    val knownHevyExerciseTypes: List<String> by derivedStateOf {
        hevyTemplates
            .mapNotNull { it.type?.lowercase()?.takeIf(String::isNotBlank) }
            .toSortedSet().toList()
    }
    val knownHevyLevels: List<String> = listOf("beginner", "intermediate", "advanced")
    val knownHevyCategories: List<String> = listOf("compound", "isolation", "assistance-compound")

    val knownMmAreas: List<String> by lazy {
        mmCatalog.mapNotNull { it.area.takeIf(String::isNotBlank) }.toSortedSet().toList()
    }
    val knownMmSubAreas: List<String> by lazy {
        mmCatalog.flatMap { it.subAreas }.toSortedSet().toList()
    }
    val knownMmEquipment: List<String> by lazy {
        mmCatalog.flatMap { it.equipment }.toSortedSet().toList()
    }
    val knownMmCategories: List<String> by lazy {
        mmCatalog.mapNotNull { it.category.takeIf(String::isNotBlank) }.toSortedSet().toList()
    }
    val knownMmTypes: List<String> by lazy {
        mmCatalog.mapNotNull { it.type?.takeIf(String::isNotBlank) }.toSortedSet().toList()
    }
    val knownMmMovementPatterns: List<String> by lazy {
        mmCatalog.flatMap { it.movementPattern }.toSortedSet().toList()
    }

    /** Filter results; recompute only when the relevant inputs change. */
    val filteredHevy: List<ExerciseTemplate> by derivedStateOf {
        HevyExerciseListFilterEngine.apply(hevyTemplates, attrsById, hevyFilter)
    }
    val filteredMm: List<MmExercise> by derivedStateOf {
        MmExerciseListFilterEngine.apply(mmCatalog, mmFilter)
    }

    /**
     * Liftoff curated-picks split. Only meaningful when source = HEVY and the
     * user has narrowed to exactly one Hevy anatomical muscle group (i.e. they
     * just tapped a card in MUSCLE_GRID mode). Returns (suggested, rest).
     * Empty/empty for any other state — the screen renders the flat list.
     */
    val curatedSplit: Pair<List<ExerciseTemplate>, List<ExerciseTemplate>> by derivedStateOf {
        if (source != Source.HEVY) return@derivedStateOf emptyList<ExerciseTemplate>() to emptyList()
        if (hevyFilter.muscleGroups.size != 1) return@derivedStateOf emptyList<ExerciseTemplate>() to emptyList()
        val solo = hevyFilter.muscleGroups.first()
        if (solo !in HevyMuscleGroup.ANATOMICAL) return@derivedStateOf emptyList<ExerciseTemplate>() to emptyList()
        val matching = filteredHevy
        val curatedSlugs = LiftoffCuratedPicks.picksForHevyGroup(solo)
        if (curatedSlugs.isEmpty()) return@derivedStateOf emptyList<ExerciseTemplate>() to matching
        val suggested = mutableListOf<ExerciseTemplate>()
        val seenIds = mutableSetOf<String>()
        for (slug in curatedSlugs) {
            val match = matching.firstOrNull { ex ->
                slug in LiftoffSlug.candidateSlugs(ex.title)
            } ?: continue
            if (seenIds.add(match.id)) suggested += match
        }
        suggested to matching.filterNot { it.id in seenIds }
    }

    fun open() {
        isOpen = true
        if (_source == Source.HEVY) ensureHevyLoaded()
    }
    fun close() { isOpen = false }

    fun setSource(next: Source) {
        if (_source == next) return
        _source = next
        browsePrefs.lastSource = next
        if (next == Source.HEVY) ensureHevyLoaded()
    }

    fun setViewMode(next: ViewMode) {
        if (_viewMode == next) return
        _viewMode = next
        browsePrefs.lastViewMode = next
    }

    /**
     * Tap on a Liftoff muscle card (MUSCLE_GRID mode). Multi-select toggle:
     * adds to the active source's filter if not already included, removes if
     * it is. The view stays on MUSCLE_GRID so the user can keep picking; the
     * Source/View segmented controls (or the explicit "Show results" header
     * button) flip to LIST when they're done.
     *
     * For M&M, the union of `areas` (cards with no sub-area split — e.g.
     * Upper/Lower Chest, since M&M's catalog has no `Chest | …` sub-areas)
     * and `subAreas` (every other card) is OR'd by the filter engine when
     * both are populated, so mixing Chest + Quadriceps cards yields the
     * union of Chest exercises and Quad exercises rather than the (empty)
     * intersection.
     */
    fun onMuscleCardTap(card: LiftoffMuscleCard) {
        when (_source) {
            Source.HEVY -> updateHevyFilter {
                val current = it.muscleGroups
                val next = if (card.hevyGroup in current) current - card.hevyGroup
                           else current + card.hevyGroup
                it.copy(muscleGroups = next)
            }
            Source.MM -> updateMmFilter {
                if (card.mmSubAreas.isNotEmpty()) {
                    // Sub-area precision when available — Quadriceps card
                    // narrows to "Legs | Quads" + "Legs | Hip Flexors".
                    val cardSubs = card.mmSubAreas.toSet()
                    val allSelected = cardSubs.all { s -> s in it.subAreas }
                    val nextSub = if (allSelected) it.subAreas - cardSubs
                                  else it.subAreas + cardSubs
                    it.copy(subAreas = nextSub)
                } else {
                    // Some cards (Upper/Lower Chest) have no M&M sub-area
                    // analogue — area-level filter is the best we can do.
                    val area = card.mmAreaFallback
                    val nextAreas = if (area in it.areas) it.areas - area
                                    else it.areas + area
                    it.copy(areas = nextAreas)
                }
            }
        }
    }

    /**
     * Tap on an "Other" group chip in MUSCLE_GRID mode (Hevy only — those
     * groups don't exist in M&M). Toggle semantics matching the cards above;
     * the view stays on MUSCLE_GRID for further picking.
     */
    fun onOtherGroupTap(hevyGroup: String) {
        if (_source != Source.HEVY) return
        updateHevyFilter {
            val next = if (hevyGroup in it.muscleGroups) it.muscleGroups - hevyGroup
                       else it.muscleGroups + hevyGroup
            it.copy(muscleGroups = next)
        }
    }

    /**
     * Whether [card] is currently selected on the active source's filter.
     * Used to render the highlighted-card visual in MUSCLE_GRID mode.
     *
     * - HEVY: the card's `hevyGroup` is in the muscleGroups filter.
     * - MM: cards with sub-areas count as selected when ALL of their
     *   sub-areas are present in the subAreas filter (matches the toggle
     *   semantics in [onMuscleCardTap]); cards without sub-areas count as
     *   selected when the area fallback is in the areas filter.
     */
    fun isCardSelected(card: LiftoffMuscleCard): Boolean = when (_source) {
        Source.HEVY -> card.hevyGroup in hevyFilter.muscleGroups
        Source.MM -> if (card.mmSubAreas.isNotEmpty()) {
            card.mmSubAreas.all { it in mmFilter.subAreas }
        } else {
            card.mmAreaFallback in mmFilter.areas
        }
    }

    /** Whether an "Other" chip ("cardio" / "full_body" / "neck" / "other") is
     *  currently selected. HEVY-only — returns false on MM. */
    fun isOtherGroupSelected(hevyGroup: String): Boolean =
        _source == Source.HEVY && hevyGroup in hevyFilter.muscleGroups

    // Hevy filter setters --------------------------------------------------------
    fun setHevyQuery(q: String) = updateHevyFilter { it.copy(query = q) }
    fun toggleHevyMuscle(m: String) = updateHevyFilter {
        it.copy(muscleGroups = if (m in it.muscleGroups) it.muscleGroups - m else it.muscleGroups + m)
    }
    fun toggleHevyEquipment(e: String) = updateHevyFilter {
        it.copy(equipment = if (e in it.equipment) it.equipment - e else it.equipment + e)
    }
    fun toggleHevyExerciseType(t: String) = updateHevyFilter {
        it.copy(exerciseTypes = if (t in it.exerciseTypes) it.exerciseTypes - t else it.exerciseTypes + t)
    }
    fun toggleHevyLevel(l: String) = updateHevyFilter {
        it.copy(levels = if (l in it.levels) it.levels - l else it.levels + l)
    }
    fun toggleHevyCategory(c: String) = updateHevyFilter {
        it.copy(categories = if (c in it.categories) it.categories - c else it.categories + c)
    }
    fun clearHevyFilters() = updateHevyFilter { HevyExerciseListFilter() }

    // M&M filter setters ---------------------------------------------------------
    fun setMmQuery(q: String) = updateMmFilter { it.copy(query = q) }
    fun toggleMmArea(a: String) = updateMmFilter {
        it.copy(areas = if (a in it.areas) it.areas - a else it.areas + a)
    }
    fun toggleMmSubArea(s: String) = updateMmFilter {
        it.copy(subAreas = if (s in it.subAreas) it.subAreas - s else it.subAreas + s)
    }
    fun toggleMmEquipment(e: String) = updateMmFilter {
        it.copy(equipment = if (e in it.equipment) it.equipment - e else it.equipment + e)
    }
    fun toggleMmCategory(c: String) = updateMmFilter {
        it.copy(categories = if (c in it.categories) it.categories - c else it.categories + c)
    }
    fun toggleMmType(t: String) = updateMmFilter {
        it.copy(types = if (t in it.types) it.types - t else it.types + t)
    }
    fun toggleMmMovementPattern(p: String) = updateMmFilter {
        it.copy(movementPatterns = if (p in it.movementPatterns) it.movementPatterns - p else it.movementPatterns + p)
    }
    fun clearMmFilters() = updateMmFilter { MmExerciseListFilter() }

    private fun updateHevyFilter(transform: (HevyExerciseListFilter) -> HevyExerciseListFilter) {
        val next = transform(hevyFilter)
        hevyFilter = next
        browsePrefs.hevyFilter = next
    }

    private fun updateMmFilter(transform: (MmExerciseListFilter) -> MmExerciseListFilter) {
        val next = transform(mmFilter)
        mmFilter = next
        browsePrefs.mmFilter = next
    }

    private fun ensureHevyLoaded() {
        if (hevyTemplates.isNotEmpty() || isLoadingHevy) return
        repo.cached()?.let { hevyTemplates = it; return }
        viewModelScope.launch(Dispatchers.IO) {
            isLoadingHevy = true
            hevyError = null
            try {
                hevyTemplates = repo.refresh()
            } catch (e: Exception) {
                hevyError = "Could not load exercises: ${e.message}"
            } finally {
                isLoadingHevy = false
            }
        }
    }

    /** Hevy-only: bundled level/category attrs for a Hevy template. */
    fun hevyAttrsFor(templateId: String): HevyExerciseAttrs? = attrsById[templateId]
}
