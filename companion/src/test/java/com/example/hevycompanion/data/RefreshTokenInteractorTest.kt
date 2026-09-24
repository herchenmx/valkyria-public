package com.example.hevycompanion.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class RefreshTokenInteractorTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: AuthPrefs
    private lateinit var interactor: RefreshTokenInteractor

    private val fixedClock: () -> Long = { 1_700_000_000_000L }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prefs = AuthPrefs(ApplicationProvider.getApplicationContext())
        prefs.clear()
        prefs.save("old_at", "old_rt", "2020-01-01T00:00:00Z")
        val api = buildHevyAuthApi(server.url("/").toString())
        interactor = RefreshTokenInteractor(api = api, clock = fixedClock)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success persists tokens and stamps success timestamp`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"new_at","refresh_token":"new_rt","expires_at":"2030-01-01T00:00:00Z"}"""
            )
        )

        val result = interactor.refresh(prefs)

        assertTrue(result is RefreshResult.Success)
        assertEquals("2030-01-01T00:00:00Z", (result as RefreshResult.Success).expiresAt)
        assertEquals("new_at", prefs.accessToken)
        assertEquals("new_rt", prefs.refreshToken)
        assertEquals("2030-01-01T00:00:00Z", prefs.expiresAt)
        assertEquals(1_700_000_000_000L, prefs.lastTokenRefreshedAt)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
        assertNull(prefs.lastTokenRefreshError)
    }

    @Test
    fun `success clears any prior error atomically`() = runTest {
        prefs.markRefreshError(RefreshErrorCategory.AUTH_EXPIRED, "HTTP 401", 1_000L)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"a","refresh_token":"r","expires_at":"e"}"""
            )
        )
        interactor.refresh(prefs)

        assertNull(prefs.lastTokenRefreshError)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
    }

    @Test
    fun `HTTP 401 returns AuthExpired clears credentials and stamps AUTH_EXPIRED category`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauth"))

        val result = interactor.refresh(prefs)

        // 401 means the refresh token is dead server-side; the interactor must
        // wipe credentials so the worker stops hammering the API. The category
        // stamp tells the widget formatter to render "Sign in again".
        assertEquals(RefreshResult.AuthExpired, result)
        assertNull("access token must be cleared", prefs.accessToken)
        assertNull("refresh token must be cleared", prefs.refreshToken)
        assertFalse("isLoggedIn must flip to false", prefs.isLoggedIn)
        assertEquals(RefreshErrorCategory.AUTH_EXPIRED, prefs.lastTokenRefreshErrorCategory)
        assertEquals(1_700_000_000_000L, prefs.lastTokenRefreshErrorAt)
        assertEquals(0L, prefs.lastTokenRefreshedAt)
    }

    @Test
    fun `HTTP 403 maps to Forbidden and stamps FORBIDDEN category without clearing creds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = interactor.refresh(prefs)

        assertTrue(result is RefreshResult.Forbidden)
        assertEquals(403, (result as RefreshResult.Forbidden).code)
        assertEquals(RefreshErrorCategory.FORBIDDEN, prefs.lastTokenRefreshErrorCategory)
        // Credentials stay — the user's tokens aren't the problem, the
        // account is. Retry won't help but we shouldn't wipe state either.
        assertEquals("old_at", prefs.accessToken)
        assertEquals("old_rt", prefs.refreshToken)
    }

    @Test
    fun `HTTP 429 maps to RateLimited and does NOT persist an error stamp`() = runTest {
        // 429 is "usually invisible to user" per the taxonomy — the widget
        // keeps showing the last-known-good state.
        server.enqueue(
            MockResponse().setResponseCode(429).addHeader("Retry-After", "30")
        )

        val result = interactor.refresh(prefs)

        assertTrue(result is RefreshResult.RateLimited)
        assertEquals(30L, (result as RefreshResult.RateLimited).retryAfterSeconds)
        // Critical: the prefs error stamp must NOT be written, otherwise the
        // widget would surface a red error band the user can't act on.
        assertNull(prefs.lastTokenRefreshErrorCategory)
        assertNull(prefs.lastTokenRefreshError)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
    }

    @Test
    fun `HTTP 429 without Retry-After header still parses cleanly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        val result = interactor.refresh(prefs)

        assertTrue(result is RefreshResult.RateLimited)
        assertNull((result as RefreshResult.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `HTTP 500 maps to ServerError with code preserved and SERVER_DOWN category stamped`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = interactor.refresh(prefs)

        assertTrue(result is RefreshResult.ServerError)
        assertEquals(500, (result as RefreshResult.ServerError).code)
        assertEquals(RefreshErrorCategory.SERVER_DOWN, prefs.lastTokenRefreshErrorCategory)
        assertEquals("HTTP 500", prefs.lastTokenRefreshError)
    }

    @Test
    fun `HTTP 502 also maps to ServerError — full 5xx range bucketed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502))

        val result = interactor.refresh(prefs)

        assertTrue(result is RefreshResult.ServerError)
        assertEquals(502, (result as RefreshResult.ServerError).code)
    }

    @Test
    fun `HTTP 422 maps to OtherHttpError — developer-signal bucket`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422))

        val result = interactor.refresh(prefs)

        assertTrue(result is RefreshResult.OtherHttpError)
        assertEquals(422, (result as RefreshResult.OtherHttpError).code)
        assertEquals(RefreshErrorCategory.OTHER_HTTP, prefs.lastTokenRefreshErrorCategory)
    }

    @Test
    fun `200 with partial body maps to ContractError and stamps CONTRACT category`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"access_token":"a"}""")
        )

        val result = interactor.refresh(prefs)

        // Server contract drift: response was technically successful but the
        // body shape changed under us. Must NOT persist a half-valid token set.
        assertTrue(result is RefreshResult.ContractError)
        assertEquals(RefreshErrorCategory.CONTRACT, prefs.lastTokenRefreshErrorCategory)
        assertEquals("old_at", prefs.accessToken)
    }

    @Test
    fun `no refresh token stored returns NotLoggedIn without hitting the API`() = runTest {
        prefs.clear()

        val result = interactor.refresh(prefs)

        assertEquals(RefreshResult.NotLoggedIn, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `network exception maps to NetworkError and stamps NETWORK category`() = runTest {
        server.shutdown()

        val result = interactor.refresh(prefs)

        assertTrue(result is RefreshResult.NetworkError)
        assertEquals(RefreshErrorCategory.NETWORK, prefs.lastTokenRefreshErrorCategory)
        assertEquals(1_700_000_000_000L, prefs.lastTokenRefreshErrorAt)
        assertFalse(prefs.lastTokenRefreshError.isNullOrBlank())
        assertEquals(0L, prefs.lastTokenRefreshedAt)
    }

    @Test
    fun `success clears the category stamp as well as the message`() = runTest {
        // Atomicity: after a successful refresh the widget must not see a
        // stale category from a prior failure.
        prefs.markRefreshError(RefreshErrorCategory.NETWORK, "Unable to resolve host", 1_000L)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"a","refresh_token":"r","expires_at":"e"}"""
            )
        )

        interactor.refresh(prefs)

        assertNull(prefs.lastTokenRefreshErrorCategory)
        assertNull(prefs.lastTokenRefreshError)
        assertEquals(0L, prefs.lastTokenRefreshErrorAt)
    }

    @Test
    fun `Bearer access token is forwarded on refresh`() = runTest {
        // The Hevy private API returns HTTP 400 for refresh requests that
        // arrive without an Authorization header — even an expired access
        // token is fine, because the refresh_token in the body is what
        // authenticates the rotation. The interactor must therefore
        // forward whatever bearer is on file.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"a","refresh_token":"r","expires_at":"e"}"""
            )
        )

        interactor.refresh(prefs)

        val recorded = server.takeRequest()
        assertEquals("Bearer old_at", recorded.getHeader("Authorization"))
    }

    @Test
    fun `concurrent refreshes are serialized so the second sees the rotated token`() = runTest {
        // The whole point of the mutex: when two callers race (worker + widget tap),
        // the first should rotate old_rt → new_rt, and the second should pick up
        // new_rt from prefs and use that — not blast old_rt at the server while the
        // first is still in flight, which is what produced the intermittent 401s.
        prefs.save("at1", "rt1", "2020-01-01T00:00:00Z")

        val callIndex = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val i = callIndex.incrementAndGet()
                return MockResponse().setResponseCode(200).setBody(
                    """{"access_token":"at${i + 1}","refresh_token":"rt${i + 1}","expires_at":"2030-01-01T00:00:00Z"}"""
                )
            }
        }

        coroutineScope {
            val a = async { interactor.refresh(prefs) }
            val b = async { interactor.refresh(prefs) }
            awaitAll(a, b)
        }

        val first = server.takeRequest()
        val second = server.takeRequest()
        // Both requests carried *some* refresh token; critically, the second
        // saw the rotated value, not the same stale one as the first.
        val firstBody = first.body.readUtf8()
        val secondBody = second.body.readUtf8()
        assertTrue("first request should send rt1", firstBody.contains("\"refresh_token\":\"rt1\""))
        assertTrue("second request should send rt2 (rotated)", secondBody.contains("\"refresh_token\":\"rt2\""))
        // Final state reflects the second (winning) call.
        assertEquals("at3", prefs.accessToken)
        assertEquals("rt3", prefs.refreshToken)
        assertNotNull(prefs.expiresAt)
    }
}
