package com.example.hevycompanion.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for /auth/refresh_token.
 *
 * Replaces three near-identical implementations in [MainViewModel.refreshToken],
 * [TokenRefreshWorker.doWork], and [TokenWidgetProvider.refreshAndPush]. Each
 * caller still decides UI/log/retry behaviour via the returned [RefreshResult];
 * the interactor is responsible only for calling the API, persisting the new
 * tokens, and stamping prefs atomically so the widget reflects outcome correctly.
 */
/**
 * Stable identifiers for refresh failure categories. Persisted in [AuthPrefs]
 * (so the widget can render a category-appropriate string even after process
 * death) and consumed by [WidgetStatusFormatter] to map cause → display text.
 *
 * Cause-driven, not symptom-driven: 401 lives in [AUTH_EXPIRED] (its own
 * bucket because the recovery is "sign in again"), other 4XX lives in
 * [OTHER_HTTP] (developer signal — almost always a client/contract bug),
 * 5XX lives in [SERVER_DOWN], etc. See PRD-COMPANION-APP.md "Refresh error
 * taxonomy" for the full table.
 *
 * 429 (rate-limited) is deliberately NOT a persisted category: the worker
 * gets [RefreshResult.RateLimited] from the interactor, logs it, returns
 * Result.retry(), but the widget never surfaces it. Matches "usually
 * invisible to user" in the taxonomy.
 */
object RefreshErrorCategory {
    /** HTTP 5XX from Hevy. Retryable; not the user's fault. */
    const val SERVER_DOWN = "SERVER_DOWN"

    /** HTTP 401 — refresh token rejected. Interactor wipes credentials;
     *  widget swaps to sign-in glyph. */
    const val AUTH_EXPIRED = "AUTH_EXPIRED"

    /** HTTP 403 — account suspended / region-blocked. Retry won't help. */
    const val FORBIDDEN = "FORBIDDEN"

    /** Other 4XX or 3XX from Hevy. Developer signal — usually a contract
     *  drift on the request side. */
    const val OTHER_HTTP = "OTHER_HTTP"

    /** 2xx response but body is malformed or missing required fields, or
     *  the response failed to parse. Server-side contract drift. */
    const val CONTRACT = "CONTRACT"

    /** Transport-layer failure: DNS, socket timeout, TLS, no connectivity,
     *  OS process freezer surfacing as UnknownHostException. */
    const val NETWORK = "NETWORK"

    /** Local SharedPreferences / Keystore write failed. Very rare. */
    const val PERSISTENCE = "PERSISTENCE"
}

/** Stable identifiers for watch-push failure categories. */
object PushErrorCategory {
    /** No reachable watch node. Bluetooth off, watch dead, or no pairing. */
    const val NO_WATCH = "NO_WATCH"

    /** Send threw — Wearable MessageAPI timeout or transport error. */
    const val FAILED = "FAILED"
}

sealed class RefreshResult {
    /** Refresh succeeded and [AuthPrefs] now holds the new tokens. */
    data class Success(val expiresAt: String) : RefreshResult()

    /** No refresh token on file — caller should surface "log in first". */
    object NotLoggedIn : RefreshResult()

    /**
     * Server returned HTTP 401 — the stored refresh token is no longer
     * accepted (revoked, password changed elsewhere, account deleted, etc.).
     * The interactor has cleared [AuthPrefs] so the app is now in the same
     * logged-out state as if the user had tapped Logout. The caller should
     * not retry; the user has to sign in again.
     */
    object AuthExpired : RefreshResult()

    /** HTTP 5XX — Hevy is broken. Retryable. */
    data class ServerError(val code: Int) : RefreshResult()

    /**
     * HTTP 429 — Hevy is rate-limiting us. Intentionally NOT persisted as a
     * user-visible error: the widget keeps showing the last-known-good state.
     * When [retryAfterSeconds] is non-null / positive the worker schedules a
     * one-shot deferred refresh via `runAfterDelay` (capped at 1 h); when it
     * is missing the worker falls back to WorkManager's default exponential
     * backoff.
     */
    data class RateLimited(val retryAfterSeconds: Long?) : RefreshResult()

    /** HTTP 403 — account blocked / forbidden. Retry won't help. */
    data class Forbidden(val code: Int) : RefreshResult()

    /** Any other non-2xx HTTP response (other 4XX, or 3XX if it somehow
     *  reaches us despite OkHttp's redirect handling). Almost always a
     *  client/contract bug. */
    data class OtherHttpError(val code: Int) : RefreshResult()

    /** 2xx but body was malformed / had null required fields / failed to
     *  parse. Server contract drifted; client likely needs an update. */
    data class ContractError(val detail: String) : RefreshResult()

    /** Transport/network failure; [message] is the exception text. */
    data class NetworkError(val message: String) : RefreshResult()

    /** Local persistence write failed (encrypted prefs / Keystore). Rare. */
    data class PersistenceError(val message: String) : RefreshResult()
}

