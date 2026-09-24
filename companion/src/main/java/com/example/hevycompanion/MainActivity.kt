package com.example.hevycompanion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hevycompanion.browse.BrowseItem
import com.example.hevycompanion.browse.BrowserScreen
import com.example.hevycompanion.browse.BrowserViewModel
import com.example.hevycompanion.browse.ExerciseDetailViewModel
import com.example.hevycompanion.browse.HevyExerciseDetailScreen
import com.example.hevycompanion.browse.MmExerciseDetailScreen
import com.example.hevycompanion.browse.Source
import com.example.hevycompanion.browse.ViewMode
import com.example.hevycompanion.generate.GenerateEntryViewModel
import com.example.hevycompanion.generate.GenerateWorkoutScreen
import com.example.hevycompanion.generate.GeneratorViewModel
import com.example.hevycompanion.generate.mm.MmCatalog
import com.example.hevycompanion.generate.mm.MmGenerateScreen
import com.example.hevycompanion.generate.mm.MmGeneratorViewModel
import com.example.hevycompanion.alternatives.AltGroupsScreen
import com.example.hevycompanion.alternatives.AltGroupsViewModel
import com.example.hevycompanion.overview.ExerciseMaxScreen
import com.example.hevycompanion.overview.ExerciseMaxViewModel
import com.example.hevycompanion.data.BodyweightPrefs
import com.example.hevycompanion.recents.RecentsScreen
import com.example.hevycompanion.recents.RecentsViewModel
import com.example.hevycompanion.recents.ResumeWorkoutScreen
import com.example.hevycompanion.recents.WorkoutDetailScreen
import com.example.hevycompanion.trends.RoutineTrendDetailScreen
import com.example.hevycompanion.trends.RoutineTrendsScreen
import com.example.hevycompanion.trends.RoutineTrendsViewModel
import com.example.hevycompanion.wear.WatchResumeSender
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.example.hevycompanion.muscle.MuscleSelectorMode
import com.example.hevycompanion.muscle.MuscleSelectorScreen
import com.example.hevycompanion.ui.ValkyriaTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Schedule hourly background token refresh + watch push.
        // We deliberately do NOT fire a one-shot on app open: the refresh-token
        // endpoint rotates the RT on every success, so opening the app while
        // the periodic worker (or a widget tap) is in flight would race two
        // requests against the same RT and the loser comes back HTTP 401. The
        // hourly worker keeps things fresh; users who want a manual kick can
        // still tap the widget refresh icon or the in-app refresh button.
        TokenRefreshWorker.schedule(this)
        // Request battery optimization exemption so the worker runs reliably
        requestIgnoreBatteryOptimizations()
        requestPostNotificationsIfNeeded()

        // U8 — when the widget body is tapped in an auth-expired state, jump
        // straight to WebLoginActivity so the user doesn't have to navigate
        // through the home screen. Falls through to the normal app on a
        // regular launch.
        if (intent?.getBooleanExtra(TokenWidgetProvider.EXTRA_OPEN_LOGIN, false) == true) {
            intent.removeExtra(TokenWidgetProvider.EXTRA_OPEN_LOGIN)
            startActivity(
                Intent(this, WebLoginActivity::class.java)
            )
        }

        // Background-prime the M&M catalog so the first navigation to the
        // generator doesn't block a compose frame on a 2 MB JSON parse. The
        // user almost always opens the app before reaching the generator, so
        // by the time they tap through the catalog is already in memory.
        // Runs on the Application's scope rather than an orphan one so it
        // isn't leaked past this Activity's destruction.
        (application as HevyCompanionApp).applicationScope.launch {
            MmCatalog.preload(applicationContext)
        }
        setContent {
            ValkyriaTheme {
                // Android 15 forces edge-to-edge for targetSdk = 35 apps and
                // `WindowCompat.setDecorFitsSystemWindows` is a no-op on that
                // path. So we cede the status-bar area to the OS by:
                //   1. Reserving a slot at the top of the layout sized to the
                //      `WindowInsets.statusBars` height, and
                //   2. Painting it solid black so the white system icons stay
                //      legible no matter what app surface we're showing.
                // Everything else (CompanionScreen, MuscleSelectorScreen, etc.)
                // renders below that slot in normal screen real estate.
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(Color.Black)
                    )
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        CompanionScreen()
                    }
                }
            }
        }
    }

    /**
     * Android 13+ requires a runtime grant for POST_NOTIFICATIONS — declaring
     * it in the manifest isn't enough. Without the grant the TOFU
     * "New watch wants to sign in" prompt is silently dropped, so a fresh
     * watch pairing would fail with no visible cause. The in-app banner in
     * [LoggedInSection] is the fallback when the user denies this.
     */
    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        // One system dialog; if the user denies, the OS won't re-prompt and
        // the in-app banner carries the flow instead.
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        // The OS dialog used to pop on *every* launch until granted, which is
        // hostile if the user has deliberately declined. Ask at most once a
        // week; the exemption is a nice-to-have for worker reliability, not a
        // hard requirement.
        val prefs = getSharedPreferences(PROMPT_PREFS, MODE_PRIVATE)
        val lastAsked = prefs.getLong(KEY_BATTERY_PROMPT_AT, 0L)
        val now = System.currentTimeMillis()
        if (now - lastAsked < BATTERY_PROMPT_INTERVAL_MS) return
        prefs.edit().putLong(KEY_BATTERY_PROMPT_AT, now).apply()

        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
        private const val PROMPT_PREFS = "companion_prompts"
        private const val KEY_BATTERY_PROMPT_AT = "battery_opt_prompt_at"
        private const val BATTERY_PROMPT_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

