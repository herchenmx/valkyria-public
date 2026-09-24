package com.example.hevycompanion.data

import com.example.hevycompanion.BuildConfig
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * Private (Bearer-authed) Hevy v2 client — the companion counterpart to the
 * watch's `HevyApiClient.createPrivate` + the v2 endpoints on `HevyApiService`.
 * Backs **Recents → Resume here**, which mirrors the watch's resume mechanism:
 * a resumed workout is **replaced** (POST a merged new workout, DELETE the
 * original) rather than updated in place via the v1 PUT (see
 * `d12a5f2 "Watch: resume via POST + DELETE on private v2 so HR persists"`).
 *
 * Unlike the watch the phone has no biometrics of its own, but the merged POST
 * still passes the **original** session's biometrics straight through, so the
 * original HR chart survives the replace.
 *
 * The private v2 routes (`GET/DELETE workout/{id}`, `POST v2/workout`) are gated
 * server-side on the `Hevy-App-Version` / `Hevy-App-Build` headers — without
 * them the server 404s as if the route didn't exist. The companion already
 * tracks the spoofed wear-OS version pair (`HevyApiVersionPrefs`, synced from
 * `api-versions/active.json`); `Hevy-Platform: wearos` matches that pair, the
 * same impersonation the token bridge already relies on.
 */
interface HevyPrivateApi {

    /** Full v2 workout incl. biometrics + unix-seconds timestamps. */
    @GET("workout/{workoutId}")
    suspend fun getWorkout(@Path("workoutId") workoutId: String): WorkoutDetailResponseV2

    /** Same GET, unparsed. Gson drops every field WorkoutDetailResponseV2 does
     *  not declare, and resume rebuilds its POST from that parsed object -- so
     *  anything Hevy added since is silently lost from the replacement. The raw
     *  tree lets those fields be carried through (see ResumeBodyMerge). */
    @GET("workout/{workoutId}")
    suspend fun getWorkoutRaw(@Path("workoutId") workoutId: String): com.google.gson.JsonObject

    /** Create a (merged) workout. */
    @POST("v2/workout")
    suspend fun postWorkout(@Body request: WorkoutPostRequestV2): Response<Unit>

    /** Same endpoint, posting a JSON tree so resume can carry unmodelled
     *  fields through. Normal posts keep the typed overload. */
    @POST("v2/workout")
    suspend fun postWorkoutJson(@Body request: com.google.gson.JsonObject): Response<Unit>

    /** Delete the original after a successful merged POST. */
    @DELETE("workout/{workoutId}")
    suspend fun deleteWorkout(@Path("workoutId") workoutId: String): Response<Unit>
}

/**
 * @param accessToken the user's Bearer token ([AuthPrefs.accessToken]).
 * @param versionName / @param versionCode the spoofed Hevy app version
 *   ([HevyApiVersionPrefs]); fall back to [DEFAULT_APP_VERSION] / [DEFAULT_APP_BUILD]
 *   when the companion hasn't synced `active.json` yet.
 */
fun buildHevyPrivateApi(
    accessToken: String,
    versionName: String,
    versionCode: String,
    baseUrl: String = "https://api.hevyapp.com/",
): HevyPrivateApi {
    // No logging interceptor on the Bearer path — mirrors the watch's
    // buildPrivateClient invariant so the Authorization header can never leak
    // into logs even if a future change bumps the log level.
    val authInterceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("X-Api-Key", BuildConfig.HEVY_PRIVATE_API_KEY)
                .addHeader("Hevy-App-Version", versionName)
                .addHeader("Hevy-App-Build", versionCode)
                .addHeader("Hevy-Platform", "wearos")
                .build()
        )
    }
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        // Reads no headers, so it does not weaken the invariant above; mirrors
        // workout request/response bodies to the debug webhook when one is
        // configured, and is inert otherwise.
        .addInterceptor(com.example.hevycore.debug.DebugWebhookInterceptor("private"))
        .build()
    val gson = GsonBuilder().serializeNulls().create()
    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(HevyPrivateApi::class.java)
}

/** Spoofed wear-OS version fallback, matching the watch's BuildConfig defaults. */
const val DEFAULT_APP_VERSION = "3.0.12"
const val DEFAULT_APP_BUILD = "2032997"

// ── GET /workout/{id} (private v2) ───────────────────────────────────────────
// Ported from the watch's WorkoutDetailResponseV2 — times are unix-seconds, and
// biometrics + the wearos/biometrics-public flags are present (v1 strips them).

