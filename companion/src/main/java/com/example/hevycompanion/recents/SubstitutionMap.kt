package com.example.hevycompanion.recents

/**
 * Companion-side entry point for the shared substitution table. Delegates to
 * [com.example.hevycore.exercise.SubstitutionMap] so the two apps can't
 * drift; keeps this typealias-style wrapper so existing call sites
 * (`WorkoutCompletion`, `AltGroups`, etc.) don't have to touch their imports.
 *
 * Substitution is only applied for routines in a Progressive-Overload folder
 * (see [ProgressiveOverloadFolders]); the scope check lives at the call site
 * in [WorkoutCompletion.buildCompletionStatuses].
 */
object SubstitutionMap {

    fun groupOf(templateId: String): Int? =
        com.example.hevycore.exercise.SubstitutionMap.groupOf(templateId)

    fun substitutesFor(templateId: String): List<String> =
        com.example.hevycore.exercise.SubstitutionMap.substitutesFor(templateId)

    fun allGroups(): List<List<String>> =
        com.example.hevycore.exercise.SubstitutionMap.allGroups()
}

/**
 * Folder IDs treated as Progressive-Overload folders for substitution + extras.
 *
 * Mirrors the watch's `ProgressiveOverloadStore.DEFAULT_FOLDER_IDS`. The watch
 * lets the user reconfigure this via SharedPreferences; the companion has no
 * such setting, so it pins the default. Compared against
 * [RoutineDetail.folderId] (a numeric id) as a string.
 */
object ProgressiveOverloadFolders {
    val IDS: Set<String> = setOf("2525049")
}
