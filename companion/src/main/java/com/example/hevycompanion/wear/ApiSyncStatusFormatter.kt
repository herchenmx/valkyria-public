package com.example.hevycompanion.wear

/**
 * Turns the api-version sync bookkeeping into the "API synced:" line.
 *
 * Exists because that line used to read `lastSyncedAt` alone, which is only
 * ever written on success — so a run of failed syncs left the last *good*
 * date on screen with nothing to distinguish "checked, fine" from "has not
 * managed to check since". That is how a companion whose auto-sync had never
 * worked from a CI build still showed a plausible-looking sync date: the
 * timestamp came from an earlier locally-built APK, SharedPreferences survive
 * an update install, and every failure since was silent.
 *
 * Pure so the rule — never claim a sync that did not happen — is unit-testable
 * without a device.
 */
object ApiSyncStatusFormatter {

    /** Keeps a long server/exception message from pushing the line off-screen. */
    private const val MAX_REASON = 48

    /**
     * @param lastSyncedAt epoch-ms of the last SUCCESSFUL fetch, 0 if never.
     * @param lastAttemptAt epoch-ms of the last attempt of any outcome, 0 if never.
     * @param lastError reason the last attempt failed, or null if it succeeded.
     * @param format renders an epoch-ms as the caller's display string.
     */
    fun label(
        lastSyncedAt: Long,
        lastAttemptAt: Long,
        lastError: String?,
        format: (Long) -> String,
    ): String {
        if (lastError.isNullOrBlank()) {
            return if (lastSyncedAt == 0L) "Never" else format(lastSyncedAt)
        }
        val reason = lastError.trim().let {
            if (it.length > MAX_REASON) it.take(MAX_REASON - 1) + "…" else it
        }
        val attempt = if (lastAttemptAt == 0L) "" else " " + format(lastAttemptAt)
        // A failure with no prior success is the case worth shouting about: the
        // pair on screen is the value baked in at build time, not anything
        // fetched, so "never" is the honest word for it.
        val history = if (lastSyncedAt == 0L) "never synced"
                      else "last OK " + format(lastSyncedAt)
        return "FAILED$attempt ($history) — $reason"
    }
}
