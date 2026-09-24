package com.example.hevywatch.data.model

import com.example.hevywatch.data.api.model.RoutineResponse

data class Routine(
    val id: String,
    val title: String,
    val notes: String?,
    val folderId: String?,
    val exercises: List<RoutineExercise>,
    val updatedAt: String,
    val progressiveOverload: Boolean = false
) {
    val exerciseCount: Int get() = exercises.size
}

data class RoutineExercise(
    val exerciseTemplateId: String,
    val index: Int,
    val title: String,
    val setCount: Int,
    val restSeconds: Int?,
    val sets: List<RoutineSet>,
    val equipment: String? = null,
    val notes: String? = null
)

data class RoutineSet(
    val type: SetType,
    val weightKg: Float?,
    val reps: Int?,
    val repRangeStart: Int?,
    val repRangeEnd: Int?,
    val distanceMeters: Float?,
    val durationSeconds: Int?,
)

fun RoutineResponse.toDomain(poFolderIds: Set<String> = emptySet()) = Routine(
    id = id,
    title = title,
    notes = notes,
    folderId = folderId,
    progressiveOverload = folderId != null && folderId in poFolderIds,
    exercises = exercises.sortedBy { it.index }.map { ex ->
        RoutineExercise(
            exerciseTemplateId = ex.exerciseTemplateId,
            index = ex.index,
            title = ex.title ?: ex.exerciseTemplateId,
            setCount = ex.sets.size,
            restSeconds = ex.restSeconds,
            equipment = ex.equipment,
            notes = ex.notes?.takeIf { it.isNotBlank() },
            sets = ex.sets.map { s ->
                RoutineSet(
                    type = SetType.fromApiValue(s.type),
                    weightKg = s.weightKg,
                    reps = s.reps,
                    repRangeStart = s.repRange?.start,
                    repRangeEnd = s.repRange?.end,
                    distanceMeters = s.distanceMeters,
                    durationSeconds = s.durationSeconds,
                )
            }
        )
    },
    updatedAt = updatedAt
)

fun Routine.toActiveWorkout() = ActiveWorkout(
    name = title,
    routineId = id,
    progressiveOverload = progressiveOverload,
    exercises = exercises.map { ex ->
        ActiveExercise(
            exerciseTemplateId = ex.exerciseTemplateId,
            title = ex.title,
            equipment = ex.equipment,
            sets = ex.sets.map { s ->
                ActiveSet(
                    setType = s.type,
                    weightKg = s.weightKg,
                    // Prefer fixed reps; fall back to start of rep range so chips & prepopulation work
                    reps = s.reps ?: s.repRangeStart ?: s.repRangeEnd,
                    repRangeStart = s.repRangeStart,
                    repRangeEnd = s.repRangeEnd,
                    distanceMeters = s.distanceMeters,
                    durationSeconds = s.durationSeconds,
                )
            },
            restTimerSeconds = ex.restSeconds,
            notes = ex.notes
        )
    }
)
