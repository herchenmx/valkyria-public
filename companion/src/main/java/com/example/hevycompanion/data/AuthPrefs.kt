package com.example.hevycompanion.data

import android.content.Context
import android.content.SharedPreferences

class AuthPrefs(context: Context) {
    // Encrypted-at-rest via androidx.security:security-crypto. Plain-prefs
    // file from older installs is migrated forward on first read; falls back
    // to plain prefs if the device's keystore is in a bad state.
    private val prefs: SharedPreferences = SecurePrefs.open(
        context = context,
        name = "hevy_auth_enc",
        legacyName = "hevy_auth",
    )

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        private set(v) = prefs.edit().putString(KEY_ACCESS_TOKEN, v).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        private set(v) = prefs.edit().putString(KEY_REFRESH_TOKEN, v).apply()

    var expiresAt: String?
        get() = prefs.getString(KEY_EXPIRES_AT, null)
        private set(v) = prefs.edit().putString(KEY_EXPIRES_AT, v).apply()

    val isLoggedIn: Boolean
        get() = accessToken != null && refreshToken != null

    fun save(accessToken: String, refreshToken: String, expiresAt: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    /** Epoch-ms when the access token was last successfully refreshed (0 = never). */
    var lastTokenRefreshedAt: Long
        get() = prefs.getLong(KEY_LAST_REFRESHED, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_REFRESHED, v).apply()

    /** Epoch-ms when tokens were last successfully pushed to the watch (0 = never). */
    var lastTokenPushedAt: Long
        get() = prefs.getLong(KEY_LAST_PUSHED, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_PUSHED, v).apply()

    /** Epoch-ms of the most recent token-refresh failure (0 = never failed).
     *  Written by every refresh path (widget, worker, in-app) alongside
     *  [lastTokenRefreshError] and [lastTokenRefreshErrorCategory]. Cleared on
     *  the next successful refresh. */
    var lastTokenRefreshErrorAt: Long
        get() = prefs.getLong(KEY_LAST_REFRESH_ERROR_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_REFRESH_ERROR_AT, v).apply()

    /** Detail string for the most recent refresh failure (HTTP code, exception
     *  text, etc.). Useful for logs / future "developer mode" surface — the
     *  widget itself renders a category-mapped string, not this raw value. */
    var lastTokenRefreshError: String?
        get() = prefs.getString(KEY_LAST_REFRESH_ERROR, null)
        set(v) = prefs.edit().putString(KEY_LAST_REFRESH_ERROR, v).apply()

    /** Category bucket for the most recent refresh failure, one of the
     *  constants in [RefreshErrorCategory]. The widget formatter switches on
     *  this to pick the user-facing message; the raw detail in
     *  [lastTokenRefreshError] is preserved for debugging. */
    var lastTokenRefreshErrorCategory: String?
        get() = prefs.getString(KEY_LAST_REFRESH_ERROR_CATEGORY, null)
        set(v) = prefs.edit().putString(KEY_LAST_REFRESH_ERROR_CATEGORY, v).apply()

    /** Epoch-ms of the most recent watch-push failure (0 = never failed).
     *  Mirrors the refresh-error fields but for the Wearable-MessageAPI side
     *  of the pipeline. Cleared on the next successful push. */
    var lastTokenPushErrorAt: Long
        get() = prefs.getLong(KEY_LAST_PUSH_ERROR_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_PUSH_ERROR_AT, v).apply()

    /** Detail string for the most recent push failure (exception message
     *  or "no watch connected"). Widget renders a category-mapped string. */
    var lastTokenPushError: String?
        get() = prefs.getString(KEY_LAST_PUSH_ERROR, null)
        set(v) = prefs.edit().putString(KEY_LAST_PUSH_ERROR, v).apply()

    /** Category bucket for the most recent push failure, one of the
     *  constants in [PushErrorCategory]. */
    var lastTokenPushErrorCategory: String?
        get() = prefs.getString(KEY_LAST_PUSH_ERROR_CATEGORY, null)
        set(v) = prefs.edit().putString(KEY_LAST_PUSH_ERROR_CATEGORY, v).apply()

    /**
     * Allowlist of trusted watch Wearable nodeIds. A node in this set may pull
     * tokens via `/request_auth`, push rotated tokens via `/tokens_from_watch`,
     * and read/write the seed cache; anything else is rejected. Cleared by
     * [clear] so a logout / 401 re-arms approval.
     *
     * **A set, not a single pin.** The user runs two watches (ray + shiner)
     * interchangeably. A single pin meant only one of them could ever use the
     * watch-initiated pull — the other silently fell back to whatever the
     * phone happened to push — and swapping would have needed a logout. Both
     * are first-class members here.
     *
     * Mirrors `AuthStore.trustedPhoneNodeId` on the watch side (which stays
     * singular — there is only ever one companion phone).
     */
    var trustedWatchNodeIds: Set<String>
        get() = prefs.getStringSet(KEY_TRUSTED_WATCH_NODE_IDS, null)
            // One-time read-side migration off the old single-pin key so an
            // upgrade doesn't drop the already-trusted watch.
            ?: prefs.getString(KEY_TRUSTED_WATCH_NODE_ID, null)?.let { setOf(it) }
            ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_TRUSTED_WATCH_NODE_IDS, v).apply()

