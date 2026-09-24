package com.example.hevywatch.presentation.workout

import retrofit2.HttpException
import java.io.IOException

/**
 * Progress of a workout save, surfaced to the Finish spinner so the user sees
 * what's actually happening instead of an opaque "Saving…" that can spin for
 * minutes. Drives [com.example.hevywatch.presentation.workout.LogWorkoutScreen]'s
 * save overlay: which path is being tried (private vs the public fallback), the
 * attempt number, and why the previous attempt failed (incl. the HTTP code, so
 * a 429 rate-limit is visible).
 *
 * Pure data — covered by [SaveProgressTest].
 */
data class SaveProgress(
    val phase: Phase,
    val attempt: Int,
    val maxAttempts: Int,
    /** Human reason the previous attempt failed (e.g. "Rate limited (429)"),
     *  or null on the first attempt of a phase. */
    val lastError: String? = null,
) {
    enum class Phase {
        /** Primary path — private v2 POST (+DELETE) for resume, or POST for new. */
        PRIVATE,

        /** Fallback path — public api-key v1 PUT/POST after the private path
         *  was exhausted (or unavailable). */
        FALLBACK,
    }
}

/**
 * Whether a save failure is worth retrying. Transport failures and transient
 * server states (408 / 429 / 5xx) are; auth (401/403) and other 4xx client
 * errors are not — they won't fix themselves by waiting (auth → fall back to
 * the public path or refresh; a 4xx validation error means the body is wrong,
 * so retrying the identical body is pointless).
 *
 * Pure function — covered by [SaveProgressTest].
 */
internal fun isRetryableSaveError(t: Throwable): Boolean = when (t) {
    is HttpException -> t.code() == 408 || t.code() == 429 || t.code() in 500..599
    is IOException -> true
    else -> false
}

/**
 * Short, user-facing reason for a save failure, including the HTTP code so a
 * 429 (rate limit) or a 5xx is visible on the watch. Kept terse for the small
 * round display.
 *
 * Pure function — covered by [SaveProgressTest].
 */
internal fun saveErrorReason(t: Throwable): String = when (t) {
    is HttpException -> when (val code = t.code()) {
        401, 403 -> "Auth failed ($code)"
        429 -> "Rate limited (429)"
        in 500..599 -> "Server error ($code)"
        else -> "Failed ($code)"
    }
    is IOException -> "Network error"
    else -> t.message?.take(40) ?: "Unknown error"
}
