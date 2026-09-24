package com.example.hevywatch

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.hevycore.wear.WearMessagePaths
import com.example.hevywatch.data.WorkoutDataLoader
import com.example.hevywatch.data.api.HevyApiClient
import com.example.hevywatch.data.api.HevyApiService
import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import com.example.hevywatch.data.api.model.RoutineFolderResponse
import com.example.hevywatch.data.api.model.RefreshTokenRequest
import com.example.hevywatch.data.api.model.WorkoutsListResponse
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.data.model.toActiveWorkout
import com.example.hevywatch.data.store.ActiveWorkoutStore
import com.example.hevywatch.data.store.BodyweightStore
import com.example.hevywatch.data.store.TilePreferenceStore
import com.example.hevywatch.data.store.AuthStore
import com.example.hevywatch.data.store.ExerciseTemplateStore
import com.example.hevywatch.data.store.FolderCacheStore
import com.example.hevywatch.data.store.PendingRequestStore
import com.example.hevywatch.data.store.RoutineCacheStore
import com.example.hevywatch.tile.HevyTileService
import com.example.hevywatch.data.store.ProgressiveOverloadStore
import com.example.hevywatch.data.store.WorkoutHistoryStore
import com.example.hevywatch.wear.CompanionTokenSender
import com.example.hevywatch.wear.WatchSnapshot
import com.example.hevywatch.wear.WatchSnapshotSender
import com.google.android.gms.wearable.Wearable
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private val HEVY_API_KEY = BuildConfig.HEVY_PUBLIC_API_KEY
private const val TOKEN_REFRESH_THROTTLE_MS = 20_000L
private const val TOKEN_REFRESH_PROACTIVE_S = 60L
/** Max exercise-history entries kept in the in-memory LRU. One routine touches
 *  ~6-12 exercises; 40 covers a few routines' worth before evicting. */
private const val EXERCISE_HISTORY_CACHE_MAX = 40


/**
 * Returns true if the token is expired or will expire within [TOKEN_REFRESH_PROACTIVE_S] seconds.
 * Exposed as internal so it can be tested without an Android context.
 */
internal fun isTokenExpiringSoon(
    expiresAt: String?,
    now: Instant = Instant.now()
): Boolean {
    if (expiresAt == null) return true
    return try {
        val expiry = Instant.parse(expiresAt)
        val threshold = now.plusSeconds(TOKEN_REFRESH_PROACTIVE_S)
        !expiry.isAfter(threshold)   // refresh if expiry <= threshold (inclusive)
    } catch (_: Exception) {
        true
    }
}

class HevyApp : Application() {

    val authStore: AuthStore by lazy { AuthStore(this) }
    val progressiveOverloadStore: ProgressiveOverloadStore by lazy { ProgressiveOverloadStore(this) }
    val workoutHistoryStore: WorkoutHistoryStore by lazy { WorkoutHistoryStore(this) }
    val activeWorkoutStore: ActiveWorkoutStore by lazy { ActiveWorkoutStore(this) }
    val tilePreferenceStore: TilePreferenceStore by lazy { TilePreferenceStore(this) }
    val pendingRequestStore: PendingRequestStore by lazy { PendingRequestStore(this) }
    val bodyweightStore: BodyweightStore by lazy { BodyweightStore(this) }
    val baseResistanceStore: com.example.hevywatch.data.store.BaseResistanceStore
        by lazy { com.example.hevywatch.data.store.BaseResistanceStore(this) }
    val userProfileStore: com.example.hevywatch.data.store.UserProfileStore
        by lazy { com.example.hevywatch.data.store.UserProfileStore(this) }
    val displayLimitsStore: com.example.hevywatch.data.store.DisplayLimitsStore
        by lazy { com.example.hevywatch.data.store.DisplayLimitsStore(this) }
    val brightnessSettingsStore: com.example.hevywatch.data.store.BrightnessSettingsStore
        by lazy { com.example.hevywatch.data.store.BrightnessSettingsStore(this) }
    val themeSettingsStore: com.example.hevywatch.data.store.ThemeSettingsStore
        by lazy { com.example.hevywatch.data.store.ThemeSettingsStore(this) }
    val wifiPriorityStore: com.example.hevywatch.data.store.WifiPriorityStore
        by lazy { com.example.hevywatch.data.store.WifiPriorityStore(this) }
    val commitInfoStore: com.example.hevywatch.data.store.CommitInfoStore
        by lazy { com.example.hevywatch.data.store.CommitInfoStore(this) }
    val hevyAppVersionStore: com.example.hevywatch.data.store.HevyAppVersionStore
        by lazy { com.example.hevywatch.data.store.HevyAppVersionStore(this) }

    /** Invoked by [HevyApiClient]'s OkHttp interceptor on every private-API
     *  request, so a sharedPrefs override (via ADB broadcast or the
     *  /api_version DataClient push) takes effect on the next call without
     *  rebuilding or restarting. */
    internal fun versionSupplier(): Pair<String, String> = hevyAppVersionStore.asPair()

