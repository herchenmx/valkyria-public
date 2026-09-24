package com.example.hevywatch.data.model

enum class ExerciseType(val apiValue: String) {
    WEIGHT_AND_REPS("weight_and_reps"),
    WEIGHTED_BODYWEIGHT("weighted_bodyweight"),
    ASSISTED_BODYWEIGHT("assisted_bodyweight"),
    REPS_ONLY("reps_only"),
    DISTANCE_AND_DURATION("distance_and_duration"),
    DURATION("duration"),
    WEIGHT_AND_DISTANCE("weight_and_distance"),
    WEIGHT_AND_DURATION("weight_and_duration"),
    FLOORS_AND_DURATION("floors_and_duration"),
    STEPS_AND_DURATION("steps_and_duration");

    val usesWeight get() = this in setOf(
        WEIGHT_AND_REPS, WEIGHTED_BODYWEIGHT, ASSISTED_BODYWEIGHT,
        WEIGHT_AND_DISTANCE, WEIGHT_AND_DURATION
    )
    val usesReps get() = this in setOf(
        WEIGHT_AND_REPS, WEIGHTED_BODYWEIGHT, ASSISTED_BODYWEIGHT, REPS_ONLY
    )
}
