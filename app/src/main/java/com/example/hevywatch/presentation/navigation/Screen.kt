package com.example.hevywatch.presentation.navigation

object Screen {
    const val MODE_SELECTION = "mode_selection"
    const val ROUTINE_FOLDERS = "routine_folders"
    const val ROUTINE_LIST = "routine_list/{folderId}"
    const val ROUTINE_DETAIL = "routine_detail/{routineId}"
    // No nav args — indices and workout state live in activity-scoped LogWorkoutViewModel
    const val LOG_WORKOUT = "log_workout"
    const val LOG_SET = "log_set"
    const val SWAP_EXERCISE = "swap_exercise"       // In-workout substitute picker (state in LogWorkoutViewModel)
    const val WORKOUT_CONTROL = "workout_control"   // Pause / Discard prompt
    const val REST_TIMER = "rest_timer/{seconds}"
    const val WORKOUT_DETAIL = "workout_detail/{workoutId}"
    const val CONGRATS = "congrats"
    const val SETTINGS = "settings"

    fun routineList(folderId: String) = "routine_list/$folderId"
    fun routineDetail(routineId: String) = "routine_detail/$routineId"
    fun restTimer(seconds: Int) = "rest_timer/$seconds"
    fun workoutDetail(workoutId: String) = "workout_detail/$workoutId"

    /** Routes the tile (an exported component reachable by other apps via
     *  crafted Intent extras) is allowed to deep-link into. Returns the input
     *  if it matches a known shape, else null — anything unrecognised gets
     *  ignored rather than navigating to an attacker-controlled destination. */
    fun sanitizeTileRoute(raw: String?): String? {
        if (raw == null) return null
        // Literal screens reachable from the tile.
        if (raw in setOf(ROUTINE_FOLDERS, LOG_WORKOUT, LOG_SET, WORKOUT_CONTROL)) return raw
        // routine_detail/<id> and workout_detail/<id> — allow only safe id
        // characters so a forged Intent can't smuggle path traversal or extra
        // path segments. workout_detail is the resume-on-watch hand-off target:
        // the companion sends /resume_workout and the watch deep-links here so
        // the user can tap Resume on the device that will do the logging.
        for (prefix in setOf("routine_detail/", "workout_detail/")) {
            if (raw.startsWith(prefix)) {
                val id = raw.removePrefix(prefix)
                if (id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                    return raw
                }
            }
        }
        return null
    }
}