    /**
     * P8 — process-scoped background scope, cancelled in [onTerminate]. Used
     * for fire-and-forget warm-up work that needs to outlive any particular
     * Activity / ViewModel but should not leak past process death. Previously
     * each `CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { … }`
     * call created an orphan scope; collecting them here means we can cancel
     * the lot on app teardown.
     */
    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Run [block] on the process-scoped [applicationScope].
     *
     * For work a *screen* starts but whose result a *later* screen depends on.
     * viewModelScope is the wrong scope for that by construction: it is
     * cancelled the moment its ViewModel is cleared, so a request started just
     * before navigating away is killed in flight. The resume flow lost its
     * private v2 workout detail exactly this way — fetched on the detail
     * screen, needed at Finish on the log screen, cancelled by the navigation
     * in between, and the resulting CancellationException swallowed by a
     * `catch (e: Exception)` that turned it into "no detail available".
     */
    fun launchAppScoped(block: suspend CoroutineScope.() -> Unit): kotlinx.coroutines.Job =
        applicationScope.launch(block = block)

    /**
     * Point the debug webhook at its bin, or leave it inert when no URL was
     * built in. Also called by CommitInfoReceiver, so a re-stamped install
     * reports the commit it actually has rather than the one it booted with.
     *
     * The expiry is derived from the APK's own install time, so mirroring
     * stops on its own two weeks after an install even if the secret is never
     * cleared. lastUpdateTime is used rather than a baked-in build timestamp
     * because a BuildConfig value that changes every build would invalidate
     * the generated-BuildConfig task on every single compile.
     */
    fun configureDebugWebhook() {
        val installedAt = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        com.example.hevycore.debug.DebugWebhook.configure(
            webhookUrl = BuildConfig.HEVY_DEBUG_WEBHOOK_URL,
            deviceTag = "watch",
            commitHash = commitInfoStore.commitHash.ifEmpty { "unstamped" },
            expiresAtMillis = if (installedAt > 0L)
                installedAt + com.example.hevycore.debug.DebugWebhook.TTL_MILLIS else 0L,
        )
    }

    /** Why the private v2 detail is missing, when it is. Kept so a resume that
     *  silently downgrades to the public fallback can say what it lost and why,
     *  rather than looking like an ordinary save. */
    @Volatile var continuingWorkoutV2Error: String? = null

    /** User bodyweight in kg, used to compute effective work for assisted-bodyweight
     *  exercises (chinup/pull-up/dip on the assisted machine). Read every time so
     *  Settings edits take effect immediately on the next computation. */
    val bodyweightKg: Float get() = bodyweightStore.bodyweightKg

    override fun onCreate() {
        super.onCreate()
        if (authStore.apiKey == null) {
            authStore.apiKey = HEVY_API_KEY
        }
        configureDebugWebhook()
        // Phase E — if we crashed mid-workout, the persisted "wifi suppressed"
        // bit might still be set with Wi-Fi off. Restore it now if no active
        // workout will be resumed (cleared / 24h-expired), so the user isn't
        // stuck offline outside the workout flow.
        val hasRecoverableWorkout = activeWorkoutStore.load() != null
        com.example.hevywatch.util.WifiSuppressor
            .restoreIfStaleFromCrash(this, hasRecoverableWorkout)
        // Restore persisted workout dates and workout IDs into Compose state
        val persisted = workoutHistoryStore.routineLastWorkoutAt
        if (persisted.isNotEmpty()) {
            routineLastWorkoutAt = persisted
        }
        val persistedIds = workoutHistoryStore.routineWorkoutIds
        if (persistedIds.isNotEmpty()) {
            routineWorkoutIds = persistedIds
        }
        // Restore cached routines from disk so the tile works immediately
        val diskRoutines = routineCacheStore.load()
        if (diskRoutines.isNotEmpty()) {
            _cachedRoutines = diskRoutines  // bypass setter to avoid re-saving
        }
        // Restore cached folders from disk so the Folders tab paints instantly
        val diskFolders = folderCacheStore.load()
        if (diskFolders.isNotEmpty()) {
            cachedFolders = diskFolders
        }
        // Restore exercise-template metadata (equipment + muscle group) from disk
        // if the persisted snapshot is within its TTL. Missing entries are filled
        // at workout-start time by WorkoutDataLoader.fetchExerciseTemplates.
        exerciseTemplateStore.loadFresh()?.let { snapshot ->
            exerciseEquipment.putAll(snapshot.equipment)
            exerciseMuscleGroup.putAll(snapshot.muscleGroup)
        }

        // Prime the exercise-template cache in the background so the first routine the
        // user opens doesn't pay the paginated /exercise_templates fetch. Best-effort:
        // any failure here is absorbed by WorkoutDataLoader.warmExerciseTemplateCache.
        if (authStore.apiKey != null && exerciseEquipment.isEmpty()) {
            applicationScope.launch {
                WorkoutDataLoader.warmExerciseTemplateCache(this@HevyApp)
            }
        }

        // Fresh install (all primary caches empty) — ask the companion for its
        // last-known snapshot so the Folder screen can paint from seeded data
        // within ~1 s instead of waiting on the first paginated API fetch. If
        // the companion has nothing to send (also-fresh install), the normal
        // API path takes over without delay.
        if (_cachedFolders.isEmpty() && _cachedRoutines.isEmpty() &&
            _routineLastWorkoutAt.isEmpty()) {
            WatchSnapshotSender.requestSeed(this)
        }
    }

    // ── Public API (api-key) ──────────────────────────────────────────────────

    private var _apiService: HevyApiService? = null

    fun createApiService(apiKey: String): HevyApiService =
        HevyApiClient.create(apiKey).also { _apiService = it }

    fun requireApiService(): HevyApiService =
        _apiService ?: authStore.apiKey?.let { createApiService(it) }
            ?: error("Not authenticated")

