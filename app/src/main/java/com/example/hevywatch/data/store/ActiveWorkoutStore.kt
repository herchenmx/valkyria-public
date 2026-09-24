package com.example.hevywatch.data.store

import android.content.Context
import com.example.hevywatch.data.model.ActiveWorkout

/**
 * Persists the active workout to SharedPreferences so it can be recovered
 * after a crash or unexpected process kill. Cleared after a successful
 * POST or explicit discard.
 *
 * `save()` (apply) is used for normal mutations — every weight/reps scroll
 * fires it; we don't want each tap to block on disk IO. Use
 * [saveBlocking] at durable checkpoints (set completion, pause) where a
 * guaranteed flush is worth the latency.
 */
class ActiveWorkoutStore(context: Context) : JsonPrefsStore<ActiveWorkout>(
    context,
    prefsName = "active_workout",
    key = "workout_json",
    type = ActiveWorkout::class.java,
) {
    fun load(): ActiveWorkout? = loadOrNull()
}