class RefreshTokenInteractor(
    private val api: HevyAuthApi = buildHevyAuthApi(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Calls /auth/refresh_token and atomically persists the outcome into [prefs]:
     *   - Success → new tokens saved + [AuthPrefs.markRefreshSuccess]
     *   - Failure (HTTP or network) → [AuthPrefs.markRefreshError]
     *
     * Does NOT touch the watch-push state or trigger widget redraws — callers
     * handle those concerns differently (widget redraws, worker return value,
     * in-app UI state).
     */
    suspend fun refresh(prefs: AuthPrefs): RefreshResult = refreshMutex.withLock {
        // The Hevy server rotates the refresh token on every successful call —
        // the response carries a fresh refresh_token and the old one is dead.
        // Without serialization, the worker, the widget tap, and the in-app
        // button can each read the same RT, fire concurrent requests, and one
        // of them inevitably gets HTTP 401 because the server already retired
        // its token. The mutex (process-wide via the companion-object holder)
        // guarantees one refresh in flight at a time.
        val rt = prefs.refreshToken ?: return@withLock RefreshResult.NotLoggedIn
        // Send the current access token as Bearer even if it's expired: the
        // Hevy private API returns HTTP 400 for refresh requests that arrive
        // without an Authorization header (the server appears to use the
        // bearer to identify the user/session shape and treats its absence
        // as a malformed request). Validity of that bearer doesn't matter —
        // the refresh token in the body is what authenticates the rotation —
        // but it has to be present for the server to even parse the call.
        val bearer = prefs.accessToken?.let { "Bearer $it" }

        return@withLock try {
            val response = api.refreshToken(bearer, RefreshTokenRequest(rt))
            classifyResponse(response, prefs)
        } catch (e: Exception) {
            val msg = e.message ?: "network error"
            prefs.markRefreshError(RefreshErrorCategory.NETWORK, msg, clock())
            RefreshResult.NetworkError(msg)
        }
    }

    /**
     * Pure-ish classification of the HTTP response into a [RefreshResult],
     * with the appropriate side-effects on [prefs] (success stamp, error
     * stamp with category, credential wipe on 401, no-op on 429).
     *
     * Split from [refresh] so the mapping table stays readable. The body of
     * `try { … } catch (e) { NetworkError }` in [refresh] handles transport
     * exceptions; this method handles every server-replied case.
     */
    private fun classifyResponse(
        response: retrofit2.Response<AuthTokenResponse>,
        prefs: AuthPrefs
    ): RefreshResult {
        val code = response.code()

        // 2xx with a fully-populated body → success.
        if (response.isSuccessful) {
            val body = response.body()
            val at = body?.accessToken
            val rtNew = body?.refreshToken
            val exp = body?.expiresAt
            if (at != null && rtNew != null && exp != null) {
                return try {
                    prefs.save(at, rtNew, exp)
                    prefs.markRefreshSuccess(clock())
                    RefreshResult.Success(exp)
                } catch (e: Exception) {
                    // EncryptedSharedPreferences / Keystore write threw.
                    // The refresh succeeded over the wire but we couldn't
                    // persist the result — surface it so the widget can say
                    // "Can't save tokens" instead of silently misleading
                    // the user with an old timestamp.
                    val msg = e.message ?: "persistence failed"
                    prefs.markRefreshError(RefreshErrorCategory.PERSISTENCE, msg, clock())
                    RefreshResult.PersistenceError(msg)
                }
            }
            // 2xx but body is malformed / missing required fields → contract drift.
            val detail = "200 with missing fields"
            prefs.markRefreshError(RefreshErrorCategory.CONTRACT, detail, clock())
            return RefreshResult.ContractError(detail)
        }

        // 401 — refresh token rejected. Wipe credentials and re-stamp the
        // error so the widget renders "Sign in again" instead of going
        // silently empty. The interactor's clear() also nukes the error
        // stamp, hence the explicit re-write below.
        if (code == 401) {
            val now = clock()
            prefs.markRefreshError(RefreshErrorCategory.AUTH_EXPIRED, "HTTP 401", now)
            prefs.clear()
            prefs.markRefreshError(RefreshErrorCategory.AUTH_EXPIRED, "HTTP 401", now)
            return RefreshResult.AuthExpired
        }

        // 403 — account suspended / region-blocked. Retry won't help; mark
        // as such so the worker can stop retrying.
        if (code == 403) {
            prefs.markRefreshError(RefreshErrorCategory.FORBIDDEN, "HTTP 403", clock())
            return RefreshResult.Forbidden(code)
        }

        // 429 — rate-limited. Deliberately do NOT persist as an error so
        // the widget keeps showing the last-known-good state. Parse Retry-After
        // if the server sent one (delta-seconds — HTTP-date form is not honoured
        // yet) so the worker can enqueue a precise one-shot rather than falling
        // back to WorkManager's exponential backoff.
        if (code == 429) {
            val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
            return RefreshResult.RateLimited(retryAfter)
        }

        // 5XX — Hevy is broken. Not the user's fault.
        if (code in 500..599) {
            prefs.markRefreshError(RefreshErrorCategory.SERVER_DOWN, "HTTP $code", clock())
            return RefreshResult.ServerError(code)
        }

        // Any other 4XX (400/404/422 etc.) or 3XX is a client/contract issue.
        // OkHttp follows redirects by default so a real 3XX rarely surfaces;
        // we treat any unexpected code that lands here as OTHER_HTTP.
        prefs.markRefreshError(RefreshErrorCategory.OTHER_HTTP, "HTTP $code", clock())
        return RefreshResult.OtherHttpError(code)
    }

    companion object {
        // Process-wide mutex. Every caller (worker, widget, in-app VM) builds
        // its own RefreshTokenInteractor instance, so the lock has to live on
        // the class, not the instance, to actually serialize them.
        private val refreshMutex = Mutex()
    }
}
