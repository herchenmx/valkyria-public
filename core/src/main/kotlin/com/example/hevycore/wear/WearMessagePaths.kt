package com.example.hevycore.wear

/**
 * Single source of truth for the Wearable MessageAPI path strings shared
 * between the watch (`:app`) and the companion (`:companion`). Any watch path
 * whose format is renamed on one side without touching the other will silently
 * drop messages on the receiving service — the framework does not warn.
 *
 * Both modules previously defined these as `const val`s inside their own
 * dispatcher classes; deduplication happened by hand. Pull all names in here
 * and reference them from both sides so the compiler enforces sync.
 */
object WearMessagePaths {
    /** watch → phone: watch asks the paired companion to send its stored tokens. */
    const val REQUEST_AUTH = "/request_auth"

    /** phone → watch: JSON `{access_token, refresh_token, expires_at}` reply
     *  to [REQUEST_AUTH], or a fresh push after the phone rotates tokens. */
    const val AUTH_TOKENS = "/auth_tokens"

    /** phone → watch: fire when the companion has just completed a login;
     *  the watch reacts by sending [REQUEST_AUTH] if it isn't logged in. */
    const val ON_PHONE_AUTHENTICATED = "/on_phone_authenticated"

    /** watch → phone: watch's periodic cache backup snapshot. */
    const val WATCH_SNAPSHOT = "/watch_snapshot"

    /** watch → phone: ask the phone to send back the last stored snapshot. */
    const val REQUEST_SEED = "/request_seed"

    /** phone → watch: reply to [REQUEST_SEED] with the snapshot bytes (empty
     *  payload if the companion has never received one). */
    const val WATCH_SEED = "/watch_seed"

    /** watch → phone: watch pushes its freshly-rotated tokens up so the
     *  companion doesn't drift onto a stale refresh_token. */
    const val TOKENS_FROM_WATCH = "/tokens_from_watch"

    /** phone → watch: overrides the spoofed Hevy-App-Version/Hevy-App-Build
     *  headers used by the private API interceptor. Payload is
     *  `{version_name, version_code}`. */
    const val API_VERSION = "/api_version"

    /** phone → watch: pop the watch into Workout Detail for a specific
     *  workout id so the user can Resume it there. */
    const val RESUME_WORKOUT = "/resume_workout"
}
