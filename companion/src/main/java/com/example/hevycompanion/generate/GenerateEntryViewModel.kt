package com.example.hevycompanion.generate

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.hevycompanion.browse.Source
import com.example.hevycompanion.generate.mm.MmGeneratorViewModel

/**
 * Entry coordinator for the unified Generate Workout feature. Owns the
 * persisted "which source was the user last using" + a single `isOpen` flag,
 * and delegates the actual setup/result/save flow to the existing
 * [GeneratorViewModel] (Hevy) and [MmGeneratorViewModel] (M&M).
 *
 * Why a coordinator and not a single merged VM: the two algorithms diverge
 * meaningfully (Hevy is shuffle-then-take with strict equipment/level/category
 * filtering and a save-to-Hevy-routine path; M&M is greedy volume-balanced
 * with movement-pattern penalties and no save target), and so do the result
 * screens (Hevy is a flat list with a muscle-share bar; M&M has warmup +
 * main blocks with a volume bar + cooldown). Squashing both into one VM
 * would be a heavy refactor that doesn't pay off — the user-visible win is
 * the single home-screen entry plus a Source segmented control on the
 * setup phase.
 *
 * Source persistence mirrors `BrowsePrefs.lastSource` so tapping "Generate
 * Workout" re-opens the catalog the user was last working with.
 */
class GenerateEntryViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    var isOpen by mutableStateOf(false); private set
    private var _source by mutableStateOf(loadSource())
    val source: Source get() = _source

    /**
     * Open the feature. Reads the persisted [source] and kicks the
     * corresponding inner VM into its first phase (Hevy → MusclePicker;
     * M&M → Setup).
     */
    fun open(genVm: GeneratorViewModel, mmGenVm: MmGeneratorViewModel) {
        isOpen = true
        when (_source) {
            Source.HEVY -> genVm.openPicker()
            Source.MM -> mmGenVm.open()
        }
    }

    /**
     * Close the feature. Always closes both inner VMs defensively — the
     * router doesn't track which one is "live", and closing an already-
     * closed VM is a no-op.
     */
    fun close(genVm: GeneratorViewModel, mmGenVm: MmGeneratorViewModel) {
        isOpen = false
        genVm.close()
        mmGenVm.close()
    }

    /**
     * Switch source mid-flow. Closes the current source's inner VM (so the
     * user doesn't return to a stale Result screen behind the curtain),
     * persists the new source, and opens the new source's first phase.
     *
     * Only callable from the SETUP phase via the on-screen segmented
     * control — we don't expose a toggle past that point because committing
     * to a source mid-workout doesn't make sense (the result is bound to
     * the catalog the algorithm ran against).
     */
    fun setSource(next: Source, genVm: GeneratorViewModel, mmGenVm: MmGeneratorViewModel) {
        if (_source == next) return
        when (_source) {
            Source.HEVY -> genVm.close()
            Source.MM -> mmGenVm.close()
        }
        _source = next
        prefs.edit().putString(KEY_LAST_SOURCE, next.name).apply()
        when (next) {
            Source.HEVY -> genVm.openPicker()
            Source.MM -> mmGenVm.open()
        }
    }

    private fun loadSource(): Source {
        val raw = prefs.getString(KEY_LAST_SOURCE, null) ?: return Source.DEFAULT
        return runCatching { Source.valueOf(raw) }.getOrDefault(Source.DEFAULT)
    }

    companion object {
        private const val PREFS = "hevy_generate_entry"
        private const val KEY_LAST_SOURCE = "last_source"
    }
}
