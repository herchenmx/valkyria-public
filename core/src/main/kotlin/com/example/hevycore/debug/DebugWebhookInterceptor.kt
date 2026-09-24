package com.example.hevycore.debug

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * Mirrors Hevy workout writes to [DebugWebhook].
 *
 * Sits in the OkHttp chain rather than at the Retrofit call sites, which is
 * what makes it complete: it sees the request body as actually serialized and
 * the response body as actually returned, on every path — private v2 POST,
 * public v1 POST/PUT, and the resume DELETE — with no change to any interface
 * or view-model, and so with no way for one path to be forgotten.
 *
 * Reads no headers. See [DebugWebhook] for why that is structural.
 *
 * @param api "private" or "public", supplied by whichever client is being
 *   built, because the path alone cannot distinguish them.
 */
class DebugWebhookInterceptor(private val api: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!DebugWebhook.isEnabled || !isWatched(request.method, request.url.encodedPath)) {
            return chain.proceed(request)
        }

        val requestBody = readBody(request)
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (t: IOException) {
            // A transport failure never reaches the response branch, and is
            // exactly the case where "nothing happened" needs explaining.
            DebugWebhook.send(
                api = api,
                method = request.method,
                path = request.url.encodedPath,
                status = null,
                durationMs = elapsedMs(started),
                requestBody = requestBody,
                responseBody = null,
                error = t.toString(),
            )
            throw t
        }

        // peekBody, not body: it copies up to the limit and leaves the real
        // body unread, so Retrofit still parses the response normally. Reading
        // response.body directly here would consume the one-shot stream and
        // break every caller.
        val responseBody = runCatching {
            response.peekBody(DebugWebhook.BODY_LIMIT.toLong()).string()
        }.getOrNull()

        DebugWebhook.send(
            api = api,
            method = request.method,
            path = request.url.encodedPath,
            status = response.code,
            durationMs = elapsedMs(started),
            requestBody = requestBody,
            responseBody = responseBody,
        )
        return response
    }

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000

    private fun readBody(request: okhttp3.Request): String? {
        val body = request.body ?: return null
        // A one-shot or duplex body can only be written once, and that write
        // belongs to the network. Retrofit's Gson bodies are neither, so this
        // guard costs nothing in practice and prevents a corrupted request if
        // that ever changes.
        if (body.isOneShot() || body.isDuplex()) return null

        // Bounded, unlike the first version. The response side was capped by
        // peekBody from the start; this side buffered the whole request into
        // memory before anything trimmed it. A resumed workout POST carries
        // every heart-rate sample, so on a 512 MB watch the unbounded case was
        // the one that mattered. Checked BEFORE writing, so an oversized body
        // is never materialised at all.
        val declared = body.contentLength()
        if (declared > MAX_CAPTURE_BYTES) {
            return "…[request body $declared bytes — over the ${MAX_CAPTURE_BYTES}-byte capture limit, not sent]"
        }
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            // Second cap for a body that did not declare its length (-1): by
            // now it is buffered, but at least the payload stays bounded.
            val take = minOf(buffer.size, MAX_CAPTURE_BYTES)
            buffer.readString(take, Charsets.UTF_8)
        }.getOrNull()
    }

    internal companion object {
        /** Cap on the request body captured, matching the response side's
         *  [DebugWebhook.BODY_LIMIT]. Bytes here rather than characters, since
         *  it is applied to the wire form before any decoding. */
        const val MAX_CAPTURE_BYTES: Long = DebugWebhook.BODY_LIMIT.toLong()

        /**
         * Workout writes, plus the one read the resume path hinges on.
         *
         * Other GETs stay out: listing workouts or routines is noise, and the
         * responses are large.
         */
        fun isWatched(method: String, encodedPath: String): Boolean {
            val p = encodedPath.trimEnd('/')
            // The one GET worth mirroring: the private workout detail the whole
            // resume path depends on. Whether it succeeded decides whether the
            // private POST runs at all, and its absence is invisible from the
            // POST side — the request simply never happens. Excluding it once
            // cost a debugging round where the interesting event was the one
            // call not being recorded.
            if (method == "GET") return p.startsWith("/workout/")
            if (method != "POST" && method != "PUT" && method != "DELETE") return false
            return p == "/v2/workout" ||               // private create (new + resume)
                p == "/v1/workouts" ||                 // public create
                p.startsWith("/v1/workouts/") ||       // public update
                p.startsWith("/workout/")              // private delete of the original
        }
    }
}