data class WorkoutDetailResponseV2(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("start_time") val startTime: Long,
    @SerializedName("end_time") val endTime: Long,
    @SerializedName("is_private") val isPrivate: Boolean?,
    @SerializedName("is_biometrics_public") val isBiometricsPublic: Boolean?,
    @SerializedName("wearos_watch") val wearosWatch: Boolean?,
    @SerializedName("biometrics") val biometrics: BiometricsBody?,
    @SerializedName("exercises") val exercises: List<WorkoutDetailExerciseV2> = emptyList()
)

data class WorkoutDetailExerciseV2(
    @SerializedName("title") val title: String?,
    @SerializedName("notes") val notes: String?,
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: String?,
    @SerializedName("rest_seconds") val restSeconds: Int?,
    @SerializedName("sets") val sets: List<WorkoutDetailSetV2> = emptyList()
)

data class WorkoutDetailSetV2(
    @SerializedName("index") val index: Int?,
    @SerializedName("indicator") val indicator: String?,   // "normal" / "warmup" / "dropset" / "failure"
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    @SerializedName("distance_meters") val distanceMeters: Float?,
    @SerializedName("duration_seconds") val durationSeconds: Int?,
    @SerializedName("custom_metric") val customMetric: Float?,
    @SerializedName("rpe") val rpe: Float?,
    @SerializedName("completed_at") val completedAt: String?
)

// ── POST /v2/workout (private v2) ────────────────────────────────────────────
// Ported from the watch's WorkoutPostRequestV2 family.

data class WorkoutPostRequestV2(
    @SerializedName("workout") val workout: WorkoutPostBodyV2
)

data class WorkoutPostBodyV2(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String = "",
    @SerializedName("media") val media: List<Any> = emptyList(),
    @SerializedName("start_time") val startTime: Long,
    @SerializedName("end_time") val endTime: Long,
    @SerializedName("routine_id") val routineId: String?,
    @SerializedName("wearos_watch") val wearosWatch: Boolean = true,
    @SerializedName("apple_watch") val appleWatch: Boolean = false,
    @SerializedName("workout_id") val workoutId: String,
    // NO is_private HERE, DELIBERATELY — this body is only ever built by the
    // resume path (ResumeRequestBuilder.buildPostV2). `is_private` is absent
    // from the Workout response schema, so a resume can't know the original's
    // visibility; sending a hardcoded `false` published private workouts.
    // Omitted, so the account default applies to the replacement record.
    @SerializedName("is_biometrics_public") val isBiometricsPublic: Boolean = true,
    @SerializedName("biometrics") val biometrics: BiometricsBody? = null,
    // These three exist on the watch's copy of this body (app/.../
    // WorkoutPostRequestV2.kt) and were missing here, so the companion's resume
    // POST sent a strict subset of the shape the watch posts successfully every
    // workout. The client is built with serializeNulls(), so on the watch these
    // go out as explicit `null` keys rather than being absent -- a difference
    // that matters if the endpoint validates on key presence.
    @SerializedName("trainer_program_id") val trainerProgramId: Any? = null,
    @SerializedName("exercises") val exercises: List<WorkoutPostExerciseV2>,
    @SerializedName("share_to_strava") val shareToStrava: Any? = null,
    @SerializedName("strava_activity_local_time") val stravaActivityLocalTime: Any? = null
)

data class BiometricsBody(
    @SerializedName("total_calories") val totalCalories: Int?,
    @SerializedName("heart_rate_samples") val heartRateSamples: List<HeartRateSampleBody> = emptyList()
)

data class HeartRateSampleBody(
    @SerializedName("bpm") val bpm: Double,
    @SerializedName("timestamp_ms") val timestampMs: Long
)

data class WorkoutPostExerciseV2(
    @SerializedName("title") val title: String,
    @SerializedName("exercise_template_id") val exerciseTemplateId: String,
    @SerializedName("superset_id") val supersetId: String? = null,
    @SerializedName("rest_timer_seconds") val restTimerSeconds: Int?,
    @SerializedName("notes") val notes: String = "",
    @SerializedName("sets") val sets: List<WorkoutPostSetV2>
)

data class WorkoutPostSetV2(
    @SerializedName("index") val index: Int,
    @SerializedName("type") val type: String,
    @SerializedName("weight_kg") val weightKg: Float?,
    @SerializedName("reps") val reps: Int?,
    @SerializedName("distance_meters") val distanceMeters: Float?,
    @SerializedName("duration_seconds") val durationSeconds: Int?,
    @SerializedName("custom_metric") val customMetric: Float?,
    @SerializedName("rpe") val rpe: Float?,
    @SerializedName("completed_at") val completedAt: String?
)
