package com.example.hevywatch.data.store

import android.content.Context
import com.example.hevywatch.util.GsonHolder

/**
 * Persists the serialized request body (POST or PUT) to disk before sending it
 * to the API. Cleared only after a successful 2XX response. This ensures the
 * request body can be recovered if the network call fails, the app crashes,
 * or the watch disconnects mid-flight.
 */
class PendingRequestStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = GsonHolder.gson

    /**
     * Save the request body JSON + metadata before sending. Returns true on
     * success, false if the serialized body exceeded [MAX_BODY_BYTES] — the
     * cap exists so a runaway state bug can't inflate SharedPreferences with a
     * giant JSON; a real workout body is well under 64 KB.
     */
    fun save(method: String, url: String, body: Any): Boolean {
        val json = gson.toJson(body)
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            // Drop on the floor rather than persist a malformed/oversized
            // body. The send still happens — recovery loses only this one
            // pending entry, which we'd never have been able to retry from
            // disk anyway.
            android.util.Log.e(
                TAG,
                "Rejected oversized pending body (${json.length} chars > $MAX_BODY_BYTES bytes)"
            )
            return false
        }
        // commit() is synchronous: we MUST be on disk before the network send
        // returns, otherwise a crash between save() and the next dispatcher
        // tick could lose the unsent request. clear() uses apply() because by
        // then the request already succeeded.
        prefs.edit()
            .putString(KEY_METHOD, method)
            .putString(KEY_URL, url)
            .putString(KEY_BODY, json)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .commit()
        return true
    }

    /** Clear after successful 2XX response. */
    fun clear() {
        prefs.edit()
            .remove(KEY_METHOD)
            .remove(KEY_URL)
            .remove(KEY_BODY)
            .remove(KEY_SAVED_AT)
            .apply()
    }

    /** Check if there's a pending unsent request. */
    fun hasPending(): Boolean = prefs.getString(KEY_BODY, null) != null

    /** Get the pending request body JSON for manual recovery/inspection. */
    fun getPendingBody(): String? = prefs.getString(KEY_BODY, null)
    fun getPendingMethod(): String? = prefs.getString(KEY_METHOD, null)
    fun getPendingUrl(): String? = prefs.getString(KEY_URL, null)

    companion object {
        /** Upper bound on the serialized pending body. A real workout body
         *  (POST /v2/workout) is < 64 KB; the cap is a guard against runaway
         *  growth, not a tight fit. */
        const val MAX_BODY_BYTES = 256 * 1024

        private const val PREFS_NAME = "pending_request"
        private const val KEY_METHOD = "method"
        private const val KEY_URL = "url"
        private const val KEY_BODY = "body"
        private const val KEY_SAVED_AT = "saved_at_ms"
        private const val TAG = "PendingRequestStore"
    }
}
