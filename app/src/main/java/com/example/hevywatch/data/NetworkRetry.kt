package com.example.hevywatch.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException

/**
 * Retries a suspending network call with exponential backoff. Use only for
 * read-style endpoints — writes (POST/PUT workout) must never be silently
 * retried client-side (they're covered by PendingRequestStore instead, which
 * is user-prompted and idempotent via workout id).
 *
 * Defaults: 3 attempts, delays 500 ms → 1.5 s → 4.5 s. Total worst-case wait
 * before surfacing an error is ~6 s, which fits inside the wait most users
 * tolerate on a Wear screen. [CancellationException] is always rethrown so
 * coroutine cancellation still works as expected.
 *
 * The default [shouldRetry] skips 401/403 — a stale token won't fix itself
 * by waiting, and burning two extra round-trips delays the refresh-token
 * path. Callers needing different behavior can pass their own predicate.
 */
private fun isAuthError(t: Throwable): Boolean {
    val code = (t as? HttpException)?.code() ?: return false
    return code == 401 || code == 403
}

val defaultShouldRetry: (Throwable) -> Boolean = { t -> !isAuthError(t) }

/** Hard ceiling so a tweaked [factor] or larger [attempts] count can never wedge
 * the watch UI for minutes. 30 s is well past the patience threshold but stays
 * inside Wear's foreground-service / ANR comfort zone. */
const val NETWORK_RETRY_MAX_DELAY_MS = 30_000L

suspend fun <T> withNetworkRetry(
    attempts: Int = 3,
    initialDelayMs: Long = 500L,
    factor: Double = 3.0,
    maxDelayMs: Long = NETWORK_RETRY_MAX_DELAY_MS,
    shouldRetry: (Throwable) -> Boolean = defaultShouldRetry,
    block: suspend () -> T
): T {
    require(attempts >= 1) { "attempts must be >= 1" }
    var delayMs = initialDelayMs.coerceAtMost(maxDelayMs)
    var lastError: Throwable? = null
    repeat(attempts) { index ->
        try {
            return block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            lastError = t
            val isLast = index == attempts - 1
            if (isLast || !shouldRetry(t)) throw t
            delay(delayMs)
            delayMs = (delayMs * factor).toLong().coerceAtMost(maxDelayMs)
        }
    }
    throw lastError ?: IllegalStateException("withNetworkRetry exhausted without a captured error")
}
