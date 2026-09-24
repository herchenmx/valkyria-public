package com.example.hevywatch.data.api

import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import com.example.hevywatch.data.api.model.RoutineDetailResponse
import com.example.hevywatch.data.api.model.WorkoutDetailResponse
import com.example.hevywatch.data.api.model.WorkoutDetailResponseV2
import com.google.gson.JsonObject
import com.example.hevywatch.data.api.model.ExerciseTemplatesResponse
import com.example.hevywatch.data.api.model.RefreshTokenRequest
import com.example.hevywatch.data.api.model.RefreshTokenResponse
import com.example.hevywatch.data.api.model.RoutineFoldersResponse
import com.example.hevywatch.data.api.model.RoutinesResponse
import com.example.hevywatch.data.api.model.WorkoutPostRequest
import com.example.hevywatch.data.api.model.WorkoutPostRequestV2
import com.example.hevywatch.data.api.model.WorkoutPutRequest
import com.example.hevywatch.data.api.model.WorkoutsListResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Path
import retrofit2.http.Query

interface HevyApiService {

    @GET("v1/routine_folders")
    suspend fun getRoutineFolders(): RoutineFoldersResponse

    @GET("v1/routines")
    suspend fun getRoutines(
        @Query("page") page: Int = 1
    ): RoutinesResponse

    @GET("v1/routines/{routineId}")
    suspend fun getRoutineDetail(
        @Path("routineId") routineId: String
    ): RoutineDetailResponse

    @GET("v1/exercise_templates")
    suspend fun getExerciseTemplates(
        @Query("page") page: Int = 1
    ): ExerciseTemplatesResponse

    @GET("v1/exercise_history/{exerciseTemplateId}")
    suspend fun getExerciseHistory(
        @Path("exerciseTemplateId") exerciseTemplateId: String,
        @Query("page") page: Int = 1
    ): ExerciseHistoryResponse

    @GET("v1/workouts")
    suspend fun getWorkouts(
        @Query("page") page: Int = 1
    ): WorkoutsListResponse

    @GET("v1/workouts/{workoutId}")
    suspend fun getWorkoutDetail(
        @Path("workoutId") workoutId: String
    ): WorkoutDetailResponse

    @POST("auth/refresh_token")
    suspend fun refreshToken(
        @Header("Authorization") authorization: String?,
        @Body body: RefreshTokenRequest
    ): RefreshTokenResponse

    @POST("v1/workouts")
    suspend fun postWorkout(
        @Body request: WorkoutPostRequest
    ): Response<Unit>

    @PUT("v1/workouts/{workoutId}")
    suspend fun putWorkout(
        @Path("workoutId") workoutId: String,
        @Body request: WorkoutPutRequest
    ): Response<Unit>

    @POST("v2/workout")
    suspend fun postWorkoutPrivate(
        @Body request: WorkoutPostRequestV2
    ): Response<Unit>

    /** Same endpoint, posting a JSON tree instead of the typed body. Used by
     *  resume so fields Hevy returns that [WorkoutPostBodyV2] does not declare
     *  can be carried through unchanged (see ResumeBodyMerge). The typed
     *  overload stays the path for a normal workout, which has nothing to
     *  carry. */
    @POST("v2/workout")
    suspend fun postWorkoutPrivateJson(
        @Body request: JsonObject
    ): Response<Unit>

    /** Private v2 GET — returns the full workout body INCLUDING biometrics
     *  (`heart_rate_samples`, `total_calories`, `average_heart_rate`). Used on
     *  resume to fetch the original session's HR data so the resumed-segment
     *  samples can be appended into a single combined POST. */
    // Returns the raw tree, not WorkoutDetailResponseV2. Gson discards any
    // field the DTO does not declare, and resume rebuilds its POST from that
    // parsed object -- so every field Hevy adds is silently dropped from the
    // replacement workout. Keeping the raw JSON lets resume echo the original
    // faithfully (see buildResumePostRequestV2) while the typed view is still
    // parsed from it locally for the merge logic.
    @GET("workout/{workoutId}")
    suspend fun getWorkoutPrivate(
        @Path("workoutId") workoutId: String
    ): JsonObject

    /** Private v2 DELETE — removes the workout entirely. Used as the second
     *  half of the resume POST+DELETE flow: after a successful POST of the
     *  merged workout, the original is deleted to avoid duplication. */
    @DELETE("workout/{workoutId}")
    suspend fun deleteWorkoutPrivate(
        @Path("workoutId") workoutId: String
    ): Response<Unit>
}
