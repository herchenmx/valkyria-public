package com.example.hevycompanion

import android.app.Application
import android.util.Log
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevycompanion.data.AuthPrefs
import com.example.hevycompanion.data.PushErrorCategory
import com.example.hevycompanion.data.RefreshResult
import com.example.hevycompanion.data.RefreshTokenInteractor
import com.example.hevycompanion.wear.HevyApiVersionPrefs
import com.example.hevycompanion.wear.WatchTokenSender
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AuthPrefs(application)
    private val apiVersionPrefs = HevyApiVersionPrefs(application)
    private val refreshInteractor = RefreshTokenInteractor()

    var isLoggedIn    by mutableStateOf(prefs.isLoggedIn)
        private set
    var isLoading     by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
    var watchConnected by mutableStateOf(false)
        private set

    /** Epoch-ms of the last successful token refresh (from any source). */
    var lastTokenRefreshedAt by mutableLongStateOf(prefs.lastTokenRefreshedAt)
        private set

    /** Epoch-ms of the last successful token push to the watch. */
    var lastTokenPushedAt by mutableLongStateOf(prefs.lastTokenPushedAt)
        private set

    /** Active Hevy-App-Version / Hevy-App-Build values pulled from
     *  api-versions/active.json on the repo. Null until the first sync
     *  completes (e.g. fresh install with no network on first launch). */
    var apiVersionName by mutableStateOf(apiVersionPrefs.versionName)
        private set
    var apiVersionCode by mutableStateOf(apiVersionPrefs.versionCode)
        private set
    /** Epoch-ms of the last successful fetch of active.json (regardless of
     *  whether the values changed). */
    var apiVersionSyncedAt by mutableLongStateOf(apiVersionPrefs.lastSyncedAt)
        private set
    /** Epoch-ms of the last successful push of the spoof pair to the watch. */
    var apiVersionPushedAt by mutableLongStateOf(apiVersionPrefs.lastPushedAt)
        private set
    /** Epoch-ms of the last sync ATTEMPT, successful or not. */
    var apiVersionSyncAttemptAt by mutableLongStateOf(apiVersionPrefs.lastSyncAttemptAt)
        private set
    /** Why the last sync attempt failed, or null if it succeeded. Drives the
     *  "API synced" line so a run of failures can't masquerade as the last
     *  good date. */
    var apiVersionSyncError by mutableStateOf(apiVersionPrefs.lastSyncError)
        private set

    /** Re-reads timestamp fields from SharedPrefs. Driven reactively by
     *  [prefsListener] rather than the old `while (true) { delay(60s) }` UI
     *  loop — the values only change when the worker, the widget tap, or the
     *  watch bridge writes them, so waking every minute to re-read unchanged
     *  values was pure overhead. */
    fun refreshTimestamps() {
        lastTokenRefreshedAt = prefs.lastTokenRefreshedAt
        lastTokenPushedAt    = prefs.lastTokenPushedAt
        apiVersionName       = apiVersionPrefs.versionName
        apiVersionCode       = apiVersionPrefs.versionCode
        apiVersionSyncedAt   = apiVersionPrefs.lastSyncedAt
        apiVersionPushedAt   = apiVersionPrefs.lastPushedAt
        apiVersionSyncAttemptAt = apiVersionPrefs.lastSyncAttemptAt
        apiVersionSyncError     = apiVersionPrefs.lastSyncError
        pendingWatchNodeId   = prefs.pendingWatchNodeId
    }

    /**
     * Wearable nodeId of a watch awaiting TOFU approval, or null. Surfaced
     * in-app as well as via the notification, because POST_NOTIFICATIONS can
     * be denied on Android 13+ — without this the approval would be
     * unreachable and the watch would sit tokenless with no visible cause.
     */
    var pendingWatchNodeId by mutableStateOf(prefs.pendingWatchNodeId)
        private set

    /** Approve the pending watch: promote the pin and push tokens to it. */
    fun trustPendingWatch() {
        val nodeId = prefs.pendingWatchNodeId ?: return
        // Additive — trusting a second watch never evicts the first.
        prefs.addTrustedWatch(nodeId)
        pendingWatchNodeId = null
        statusMessage = "Watch trusted — pushing tokens…"
        viewModelScope.launch(Dispatchers.IO) {
            val result = WatchTokenSender.push(getApplication(), prefs)
            statusMessage = when (result) {
                is WatchTokenSender.Result.Pushed -> "Watch trusted. Tokens sent."
                WatchTokenSender.Result.NoWatchConnected -> "Watch trusted, but it's not reachable right now."
                is WatchTokenSender.Result.Failed -> "Watch trusted, but the push failed: ${result.message}"
            }
        }
    }

    /** Reject the pending watch. It can ask again; nothing is pinned. */
    fun rejectPendingWatch() {
        prefs.pendingWatchNodeId = null
        pendingWatchNodeId = null
        statusMessage = "Watch request rejected."
    }

    // Strong reference required — SharedPreferences holds listeners weakly.
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            refreshTimestamps()
        }

    init {
        prefs.registerListener(prefsListener)
        apiVersionPrefs.registerListener(prefsListener)
    }

    override fun onCleared() {
        prefs.unregisterListener(prefsListener)
        apiVersionPrefs.unregisterListener(prefsListener)
        super.onCleared()
    }

    /** Called with tokens captured from the WebView login intercept (or manual paste). */
    fun saveTokensFromWebLogin(accessToken: String, refreshToken: String, expiresAt: String) {
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            statusMessage = "Access token and refresh token are required."
            return
        }
        prefs.save(accessToken.trim(), refreshToken.trim(), expiresAt.trim())
        val now = System.currentTimeMillis()
        prefs.markRefreshSuccess(now)
        lastTokenRefreshedAt = now
        isLoggedIn = true
        statusMessage = "Tokens saved. Notifying watch…"
        TokenWidgetProvider.refreshAllWidgets(getApplication())
        // Fresh login counts as a successful refresh from a non-periodic
        // path — reset the periodic timer so it fires 1h from now rather
        // than on whatever stale cadence existed before.
        TokenRefreshWorker.rescheduleAfterSuccess(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            pushTokensToWatch()
        }
    }

    fun refreshToken() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            statusMessage = null
            try {
                when (val result = refreshInteractor.refresh(prefs)) {
                    is RefreshResult.Success -> {
                        lastTokenRefreshedAt = prefs.lastTokenRefreshedAt
                        isLoggedIn = true
                        statusMessage = "Token refreshed ✓  Expires: ${result.expiresAt}"
                        TokenWidgetProvider.refreshAllWidgets(getApplication())
                        // In-app refresh just succeeded — reset the periodic
                        // timer so the worker fires 1h from now rather than
                        // duplicating this work minutes later.
                        TokenRefreshWorker.rescheduleAfterSuccess(getApplication())
                        pushTokensToWatch()
                    }
                    RefreshResult.AuthExpired -> {
                        // The interactor already cleared AuthPrefs; mirror that
                        // into the in-memory UI state so the screen flips to the
                        // logged-out view immediately instead of staying stuck on
                        // the "logged in but failing" surface.
                        isLoggedIn = false
                        lastTokenRefreshedAt = 0L
                        lastTokenPushedAt = 0L
                        TokenWidgetProvider.refreshAllWidgets(getApplication())
                        statusMessage = "Sign in again."
                    }
                    is RefreshResult.ServerError -> {
                        TokenWidgetProvider.refreshAllWidgets(getApplication())
                        statusMessage = "Hevy is down (HTTP ${result.code}) — will retry."
                    }
                    is RefreshResult.RateLimited -> {
                        // No prefs stamp, no widget change — matches "usually
                        // invisible to user". Surface a quiet status string for
                        // the in-app refresh button only.
                        statusMessage = "Rate limited — try again in a moment."
                    }
                    is RefreshResult.Forbidden -> {
                        TokenWidgetProvider.refreshAllWidgets(getApplication())
                        statusMessage = "Account blocked — contact Hevy."
                    }
                    is RefreshResult.OtherHttpError -> {
                        TokenWidgetProvider.refreshAllWidgets(getApplication())
                        statusMessage = "Refresh rejected (HTTP ${result.code})."
                    }
                    is RefreshResult.ContractError -> {
                        TokenWidgetProvider.refreshAllWidgets(getApplication())
                        statusMessage = "Unexpected response — app may need update."
                    }
                    is RefreshResult.NetworkError -> {
                        TokenWidgetProvider.refreshAllWidgets(getApplication())
                        statusMessage = "Can't reach Hevy."
                    }
                    is RefreshResult.PersistenceError -> {
                        TokenWidgetProvider.refreshAllWidgets(getApplication())
                        statusMessage = "Can't save tokens (${result.message})."
                    }
                    RefreshResult.NotLoggedIn -> {
                        statusMessage = "No refresh token stored."
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun logout() {
        prefs.clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        isLoggedIn = false
        lastTokenRefreshedAt = 0L
        lastTokenPushedAt = 0L
        statusMessage = "Logged out."
        TokenWidgetProvider.refreshAllWidgets(getApplication())
    }

    fun checkWatchStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nodes = Tasks.await(
                    Wearable.getNodeClient(getApplication<Application>()).connectedNodes,
                    5, TimeUnit.SECONDS
                )
                watchConnected = nodes.isNotEmpty()
                statusMessage = if (nodes.isNotEmpty())
                    "Watch reachable via Wear network: ${nodes.joinToString { it.displayName }}"
                else
                    "Watch not reachable."
            } catch (e: Exception) {
                watchConnected = false
                statusMessage = "Could not check watch: ${e.message}"
            }
        }
    }

    private suspend fun pushTokensToWatch() {
        val now = System.currentTimeMillis()
        when (val r = WatchTokenSender.push(getApplication(), prefs)) {
            is WatchTokenSender.Result.Pushed -> {
                prefs.markPushSuccess(now)
                lastTokenPushedAt = prefs.lastTokenPushedAt
                TokenWidgetProvider.refreshAllWidgets(getApplication())
                statusMessage = "Logged in ✓  Watch notified (${r.nodeCount} node(s))."
            }
            WatchTokenSender.Result.NoWatchConnected -> {
                Log.w(TAG, "push: no reachable nodes — enqueueing retry")
                prefs.markPushError(PushErrorCategory.NO_WATCH, "no reachable watch", now)
                TokenWidgetProvider.refreshAllWidgets(getApplication())
                TokenRefreshWorker.runOnce(getApplication<Application>())
                statusMessage = "Watch unreachable — will retry in background."
            }
            is WatchTokenSender.Result.Failed -> {
                Log.e(TAG, "push failed: ${r.message} — enqueueing retry")
                prefs.markPushError(PushErrorCategory.FAILED, r.message, now)
                TokenWidgetProvider.refreshAllWidgets(getApplication())
                try { TokenRefreshWorker.runOnce(getApplication<Application>()) } catch (_: Exception) {}
                statusMessage = "Watch unreachable (${r.message}) — will retry in background."
            }
        }
    }

    companion object {
        private const val TAG = "HevyCompanion"
    }
}
