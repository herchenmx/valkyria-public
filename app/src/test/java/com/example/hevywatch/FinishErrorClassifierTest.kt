package com.example.hevywatch

import com.example.hevywatch.presentation.workout.FinishError
import com.example.hevywatch.presentation.workout.classifyFinishError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FinishErrorClassifierTest {

    private fun httpException(code: Int): HttpException {
        val body = "".toResponseBody("text/plain".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }

    @Test
    fun `401 maps to AuthFailed`() {
        assertTrue(classifyFinishError(httpException(401)) is FinishError.AuthFailed)
    }

    @Test
    fun `403 maps to AuthFailed`() {
        assertTrue(classifyFinishError(httpException(403)) is FinishError.AuthFailed)
    }

    @Test
    fun `500 maps to ServerError with code`() {
        val result = classifyFinishError(httpException(500))
        assertTrue(result is FinishError.ServerError)
        assertEquals(500, (result as FinishError.ServerError).code)
    }

    // 400/422 used to fold into ServerError, which framed a rejected request
    // body as a transient server fault and told the user to retry. Retrying
    // cannot help: the same body will be rejected again. They are now their own
    // case so the UI can say so explicitly.
    @Test
    fun `400 maps to InvalidRequest with code`() {
        val result = classifyFinishError(httpException(400))
        assertTrue(result is FinishError.InvalidRequest)
        assertEquals(400, (result as FinishError.InvalidRequest).code)
    }

    @Test
    fun `422 maps to InvalidRequest with code`() {
        val result = classifyFinishError(httpException(422))
        assertTrue(result is FinishError.InvalidRequest)
        assertEquals(422, (result as FinishError.InvalidRequest).code)
    }

    @Test
    fun `503 stays a ServerError — a retry or the public fallback may help`() {
        val result = classifyFinishError(httpException(503))
        assertTrue(result is FinishError.ServerError)
        assertEquals(503, (result as FinishError.ServerError).code)
    }

    @Test
    fun `IOException maps to NetworkError`() {
        assertTrue(classifyFinishError(IOException("broken pipe")) is FinishError.NetworkError)
    }

    @Test
    fun `UnknownHostException maps to NetworkError`() {
        assertTrue(classifyFinishError(UnknownHostException("api.hevyapp.com")) is FinishError.NetworkError)
    }

    @Test
    fun `SocketTimeoutException maps to NetworkError`() {
        assertTrue(classifyFinishError(SocketTimeoutException("read timed out")) is FinishError.NetworkError)
    }

    @Test
    fun `arbitrary exception maps to Unknown with message`() {
        val result = classifyFinishError(IllegalStateException("nope"))
        assertTrue(result is FinishError.Unknown)
        assertEquals("nope", (result as FinishError.Unknown).message)
    }

    @Test
    fun `exception with null message maps to Unknown with null message`() {
        val result = classifyFinishError(RuntimeException())
        assertTrue(result is FinishError.Unknown)
        assertEquals(null, (result as FinishError.Unknown).message)
    }

    @Test
    fun `HTTP 429 rate-limit maps to ServerError with code`() {
        val result = classifyFinishError(httpException(429))
        assertTrue(result is FinishError.ServerError)
        assertEquals(429, (result as FinishError.ServerError).code)
    }

    @Test
    fun `HTTP 502 bad gateway maps to ServerError with code`() {
        val result = classifyFinishError(httpException(502))
        assertTrue(result is FinishError.ServerError)
        assertEquals(502, (result as FinishError.ServerError).code)
    }
}
