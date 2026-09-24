package com.example.hevywatch.data.api

import retrofit2.HttpException
import retrofit2.Response

/**
 * Turn a non-2xx [Response] into the exception the rest of the app already
 * knows how to classify.
 *
 * ## Why this exists
 *
 * A `suspend fun` returning `Response<T>` **does not throw** on an HTTP error —
 * Retrofit only throws for suspend functions declared to return the body type
 * directly. Every write in [HevyApiService] returns `Response<T>`, and until
 * this was introduced not one call site inspected the result:
 *
 * ```kotlin
 * runSaveAttempts(SaveProgress.Phase.PRIVATE, PRIVATE_SAVE_ATTEMPTS) {
 *     hevyApp.requirePrivateApiService().postWorkoutPrivateJson(request)  // result discarded
 * }
 * ```
 *
 * So a 400, 401 or 500 completed normally, the code logged "POST ok", ran the
 * follow-up DELETE (also unchecked), and showed the success screen. The
 * failure was structurally invisible: every error message, retry rule and
 * fallback in this app keys off a thrown exception, so none of them could
 * fire. That is why resume appeared to succeed while nothing reached Hevy —
 * and why the normal new-workout POST seemed fine, since it genuinely returns
 * 2xx and so never exercised the blindness.
 *
 * Calling this on every response makes an HTTP error observable again.
 * [retrofit2.HttpException] is what [classifyFinishError] already understands,
 * so no downstream code needs to change.
 */
fun <T> Response<T>.orThrow(): Response<T> =
    if (isSuccessful) this else throw HttpException(this)

/**
 * True when Hevy accepted the call. Use where a failure is tolerable and worth
 * *reporting* rather than throwing — the resume DELETE, whose failure leaves a
 * duplicate but loses nothing.
 */
val Response<*>.acceptedByHevy: Boolean get() = isSuccessful
