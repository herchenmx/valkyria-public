package com.example.hevycompanion.browse

/**
 * Which catalog the unified Browser is currently showing. Exclusive — exactly
 * one source is active at a time, never both. The Browser keeps a separate
 * filter for each source; flipping back and forth restores the per-source
 * chip state from [BrowsePrefs].
 */
enum class Source { HEVY, MM;
    companion object { val DEFAULT = HEVY }
}

/** Browser layout mode — the flat searchable list, or the 20-card Liftoff grid. */
enum class ViewMode { LIST, MUSCLE_GRID;
    companion object { val DEFAULT = LIST }
}