    /** True if [nodeId] is on the allowlist. Null is never trusted — the
     *  Wearable framework always populates sourceNodeId on real messages. */
    fun isTrustedWatch(nodeId: String?): Boolean =
        nodeId != null && nodeId in trustedWatchNodeIds

    /** Add [nodeId] to the allowlist and clear it from the pending slot.
     *  Idempotent. */
    fun addTrustedWatch(nodeId: String) {
        val next = trustedWatchNodeIds + nodeId
        prefs.edit()
            .putStringSet(KEY_TRUSTED_WATCH_NODE_IDS, next)
            .remove(KEY_TRUSTED_WATCH_NODE_ID)
            .let { if (prefs.getString(KEY_PENDING_WATCH_NODE_ID, null) == nodeId) it.remove(KEY_PENDING_WATCH_NODE_ID) else it }
            .apply()
    }

    /** Add several nodes at once — used by the one-time upgrade migration
     *  that adopts the watches already connected at the time. */
    fun addTrustedWatches(nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        prefs.edit()
            .putStringSet(KEY_TRUSTED_WATCH_NODE_IDS, trustedWatchNodeIds + nodeIds)
            .remove(KEY_TRUSTED_WATCH_NODE_ID)
            .apply()
    }

    /** Guards the one-time "adopt currently-connected watches" migration so
     *  it runs once per install rather than on every launch. */
    var trustedWatchesSeeded: Boolean
        get() = prefs.getBoolean(KEY_TRUSTED_SEEDED, false)
        set(v) = prefs.edit().putBoolean(KEY_TRUSTED_SEEDED, v).apply()

    /** Writes the pre-set legacy single-pin key so the migration path can be
     *  exercised. Test-only — production code never writes this key. */
    internal fun seedLegacyPinForTest(nodeId: String) {
        prefs.edit()
            .remove(KEY_TRUSTED_WATCH_NODE_IDS)
            .putString(KEY_TRUSTED_WATCH_NODE_ID, nodeId)
            .apply()
    }

    /**
     * Wearable nodeId that recently asked for tokens (`/request_auth`) but is
     * NOT yet the trusted watch — awaiting explicit user approval via the
     * TOFU notification. On approval the value is promoted to
     * [trustedWatchNodeId] and cleared here; on reject or logout it is
     * cleared without promotion. Only ever one pending at a time — a second
     * un-pinned request overwrites this slot.
     *
     * Without this, the first Wearable peer to send `/request_auth` would
     * silently receive the user's Hevy tokens.
     */
    var pendingWatchNodeId: String?
        get() = prefs.getString(KEY_PENDING_WATCH_NODE_ID, null)
        set(v) = prefs.edit().putString(KEY_PENDING_WATCH_NODE_ID, v).apply()