@Composable
fun CompanionScreen(
    vm: MainViewModel = viewModel(),
    browserVm: BrowserViewModel = viewModel(),
    genVm: GeneratorViewModel = viewModel(),
    mmGenVm: MmGeneratorViewModel = viewModel(),
    detailVm: ExerciseDetailViewModel = viewModel(),
    entryVm: GenerateEntryViewModel = viewModel(),
    overviewVm: ExerciseMaxViewModel = viewModel(),
    recentsVm: RecentsViewModel = viewModel(),
    altGroupsVm: AltGroupsViewModel = viewModel(),
    trendsVm: RoutineTrendsViewModel = viewModel(),
) {
    LaunchedEffect(Unit) {
        vm.checkWatchStatus()
        // Timestamps refresh reactively via the VM's SharedPreferences
        // listener — no polling loop needed.
    }

    // Recents → Resume: a tapped "Resume" on an incomplete workout opens a
    // chooser (finish here on the phone, or hand off to the watch). State lives
    // here at the host so it survives the detail/resume screen swaps below.
    val resumeContext = LocalContext.current
    val resumeScope = rememberCoroutineScope()
    var resumeHereOpen by remember { mutableStateOf(false) }
    var showResumeChooser by remember { mutableStateOf(false) }

    // Detail page takes over the whole screen from any feature when an
    // exercise is selected. Rendered FIRST so it always wins over the
    // feature flows below — predictive-back / system-back is wired to the
    // VM's stack pop, so swipe-back returns to whichever feature spawned
    // the detail (or to a previous detail level if the user re-targeted
    // through tap-similar).
    detailVm.current?.let { sel ->
        when (sel) {
            is ExerciseDetailViewModel.Selection.Hevy -> {
                detailVm.hevyTarget?.let { target ->
                    BackHandler { detailVm.goBack() }
                    HevyExerciseDetailScreen(
                        target = target,
                        catalog = detailVm.hevyCatalog,
                        attrs = detailVm.attrsFor(target.id),
                        onBack = { detailVm.goBack() },
                        onSimilarTap = { detailVm.openHevy(it.id) },
                    )
                    return
                }
                // Hevy catalog still loading on first-ever open — render
                // a thin placeholder so we don't fall through to the
                // spawning feature behind us.
                BackHandler { detailVm.goBack() }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                    )
                }
                return
            }
            is ExerciseDetailViewModel.Selection.Mm -> {
                detailVm.mmTarget?.let { target ->
                    BackHandler { detailVm.goBack() }
                    MmExerciseDetailScreen(
                        target = target,
                        catalog = detailVm.mmCatalog,
                        onBack = { detailVm.goBack() },
                        onSimilarTap = { detailVm.openMm(it.id) },
                    )
                    return
                }
            }
        }
    }

    // Unified Browser takes over the whole screen when active. From the
    // muscle-grid sub-mode, swipe-back pops back to the list view (mirroring
    // the legacy "browse-by-muscle → muscle-list" hierarchy); from the list
    // view, swipe-back closes the Browser entirely.
    if (browserVm.isOpen) {
        BackHandler {
            if (browserVm.viewMode == ViewMode.MUSCLE_GRID) browserVm.setViewMode(ViewMode.LIST)
            else browserVm.close()
        }
        BrowserScreen(
            vm = browserVm,
            onBack = { browserVm.close() },
            onAvatarTap = { item ->
                when (item) {
                    is BrowseItem.Hevy -> detailVm.openHevy(item.id)
                    is BrowseItem.Mm -> detailVm.openMm(item.id)
                }
            },
        )
        return
    }

    // Generate-workout flow takes over the whole screen. The unified entry
    // (single home button) reads `entryVm.source` to decide which inner VM
    // to drive; the Source segmented control on the SETUP phase lets the
    // user flip catalogs before committing to a workout. Past the Setup
    // phase the toggle hides — the result is bound to the algorithm's
    // catalog and switching mid-flow would discard work.
    when (genVm.screen) {
        GeneratorViewModel.Screen.Closed -> Unit // fall through
        GeneratorViewModel.Screen.MusclePicker -> {
            BackHandler { entryVm.close(genVm, mmGenVm) }
            Column(modifier = Modifier.fillMaxSize()) {
                GenerateSourceHeader(
                    source = entryVm.source,
                    onChange = { entryVm.setSource(it, genVm, mmGenVm) },
                )
                MuscleSelectorScreen(
                    mode = MuscleSelectorMode.Multi(
                        initial = genVm.selectedMuscles,
                        onConfirm = { genVm.confirmMuscles(it) },
                    ),
                    onBack = { entryVm.close(genVm, mmGenVm) },
                    modifier = Modifier.weight(1f),
                )
            }
            return
        }
        GeneratorViewModel.Screen.Result -> {
            BackHandler { entryVm.close(genVm, mmGenVm) }
            GenerateWorkoutScreen(
                vm = genVm,
                onBack = { entryVm.close(genVm, mmGenVm) },
                onAvatarTap = { detailVm.openHevy(it) },
            )
            return
        }
        GeneratorViewModel.Screen.SaveFolderPick -> {
            BackHandler { genVm.cancelSave() }
            com.example.hevycompanion.generate.SaveRoutineFolderScreen(
                folders = genVm.folders,
                isLoading = genVm.isLoadingFolders,
                errorMessage = genVm.folderError,
                onPick = { genVm.pickFolder(it) },
                onRetry = { genVm.fetchFolders() },
                onBack = { genVm.cancelSave() },
            )
            return
        }
        GeneratorViewModel.Screen.SaveDetails -> {
            BackHandler { genVm.backToFolderPick() }
            com.example.hevycompanion.generate.SaveRoutineDetailsScreen(
                vm = genVm,
                onBack = { genVm.backToFolderPick() },
                onCancel = { genVm.cancelSave() },
            )
            return
        }
    }

    // M&M generator: an entirely separate flow built on the bundled M&M
    // catalog. Same Source-toggle gating as the Hevy side — visible on Setup
    // only, hidden on Result.
    when (mmGenVm.screen) {
        MmGeneratorViewModel.Screen.Closed -> Unit // fall through
        MmGeneratorViewModel.Screen.Setup -> {
            BackHandler { entryVm.close(genVm, mmGenVm) }
            Column(modifier = Modifier.fillMaxSize()) {
                GenerateSourceHeader(
                    source = entryVm.source,
                    onChange = { entryVm.setSource(it, genVm, mmGenVm) },
                )
                MmGenerateScreen(
                    vm = mmGenVm,
                    onBack = { entryVm.close(genVm, mmGenVm) },
                    onAvatarTap = { detailVm.openMm(it) },
                    modifier = Modifier.weight(1f),
                )
            }
            return
        }
        MmGeneratorViewModel.Screen.Result -> {
            BackHandler { entryVm.close(genVm, mmGenVm) }
            MmGenerateScreen(
                vm = mmGenVm,
                onBack = { entryVm.close(genVm, mmGenVm) },
                onAvatarTap = { detailVm.openMm(it) },
            )
            return
        }
    }

    // Strength Overview: read-only table of the user's highest "managed"
    // weight per weight-rep exercise. Hits the public api-key endpoints, so
    // like the Browser/Generator it works independently of login state.
    if (overviewVm.isOpen) {
        BackHandler { overviewVm.close() }
        // Bucket-G item 40 — Alternatives is otherwise only reachable from
        // the home screen; users in Strength Overview would typically want
        // to jump to "similar exercises" for whatever they're looking at,
        // but had to back all the way out first. Expose it as a header
        // action so a single tap closes the overview and opens Alternatives.
        ExerciseMaxScreen(
            vm = overviewVm,
            onBack = { overviewVm.close() },
            onOpenAlternatives = {
                overviewVm.close()
                altGroupsVm.open()
            },
        )
        return
    }

    // Exercise Alternatives: browsable curated substitution groups, each a card
    // of interchangeable exercises with catalogue pictures. Tapping a row opens
    // that exercise's Hevy detail (rendered by the detail takeover above, which
    // wins over this block). Public api-key, so it works regardless of login.
    if (altGroupsVm.isOpen) {
        BackHandler { altGroupsVm.close() }
        AltGroupsScreen(
            vm = altGroupsVm,
            onBack = { altGroupsVm.close() },
            onExerciseTap = { detailVm.openHevy(it) },
        )
        return
    }

    // Routine Trends: per-routine totals over the last 12 months. The routine
    // list gives way to the chart + delta breakdown when a routine is picked
    // (back clears the routine, then closes the feature) — same two-level
    // takeover as Recents → Workout Detail. Public api-key, so it works
    // independently of login state.
    if (trendsVm.isOpen) {
        if (trendsVm.selectedRoutineId != null) {
            BackHandler { trendsVm.closeRoutine() }
            RoutineTrendDetailScreen(
                vm = trendsVm,
                onBack = { trendsVm.closeRoutine() },
            )
        } else {
            BackHandler { trendsVm.close() }
            RoutineTrendsScreen(
                vm = trendsVm,
                onOpenRoutine = { trendsVm.openRoutine(it) },
                onBack = { trendsVm.close() },
            )
        }
        return
    }

    // Recents: recently logged workouts. Selecting one opens the Workout Detail
    // screen over the list (back clears the selection, then closes the feature)
    // — mirrors the watch's Recent page → Workout Detail navigation. Public
    // api-key, so it works independently of login state.
    if (recentsVm.isOpen) {
        val selected = recentsVm.selectedWorkoutId
        when {
            selected != null && resumeHereOpen -> {
                BackHandler { resumeHereOpen = false }
                ResumeWorkoutScreen(
                    workoutId = selected,
                    onBack = { resumeHereOpen = false },
                    // After a successful submit, drop the user back to the list.
                    onDone = {
                        resumeHereOpen = false
                        recentsVm.closeWorkout()
                    },
                )
            }
            selected != null -> {
                BackHandler { recentsVm.closeWorkout() }
                WorkoutDetailScreen(
                    workoutId = selected,
                    onBack = { recentsVm.closeWorkout() },
                    onResume = { showResumeChooser = true },
                )
            }
            else -> {
                BackHandler { recentsVm.close() }
                RecentsScreen(
                    vm = recentsVm,
                    onOpenWorkout = { recentsVm.openWorkout(it) },
                    onBack = { recentsVm.close() },
                )
            }
        }

        if (showResumeChooser && selected != null) {
            ResumeChooserDialog(
                onResumeHere = {
                    showResumeChooser = false
                    resumeHereOpen = true
                },
                onResumeOnWatch = {
                    showResumeChooser = false
                    resumeScope.launch {
                        val msg = when (val r = WatchResumeSender.send(resumeContext, selected)) {
                            is WatchResumeSender.Result.Sent ->
                                "Sent to watch — open valkyria there and tap Resume."
                            WatchResumeSender.Result.NoWatchConnected ->
                                "No watch connected."
                            is WatchResumeSender.Result.Failed ->
                                "Couldn't reach the watch: ${r.message}"
                        }
                        Toast.makeText(resumeContext, msg, Toast.LENGTH_LONG).show()
                    }
                },
                onDismiss = { showResumeChooser = false },
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("valkyria", style = MaterialTheme.typography.headlineMedium)

        Text(
            text = "Bridges auth tokens from your account to the Wear OS watch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        if (vm.isLoggedIn) {
            LoggedInSection(vm)
        } else {
            LoginSection(vm)
        }

        // The Browser and the unified workout generator hit the public Hevy
        // API (compile-time api-key) or the bundled M&M asset, so they work
        // independently of the user's login state — always surface the entry
        // points. The previous five-button row (Browse by Muscle / Hevy
        // Generator / M&M Generator / Hevy List / M&M List) collapsed to
        // two: Browser + Generate Workout, each with an internal Source
        // segmented control to flip catalogs.
        HorizontalDivider()
        OutlinedButton(
            onClick = { browserVm.open() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Browse Exercises") }
        OutlinedButton(
            onClick = { entryVm.open(genVm, mmGenVm) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Generate Workout") }
        OutlinedButton(
            onClick = { overviewVm.open() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Strength Overview") }
        OutlinedButton(
            onClick = { altGroupsVm.open() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Exercise Alternatives") }
        OutlinedButton(
            onClick = { recentsVm.open() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Recents") }
        OutlinedButton(
            onClick = { trendsVm.open() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Routine Trends") }

        BodyweightSetting()

        vm.statusMessage?.let { msg ->
            HorizontalDivider()
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (msg.lowercase().let { it.contains("fail") || it.contains("error") })
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Choice presented when the user taps "Resume" on an incomplete workout: finish
 * the remaining sets here on the phone (PUT-merge submit), or hand the workout
 * off to the paired watch (Wearable `/resume_workout`) to log there.
 */
@Composable
private fun ResumeChooserDialog(
    onResumeHere: () -> Unit,
    onResumeOnWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Both "Resume here" and "On watch" are affirmative choices, so they share
    // the confirm slot; the dismiss slot carries a real Cancel. Previously
    // "On watch" sat in the dismiss slot, which read as the cancel action and
    // left an accidentally-opened dialog with no explicit way out.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume workout") },
        text = { Text("Finish the remaining sets here, or open it on your watch to log there.") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onResumeOnWatch) { Text("On watch") }
                TextButton(onClick = onResumeHere) { Text("Resume here") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Compact bodyweight editor. The value feeds the Recents progressive-overload /
 * warmup advisors for assisted-bodyweight exercises (dips, pull-ups) — see
 * [BodyweightPrefs]. Persists on every valid edit; an unparseable / non-positive
 * entry is ignored (the field reverts to the stored value on the next read).
 */
@Composable
private fun BodyweightSetting() {
    val context = LocalContext.current
    val prefs = remember { BodyweightPrefs(context) }
    var text by remember { mutableStateOf(formatKg(prefs.bodyweightKg)) }

    fun commit() {
        text.trim().toFloatOrNull()?.takeIf { it > 0f }?.let { prefs.bodyweightKg = it }
        // Re-normalise the field to the stored value (drops invalid input).
        text = formatKg(prefs.bodyweightKg)
    }

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Bodyweight",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            suffix = { Text("kg") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
            ),
            modifier = Modifier.width(120.dp),
        )
        TextButton(onClick = { commit() }) { Text("Save") }
    }
    Text(
        text = "Used by Recents to size warmups and progressive-overload targets " +
            "for assisted exercises (dips, pull-ups).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Trim a trailing ".0" so whole-kg bodyweights read "57", not "57.0". */
private fun formatKg(kg: Float): String =
    if (kg == kg.toLong().toFloat()) kg.toLong().toString() else kg.toString()

@Composable
private fun LoggedInSection(vm: MainViewModel) {
    Text("✓ Logged in", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

    // TOFU approval, surfaced in-app as well as via the notification. The
    // notification needs POST_NOTIFICATIONS, which the user can deny on
    // Android 13+; without this fallback the approval would be unreachable
    // and the watch would sit tokenless with no visible cause.
    vm.pendingWatchNodeId?.let { nodeId ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "New watch wants to sign in",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "A Wear OS device (…${nodeId.takeLast(6)}) is requesting your Hevy " +
                        "tokens. Trust it only if you just installed valkyria on your own watch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.trustPendingWatch() }) { Text("Trust") }
                    OutlinedButton(onClick = { vm.rejectPendingWatch() }) { Text("Reject") }
                }
            }
        }
    }

    Text(
        text = "WatchBridgeService runs in the background. When the watch reconnects it " +
            "sends /request_auth and the service responds with your tokens automatically.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Token refresh / push timestamps
    val tsFmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    val refreshedLabel = if (vm.lastTokenRefreshedAt == 0L) "Never"
                         else tsFmt.format(Date(vm.lastTokenRefreshedAt))
    val pushedLabel    = if (vm.lastTokenPushedAt == 0L) "Never"
                         else tsFmt.format(Date(vm.lastTokenPushedAt))
    // Hevy-App-Version / Hevy-App-Build spoof pair pushed to the watch. Pulled
    // from api-versions/active.json on every cold start via HevyApiVersionSync.
    val apiVersionLabel = when {
        vm.apiVersionName != null && vm.apiVersionCode != null ->
            "${vm.apiVersionName} (${vm.apiVersionCode})"
        else -> "Unknown — waiting for first sync"
    }
    val apiSyncedLabel = com.example.hevycompanion.wear.ApiSyncStatusFormatter.label(
        lastSyncedAt = vm.apiVersionSyncedAt,
        lastAttemptAt = vm.apiVersionSyncAttemptAt,
        lastError = vm.apiVersionSyncError,
        format = { tsFmt.format(Date(it)) },
    )
    val apiPushedLabel = if (vm.apiVersionPushedAt == 0L) "Never"
                         else tsFmt.format(Date(vm.apiVersionPushedAt))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Token refreshed: $refreshedLabel",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Token pushed:    $pushedLabel",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "API spoof:       $apiVersionLabel",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "API synced:      $apiSyncedLabel",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "API pushed:      $apiPushedLabel",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.checkWatchStatus() }) { Text("Check Watch") }
        OutlinedButton(onClick = { vm.refreshToken() }) { Text("Refresh Token") }
    }

    Button(
        onClick = { vm.logout() },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor   = MaterialTheme.colorScheme.onErrorContainer
        )
    ) { Text("Log Out") }
}

@Composable
private fun LoginSection(vm: MainViewModel) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@rememberLauncherForActivityResult
            val accessToken  = data.getStringExtra(WebLoginActivity.RESULT_ACCESS_TOKEN)  ?: return@rememberLauncherForActivityResult
            val refreshToken = data.getStringExtra(WebLoginActivity.RESULT_REFRESH_TOKEN) ?: return@rememberLauncherForActivityResult
            val expiresAt    = data.getStringExtra(WebLoginActivity.RESULT_EXPIRES_AT)    ?: ""
            vm.saveTokensFromWebLogin(accessToken, refreshToken, expiresAt)
        }
    }

    Text("Log in", style = MaterialTheme.typography.titleMedium)

    Text(
        text = "Opens the source-app website so reCAPTCHA can verify you normally. " +
               "Your credentials are pre-filled — just tap Log In.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (vm.isLoading) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                text = vm.statusMessage ?: "Working…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Button(
        onClick = {
            launcher.launch(
                Intent(context, WebLoginActivity::class.java)
            )
        },
        enabled = !vm.isLoading,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Log In")
    }

    HorizontalDivider()
    ManualTokenSection(vm)
}

@Composable
private fun ManualTokenSection(vm: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "▲ Hide Manual Token Entry" else "▼ Paste Tokens Manually")
    }

    if (!expanded) return

    Text(
        text = "Fallback: intercept an auth response from the official source app with a proxy " +
            "(Charles, mitmproxy) and paste the three token fields here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    var accessToken  by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf("") }
    var expiresAt    by remember { mutableStateOf("") }

    OutlinedTextField(value = accessToken,  onValueChange = { accessToken  = it }, label = { Text("access_token") },  singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = refreshToken, onValueChange = { refreshToken = it }, label = { Text("refresh_token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = expiresAt,    onValueChange = { expiresAt    = it }, label = { Text("expires_at  (e.g. 2026-09-01T12:00:00.000Z)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

    Button(
        onClick = { vm.saveTokensFromWebLogin(accessToken, refreshToken, expiresAt) },
        enabled = accessToken.isNotBlank() && refreshToken.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Save Tokens & Notify Watch") }
}

/**
 * Source segmented control rendered above the unified Generate Workout
 * Setup phase (Hevy MusclePicker / M&M Setup). Visible on Setup only —
 * past that point the result is bound to the chosen catalog and switching
 * mid-flow would discard work.
 */
@Composable
private fun GenerateSourceHeader(
    source: Source,
    onChange: (Source) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Source.entries.forEachIndexed { idx, src ->
                SegmentedButton(
                    selected = source == src,
                    onClick = { onChange(src) },
                    shape = SegmentedButtonDefaults.itemShape(idx, Source.entries.size),
                ) { Text(if (src == Source.HEVY) "Hevy" else "M&M") }
            }
        }
    }
}
