package com.example.hevycore.debug

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Fire-and-forget mirror of Hevy API request/response pairs to an external
 * webhook, so a failure on the watch can be read from a phone browser instead
 * of `adb logcat` at a desk.
 *
 * ## What is sent, and what is deliberately not
 *
 * The payload carries the method, path, HTTP status, timing, and the request
 * and response **bodies**. It carries **no headers, ever** — not even a
 * redacted or hashed form. That is not politeness, it is the security
 * boundary: `Authorization: Bearer <token>`, `X-Api-Key` and the public
 * `api-key` all live in headers, and the destination is a third-party URL
 * outside your control. [send] has no parameter through which a header could
 * be passed, so leaking one would require editing this file rather than
 * mis-calling it. The same reasoning is why the private OkHttp client refuses
 * an HttpLoggingInterceptor (see HevyApiClient.buildPrivateClient).
 *
 * Workout bodies themselves are still your training data going to a third
 * party. That is the deliberate trade for being able to debug away from the
 * Mac; point [configure] at a URL you control and rotate it when done.
 *
 * ## Failure behaviour
 *
 * Disabled unless [configure] is given a non-blank URL, so a build with no
 * `HEVY_DEBUG_WEBHOOK_URL` does nothing at all, and it disables itself
 * [TTL_MILLIS] after the APK was installed so a forgotten secret cannot keep
 * shipping workout data indefinitely. Delivery happens on a daemon
 * thread with a bounded queue and short timeouts, and every error is
 * swallowed: a debug aid must never be able to fail a workout save, slow one
 * down, or keep the process alive.
 */
object DebugWebhook {

    /** Bodies are truncated to this many characters each. A resumed workout
     *  carries heart-rate samples and can run to hundreds of KB; the interesting
     *  part (the fields, and Hevy's complaint) is always at the start, and most
     *  webhook sinks reject or silently trim very large payloads. */
    const val BODY_LIMIT = 20_000

    /** Mirroring stops this long after the APK was installed. See [configure]. */
    const val TTL_MILLIS: Long = 14L * 24 * 60 * 60 * 1000

    @Volatile private var url: String = ""
    @Volatile private var device: String = "?"
    @Volatile private var commit: String = "?"
    @Volatile private var expiresAtMillis: Long = 0L

    /** Bounded and discarding: if the network stalls, queued debug payloads
     *  must not accumulate on a watch with 512 MB of RAM. Dropping a debug
     *  event is always preferable to pressuring the app that produced it. */
    private val io = ThreadPoolExecutor(
        0, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        ThreadFactory { r -> Thread(r, "hevy-debug-webhook").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    /**
     * @param webhookUrl destination; blank (the default when the secret is
     *   absent) disables everything.
     * @param deviceTag which device this is — "watch" or "phone" — so the two
     *   are distinguishable in one webhook feed.
     * @param commitHash build identity, so an old APK's traffic is not mistaken
     *   for the build you think you are testing.
     * @param expiresAtMillis epoch millis after which mirroring stops, or 0 to
     *   never expire. Callers pass the APK's install time + [TTL_MILLIS].
     */
    fun configure(
        webhookUrl: String,
        deviceTag: String,
        commitHash: String,
        expiresAtMillis: Long = 0L,
    ) {
        url = webhookUrl.trim()
        device = deviceTag
        commit = commitHash
        this.expiresAtMillis = expiresAtMillis
    }

    /**
     * Enabled only while a URL is configured AND the build has not aged out.
     *
     * The expiry is the answer to "what stops this if it is forgotten?".
     * Gating on BuildConfig.DEBUG — the obvious suggestion — would not work
     * here: the watch app ships as a NON-debuggable release build (so ART can
     * AOT-compile it on 2100-class hardware), and that is the only build that
     * ever runs on the device. A DEBUG gate would therefore disable mirroring
     * exactly where the failures happen and nowhere else.
     *
     * Time-boxing achieves the real goal instead. Clearing the secret is still
     * the deliberate off switch; this is the one that works when nobody
     * remembers to throw it.
     */
    val isEnabled: Boolean
        get() {
            if (url.isEmpty()) return false
            val deadline = expiresAtMillis
            return deadline == 0L || System.currentTimeMillis() < deadline
        }

    /**
     * Queue one request/response pair. Never throws.
     *
     * Note there is no `headers` parameter, by design — see the class comment.
     */
    fun send(
        api: String,
        method: String,
        path: String,
        status: Int?,
        durationMs: Long,
        requestBody: String?,
        responseBody: String?,
        error: String? = null,
    ) {
        if (!isEnabled) return
        val target = url
        val payload = buildPayload(api, method, path, status, durationMs, requestBody, responseBody, error)
        runCatching { io.execute { runCatching { post(target, payload) } } }
    }

    /** Split out so tests can assert the shape and the absence of credentials
     *  without standing up an HTTP server. */
    internal fun buildPayload(
        api: String,
        method: String,
        path: String,
        status: Int?,
        durationMs: Long,
        requestBody: String?,
        responseBody: String?,
        error: String?,
    ): String {
        val o = JsonObject()
        o.addProperty("device", device)
        o.addProperty("commit", commit)
        o.addProperty("at", Instant.now().toString())
        o.addProperty("api", api)
        o.addProperty("method", method)
        o.addProperty("path", path)
        if (status != null) o.addProperty("status", status) else o.add("status", JsonNull.INSTANCE)
        // Named "outcome" rather than "ok": whether a 2xx from Hevy means the
        // workout was stored is precisely the open question, so this states
        // what happened at the transport level and asserts nothing beyond it.
        o.addProperty("outcome", when {
            error != null -> "transport-error"
            status == null -> "unknown"
            else -> "http-$status"
        })
        o.addProperty("durationMs", durationMs)
        if (error != null) o.addProperty("error", error)
        o.addProperty("requestBody", truncate(requestBody))
        o.addProperty("responseBody", truncate(responseBody))
        return o.toString()
    }

    private fun truncate(s: String?): String {
        if (s == null) return ""
        return if (s.length <= BODY_LIMIT) s
        else s.take(BODY_LIMIT) + "\n…[truncated ${s.length - BODY_LIMIT} more chars]"
    }

    private fun post(target: String, payload: String) {
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { out: OutputStream -> out.write(payload.toByteArray(Charsets.UTF_8)) }
            // The status must be read for the request to actually be sent, but
            // nothing is done with it: there is no sensible recovery, and a
            // failed debug delivery is not worth a retry that could outlive the
            // save it was describing.
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
