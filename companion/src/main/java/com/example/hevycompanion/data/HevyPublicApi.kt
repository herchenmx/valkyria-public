package com.example.hevycompanion.data

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Public Hevy API client. Uses the user's personal api-key from
 * [BuildConfig.HEVY_PUBLIC_API_KEY]. Unlike the Bearer-authed auth
 * client, this mirrors what hevy.com's Swagger docs describe.
 */
interface HevyPublicApi {
    @GET("v1/exercise_templates")
    suspend fun getExerciseTemplates(
        @Header("api-key") apiKey: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 10
    ): ExerciseTemplatesResponse

    @GET("v1/routine_folders")
    suspend fun getRoutineFolders(
        @Header("api-key") apiKey: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 10
    ): RoutineFoldersResponse

    @POST("v1/routines")
    suspend fun postRoutine(
        @Header("api-key") apiKey: String,
        @Body body: PostRoutinesRequestBody
    ): retrofit2.Response<Unit>

    /** The user's logged sets for one exercise template, newest first. The
     *  public api-key is account-scoped, so this returns the same data the
     *  watch fetches over the private Bearer client — see the Strength
     *  Overview feature ([com.example.hevycompanion.overview]). */
    @GET("v1/exercise_history/{exerciseTemplateId}")
    suspend fun getExerciseHistory(
        @Header("api-key") apiKey: String,
        @Path("exerciseTemplateId") exerciseTemplateId: String,
        @Query("page") page: Int = 1
    ): ExerciseHistoryResponse

    /** The user's logged workouts. The public api-key is account-scoped, so the
     *  list returns full workout objects (title, start_time, routine_id and the
     *  nested exercises + sets). Strength Overview reads only the template IDs;
     *  the Recents feature ([com.example.hevycompanion.recents]) renders the
     *  id / title / start_time fields for its list. */
    @GET("v1/workouts")
    suspend fun getWorkouts(
        @Header("api-key") apiKey: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 10
    ): WorkoutsResponse

    /** One logged workout in full — exercises, sets and set types. Backs the
     *  Recents → Workout Detail completeness comparison. Same payload the watch
     *  fetches over its private Bearer client. */
    @GET("v1/workouts/{workoutId}")
    suspend fun getWorkout(
        @Header("api-key") apiKey: String,
        @Path("workoutId") workoutId: String
    ): WorkoutDetail

    /** A single routine (its prescription) wrapped as `{ "routine": {...} }`.
     *  Used by the Workout Detail screen to compare what was logged against
     *  what the routine prescribes. */
    @GET("v1/routines/{routineId}")
    suspend fun getRoutine(
        @Header("api-key") apiKey: String,
        @Path("routineId") routineId: String
    ): RoutineDetailWrapper

    /** Update a logged workout. Backs Recents → "Resume here": the original
     *  workout's exercises/sets are preserved verbatim and the newly logged
     *  remaining sets are merged in (see [com.example.hevycompanion.recents.ResumeRequestBuilder]).
     *  Mirrors the watch's v1 PUT resume fallback — same endpoint, same body,
     *  same account-scoped api-key auth. No biometrics (the phone isn't on the
     *  wrist), so the simpler v1 PUT suffices rather than the watch's primary
     *  private-v2 POST+DELETE path. */
    @PUT("v1/workouts/{workoutId}")
    suspend fun updateWorkout(
        @Header("api-key") apiKey: String,
        @Path("workoutId") workoutId: String,
        @Body body: WorkoutPutRequest
    ): retrofit2.Response<Unit>
}

// ---- workout update (PUT /v1/workouts/{id}) ---------------------------------
// Mirrors the watch's WorkoutPutRequest family. Gson serializes only the fields
// the v1 PUT whitelist accepts; biometrics / wearos_watch are never sent.

data class WorkoutPutRequest(
    @SerializedName("workout") val workout: WorkoutPutBody
)

