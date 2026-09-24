package com.example.hevycompanion

import com.example.hevycompanion.data.PushErrorCategory
import com.example.hevycompanion.data.RefreshErrorCategory

/**
 * Pure helper that decides what the widget should display given:
 *  - last successful refresh timestamp
 *  - last refresh-error timestamp + category (from [RefreshErrorCategory])
 *  - last successful push timestamp
 *  - last push-error timestamp + category (from [PushErrorCategory])
 *  - whether credentials are currently on file
 *
 * The output [Display] is the only thing [TokenWidgetProvider] needs in order
 * to populate the RemoteViews. The category → user-facing string mapping is
 * the table the team agreed on:
 *
 *  | Category        | Display string                             |
 *  |-----------------|--------------------------------------------|
 *  | SERVER_DOWN     | "Hevy is down — will retry"                |
 *  | AUTH_EXPIRED    | "Sign in again"                            |
 *  | FORBIDDEN       | "Account blocked — contact Hevy"           |
 *  | OTHER_HTTP      | "Refresh rejected (HTTP <code>)"           |
 *  | CONTRACT        | "Unexpected response — app may need update"|
 *  | NETWORK         | "Can't reach Hevy"                         |
 *  | PERSISTENCE     | "Can't save tokens"                        |
 *  | NO_WATCH (push) | "Watch unreachable"                        |
 *  | FAILED (push)   | "Watch unreachable"                        |
 *
 * 429 is intentionally absent: the interactor doesn't persist an error stamp
 * for rate-limited responses, so the widget keeps showing the prior good
 * state ("usually invisible to user").
 *
 * Extracted so the display decisions are unit-testable without Android
 * framework (SharedPreferences / RemoteViews / formatter locale).
 */
object WidgetStatusFormatter {

    data class Display(
        /** First line — refresh state. Either "Refreshed: <ts>", "Tap to sign in", or the per-category error text. */
        val refreshLine: String,
        /** Second line — watch-push state. Either "Pushed: <ts>" or the push error string. */
        val pushLine: String,
        /** Optional third line; only populated when [isError] is true. Contains the detail+"last OK" suffix. */
        val errorLine: String?,
        /** True if the refresh side is in an error state. Drives the red error line + colour. */
        val isError: Boolean,
        /** True when the action icon should swap to a sign-in glyph (error AND not logged in). */
        val needsSignIn: Boolean,
        /** True if the push side has an unresolved error newer than the last successful push.
         *  Independent of [isError] — push failures don't block the refresh display. */
        val isPushError: Boolean
    )

    fun format(
        refreshedAt: Long,
        errorAt: Long,
        errorCategory: String?,
        errorDetail: String?,
        pushedAt: Long,
        pushErrorAt: Long,
        pushErrorCategory: String?,
        isLoggedIn: Boolean,
        formatTime: (Long) -> String
    ): Display {
        val refreshHasError = errorCategory != null && errorAt > refreshedAt
        val pushHasError = pushErrorCategory != null && pushErrorAt > pushedAt

        val refreshLine: String
        val errorLine: String?
        if (refreshHasError) {
            val stamp = formatTime(errorAt)
            val lastGood = if (refreshedAt > 0) " (last OK: ${formatTime(refreshedAt)})" else ""
            val userFacing = refreshMessageFor(errorCategory!!, errorDetail)
            refreshLine = "$userFacing · $stamp"
            errorLine = if (errorDetail != null && errorDetail != userFacing) {
                "$errorDetail$lastGood"
            } else if (lastGood.isNotEmpty()) {
                lastGood.removePrefix(" ")
            } else null
        } else if (refreshedAt > 0) {
            refreshLine = "Refreshed: ${formatTime(refreshedAt)}"
            errorLine = null
        } else if (!isLoggedIn) {
            // Cold-start / logged-out empty state. Not an error — quiet prompt
            // for the user to sign in. The widget body tap already routes to
            // login, so no extra wiring needed.
            refreshLine = "Tap to sign in"
            errorLine = null
        } else {
            refreshLine = "Refreshed: --"
            errorLine = null
        }

        val pushLine = if (pushHasError) {
            val stamp = formatTime(pushErrorAt)
            val lastGood = if (pushedAt > 0) " (last OK: ${formatTime(pushedAt)})" else ""
            "${pushMessageFor(pushErrorCategory!!)} · $stamp$lastGood"
        } else if (pushedAt > 0) {
            "Pushed: ${formatTime(pushedAt)}"
        } else {
            "Pushed: --"
        }

        return Display(
            refreshLine = refreshLine,
            pushLine = pushLine,
            errorLine = errorLine,
            isError = refreshHasError,
            needsSignIn = refreshHasError && !isLoggedIn,
            isPushError = pushHasError
        )
    }

    /**
     * Maps a refresh error category to its user-facing string per the agreed
     * taxonomy. Unknown categories fall back to a generic label so a forward-
     * compat prefs read (e.g. after a downgrade) doesn't render an empty line.
     */
    private fun refreshMessageFor(category: String, detail: String?): String = when (category) {
        RefreshErrorCategory.SERVER_DOWN -> "Hevy is down"
        RefreshErrorCategory.AUTH_EXPIRED -> "Sign in again"
        RefreshErrorCategory.FORBIDDEN -> "Account blocked"
        RefreshErrorCategory.OTHER_HTTP -> "Refresh rejected${detail?.let { " ($it)" }.orEmpty()}"
        RefreshErrorCategory.CONTRACT -> "Unexpected response"
        RefreshErrorCategory.NETWORK -> "Can't reach Hevy"
        RefreshErrorCategory.PERSISTENCE -> "Can't save tokens"
        else -> "Refresh failed"
    }

    private fun pushMessageFor(category: String): String = when (category) {
        PushErrorCategory.NO_WATCH -> "Watch unreachable"
        PushErrorCategory.FAILED -> "Watch unreachable"
        else -> "Push failed"
    }
}
