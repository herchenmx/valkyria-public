package com.example.hevycompanion.wear

import com.example.hevycompanion.data.AuthPrefs
import com.example.hevycompanion.util.GsonHolder
import com.google.gson.JsonSyntaxException

/**
 * Pure handler for the `/tokens_from_watch` MessageAPI path: validates the
 * JSON payload from the watch, enforces a trust-on-first-use node-id pin,
 * saves the fresh `(access_token, refresh_token, expires_at)` triple into
 * [AuthPrefs], and stamps `markRefreshSuccess`.
 *
 * Lives outside [WatchBridgeService] so the side-effect chain (TOFU check →
 * parse → save → mark success) can be unit-tested against Robolectric-backed
 * [AuthPrefs] without spinning up a real WearableListenerService.
 *
 * Watch side counterpart: [com.example.hevywatch.wear.CompanionTokenSender].
 *
 * Security: the sender must already be on [AuthPrefs.trustedWatchNodeIds].
 * Untrusted senders are rejected outright —
 * pinning happens through the explicit user-approval flow triggered by
 * `/request_auth` (see `TrustWatchReceiver`), NOT here. This avoids the
 * "first Wearable peer to send `/tokens_from_watch` locks the pin to their
 * node id and pushes attacker tokens as the user's tokens" first-writer-wins
 * race. Pin is cleared by `AuthPrefs.clear()` (logout / 401), re-arming the
 * approval flow for the next session.
 */
object TokensFromWatchHandler {

    sealed class Outcome {
        /** Tokens saved; widgets should be refreshed. */
        object Stored : Outcome()

        /** JSON was missing, partial, or sender node-id failed the TOFU check.
         *  Nothing persisted. */
        object Rejected : Outcome()
    }

    private data class TokenPayload(
        val access_token: String?,
        val refresh_token: String?,
        val expires_at: String?,
    )

    fun handle(
        data: ByteArray,
        prefs: AuthPrefs,
        sourceNodeId: String? = null,
        clock: () -> Long = { System.currentTimeMillis() },
    ): Outcome {
        // TOFU gate: require a pre-existing pin that exactly matches the
        // sender. Un-pinned senders are rejected outright — pinning only
        // happens through the explicit user-approval flow on `/request_auth`.
        // A null sourceNodeId is always rejected: the Wearable framework
        // populates sourceNodeId on real messages, so null implies a code
        // path that bypassed the framework.
        if (!prefs.isTrustedWatch(sourceNodeId)) return Outcome.Rejected

        if (data.isEmpty()) return Outcome.Rejected
        val payload = try {
            GsonHolder.gson.fromJson(data.decodeToString(), TokenPayload::class.java)
        } catch (e: JsonSyntaxException) {
            return Outcome.Rejected
        } catch (e: Exception) {
            return Outcome.Rejected
        }
        if (payload == null) return Outcome.Rejected
        val at = payload.access_token
        val rt = payload.refresh_token
        val exp = payload.expires_at
        if (at.isNullOrBlank() || rt.isNullOrBlank() || exp.isNullOrBlank()) {
            return Outcome.Rejected
        }
        prefs.save(at, rt, exp)
        prefs.markRefreshSuccess(clock())
        return Outcome.Stored
    }
}