    fun clearApiService() { _apiService = null }

    // ── Private API (Bearer token) ────────────────────────────────────────────

    private var _privateApiService: HevyApiService? = null
    private val tokenRefreshMutex = Mutex()
    private var lastRefreshAtMs: Long = 0L
    /** When a refresh is in flight, every concurrent caller awaits this same
     *  [CompletableDeferred] instead of getting silently dropped by the
     *  20 s throttle. Cleared once the refresh resolves (success or failure)
     *  so the next genuinely-stale call kicks off a new refresh. */
    private var inFlightRefresh: CompletableDeferred<Unit>? = null

    fun requirePrivateApiService(): HevyApiService {
        val token = authStore.accessToken ?: error("No access token")
        return _privateApiService ?: HevyApiClient.createPrivate(token, ::versionSupplier)
            .also { _privateApiService = it }
    }

    /**
     * Activates private API mode using tokens bridged from the companion phone app.
     * If no tokens are stored yet, requests them from the phone and waits up to 15s.
     * If the access token is still valid it is used directly; if expiring, refreshes first.
     */
    suspend fun activatePrivateMode() {
        // Hard gate — don't talk to any API until Bluetooth is ON and a paired
        // phone is reachable. Wear OS tethers internet through the phone over
        // BT, so a missing radio just produces a misleading socket timeout
        // inside OkHttp. The ModeSelectionScreen runs its own poll in parallel
        // to surface "Turn on Bluetooth" / "Connect your phone" while this
        // suspends.
        com.example.hevywatch.wear.PhoneLink.awaitReady(this)

        // If we have no tokens yet, ask the companion and wait for the response.
        if (authStore.refreshToken == null) {
            requestAuthFromPhone()
            withTimeoutOrNull(15_000L) { authTokensReceived.first() }
        }

        val refreshToken = authStore.refreshToken
            ?: error("No auth tokens received from companion app. Make sure the valkyria companion is open and logged in on your phone.")

        if (!isTokenExpiringSoon(authStore.tokenExpiresAt)) {
            // Access token is still valid — just wire up the private service and continue.
            _privateApiService = HevyApiClient.createPrivate(authStore.accessToken!!, ::versionSupplier)
            return
        }

        // Access token is expired or expiring soon — refresh it.
        val bearer = authStore.accessToken?.let { "Bearer $it" }
        val response = HevyApiClient.createAuth(::versionSupplier)
            .refreshToken(bearer, RefreshTokenRequest(refreshToken))
        val at = response.accessToken ?: error("refresh response missing access_token")
        val rt = response.refreshToken ?: error("refresh response missing refresh_token")
        val exp = response.expiresAt ?: error("refresh response missing expires_at")
        onTokensReceived(at, rt, exp)
        // Watch-initiated refresh rotated the server-side RT; push the fresh
        // triple back to the companion so its store stays in sync. See
        // [pushTokensToCompanion] for the rationale.
        pushTokensToCompanion(at, rt, exp)
    }

    // ── Phone auth bridge ─────────────────────────────────────────────────────

    private val _authTokensReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits whenever the phone bridge delivers fresh auth tokens. */
    val authTokensReceived = _authTokensReceived.asSharedFlow()

    /** Called by [PhoneAuthService] (or [signIn]) when tokens arrive from any source. */
    fun onTokensReceived(accessToken: String, refreshToken: String, expiresAt: String) {
        authStore.accessToken = accessToken
        authStore.refreshToken = refreshToken
        authStore.tokenExpiresAt = expiresAt
        _privateApiService = HevyApiClient.createPrivate(accessToken, ::versionSupplier)
        // Fresh tokens from the phone — clear any lingering refresh-error banner.
        tokenRefreshError = null
        _authTokensReceived.tryEmit(Unit)
    }

