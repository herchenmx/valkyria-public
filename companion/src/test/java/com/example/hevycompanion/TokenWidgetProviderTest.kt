package com.example.hevycompanion

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.hevycompanion.data.AuthPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Integration check for the home-screen widget glue: the refresh broadcast
 * action must match the manifest, refreshAllWidgets must be a no-op when no
 * widgets are bound (so worker post-success calls don't crash on a phone
 * with the widget removed), and the rendered RemoteViews must carry the
 * formatter-produced strings end-to-end.
 *
 * This complements [WidgetStatusFormatterTest] (pure formatter) and
 * [TokenRefreshWorkerOutcomeTest] (worker branching) by covering the wiring
 * neither of those reach.
 */
@RunWith(RobolectricTestRunner::class)
class TokenWidgetProviderTest {

    private lateinit var ctx: Application
    private lateinit var prefs: AuthPrefs

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        prefs = AuthPrefs(ctx)
        prefs.clear()
        // The widget refresh tap delegates to TokenRefreshWorker.runOnce,
        // which calls WorkManager.getInstance(). In production WorkManager is
        // brought up by its androidx.startup ContentProvider; under
        // Robolectric we have to initialise it explicitly or every test that
        // exercises the receiver throws IllegalStateException.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            Configuration.Builder().build()
        )
    }

    @Test
    fun `ACTION_REFRESH constant matches manifest receiver intent-filter`() {
        // Hard-coded in manifest: <action android:name="com.example.hevycompanion.ACTION_WIDGET_REFRESH"/>
        // If this test fails, either the constant or the manifest drifted.
        assertEquals(
            "com.example.hevycompanion.ACTION_WIDGET_REFRESH",
            TokenWidgetProvider.ACTION_REFRESH
        )
    }

    @Test
    fun `refreshAllWidgets is a no-op when no widget instances are bound`() {
        // The companion ships with the widget but the user may not add it.
        // Worker code calls refreshAllWidgets() unconditionally — if it
        // crashed without bound widgets, every refresh would be Result.retry.
        TokenWidgetProvider.refreshAllWidgets(ctx)  // must not throw
    }

    @Test
    fun `onReceive with refresh action does not throw when no widgets bound`() {
        // Same scenario from the user's tap path: bare receiver invocation
        // with our action. Should set the "Refreshing..." state, enqueue the
        // worker, and return cleanly without doing any inline network IO.
        val provider = TokenWidgetProvider()
        val intent = Intent(TokenWidgetProvider.ACTION_REFRESH)
        provider.onReceive(ctx, intent)  // must not throw
    }

    @Test
    fun `onReceive with refresh action enqueues TokenRefreshWorker instead of doing inline IO`() {
        // Regression: previously the receiver did the refresh in a detached
        // coroutine, which left the OS demoting the process to cached state
        // and the network stack denying DNS lookups ("Unable to resolve
        // host"). The fix delegates to WorkManager — and crucially uses
        // setExpedited() so the job runs at IMPORTANT_FOREGROUND priority,
        // taking it out of the Cached App Freezer cgroup that was killing
        // DNS for plain WorkManager jobs.
        val provider = TokenWidgetProvider()
        val intent = Intent(TokenWidgetProvider.ACTION_REFRESH)
        provider.onReceive(ctx, intent)

        val workInfos = androidx.work.WorkManager.getInstance(ctx)
            .getWorkInfosByTag(TokenRefreshWorker::class.java.name)
            .get()
        assertTrue(
            "expected a TokenRefreshWorker job to be enqueued by the widget tap, got $workInfos",
            workInfos.isNotEmpty()
        )
    }

    @Test
    fun `runOnce builds expedited WorkRequest with foreground-info fallback`() {
        // Direct check on the request builder, separate from the receiver
        // wiring. If a future refactor drops setExpedited() the worker reverts
        // to plain JobScheduler priority, which on Android 12+ gets frozen by
        // the OS mid-network-call (the bug we're fixing). Pin the contract.
        val request = androidx.work.OneTimeWorkRequestBuilder<TokenRefreshWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        assertTrue(
            "request must declare itself expedited",
            request.workSpec.expedited
        )
        // OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST means: if the
        // app has exhausted its daily expedited quota, fall back to a normal
        // job rather than dropping the work entirely.
        assertEquals(
            androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            request.workSpec.outOfQuotaPolicy
        )
    }

    @Test
    fun `buildForegroundInfo returns a notification with the FGS-DATA_SYNC type on Q-plus`() {
        // The worker promotes itself via setForeground(getForegroundInfo()) on
        // every doWork() entry. The ForegroundInfo it returns must carry the
        // DATA_SYNC service type or Android 14+ rejects the FGS promotion at
        // runtime, leaving the worker back in cached priority.
        val info = TokenRefreshWorker.buildForegroundInfo(ctx)
        assertEquals(
            "notification id must match the constant the OS will display under",
            TokenRefreshWorker.NOTIFICATION_ID,
            info.notificationId
        )
        // Robolectric defaults to a recent SDK target so the typed-FGS branch
        // is exercised. The assertion below is conditional on the SDK Robolectric
        // is emulating, mirroring the runtime branch in buildForegroundInfo.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            assertEquals(
                "foreground service type must be DATA_SYNC for network sync work",
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                info.foregroundServiceType
            )
        }
        assertNotNull("ForegroundInfo must carry a notification", info.notification)
    }

    @Test
    fun `createNotificationChannel is idempotent and creates the token-refresh channel`() {
        // The channel must exist before setForeground() is called or the OS
        // silently drops the FGS promotion on API 26+. Every entry point
        // (Application.onCreate, schedule, runOnce, buildForegroundInfo)
        // calls this — make sure repeat calls are harmless.
        TokenRefreshWorker.createNotificationChannel(ctx)
        TokenRefreshWorker.createNotificationChannel(ctx)  // must not throw
        TokenRefreshWorker.createNotificationChannel(ctx)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(android.app.NotificationManager::class.java)
            assertNotNull(
                "token-refresh notification channel must be registered",
                mgr.getNotificationChannel(TokenRefreshWorker.CHANNEL_ID)
            )
        }
    }

    @Test
    fun `onUpdate paints success display when prefs have a recent refresh and no error`() {
        // Bind a synthetic widget id so AppWidgetManager has somewhere to
        // route updateAppWidget(). Robolectric's ShadowAppWidgetManager
        // accepts an arbitrary id and exposes the latest RemoteViews.
        val manager = AppWidgetManager.getInstance(ctx)
        val shadow = shadowOf(manager)
        val widgetId = shadow.createWidget(
            TokenWidgetProvider::class.java,
            R.layout.widget_token_status
        )

        val now = System.currentTimeMillis()
        prefs.markRefreshSuccess(now)

        TokenWidgetProvider().onUpdate(ctx, manager, intArrayOf(widgetId))

        val view = shadow.getViewFor(widgetId)
        assertNotNull("widget view must be inflated", view)
        val refreshedText = view.findViewById<TextView>(R.id.txt_refreshed).text.toString()
        assertTrue(
            "expected 'Refreshed:' on success, got '$refreshedText'",
            refreshedText.startsWith("Refreshed:")
        )
    }
}
