package com.example.hevywatch

import com.example.hevywatch.data.api.acceptedByHevy
import com.example.hevywatch.data.api.orThrow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Regression cover for the resume bug: every write in HevyApiService returns
 * `Response<T>`, which Retrofit does NOT throw for on an HTTP error. No call
 * site inspected the result, so a 400 from Hevy completed normally — the app
 * logged "POST ok", ran the follow-up DELETE, and showed the success screen
 * while nothing had been stored.
 */
class ResponseChecksTest {

    private fun error(code: Int): Response<Unit> =
        Response.error(code, """{"error":"nope"}""".toResponseBody("application/json".toMediaType()))

    @Test fun `a rejected body throws so the app can classify it`() {
        try {
            error(400).orThrow()
            fail("400 must not pass as success — this is the resume bug")
        } catch (e: HttpException) {
            assertEquals(400, e.code())
        }
    }

    @Test fun `auth and server failures throw too`() {
        for (code in listOf(401, 403, 404, 422, 500, 503)) {
            try {
                error(code).orThrow()
                fail("HTTP $code must not pass as success")
            } catch (e: HttpException) {
                assertEquals(code, e.code())
            }
        }
    }

    @Test fun `a success passes through unchanged`() {
        val ok = Response.success(Unit)
        // Same instance back, so `.orThrow()` can be appended to a call without
        // changing what the caller receives.
        assertSame(ok, ok.orThrow())
    }

    @Test fun `204 counts as success`() {
        // The private DELETE answers 204; treating "no content" as a failure
        // would report a duplicate that does not exist.
        val noContent: Response<Unit> = Response.success(204, Unit)
        assertTrue(noContent.acceptedByHevy)
        assertSame(noContent, noContent.orThrow())
    }

    @Test fun `acceptedByHevy reads the status, not merely whether the call returned`() {
        // The shape of the second half of this bug: the resume DELETE was
        // wrapped in runCatching, which only catches throws — so every HTTP
        // status was reported as a successful delete.
        assertFalse(error(404).acceptedByHevy)
        assertTrue(Response.success(Unit).acceptedByHevy)
    }
}
