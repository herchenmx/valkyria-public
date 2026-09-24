package com.example.hevywatch.presentation.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.store.BodyweightStore
import com.example.hevywatch.data.store.BrightnessSettingsStore
import com.example.hevywatch.data.store.DisplayLimitsStore
import com.example.hevywatch.data.store.Sex
import com.example.hevywatch.data.store.UserProfileStore
import com.example.hevywatch.data.store.ThemeMode
import com.example.hevywatch.sensors.HeartRateAvailability
import com.example.hevywatch.ui.components.AppScaffold

/**
 * Multi-section settings screen:
 *  - Bodyweight (kg) — used by assisted-bodyweight computations (warmup
 *    advisor, PO, routine progress volume, PR detection).
 *  - Brightness — `default` (resting) / `max` (on-touch) / `idle` seconds
 *    before dropping back to default. Driven by `BrightnessSettingsStore`
 *    and applied app-wide by `BrightnessCoordinator`.
 *
 * Watch screen is tiny, so the layout is a ScalingLazyColumn — sections
 * scroll past each other rather than crowding into one viewport. Each
 * "row" uses the same ± pattern as the original bodyweight UI for muscle-
 * memory consistency.
 */
@Composable
fun SettingsScreen(@Suppress("UNUSED_PARAMETER") navController: NavController) {
    val context = LocalContext.current
    val hevyApp = remember { context.applicationContext as HevyApp }
    val brightness = hevyApp.brightnessSettingsStore
    val userProfile = hevyApp.userProfileStore
    val displayLimits = hevyApp.displayLimitsStore
    val themeStore = hevyApp.themeSettingsStore

    var bodyweight by remember { mutableFloatStateOf(hevyApp.bodyweightKg) }
    var birthYear by remember { mutableIntStateOf(userProfile.birthYear) }
    var sex by remember { mutableStateOf(userProfile.sex) }
    var heartRateEnabled by remember { mutableStateOf(userProfile.heartRateEnabled) }
    val hrHardwarePresent = remember { HeartRateAvailability.hasHardware(context) }
    var folderListLimit by remember { mutableIntStateOf(displayLimits.folderListLimit) }
    var recentWorkoutsLimit by remember { mutableIntStateOf(displayLimits.recentWorkoutsLimit) }
    var defaultBrightness by remember { mutableFloatStateOf(brightness.defaultBrightness) }
    var maxBrightness by remember { mutableFloatStateOf(brightness.maxBrightness) }
    var idleSeconds by remember { mutableIntStateOf(brightness.idleSeconds) }
    var themeMode by remember { mutableStateOf(themeStore.themeMode) }
    val commitHash = hevyApp.commitInfoStore.commitHash

    // BODY_SENSORS prompt. Triggered when the user flips HR on from this
    // screen. If denied, we leave the toggle on (user intent) and the sampler
    // silently no-ops at workout start until the next time they tap Allow.
    val bodySensorsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* no-op — heartRateEnabled stays as the user set it */ }

    val listState = rememberScalingLazyListState()

    AppScaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Bodyweight ───────────────────────────────────────────────
            item { SectionHeader("Bodyweight", "Used for assisted dip / pull-up / chin-up volume") }
            item {
                ValueRow(
                    label = formatKg(bodyweight),
                    onMinus = {
                        val next = (bodyweight - BodyweightStore.STEP_KG)
                            .coerceIn(BodyweightStore.MIN_BODYWEIGHT_KG, BodyweightStore.MAX_BODYWEIGHT_KG)
                        bodyweight = next
                        hevyApp.bodyweightStore.bodyweightKg = next
                    },
                    onPlus = {
                        val next = (bodyweight + BodyweightStore.STEP_KG)
                            .coerceIn(BodyweightStore.MIN_BODYWEIGHT_KG, BodyweightStore.MAX_BODYWEIGHT_KG)
                        bodyweight = next
                        hevyApp.bodyweightStore.bodyweightKg = next
                    },
                )
            }

            // ── Profile (for HR-driven calorie estimation) ───────────────
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader("Profile", "For HR-based calorie estimation") }

            item { SubLabel("Birth year") }
            item {
                ValueRow(
                    label = birthYear.toString(),
                    onMinus = {
                        val next = (birthYear - 1)
                            .coerceIn(UserProfileStore.MIN_BIRTH_YEAR, UserProfileStore.currentMaxBirthYear())
                        birthYear = next
                        userProfile.birthYear = next
                    },
                    onPlus = {
                        val next = (birthYear + 1)
                            .coerceIn(UserProfileStore.MIN_BIRTH_YEAR, UserProfileStore.currentMaxBirthYear())
                        birthYear = next
                        userProfile.birthYear = next
                    },
                )
            }

            item { SubLabel("Sex") }
            item {
                SexRow(
                    selected = sex,
                    onSelect = { picked ->
                        sex = picked
                        userProfile.sex = picked
                    },
                )
            }

            // ── Heart rate (only when the watch actually has a PPG sensor) ──
            if (hrHardwarePresent) {
                item { Spacer(Modifier.height(8.dp)) }
                item { SectionHeader("Heart rate", "Sample BPM every minute during workouts") }
                item {
                    HeartRateToggleRow(
                        enabled = heartRateEnabled,
                        onToggle = { next ->
                            heartRateEnabled = next
                            userProfile.heartRateEnabled = next
                            if (next && !HeartRateAvailability.hasPermission(context)) {
                                bodySensorsLauncher.launch(android.Manifest.permission.BODY_SENSORS)
                            }
                        },
                    )
                }
            }

            // Bucket-G item 38 — larger break between the "essentials" cluster
            // (Bodyweight / Profile / Heart-rate; all directly affect what a
            // logged workout looks like) and the "display polish" cluster
            // (Display / Theme / Brightness; presentation only). 16 dp reads
            // as a clear section boundary on the round 400×400 display.
            // ── Display (list caps on the Folder List screen) ────────────
            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("Display", "How many to show on the Folder List") }

            item { SubLabel("Folders on page 1") }
            item {
                ValueRow(
                    label = folderListLimit.toString(),
                    onMinus = {
                        val next = (folderListLimit - 1)
                            .coerceIn(DisplayLimitsStore.MIN_FOLDER_LIMIT, DisplayLimitsStore.MAX_FOLDER_LIMIT)
                        folderListLimit = next
                        displayLimits.folderListLimit = next
                    },
                    onPlus = {
                        val next = (folderListLimit + 1)
                            .coerceIn(DisplayLimitsStore.MIN_FOLDER_LIMIT, DisplayLimitsStore.MAX_FOLDER_LIMIT)
                        folderListLimit = next
                        displayLimits.folderListLimit = next
                    },
                )
            }

            item { SubLabel("Recent workouts on page 2") }
            item {
                ValueRow(
                    label = recentWorkoutsLimit.toString(),
                    onMinus = {
                        val next = (recentWorkoutsLimit - 1)
                            .coerceIn(DisplayLimitsStore.MIN_RECENT_LIMIT, DisplayLimitsStore.MAX_RECENT_LIMIT)
                        recentWorkoutsLimit = next
                        displayLimits.recentWorkoutsLimit = next
                    },
                    onPlus = {
                        val next = (recentWorkoutsLimit + 1)
                            .coerceIn(DisplayLimitsStore.MIN_RECENT_LIMIT, DisplayLimitsStore.MAX_RECENT_LIMIT)
                        recentWorkoutsLimit = next
                        displayLimits.recentWorkoutsLimit = next
                    },
                )
            }

            // ── Theme ────────────────────────────────────────────────────
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader("Theme", "Dark or light colour scheme") }
            item {
                ThemeRow(
                    selected = themeMode,
                    onSelect = { picked ->
                        themeMode = picked
                        themeStore.updateThemeMode(picked)
                    },
                )
            }

            // ── Brightness ───────────────────────────────────────────────
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader("Brightness", "Default / max / idle seconds") }

            item { SubLabel("Default") }
            item {
                ValueRow(
                    label = formatBrightness(defaultBrightness),
                    onMinus = {
                        val next = (defaultBrightness - BrightnessSettingsStore.BRIGHTNESS_STEP)
                            .coerceIn(BrightnessSettingsStore.MIN_BRIGHTNESS, BrightnessSettingsStore.MAX_BRIGHTNESS)
                        defaultBrightness = next
                        brightness.updateDefaultBrightness(next)
                    },
                    onPlus = {
                        val next = (defaultBrightness + BrightnessSettingsStore.BRIGHTNESS_STEP)
                            .coerceIn(BrightnessSettingsStore.MIN_BRIGHTNESS, BrightnessSettingsStore.MAX_BRIGHTNESS)
                        defaultBrightness = next
                        brightness.updateDefaultBrightness(next)
                    },
                )
            }

            item { SubLabel("Max (on touch)") }
            item {
                ValueRow(
                    label = formatBrightness(maxBrightness),
                    onMinus = {
                        val next = (maxBrightness - BrightnessSettingsStore.BRIGHTNESS_STEP)
                            .coerceIn(BrightnessSettingsStore.MIN_BRIGHTNESS, BrightnessSettingsStore.MAX_BRIGHTNESS)
                        maxBrightness = next
                        brightness.updateMaxBrightness(next)
                    },
                    onPlus = {
                        val next = (maxBrightness + BrightnessSettingsStore.BRIGHTNESS_STEP)
                            .coerceIn(BrightnessSettingsStore.MIN_BRIGHTNESS, BrightnessSettingsStore.MAX_BRIGHTNESS)
                        maxBrightness = next
                        brightness.updateMaxBrightness(next)
                    },
                )
            }

            item { SubLabel("Idle seconds") }
            item {
                ValueRow(
                    label = "${idleSeconds}s",
                    onMinus = {
                        val next = (idleSeconds - 1)
                            .coerceIn(BrightnessSettingsStore.MIN_IDLE_SECONDS, BrightnessSettingsStore.MAX_IDLE_SECONDS)
                        idleSeconds = next
                        brightness.updateIdleSeconds(next)
                    },
                    onPlus = {
                        val next = (idleSeconds + 1)
                            .coerceIn(BrightnessSettingsStore.MIN_IDLE_SECONDS, BrightnessSettingsStore.MAX_IDLE_SECONDS)
                        idleSeconds = next
                        brightness.updateIdleSeconds(next)
                    },
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            // ── Build / commit ───────────────────────────────────────────
            // Stamped post-install via ADB broadcast (see CommitInfoReceiver).
            // Blank until first stamp.
            item {
                Text(
                    text = if (commitHash.isEmpty()) "commit —" else "commit $commitHash",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    // Wrapped in a Column so the two Texts stack vertically inside the
    // ScalingLazyColumn item slot — without this they end up side-by-side.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.title3,
            color = MaterialTheme.colors.onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
        )
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption1,
        color = MaterialTheme.colors.onSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

@Composable
private fun ValueRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onMinus,
            modifier = Modifier.size(40.dp),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) { Text("−") }

        Box(
            modifier = Modifier.size(width = 80.dp, height = 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.onSurface,
            )
        }

        Button(
            onClick = onPlus,
            modifier = Modifier.size(40.dp),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) { Text("+") }
    }
}