    /** Asks the Hevy phone app to send us auth tokens via the Wearable Data Layer. */
    fun requestAuthFromPhone() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull() ?: return@addOnSuccessListener
                Wearable.getMessageClient(this)
                    .sendMessage(node.id, WearMessagePaths.REQUEST_AUTH, ByteArray(0))
            }
    }

    suspend fun refreshTokenIfNeeded() {
        // Fast path: check outside the lock first.
        if (!isTokenRefreshNeeded()) return

        // B3 — share a single in-flight refresh between concurrent waiters.
        // Two screens both noticing an expiring token at the same moment used
        // to take divergent paths: one would actually refresh and the other
        // would silently bail through the 20 s throttle, then proceed with
        // the stale token. Now both await the same [CompletableDeferred] so
        // every caller observes the same outcome.
        val (deferred, isLeader) = tokenRefreshMutex.withLock {
            if (!isTokenRefreshNeeded()) return  // someone else just refreshed
            val existing = inFlightRefresh
            if (existing != null) {
                existing to false
            } else {
                // Throttle: don't kick off a new refresh more than once every
                // 20 s. (The leader stamps `lastRefreshAtMs`; followers reuse
                // its result regardless.)
                val now = System.currentTimeMillis()
                if (now - lastRefreshAtMs < TOKEN_REFRESH_THROTTLE_MS) return
                lastRefreshAtMs = now
                val fresh = CompletableDeferred<Unit>()
                inFlightRefresh = fresh
                fresh to true
            }
        }

        if (!isLeader) {
            // Wait for the leader to finish; their exception propagates here.
            deferred.await()
            return
        }

        // Leader: perform the network call exactly once, broadcast result.
        try {
            performTokenRefresh()
            deferred.complete(Unit)
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            tokenRefreshMutex.withLock {
                if (inFlightRefresh === deferred) inFlightRefresh = null
            }
        }
    }

    private suspend fun performTokenRefresh() {
        val refreshToken = authStore.refreshToken ?: error("No refresh token")
        val bearer = authStore.accessToken?.let { "Bearer $it" }
        try {
            val response = HevyApiClient.createAuth(::versionSupplier)
                .refreshToken(bearer, RefreshTokenRequest(refreshToken))
            val at = response.accessToken ?: error("refresh response missing access_token")
            val rt = response.refreshToken ?: error("refresh response missing refresh_token")
            val exp = response.expiresAt ?: error("refresh response missing expires_at")
            authStore.accessToken = at
            authStore.refreshToken = rt
            authStore.tokenExpiresAt = exp
            _privateApiService = HevyApiClient.createPrivate(at, ::versionSupplier)
            tokenRefreshError = null
            // Hevy rotates the refresh token on every successful call. Push
            // the fresh triple back to the companion so its stored RT doesn't
            // become the now-retired one — without this, the next companion-
            // initiated refresh would fail with 401 and the home-screen widget
            // would show "Sign in again" while the watch is perfectly happy.
            pushTokensToCompanion(at, rt, exp)
        } catch (e: Exception) {
            // The companion app rotates refresh tokens on its own schedule, so
            // the watch's stored refresh token may already be invalidated. Ask
            // the phone for fresh tokens and wait briefly. If they arrive, the
            // new tokens are installed by onTokensReceived and we can proceed.
            requestAuthFromPhone()
            val gotFresh = withTimeoutOrNull(15_000L) { authTokensReceived.first() } != null
            if (!gotFresh) {
                // Reset throttle so the user's next Finish tap actually retries
                // instead of skipping straight to the (still-stale) cached token.
                lastRefreshAtMs = 0L
                tokenRefreshError = "Token refresh failed — open companion to re-auth"
                throw e
            } else {
                tokenRefreshError = null
            }
        }
    }

    /**
     * Watch-→-companion token sync: after the watch successfully rotates
     * tokens itself (either via [performTokenRefresh] or [activatePrivateMode]'s
     * own refresh), push the fresh triple back to the companion so its stored
     * credentials don't fall behind the server-side rotation. Updates
     * [companionSyncError] with a human-readable message on failure (or clears
     * it on success).
     *
     * Best-effort: failures don't abort the calling flow — the watch's own
     * tokens are already saved and usable. The banner just informs the user
     * that the home-screen widget on the phone is now temporarily out of
     * sync until the companion is opened or the next push succeeds.
     */
    private suspend fun pushTokensToCompanion(
        accessToken: String,
        refreshToken: String,
        expiresAt: String,
    ) {
        when (val r = CompanionTokenSender.push(this, accessToken, refreshToken, expiresAt)) {
            is CompanionTokenSender.Result.Pushed -> {
                companionSyncError = null
            }
            CompanionTokenSender.Result.NoCompanionConnected -> {
                companionSyncError = "Companion unreachable — open it to sync"
            }
            is CompanionTokenSender.Result.Failed -> {
                companionSyncError = "Companion sync failed: ${r.message}"
            }
        }
    }

    /**
     * Inverse of [tokenRefreshError]: this is set when the watch's own refresh
     * *succeeded* but pushing the new tokens to the companion failed. The
     * watch is fully functional in this state — only the companion's view of
     * the auth state is stale. Cleared on the next successful push.
     */
    var companionSyncError: String? by mutableStateOf(null)
        private set

    /** Last token-refresh error message; null when the most recent refresh
     *  succeeded (or no refresh has been attempted yet). Read by the watch
     *  UI to surface a small banner so the user finds out before their
     *  next Finish tap fails. Cleared on the next successful refresh. */
    var tokenRefreshError: String? by mutableStateOf(null)
        private set

    private fun isTokenRefreshNeeded(): Boolean = isTokenExpiringSoon(authStore.tokenExpiresAt)

    /**
     * R6 — derived view of the access-token lifecycle. The persisted fields
     * ([authStore.accessToken], [authStore.tokenExpiresAt], [tokenRefreshError])
     * remain the source of truth on disk; this sealed type is what callers
     * pattern-match on when they need to decide between "use the token now"
     * vs "surface a re-auth prompt." Computed on demand — no mutable state
     * to keep in sync.
     */
    sealed interface TokenState {
        object Missing : TokenState
        object Valid : TokenState
        object ExpiringSoon : TokenState
        object Refreshing : TokenState
        data class Failed(val message: String) : TokenState
    }

    val tokenState: TokenState
        get() {
            val err = tokenRefreshError
            if (err != null) return TokenState.Failed(err)
            if (inFlightRefresh != null) return TokenState.Refreshing
            val expiresAt = authStore.tokenExpiresAt ?: return TokenState.Missing
            return if (isTokenExpiringSoon(expiresAt)) TokenState.ExpiringSoon
                   else TokenState.Valid
        }

    // ── Session state ─────────────────────────────────────────────────────────

    fun logout() {
        authStore.clear()
        _apiService = null
        _privateApiService = null
        activeWorkout = null
        cachedRoutines = emptyList()
    }

    // In-memory caches — populated on first load, refreshed only on explicit user tap
    private val folderCacheStore by lazy { FolderCacheStore(this) }
    private var _cachedFolders: List<RoutineFolderResponse> = emptyList()
    var cachedFolders: List<RoutineFolderResponse>
        get() = _cachedFolders
        set(value) {
            _cachedFolders = value
            folderCacheStore.save(value)
            // P9 — the tile's idle layout filters routines by [tileFolderId];
            // a folder rename or reorder needs to flow through to the tile
            // on the same fetch that updates the screen.
            requestTileUpdate()
        }

    private val routineCacheStore by lazy { RoutineCacheStore(this) }
    private var _cachedRoutines: List<Routine> = emptyList()
    var cachedRoutines: List<Routine>
        get() = _cachedRoutines
        set(value) {
            _cachedRoutines = value
            routineCacheStore.save(value)
            cachedRoutinesAtMs = System.currentTimeMillis()
            requestTileUpdate()
        }

    /** Epoch-ms of the last successful GET /v1/routines fetch. 0 if never fetched
     *  or only loaded from disk. Used by refresh flows to decide whether the
     *  routine list is stale enough to re-fetch. */
    var cachedRoutinesAtMs: Long = 0L

    // ── Per-screen "last user-triggered refresh" timestamps ──────────────────
    // Each user-tappable Refresh chip stamps its own bucket on success so the
    // screen can render an "Updated Nm ago" line below the chip. Stored on the
    // app object (rather than per-VM) so the freshness label survives screen
    // navigations within the session — only a process kill resets these.
    /** Stamps on a successful Folders-page refresh from RoutineFolderListScreen. */
    var foldersRefreshedAtMs: Long by mutableStateOf(0L)
    /** Stamps on a successful Recent-page refresh from RoutineFolderListScreen. */
    var recentRefreshedAtMs: Long by mutableStateOf(0L)
    /** Stamps on a successful Routines-list refresh from RoutineListScreen. */
    var routinesRefreshedAtMs: Long by mutableStateOf(0L)
    /** Stamps on a successful Routine Detail refresh (per-routine, but tracked
     *  globally since the user only views one detail at a time). */
    var routineDetailRefreshedAtMs: Long by mutableStateOf(0L)
    /** Stamps on a successful Workout Detail refresh (per-workout, but tracked
     *  globally since the user only views one detail at a time). */
    var workoutDetailRefreshedAtMs: Long by mutableStateOf(0L)

    // Best normal-set weight per exerciseTemplateId — populated on history fetch, persists for session
    val exerciseBestWeights: MutableMap<String, Float?> = mutableMapOf()

    // Equipment per exerciseTemplateId — template metadata, persisted via ExerciseTemplateStore
    val exerciseEquipment: MutableMap<String, String?> = mutableMapOf()

    // Primary muscle group per exerciseTemplateId — template metadata, persisted via ExerciseTemplateStore
    val exerciseMuscleGroup: MutableMap<String, String?> = mutableMapOf()

    private val exerciseTemplateStore by lazy { ExerciseTemplateStore(this) }

    /** Persists the current [exerciseEquipment] / [exerciseMuscleGroup] snapshot. */
    fun persistExerciseTemplates() {
        exerciseTemplateStore.save(exerciseEquipment.toMap(), exerciseMuscleGroup.toMap())
    }

    // Exercise history cache — keyed by templateId, populated in
    // RoutineDetailViewModel before workout starts. Bounded LRU so a long
    // browsing session can't accumulate every exercise's full history in RAM
    // on a 512 MB watch; an evicted entry just re-fetches on next access. See
    // [boundedLruMap].
    val exerciseHistoryCache: MutableMap<String, ExerciseHistoryResponse> =
        com.example.hevywatch.data.boundedLruMap(EXERCISE_HISTORY_CACHE_MAX)

    /** Bucket-C item 16 — per-entry fetch timestamps for
     *  [exerciseHistoryCache]. [WorkoutDataLoader.fetchExerciseHistory]
     *  treats entries older than [EXERCISE_HISTORY_TTL_MS] as cache misses
     *  so today's PRs surface without a manual refresh tap. Separate map
     *  keeps the historical read paths (SimilarExerciseSuggestion,
     *  RoutineDetailScreen expand row) unchanged — a stale entry still
     *  answers direct reads until the next fetch overwrites it. */
    val exerciseHistoryFetchedAtMs: MutableMap<String, Long> =
        com.example.hevywatch.data.boundedLruMap(EXERCISE_HISTORY_CACHE_MAX)

    // ── Short-lived shared caches ────────────────────────────────────────────
    // Populated when any caller fetches GET /v1/workouts?page=1. Reused across
    // RoutineListViewModel.populateLastWorkoutDates and
    // RoutineFolderListViewModel.loadRecentWorkouts so we don't double-fetch.
    private var cachedWorkoutsPage1: WorkoutsListResponse? = null
    private var cachedWorkoutsPage1AtMs: Long = 0L
    private val workoutsPage1Mutex = Mutex()

    /**
     * Returns a recent GET /v1/workouts?page=1 response from the in-memory cache
     * (if populated within [WORKOUTS_PAGE1_TTL_MS]), otherwise calls [fetcher] and
     * memoises the result. The mutex prevents two near-simultaneous calls (e.g.
     * RoutineListViewModel.init + RoutineFolderListViewModel.init) from both
     * hitting the network.
     *
     * P5 — the TTL is intentionally long (60 min). Every screen that reads
     * recent-workout state now shows a FreshnessLine ("Updated 4m ago") plus a
     * tap-to-refresh chip, so the TTL is no longer the user's staleness
     * indicator — they see the actual age and refresh on demand. A long TTL
     * cuts API calls dramatically without hiding staleness.
     *
     * The 10-second mutex timeout prevents a stuck fetcher from freezing every
     * other screen that asks for page 1; on timeout the caller falls back to a
     * fresh fetch on its own coroutine, so the worst case is a duplicate
     * request rather than a hung UI.
     */
    suspend fun cachedWorkoutsPage1OrFetch(
        fetcher: suspend () -> WorkoutsListResponse
    ): WorkoutsListResponse {
        // Bucket-D item 23 — use tryLock + withLock so an exception inside
        // the fetcher (network throw, cancellation) can't leak a locked
        // mutex. The previous manual lock/unlock/finally worked, but a
        // future edit forgetting the finally block would deadlock every
        // subsequent caller. Semantics identical: try to acquire within
        // WORKOUTS_PAGE1_MUTEX_TIMEOUT_MS; if that times out, fetch
        // outside the mutex (duplicate request accepted).
        val acquired = withTimeoutOrNull(WORKOUTS_PAGE1_MUTEX_TIMEOUT_MS) {
            workoutsPage1Mutex.lock()
            true
        }
        if (acquired == null) return fetcher()
        return try {
            val cached = cachedWorkoutsPage1
            if (cached != null &&
                System.currentTimeMillis() - cachedWorkoutsPage1AtMs < WORKOUTS_PAGE1_TTL_MS) {
                cached
            } else {
                val fresh = fetcher()
                cachedWorkoutsPage1 = fresh
                cachedWorkoutsPage1AtMs = System.currentTimeMillis()
                fresh
            }
        } finally {
            workoutsPage1Mutex.unlock()
        }
    }

    /** Invalidate the workouts page-1 cache. Called after saving a workout. */
    fun invalidateWorkoutsPage1() {
        cachedWorkoutsPage1 = null
        cachedWorkoutsPage1AtMs = 0L
    }

    /** Timestamp of the last successful populateLastWorkoutDates run, used to
     *  throttle repeated history fetches across folder navigations. */
    var workoutHistoryPopulatedAtMs: Long = 0L

    /**
     * Apply a seed snapshot received from the paired phone companion. Only
     * populates caches that are still empty so we never overwrite fresher
     * data that the user produced between onCreate and seed arrival. All
     * populated fields are also written to their disk stores so subsequent
     * cold starts skip the round-trip entirely.
     */
    fun applySeed(snapshot: WatchSnapshot) {
        if (_cachedFolders.isEmpty() && snapshot.folders.isNotEmpty()) {
            cachedFolders = snapshot.folders
        }
        if (_cachedRoutines.isEmpty() && snapshot.routines.isNotEmpty()) {
            cachedRoutines = snapshot.routines
        }
        if (_routineLastWorkoutAt.isEmpty() && snapshot.routineLastWorkoutAt.isNotEmpty()) {
            routineLastWorkoutAt = snapshot.routineLastWorkoutAt
            workoutHistoryStore.routineLastWorkoutAt = snapshot.routineLastWorkoutAt
        }
        if (routineWorkoutIds.isEmpty() && snapshot.routineWorkoutIds.isNotEmpty()) {
            routineWorkoutIds = snapshot.routineWorkoutIds
            workoutHistoryStore.routineWorkoutIds = snapshot.routineWorkoutIds
        }
    }

    /** Push current caches to the phone companion for backup. Call after any
     *  successful fetch that updated folders / routines / workout history. */
    fun pushSnapshotToCompanion() {
        WatchSnapshotSender.pushSnapshot(this)
    }

    /**
     * Append a freshly-saved workout to [routineWorkoutIds] (and persist) so the
     * Progress tab on Routine Detail includes it immediately, without waiting for
     * the next populateLastWorkoutDates run. Called from LogWorkoutViewModel on
     * successful save.
     */
    fun appendWorkoutToRoutineHistory(routineId: String, workoutId: String) {
        val current = routineWorkoutIds.toMutableMap()
        val existing = current[routineId]?.toMutableSet() ?: mutableSetOf()
        existing.add(workoutId)
        current[routineId] = existing
        routineWorkoutIds = current
        workoutHistoryStore.routineWorkoutIds = current
    }

    /**
     * Remove a (now-deleted server-side) workout from [routineWorkoutIds]
     * and persist. Used on resume save: the original workout id is DELETED
     * by the POST+DELETE flow, so keeping it in the set would cause the
     * Progress tab to fire a wasted 404 GET on its next fetch. The next
     * page-1 workouts refresh repopulates the set with the newly-POSTed
     * workout's id.
     *
     * No-op when [workoutId] isn't in the set for [routineId].
     */
    fun removeWorkoutFromRoutineHistory(routineId: String, workoutId: String) {
        val current = routineWorkoutIds.toMutableMap()
        val existing = current[routineId]?.toMutableSet() ?: return
        if (!existing.remove(workoutId)) return
        if (existing.isEmpty()) current.remove(routineId) else current[routineId] = existing
        routineWorkoutIds = current
        workoutHistoryStore.routineWorkoutIds = current
    }

    companion object {
        /** P5 — see [cachedWorkoutsPage1OrFetch]. Long because the user has
         *  an explicit refresh button on every screen that reads this. */
        private const val WORKOUTS_PAGE1_TTL_MS = 60L * 60 * 1000

        /** Mutex acquisition timeout. After this many ms of waiting for an
         *  in-flight cache fetcher we give up and fetch directly. */
        private const val WORKOUTS_PAGE1_MUTEX_TIMEOUT_MS = 10_000L

        /** Bucket-C item 15 — trailing-edge debounce for [requestTileUpdate].
         *  Short enough that a real user pause after a set completion still
         *  fires within the perceived "immediate" window, long enough to
         *  coalesce a rapid EMOM finisher. */
        private const val TILE_UPDATE_DEBOUNCE_MS: Long = 500L

        /** Bucket-C item 16 — how long a cached exercise-history entry is
         *  trusted before being re-fetched on next access. A day is wide
         *  enough that a normal workout session reuses history freely,
         *  tight enough that today's PRs surface tomorrow without a manual
         *  refresh tap. */
        internal const val EXERCISE_HISTORY_TTL_MS: Long = 24L * 60 * 60 * 1000
    }

    // Summary of the last completed workout — read by CongratsScreen
    data class WorkoutSummary(
        val title: String,
        val durationFormatted: String,
        val volumeKg: String,
        val totalSets: Int,
        val exercises: List<ExerciseSummary>
    )
    data class ExerciseSummary(
        val title: String,
        val completedSets: Int,
        /** Strongest PR achieved in this exercise during the just-completed workout, or null. */
        val pr: com.example.hevywatch.presentation.workout.PrType? = null
    )
    var lastWorkoutSummary: WorkoutSummary? = null

    // Original GET /v1/workouts/{id} response when continuing an incomplete workout.
    // Used for screen rendering (routine cross-reference, ISO timestamps, etc.).
    var continuingWorkoutDetail: com.example.hevywatch.data.api.model.WorkoutDetailResponse? = null

    // Original GET /workout/{id} (private v2) response — carries biometrics
    // and the unix-seconds timestamps. Used by the resume Finish path to
    // build the merged POST body. Distinct from the v1 detail above because
    // v1 strips biometrics on the public read.
    /** Why an earlier submit attempt failed, even if a later one succeeded.
     *  A successful fallback otherwise hides the fact that the primary path is
     *  broken -- the workout saves, nothing is shown, and the underlying fault
     *  goes unnoticed for weeks. Survives navigation so the Congrats screen can
     *  report it after the save completes. */
    var lastSubmitDiagnostic: String? = null

    var continuingWorkoutDetailV2: com.example.hevywatch.data.api.model.WorkoutDetailResponseV2? = null

    /** The same workout as [continuingWorkoutDetailV2], unparsed. Resume posts
     *  a modified copy of THIS rather than rebuilding a body from the typed
     *  view, so fields Hevy returns that we don't model survive the round
     *  trip instead of being dropped by Gson. */
    var continuingWorkoutRawV2: com.google.gson.JsonObject? = null

    // Active workout passed between RoutineDetail → LogWorkout
    private var _activeWorkout: ActiveWorkout? by mutableStateOf(null)
    var activeWorkout: ActiveWorkout?
        get() = _activeWorkout
        private set(value) {
            _activeWorkout = value
            // Bucket-E item 24 — invalidate the memoized tile state on EVERY
            // active-workout write. Previously only setActiveWorkoutInMemory
            // cleared it, so a tile swipe after startWorkout / updateActiveWorkout
            // / adjustWorkoutStartTime / clearActiveWorkout / endWorkoutKeepingRecovery
            // (paths that don't go through the VM hot path) could paint the
            // previous workout's rings/weight until the next VM mutation
            // self-healed it. Centralizing here covers all current and future
            // write paths.
            cachedActiveTileState = null
        }

    /** Set when the workout timer is paused; null when running. */
    var workoutPausedAt: Long? by mutableStateOf(null)

    /**
     * `SystemClock.elapsedRealtime()` value when the active rest timer expires;
     * null when no rest timer is running. Monotonic since boot — immune to
     * NTP/timezone/DST clock jumps. In-memory only (lost on process death,
     * which also kills the timer).
     */
    var restTimerEndMs: Long? by mutableStateOf(null)

    /** Workout IDs per routine ID; populated during workout history fetch, persisted to disk. */
    var routineWorkoutIds: Map<String, Set<String>> = emptyMap()

    /** ISO start-time of the most recent workout per routine ID; drives RoutineListScreen
     *  subtitle and the tile's routine ordering. */
    private var _routineLastWorkoutAt: Map<String, String> by mutableStateOf(emptyMap())
    var routineLastWorkoutAt: Map<String, String>
        get() = _routineLastWorkoutAt
        set(value) {
            _routineLastWorkoutAt = value
            requestTileUpdate()
        }

    fun startWorkout(routine: Routine) {
        activeWorkout = routine.toActiveWorkout()
        workoutPausedAt = null
        activeWorkout?.let { activeWorkoutStore.save(it) }
        com.example.hevywatch.util.WifiSuppressor.suppress(this)
        requestTileUpdate()
    }

    /** Called on resume to slide the virtual start time forward by the paused duration. */
    fun adjustWorkoutStartTime(newStartTimeMs: Long) {
        activeWorkout = activeWorkout?.copy(startTimeMs = newStartTimeMs)
    }

    /** Sync the live workout state from the ViewModel back to HevyApp (for tile, etc.).
     *  Also persists to disk for crash recovery. Used by bootstrapping paths
     *  (Continue Workout, resume-from-recovery) that need an immediate save. */
    fun updateActiveWorkout(workout: ActiveWorkout?) {
        activeWorkout = workout
        if (workout != null) {
            activeWorkoutStore.save(workout)
            com.example.hevywatch.util.WifiSuppressor.suppress(this)
        }
        requestTileUpdate()
    }

    /** Mirror the live workout into HevyApp memory + tile without persisting.
     *  Used on the per-mutation hot path from LogWorkoutViewModel — that VM
     *  drives its own throttled save loop ([ThrottledSaver]) so we don't burn
     *  a Gson serialise on every weight-picker scroll tick. */
    fun setActiveWorkoutInMemory(workout: ActiveWorkout?) {
        // The activeWorkout setter invalidates cachedActiveTileState; the next
        // tile onTileRequest will recompute on demand and cache again.
        activeWorkout = workout
        requestTileUpdate()
    }

    /** Bucket-E item 24 — memoized [ActiveTileState] for [HevyTileService].
     *  The tile framework calls onTileRequest on every swipe-to; the fingerprint
     *  cache short-circuited the layout rebuild but the state computation
     *  (walking every exercise + set of the active workout) still ran per
     *  swipe. Cache the derived state and invalidate it on any activeWorkout
     *  mutation so the swipe path becomes a plain field read. */
    @Volatile
    private var cachedActiveTileState: com.example.hevywatch.tile.ActiveTileState? = null

    /** Return the memoized [ActiveTileState] for the current active workout,
     *  computing it on first access. Returns null when no active workout is
     *  in progress (idle-tile path doesn't need it). Internal so it stays
     *  within the `com.example.hevywatch` module boundary alongside
     *  `computeActiveTileState`. */
    internal fun activeTileStateOrCompute(): com.example.hevywatch.tile.ActiveTileState? {
        val w = activeWorkout ?: return null
        cachedActiveTileState?.let { return it }
        return com.example.hevywatch.tile.computeActiveTileState(w).also {
            cachedActiveTileState = it
        }
    }

    fun clearActiveWorkout() {
        activeWorkout = null
        continuingWorkoutDetail = null
        continuingWorkoutDetailV2 = null
        continuingWorkoutRawV2 = null
        workoutPausedAt = null
        activeWorkoutStore.clear()
        if (com.example.hevywatch.util.WifiSuppressor.restore(this)) {
            steerWifiToPriority()
        }
        requestTileUpdate()
    }

    /** Phase E — exit the in-memory workout flow but **keep** the recovery
     *  file on disk, so the next launch can offer "Resume workout?". Used
     *  when the user dismisses the connectivity-warning dialog after a 10 s
     *  retry on Finish — they couldn't save now, but the workout data isn't
     *  lost; they'll get another chance later. Wi-Fi is restored too. */
    fun endWorkoutKeepingRecovery() {
        activeWorkout = null
        continuingWorkoutDetail = null
        continuingWorkoutDetailV2 = null
        continuingWorkoutRawV2 = null
        workoutPausedAt = null
        // Intentionally DON'T call activeWorkoutStore.clear() — the file is
        // the user's escape hatch for the next launch.
        if (com.example.hevywatch.util.WifiSuppressor.restore(this)) {
            steerWifiToPriority()
        }
        requestTileUpdate()
    }

    /** Phase G — best-effort post-restore Wi-Fi steering. Reads the priority
     *  list from [WifiPriorityStore] (mutable at runtime via the
     *  `SET_WIFI_PRIORITY` ADB broadcast), and hands it to
     *  [WifiSuppressor.steerToPriorityNetwork]. Empty list → no-op (the OS
     *  auto-picks a saved network in range). Fired on a background thread
     *  because the WifiManager call is mildly blocking. */
    private fun steerWifiToPriority() {
        val priority = wifiPriorityStore.priority
        if (priority.isEmpty()) return
        applicationScope.launch {
            com.example.hevywatch.util.WifiSuppressor.steerToPriorityNetwork(
                this@HevyApp, priority
            )
        }
    }

    /** Bucket-C item 15 — tile-update debounce. A burst of set completions
     *  used to fire `requestUpdate` per mutation; the Tiles framework
     *  coalesces internally, but a debounce here also avoids repeated JNI /
     *  Binder round-trips in the VM path. Only the trailing edge of a
     *  [TILE_UPDATE_DEBOUNCE_MS] window survives. */
    private var pendingTileUpdate: kotlinx.coroutines.Job? = null

    /** Ask the system to re-render the HevyWatch tile with fresh data.
     *  Debounced — see [pendingTileUpdate]. */
    fun requestTileUpdate() {
        pendingTileUpdate?.cancel()
        pendingTileUpdate = applicationScope.launch {
            kotlinx.coroutines.delay(TILE_UPDATE_DEBOUNCE_MS)
            try {
                androidx.wear.tiles.TileService.getUpdater(this@HevyApp)
                    .requestUpdate(HevyTileService::class.java)
            } catch (_: Exception) { /* tile may not be added */ }
        }
    }

    override fun onTerminate() {
        // P8 — Application.onTerminate only fires on the emulator and during
        // tests; on a real device the process is just killed. Cancelling the
        // scope here is still the right defensive move when the framework
        // does cooperate, and keeps Robolectric clean.
        applicationScope.cancel()
        super.onTerminate()
    }
}
