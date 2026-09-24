package com.example.hevycompanion.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bucket-F item 27 — pins the fix from commit cb1fdb6 ("Fix intermittent
 * refresh-token 401s by serializing refresh paths").
 *
 * Without the process-wide [RefreshTokenInteractor] mutex, the widget-tap
 * refresh, the WorkManager periodic refresh, and the in-app refresh could
 * each read the same `refresh_token`, fire concurrent /auth/refresh_token
 * calls, and one of them would inevitably see HTTP 401 because the server
 * already rotated its RT and retired the old one. The mutex guarantees one
 * refresh in flight at a time.
 *
 * The tripwire: launch three concurrent refresh() calls; a fake API records
 * how many were mid-request at any moment. If the mutex is ever removed,
 * the observed maximum concurrency climbs above 1.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshTokenInteractorConcurrencyTest {

    private lateinit var prefs: AuthPrefs

    @Before
    fun setUp() {
        prefs = AuthPrefs(ApplicationProvider.getApplicationContext())
        prefs.clear()
        // Seed a valid refresh token so the interactor doesn't short-circuit
        // to RefreshResult.NotLoggedIn before ever hitting the api fake.
        prefs.save(
            accessToken = "at-initial",
            refreshToken = "rt-initial",
            expiresAt = "2027-01-01T00:00:00Z"
        )
    }

    @Test
    fun `concurrent refresh calls serialize through the process-wide mutex`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val observedMax = AtomicInteger(0)
        val callCount = AtomicInteger(0)

        val fakeApi = object : HevyAuthApi {
            override suspend fun refreshToken(
                authorization: String?,
                body: RefreshTokenRequest
            ): Response<AuthTokenResponse> {
                val current = inFlight.incrementAndGet()
                observedMax.updateAndGet { prev -> maxOf(prev, current) }
                try {
                    // Introduce enough latency that if the callers were NOT
                    // serialized, they'd race. Small in real terms (10 ms) so
                    // the whole suite finishes in the low-tens-of-ms.
                    delay(10)
                    val n = callCount.incrementAndGet()
                    return Response.success(
                        AuthTokenResponse(
                            accessToken = "at-$n",
                            refreshToken = "rt-$n",
                            expiresAt = "2027-01-01T00:00:00Z",
                        )
                    )
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        val interactor = RefreshTokenInteractor(api = fakeApi, clock = { 1_000L })

        // Three concurrent refresh() calls — this is the observed prod pattern:
        // widget tap + periodic worker + in-app kick can all overlap on cold
        // start of the phone screen.
        val results = listOf(
            async { interactor.refresh(prefs) },
            async { interactor.refresh(prefs) },
            async { interactor.refresh(prefs) },
        ).awaitAll()

        assertEquals(
            "mutex was breached — refresh calls overlapped",
            1,
            observedMax.get()
        )
        assertEquals(3, callCount.get())
        assertTrue(results.all { it is RefreshResult.Success })
        // The last successful refresh wins — its rotated tokens must be what
        // AuthPrefs sees at the end.
        assertNotNull(prefs.accessToken)
        assertTrue(prefs.accessToken!!.startsWith("at-"))
    }

    @Test
    fun `mutex releases on exception so a later caller still succeeds`() = runBlocking {
        val callState = AtomicReference("first")
        val fakeApi = object : HevyAuthApi {
            override suspend fun refreshToken(
                authorization: String?,
                body: RefreshTokenRequest
            ): Response<AuthTokenResponse> {
                return when (callState.getAndSet("second")) {
                    "first" -> throw java.io.IOException("simulated transport failure")
                    else -> Response.success(
                        AuthTokenResponse(
                            accessToken = "at-recover",
                            refreshToken = "rt-recover",
                            expiresAt = "2027-01-01T00:00:00Z",
                        )
                    )
                }
            }
        }
        val interactor = RefreshTokenInteractor(api = fakeApi, clock = { 1_000L })

        val first = interactor.refresh(prefs)
        assertTrue(
            "first call should surface as NetworkError (IOException path)",
            first is RefreshResult.NetworkError
        )
        // If the mutex leaked on exception, this second call would deadlock
        // forever. runBlocking has no wall-clock timeout in the standard
        // dispatcher, so the failure mode here is the whole suite hanging —
        // which is a very loud regression signal.
        val second = interactor.refresh(prefs)
        assertTrue(second is RefreshResult.Success)
        assertEquals("at-recover", prefs.accessToken)
    }
}