@Composable
private fun HeartRateToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    // Single segmented pair, same visual language as the Sex row. Off is the
    // explicit default; tapping On triggers the BODY_SENSORS prompt at the
    // call site if the runtime grant is still missing.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onToggle(false) },
            modifier = Modifier.size(width = 64.dp, height = 36.dp),
            colors = if (!enabled) ButtonDefaults.primaryButtonColors()
            else ButtonDefaults.secondaryButtonColors(),
        ) { Text("Off") }

        Button(
            onClick = { onToggle(true) },
            modifier = Modifier.size(width = 64.dp, height = 36.dp),
            colors = if (enabled) ButtonDefaults.primaryButtonColors()
            else ButtonDefaults.secondaryButtonColors(),
        ) { Text("On") }
    }
}

@Composable
private fun ThemeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    // Two segmented buttons, same visual language as the Sex / Heart-rate rows.
    // The selected scheme uses primary colors; the other fades to secondary.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onSelect(ThemeMode.DARK) },
            modifier = Modifier.size(width = 64.dp, height = 36.dp),
            colors = if (selected == ThemeMode.DARK) ButtonDefaults.primaryButtonColors()
            else ButtonDefaults.secondaryButtonColors(),
        ) { Text("Dark") }

        Button(
            onClick = { onSelect(ThemeMode.LIGHT) },
            modifier = Modifier.size(width = 64.dp, height = 36.dp),
            colors = if (selected == ThemeMode.LIGHT) ButtonDefaults.primaryButtonColors()
            else ButtonDefaults.secondaryButtonColors(),
        ) { Text("Light") }
    }
}

@Composable
private fun SexRow(selected: Sex, onSelect: (Sex) -> Unit) {
    // Two segmented buttons. Selected one uses primary colors, others fade
    // to secondary so the active pick is obvious at a glance on a 1.x" screen.
    // UNSPECIFIED is reachable only on first run / fresh install — there's no
    // explicit button for it; picking M or F leaves the unset state for good.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onSelect(Sex.MALE) },
            modifier = Modifier.size(width = 64.dp, height = 36.dp),
            colors = if (selected == Sex.MALE) ButtonDefaults.primaryButtonColors()
            else ButtonDefaults.secondaryButtonColors(),
        ) { Text("Male") }

        Button(
            onClick = { onSelect(Sex.FEMALE) },
            modifier = Modifier.size(width = 64.dp, height = 36.dp),
            colors = if (selected == Sex.FEMALE) ButtonDefaults.primaryButtonColors()
            else ButtonDefaults.secondaryButtonColors(),
        ) { Text("Female") }
    }
}

private fun formatKg(value: Float): String =
    com.example.hevywatch.util.FormatUtils.formatKg(value)

private fun formatBrightness(value: Float): String =
    "%.2f".format(value)