    /** Atomic "last refresh succeeded" stamp: clears any previous error
     *  (both message and category) and writes the new success timestamp. */
    fun markRefreshSuccess(ts: Long) {
        prefs.edit()
            .putLong(KEY_LAST_REFRESHED, ts)
            .remove(KEY_LAST_REFRESH_ERROR)
            .remove(KEY_LAST_REFRESH_ERROR_CATEGORY)
            .putLong(KEY_LAST_REFRESH_ERROR_AT, 0L)
            .apply()
    }

    /** Atomic "last refresh failed" stamp: records category + detail + ts
     *  without touching the last-success timestamp (so the widget can still
     *  show "last OK: …"). */
    fun markRefreshError(category: String, detail: String, ts: Long) {
        prefs.edit()
            .putString(KEY_LAST_REFRESH_ERROR_CATEGORY, category)
            .putString(KEY_LAST_REFRESH_ERROR, detail)
            .putLong(KEY_LAST_REFRESH_ERROR_AT, ts)
            .apply()
    }

    /** Atomic "last push succeeded" stamp: clears any previous push error
     *  (both message and category) and writes the new success timestamp. */
    fun markPushSuccess(ts: Long) {
        prefs.edit()
            .putLong(KEY_LAST_PUSHED, ts)
            .remove(KEY_LAST_PUSH_ERROR)
            .remove(KEY_LAST_PUSH_ERROR_CATEGORY)
            .putLong(KEY_LAST_PUSH_ERROR_AT, 0L)
            .apply()
    }

    /** Atomic "last push failed" stamp: records category + detail + ts
     *  without touching the last-success timestamp. */
    fun markPushError(category: String, detail: String, ts: Long) {
        prefs.edit()
            .putString(KEY_LAST_PUSH_ERROR_CATEGORY, category)
            .putString(KEY_LAST_PUSH_ERROR, detail)
            .putLong(KEY_LAST_PUSH_ERROR_AT, ts)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /** Observe writes so the UI can react instead of polling. Callers must
     *  hold a strong reference to [listener] — SharedPreferences keeps only a
     *  weak one — and pair this with [unregisterListener]. */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val KEY_ACCESS_TOKEN              = "access_token"
        private const val KEY_REFRESH_TOKEN             = "refresh_token"
        private const val KEY_EXPIRES_AT                = "expires_at"
        private const val KEY_LAST_REFRESHED            = "last_token_refreshed_at"
        private const val KEY_LAST_PUSHED               = "last_token_pushed_at"
        private const val KEY_LAST_REFRESH_ERROR        = "last_token_refresh_error"
        private const val KEY_LAST_REFRESH_ERROR_AT     = "last_token_refresh_error_at"
        private const val KEY_LAST_REFRESH_ERROR_CATEGORY = "last_token_refresh_error_category"
        private const val KEY_LAST_PUSH_ERROR           = "last_token_push_error"
        private const val KEY_LAST_PUSH_ERROR_AT        = "last_token_push_error_at"
        private const val KEY_LAST_PUSH_ERROR_CATEGORY  = "last_token_push_error_category"
        /** Legacy single-pin key. Read once for migration, then removed. */
        private const val KEY_TRUSTED_WATCH_NODE_ID     = "trusted_watch_node_id"
        private const val KEY_TRUSTED_WATCH_NODE_IDS    = "trusted_watch_node_ids"
        private const val KEY_TRUSTED_SEEDED            = "trusted_watches_seeded"
        private const val KEY_PENDING_WATCH_NODE_ID     = "pending_watch_node_id"
    }
}
