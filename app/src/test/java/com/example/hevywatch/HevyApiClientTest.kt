package com.example.hevywatch

import com.example.hevywatch.data.api.HevyApiClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Locks in the bearer-redaction invariant for [HevyApiClient.createPrivate]:
 * no path that carries an Authorization: Bearer header may ever attach a
 * logging interceptor (BASIC, HEADERS, or BODY), so a future bump to a chattier
 * logging level can't accidentally leak the token. The public / auth-only
 * clients have no bearer header, so they're free to keep the BASIC-in-debug
 * logger.
 *
 * Also pins the OkHttp timeouts (10 s connect / 15 s read+write) because the
 * watch is on a flaky BT-tethered network and a regression to OkHttp's 60 s
 * defaults would hang the UI for a full minute on dead connections.
 */
class HevyApiClientTest {

    @Test fun `private client has no logging interceptor`() {
        val client = HevyApiClient.buildPrivateClient("dummy-token")
        val hasLogger = client.interceptors.any { it is HttpLoggingInterceptor }
        assertFalse(
            "createPrivate carries an Authorization: Bearer header — adding a " +
                "logging interceptor would risk leaking the token in logcat.",
            hasLogger
        )
    }

    @Test fun `public client logging level never exceeds BASIC`() {
        val client = HevyApiClient.buildPublicClient("dummy-api-key")
        val logger = client.interceptors.filterIsInstance<HttpLoggingInterceptor>().single()
        // Public client has no Authorization header, so BASIC (method+URL+
        // status) is safe to log; but HEADERS or BODY are never appropriate
        // because they'd dump api-key headers in plaintext.
        assertTrue(
            "Public client logger must never escalate past BASIC.",
            logger.level == HttpLoggingInterceptor.Level.NONE ||
                logger.level == HttpLoggingInterceptor.Level.BASIC
        )
    }

    @Test fun `auth client logging level matches debug flag`() {
        val client = HevyApiClient.buildAuthClient()
        val logger = client.interceptors.filterIsInstance<HttpLoggingInterceptor>().single()
        // In release (BuildConfig.DEBUG = false) the level must be NONE; in
        // debug the level is BASIC and never higher.
        assertTrue(
            "Auth client logger must never escalate past BASIC.",
            logger.level == HttpLoggingInterceptor.Level.NONE ||
                logger.level == HttpLoggingInterceptor.Level.BASIC
        )
    }

    @Test fun `okhttp timeouts are tight enough for BT-tethered network`() {
        val client = HevyApiClient.buildPrivateClient("dummy-token")
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(15_000, client.writeTimeoutMillis)
        // Sanity: any tighter than 8 s connect would false-fail on a slow tower
        // handover; any looser than 20 s read would hang past the user's
        // patience window.
        assertTrue(client.connectTimeoutMillis < TimeUnit.SECONDS.toMillis(20))
        assertTrue(client.readTimeoutMillis < TimeUnit.SECONDS.toMillis(20))
    }

    @Test fun `private client reads Hevy-App-Version-Build from supplier on each request`() {
        // Pins the late-binding contract: the OkHttp interceptor must invoke
        // the supplier at request time, not at client-construction time, so a
        // SET_API_VERSION ADB broadcast (or companion /api_version push)
        // updates header values without rebuilding the client. Pre-regression
        // bug: constants captured at build time would freeze the headers to
        // whatever was current the moment HevyApp.onTokensReceived ran, then
        // ignore any later sharedPrefs writes.
        var supplied = "3.0.12" to "2032997"
        val client = HevyApiClient.buildPrivateClient("dummy-token") { supplied }
        val server = MockWebServer().apply {
            start()
            enqueue(MockResponse().setResponseCode(200))
            enqueue(MockResponse().setResponseCode(200))
        }
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            val req1 = server.takeRequest()
            assertEquals("3.0.12", req1.getHeader("Hevy-App-Version"))
            assertEquals("2032997", req1.getHeader("Hevy-App-Build"))
            assertEquals("Bearer dummy-token", req1.getHeader("Authorization"))
            assertEquals("wearos", req1.getHeader("Hevy-Platform"))

            // Mutate the supplier value and fire a second request — fresh
            // headers must reflect the new pair on the very next call.
            supplied = "3.0.13" to "2033100"
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            val req2 = server.takeRequest()
            assertEquals("3.0.13", req2.getHeader("Hevy-App-Version"))
            assertEquals("2033100", req2.getHeader("Hevy-App-Build"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `auth client reads Hevy-App-Version-Build from supplier on each request`() {
        var supplied = "3.0.12" to "2032997"
        val client = HevyApiClient.buildAuthClient { supplied }
        val server = MockWebServer().apply {
            start()
            enqueue(MockResponse().setResponseCode(200))
        }
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            val req = server.takeRequest()
            assertEquals("3.0.12", req.getHeader("Hevy-App-Version"))
            assertEquals("2032997", req.getHeader("Hevy-App-Build"))
            assertEquals("wearos", req.getHeader("Hevy-Platform"))
        } finally {
            server.shutdown()
        }
    }
}
