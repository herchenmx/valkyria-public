package com.example.hevycompanion.recents

import retrofit2.HttpException
import java.io.IOException

/**
 * Progress of a Resume submit, surfaced on the Resume screen so the user sees
 * what's happening — which path is being tried (private vs the public PUT
 * fallback), the attempt number, and why the previous attempt failed (incl. the
 * HTTP code). The companion counterpart to the watch's
 * [com.example.hevywatch.presentation.workout.SaveProgress].
 *
 * Pure data — covered by [SubmitProgressTest].
 */
data class SubmitProgress(
    val phase: Phase,
    val attempt: Int,
    val maxAttempts: Int,
    /** Human reason the previous attempt failed (e.g. "Rate limited (429)"),
     *  or null on the first attempt of a phase. */
    val lastError: String? = null,
) {
    enum class Phase {
        /** Primary path — private v2 POST (+DELETE). */
        PRIVATE,

        /** Fallback path — public api-key v1 PUT after the private path was
         *  exhausted (or unavailable). */
        FALLBACK,
    }
}

/**
 * Whether a submit failure is worth retrying. Transport failures and transient
 * server states (408 / 429 / 5xx) are; auth (401/403) and other 4xx client
 * errors are not. Mirrors the watch's `isRetryableSaveError`.
 *
 * Pure function — covered by [SubmitProgressTest].
 */
internal fun isRetryableSubmitError(t: Throwable): Boolean = when (t) {
    is HttpException -> t.code() == 408 || t.code() == 429 || t.code() in 500..599
    is IOException -> true
    else -> false
}

/**
 * Short, user-facing reason for a submit failure, including the HTTP code so a
 * 429 (rate limit) or a 5xx is visible. Mirrors the watch's `saveErrorReason`.
 *
 * Pure function — covered by [SubmitProgressTest].
 */
internal fun submitErrorReason(t: Throwable): String = when (t) {
    is HttpException -> when (val code = t.code()) {
        401, 403 -> "Auth failed ($code)"
        // 400/422 means the body shape is wrong, so neither a retry nor the
        // public fallback can help — say so rather than implying a retry.
        400, 422 -> "Hevy rejected the request body ($code) — API shape changed"
        429 -> "Rate limited (429)"
        in 500..599 -> "Server error ($code)"
        else -> "Hevy rejected the update ($code)"
    }
    is IOException -> "Network error — check your connection"
    else -> t.message?.take(60) ?: "Unknown error"
}
