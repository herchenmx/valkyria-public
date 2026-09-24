package com.example.hevycompanion

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.example.hevycompanion.data.AuthPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TokenWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Bucket-E item 25 — hoist the AuthPrefs read to one instance per
        // update pass instead of one per widget id. Opening
        // EncryptedSharedPreferences is cheap but not free; on a home
        // screen with 3-4 stacked widgets this was 3-4× the setup cost.
        val prefs = AuthPrefs(context)
        ids.forEach { id -> updateWidget(context, manager, id, prefs) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            Log.d(TAG, "Widget refresh tapped — delegating to TokenRefreshWorker")
            setRefreshingState(context)
            // Hand the actual refresh off to WorkManager instead of running it
            // inline in a detached coroutine. A BroadcastReceiver's process is
            // demoted to cached priority the moment onReceive() returns, and
            // in that state the per-process network stack denies DNS lookups
            // (UnknownHostException: "Unable to resolve host …: No address
            // associated with hostname"). The coroutine kept running but had
            // no working network — which is why the widget retry button never
            // healed itself, while opening MainActivity (which lifts the
            // process to foreground) instantly did. WorkManager keeps the
            // process properly hosted for the duration of the job, and the
            // worker already calls refreshAllWidgets() on completion, so the
            // widget redraws with fresh state through exactly the same path
            // the periodic refresh uses.
            TokenRefreshWorker.runOnce(context)
        }
    }

    companion object {
        private const val TAG = "TokenWidget"
        const val ACTION_REFRESH = "com.example.hevycompanion.ACTION_WIDGET_REFRESH"
        /** U8: set on the widget-body PendingIntent when the widget is in an
         *  auth-expired state, so MainActivity can route straight to login. */
        const val EXTRA_OPEN_LOGIN = "open_login"

        // SimpleDateFormat is NOT thread-safe, and updateWidget runs both on the
        // main thread (onUpdate) and on WorkManager's background coroutine thread
        // (refreshAllWidgets from TokenRefreshWorker / MainViewModel). A single
        // shared instance formatted concurrently could corrupt output or throw.
        // A ThreadLocal gives each thread its own instance for free.
        private val TIME_FMT = ThreadLocal.withInitial {
            SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        }

        private fun formatTime(millis: Long): String = TIME_FMT.get()!!.format(Date(millis))

        /** Single source for the widget's Display, so onUpdate and the
         *  "Refreshing..." transient don't drift in how they read prefs. */
        private fun statusDisplay(prefs: AuthPrefs): WidgetStatusFormatter.Display =
            WidgetStatusFormatter.format(
                refreshedAt = prefs.lastTokenRefreshedAt,
                errorAt = prefs.lastTokenRefreshErrorAt,
                errorCategory = prefs.lastTokenRefreshErrorCategory,
                errorDetail = prefs.lastTokenRefreshError,
                pushedAt = prefs.lastTokenPushedAt,
                pushErrorAt = prefs.lastTokenPushErrorAt,
                pushErrorCategory = prefs.lastTokenPushErrorCategory,
                isLoggedIn = prefs.isLoggedIn,
                formatTime = ::formatTime,
            )

        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TokenWidgetProvider::class.java))
            if (ids.isEmpty()) return
            // Bucket-E item 25 — single AuthPrefs read for the whole batch
            // (see onUpdate above for the parallel path).
            val prefs = AuthPrefs(context)
            ids.forEach { id -> updateWidget(context, manager, id, prefs) }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            prefs: AuthPrefs,
        ) {
            val display = statusDisplay(prefs)
            val views = buildViews(context, display)
            manager.updateAppWidget(widgetId, views)
        }

        /** Build a fully-wired RemoteViews for [display]: text lines, the
         *  right-hand icon, and BOTH click PendingIntents (refresh button +
         *  widget body). Because `updateAppWidget` replaces the entire view
         *  tree, every path that calls it MUST go through here — otherwise the
         *  click bindings are silently dropped (the "Refreshing..." transient
         *  used to do exactly that, leaving dead buttons until the next hourly
         *  APPWIDGET_UPDATE). */
        private fun buildViews(
            context: Context,
            display: WidgetStatusFormatter.Display,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_token_status)

            views.setTextViewText(R.id.txt_refreshed, display.refreshLine)
            views.setTextViewText(R.id.txt_pushed, display.pushLine)
            if (display.errorLine != null) {
                views.setTextViewText(R.id.txt_error, display.errorLine)
                views.setViewVisibility(R.id.txt_error, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.txt_error, View.GONE)
            }

            // ── Right-hand icon ──
            // In a normal (healthy or retry-able) state the icon is a refresh
            // arrow that broadcasts ACTION_REFRESH back to this provider. In
            // the needs-sign-in state (error AND no credentials — typically
            // after a 401) we swap to a login glyph and route the tap straight
            // to MainActivity with EXTRA_OPEN_LOGIN, because retrying the
            // refresh would short-circuit at the worker's !isLoggedIn check
            // without ever hitting the network.
            if (display.needsSignIn) {
                views.setImageViewResource(R.id.btn_refresh, R.drawable.ic_login)
                views.setContentDescription(R.id.btn_refresh, "Sign in")
                val signInIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(EXTRA_OPEN_LOGIN, true)
                }
                val signInPending = PendingIntent.getActivity(
                    context, 2, signInIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.btn_refresh, signInPending)
            } else {
                views.setImageViewResource(R.id.btn_refresh, R.drawable.ic_refresh)
                views.setContentDescription(R.id.btn_refresh, "Refresh token")
                val refreshIntent = Intent(context, TokenWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH
                }
                val refreshPending = PendingIntent.getBroadcast(
                    context, 0, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.btn_refresh, refreshPending)
            }

            // ── Widget body (anywhere except the refresh icon) → opens the
            //    companion app. When needsSignIn, MainActivity routes straight
            //    to WebLoginActivity via EXTRA_OPEN_LOGIN so the user doesn't
            //    have to take an extra tap through the home screen.
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (display.needsSignIn) {
                    putExtra(EXTRA_OPEN_LOGIN, true)
                }
            }
            val launchPending = PendingIntent.getActivity(
                context, 1, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, launchPending)

            return views
        }

        private fun setRefreshingState(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TokenWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val prefs = AuthPrefs(context)
            // Run the same formatter as updateWidget so the push-line state
            // (success timestamp or watch-unreachable error) survives across
            // the brief "Refreshing..." flicker — only the refresh line and
            // the optional error line are overridden.
            val display = statusDisplay(prefs)
            ids.forEach { id ->
                // Build the full, fully-wired views (incl. the refresh-button and
                // widget-body PendingIntents) then override only the refresh line
                // and hide the error. Previously this built a bare RemoteViews
                // and dropped the click bindings, so the refresh icon went dead
                // for the whole "Refreshing..." window (and stayed dead if the
                // worker was delayed/denied).
                val views = buildViews(context, display)
                views.setTextViewText(R.id.txt_refreshed, "Refreshing...")
                views.setViewVisibility(R.id.txt_error, View.GONE)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
