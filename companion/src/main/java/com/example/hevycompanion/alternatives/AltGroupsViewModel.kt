package com.example.hevycompanion.alternatives

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevycompanion.BuildConfig
import com.example.hevycompanion.data.ExerciseTemplateRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Drives the "Exercise Alternatives" screen. Same `isOpen` shape the Browser /
 * Strength Overview use: a flag the host `when`-navigation switches on, plus
 * loading / error / data written from a background coroutine.
 *
 * Loads the exercise catalog (public api-key, cached 7-day TTL) once and joins
 * it against the curated [com.example.hevycompanion.recents.SubstitutionMap]
 * groups via [AltGroups]. Independent of login state, like the other
 * public-api features.
 */
class AltGroupsViewModel(app: Application) : AndroidViewModel(app) {

    private val templateRepo = ExerciseTemplateRepo(context = app, apiKey = BuildConfig.HEVY_PUBLIC_API_KEY)

    var isOpen by mutableStateOf(false); private set
    var isLoading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var groups by mutableStateOf<List<AltGroup>>(emptyList()); private set

    fun open() {
        isOpen = true
        if (groups.isEmpty() && !isLoading) load()
    }

    fun close() {
        isOpen = false
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            error = null
            try {
                val meta = templateRepo.getOrFetch().associateBy { it.id.uppercase() }
                groups = AltGroups.build(meta)
                if (groups.isEmpty()) error = "No alternative groups available."
            } catch (e: Exception) {
                error = "Couldn't load the exercise catalog: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }
}