data class WorkoutPutBody(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String,
    // NO is_private HERE, DELIBERATELY.
    // `is_private` is absent from the API's Workout response schema, so a
    // resume can never learn the original workout's visibility — GET
    // /v1/workouts/{id} simply doesn't report it. Sending the field anyway
    // meant sending a guess, and the guess was a hardcoded `false`, which
    // silently reverted every private workout to public on a v1-PUT resume.
    // Omitting the key entirely leaves the stored visibility untouched.
    // Mirrors the watch's WorkoutPutBody.
    @SerializedName("exercises") val exercises: List<WorkoutPutExercise>
)

data class WorkoutPutExercise(
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: String? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("sets") val sets: List<WorkoutPutSet>
)

data class WorkoutPutSet(
    @SerializedName("type") val type: String,
    @SerializedName("weight_kg") val weightKg: Float? = null,
    @SerializedName("reps") val reps: Int? = null,
    @SerializedName("distance_meters") val distanceMeters: Float? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    @SerializedName("custom_metric") val customMetric: Float? = null,
    @SerializedName("rpe") val rpe: Float? = null
)

data class ExerciseTemplatesResponse(
    @SerializedName("exercise_templates") val exerciseTemplates: List<ExerciseTemplate> = emptyList(),
    @SerializedName("page") val page: Int = 1,
    @SerializedName("page_count") val pageCount: Int = 1
)

data class ExerciseTemplate(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("type") val type: String? = null,
    @SerializedName("primary_muscle_group") val primaryMuscleGroup: String? = null,
    @SerializedName("secondary_muscle_groups") val secondaryMuscleGroups: List<String>? = null,
    @SerializedName("equipment") val equipment: String? = null,
    @SerializedName("is_custom") val isCustom: Boolean = false
)

// ---- exercise_history -------------------------------------------------------

data class ExerciseHistoryResponse(
    @SerializedName("exercise_history") val exerciseHistory: List<ExerciseHistoryEntry> = emptyList()
)

/** One logged set. The endpoint returns a flat list across all of the user's
 *  workouts; entries that share a [workoutId] belong to the same session. */
data class ExerciseHistoryEntry(
    @SerializedName("workout_id") val workoutId: String,
    @SerializedName("workout_start_time") val workoutStartTime: String? = null,
    @SerializedName("weight_kg") val weightKg: Float? = null,
    @SerializedName("reps") val reps: Int? = null,
    @SerializedName("set_type") val setType: String? = null
)

// ---- workouts ---------------------------------------------------------------

data class WorkoutsResponse(
    @SerializedName("workouts") val workouts: List<WorkoutSummary> = emptyList(),
    @SerializedName("page") val page: Int = 1,
    @SerializedName("page_count") val pageCount: Int = 1
)

/** One workout from the `/v1/workouts` list. The list returns full detail, but
 *  Strength Overview only needs [exercises]; the Recents list additionally
 *  renders [id] / [title] / [startTime] / [routineId]. */
data class WorkoutSummary(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String? = null,
    @SerializedName("routine_id") val routineId: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("exercises") val exercises: List<WorkoutExerciseRef> = emptyList()
)

/** An exercise inside a listed workout. [exerciseTemplateId] is all Strength
 *  Overview ever reads; [title] and [sets] are modelled for Routine Trends,
 *  which totals volume/sets/reps straight off the list payload rather than
 *  fetching a year of workouts one detail call at a time. Both default empty,
 *  so a list response that turns out to omit them degrades to "no sets" and
 *  the trends repo re-reads those workouts from `/v1/workouts/{id}`. */
data class WorkoutExerciseRef(
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("sets") val sets: List<WorkoutDetailSet> = emptyList()
)

// ---- workout detail (GET /v1/workouts/{id}) ---------------------------------

data class WorkoutDetail(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("routine_id") val routineId: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    // ALWAYS null in practice: `is_private` is not part of the API's Workout
    // response schema, so GET /v1/workouts/{id} never sends it. Kept only so
    // the field would populate if that ever changes — do NOT use it to decide
    // what to put in a request body, and never default it to `false`: that is
    // what silently republished private workouts on resume. Mirrors the
    // watch's WorkoutDetailResponse.isPrivate.
    @SerializedName("is_private") val isPrivate: Boolean? = null,
    @SerializedName("exercises") val exercises: List<WorkoutDetailExercise> = emptyList()
)

