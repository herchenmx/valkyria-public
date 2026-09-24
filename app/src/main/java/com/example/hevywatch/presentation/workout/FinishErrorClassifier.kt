package com.example.hevywatch.presentation.workout

import retrofit2.HttpException
import java.io.IOException

/**
 * Classifies failures from the Finish Workout flow so the UI can present a
 * meaningful, actionable message instead of a generic "save failed" prompt.
 *
 * Pure function — no Android dependencies — covered by [FinishErrorClassifierTest].
 */
internal sealed class FinishError {
    /** Auth was rejected (401/403). User must refresh tokens via the companion. */
    object AuthFailed : FinishError()

    /** Transport failure (no route, BT drop, DNS, timeout). User can retry. */
    object NetworkError : FinishError()

    /**
     * Hevy rejected the request body itself (400 / 422). Distinct from
     * [ServerError] because retrying or falling back to the public API cannot
     * help: the same body will be rejected again. In practice this means Hevy
     * changed the shape it accepts — a field we don't send, or one we send that
     * it no longer takes — so it must be reported explicitly rather than folded
     * into a generic "save failed".
     */
    data class InvalidRequest(val code: Int) : FinishError()

    /** Server returned a non-auth HTTP error. Public API fallback may help. */
    data class ServerError(val code: Int) : FinishError()

    /** Unknown failure mode. Public API fallback may help. */
    data class Unknown(val message: String?) : FinishError()
}

internal fun classifyFinishError(t: Throwable): FinishError = when (t) {
    is HttpException -> when (val code = t.code()) {
        401, 403 -> FinishError.AuthFailed
        400, 422 -> FinishError.InvalidRequest(code)
        else -> FinishError.ServerError(code)
    }
    is IOException -> FinishError.NetworkError
    else -> FinishError.Unknown(t.message)
}
