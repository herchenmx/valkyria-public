package com.example.hevywatch.data.model

import java.util.UUID

data class ActiveSet(
    val id: String = UUID.randomUUID().toString(),
    val setType: SetType = SetType.NORMAL,
    val weightKg: Float? = null,
    val reps: Int? = null,
    val distanceMeters: Float? = null,
    val durationSeconds: Int? = null,
    val customMetric: Int? = null,
    val repRangeStart: Int? = null,
    val repRangeEnd: Int? = null,
    val completed: Boolean = false,
    val completedAtMs: Long? = null,
    // Display string of the last logged value for this exercise (e.g. "100 kg × 8")
    val previous: String? = null,
    // Weight from the previous session, stored when PO applies an increase (for reference display)
    val poBaseWeightKg: Float? = null,
    // True when the weight was suggested from a similar exercise (no direct history)
    val isSimilarSuggestion: Boolean = false,
    // True for sets carried over from an existing workout (Continue Workout flow).
    // Locked sets cannot be edited in the UI and are preserved verbatim in
    // the resume submission — either the merged POST body
    // (`buildResumePostRequestV2`) or the v1 PUT fallback body
    // (`buildWorkoutPutRequest`).
    val locked: Boolean = false
)