data class WorkoutDetailExercise(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("title") val title: String? = null,
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: Int? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("sets") val sets: List<WorkoutDetailSet> = emptyList()
)

data class WorkoutDetailSet(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("type") val type: String = "normal",
    @SerializedName("weight_kg") val weightKg: Float? = null,
    @SerializedName("reps") val reps: Int? = null,
    @SerializedName("distance_meters") val distanceMeters: Float? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    @SerializedName("rpe") val rpe: Float? = null,
    @SerializedName("custom_metric") val customMetric: Float? = null
)

// ---- routine detail (GET /v1/routines/{id}) ---------------------------------

/** GET /v1/routines/{id} returns `{ "routine": {...} }`. */
data class RoutineDetailWrapper(
    @SerializedName("routine") val routine: RoutineDetail
)

data class RoutineDetail(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("notes") val notes: String? = null,
    // The API returns folder_id as a numeric id (or null for "My Routines").
    @SerializedName("folder_id") val folderId: Long? = null,
    @SerializedName("exercises") val exercises: List<RoutineDetailExercise> = emptyList()
)

data class RoutineDetailExercise(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("title") val title: String? = null,
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: Int? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("rest_seconds") val restSeconds: Int? = null,
    @SerializedName("sets") val sets: List<RoutineDetailSet> = emptyList()
)

data class RoutineDetailSet(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("type") val type: String = "normal",
    @SerializedName("weight_kg") val weightKg: Float? = null,
    @SerializedName("reps") val reps: Int? = null,
    @SerializedName("distance_meters") val distanceMeters: Float? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    @SerializedName("custom_metric") val customMetric: Float? = null
)

// ---- routine_folders --------------------------------------------------------

data class RoutineFoldersResponse(
    @SerializedName("routine_folders") val routineFolders: List<RoutineFolder> = emptyList(),
    @SerializedName("page") val page: Int = 1,
    @SerializedName("page_count") val pageCount: Int = 1
)

data class RoutineFolder(
    @SerializedName("id") val id: Long,
    @SerializedName("index") val index: Int = 0,
    @SerializedName("title") val title: String,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

// ---- POST /v1/routines -------------------------------------------------------

data class PostRoutinesRequestBody(
    @SerializedName("routine") val routine: PostRoutinesRequestRoutine
)

data class PostRoutinesRequestRoutine(
    @SerializedName("title") val title: String,
    @SerializedName("folder_id") val folderId: Long?,
    @SerializedName("notes") val notes: String = "",
    @SerializedName("exercises") val exercises: List<PostRoutinesRequestExercise>
)

data class PostRoutinesRequestExercise(
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: Long? = null,
    @SerializedName("rest_seconds") val restSeconds: Int?,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("sets") val sets: List<PostRoutinesRequestSet>
)

data class PostRoutinesRequestSet(
    @SerializedName("type") val type: String = "normal",
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    @SerializedName("distance_meters") val distanceMeters: Int? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    @SerializedName("custom_metric") val customMetric: Float? = null,
    @SerializedName("rep_range") val repRange: RepRange? = null
)

data class RepRange(
    @SerializedName("start") val start: Int,
    @SerializedName("end") val end: Int
)

/**
 * @param serializeNulls when true (default), null fields are emitted explicitly
 *   — required by POST /v1/routines, whose `folder_id: null` is the signal to
 *   file a routine into the default folder (a missing key 400s). The Recents
 *   resume PUT builds with `serializeNulls = false` so optional set fields
 *   (`distance_meters`, `rpe`, …) are omitted rather than sent as null, matching
 *   the watch's known-good v1 PUT shape.
 */
fun buildHevyPublicApi(
    baseUrl: String = "https://api.hevyapp.com/",
    serializeNulls: Boolean = true,
): HevyPublicApi {
    val client = HevyHttpClientFactory.build {
        addInterceptor(com.example.hevycore.debug.DebugWebhookInterceptor("public"))
    }
    val gson = com.google.gson.GsonBuilder()
        .apply { if (serializeNulls) serializeNulls() }
        .create()
    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(HevyPublicApi::class.java)
}
