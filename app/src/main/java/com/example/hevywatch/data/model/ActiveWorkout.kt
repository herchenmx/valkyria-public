package com.example.hevywatch.data.model

data class ActiveWorkout(
    val name: String,
    val startTimeMs: Long = System.currentTimeMillis(),
    val exercises: List<ActiveExercise> = emptyList(),
    val routineId: String? = null,
    val progressiveOverload: Boolean = false,
    /** Non-null when continuing an incomplete workout. On Finish, the resume
     *  flow POSTs a merged workout via `POST /v2/workout` and then
     *  best-effort `DELETE /workout/{continuingWorkoutId}` to remove the
     *  original. See *Continue Incomplete Workout* in PRD-WATCH-APP.md. */
    val continuingWorkoutId: String? = null,
    /** True once the warmup advisor has made its decision on this workout — prevents re-prompting
     *  after app close/reopen when advisor-injected warmups would otherwise look like prescribed ones. */
    val warmupAdvisorApplied: Boolean = false,
    /** One reading per minute captured by HeartRateSampler while the workout
     *  is active and not paused. Populated on resumed workouts too — the
     *  merged resume POST appends these to the original workout's biometrics
     *  (loaded from `GET /workout/{id}` on Continue) before submitting. */
    val heartRateSamples: List<HeartRateSample> = emptyList()
)

/** Single HR reading. Field names + types match the v2 POST biometrics schema
 *  exactly (`bpm` as Double, `timestamp_ms` as Long-epoch-ms) so Gson can flatten
 *  the list into the request body with no transform. */
data class HeartRateSample(
    val bpm: Double,
    val timestamp_ms: Long
)
