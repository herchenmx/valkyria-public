package com.example.hevycompanion.browse

/**
 * Lightweight tag for whatever exercise the user just tapped in [BrowserScreen].
 * The screen only needs to tell the global [ExerciseDetailViewModel] which
 * catalog the id belongs to so it can dispatch to `openHevy(id)` /
 * `openMm(id)` correctly.
 *
 * No shared abstraction across the two catalogs — different fields drive
 * detail and similar-exercise rendering, so we keep the source split visible
 * to callers.
 */
sealed interface BrowseItem {
    val id: String
    data class Hevy(override val id: String) : BrowseItem
    data class Mm(override val id: String) : BrowseItem
}
