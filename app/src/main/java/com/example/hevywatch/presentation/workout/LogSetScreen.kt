package com.example.hevywatch.presentation.workout

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.ui.graphics.Color
import com.example.hevycore.workout.WarmupConstants
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.ui.components.AppScaffold
import com.example.hevywatch.ui.components.OfflineBanner
import com.example.hevywatch.ui.components.CompanionSyncBanner
import com.example.hevywatch.ui.components.RefreshErrorBanner
import com.example.hevywatch.ui.components.RotatingLogo
import com.example.hevywatch.ui.components.observableClick
import com.example.hevywatch.ui.components.observableClickable
import com.example.hevywatch.ui.components.slimButton
import com.example.hevywatch.ui.theme.ChipPalette
import com.example.hevywatch.ui.theme.color
import com.example.hevywatch.ui.theme.hevyExtendedColors
import com.example.hevywatch.util.FormatUtils

private val SET_TYPE_CYCLE = listOf(
    SetType.NORMAL, SetType.WARMUP, SetType.DROPSET, SetType.FAILURE
)

// Upper bounds on user-entered values. 999 reps / 999.9 kg sit far above any
// human-plausible session value, but they cap the JSON payload size and
// prevent a stuck-button scenario from running the counter to Int.MAX_VALUE or
// Float.NaN. Validated in tests; mirrored in [WeightInputDialog] so the numpad
// path uses the same ceiling.
internal const val MAX_REPS = 999
internal const val MAX_WEIGHT_KG = 999.9f

internal fun clampReps(raw: Int): Int = raw.coerceIn(0, MAX_REPS)
internal fun clampWeightKg(raw: Float): Float = when {
    raw.isNaN() || raw.isInfinite() -> 0f
    else -> raw.coerceIn(0f, MAX_WEIGHT_KG)
}

/**
 * Seed value for the base-resistance prompt when the user has no last-used base
 * for the exercise: a barbell's bare Olympic bar is 20 kg; machines/plate-loaded
 * setups vary too much to guess, so start at 0 and let the user enter it.
 */
internal fun defaultBaseFor(equipment: String?): Float =
    if (equipment?.lowercase() == "barbell") 20f else 0f

/**
 * True when this screen should show weights PER SIDE OF THE BAR rather than as
 * the whole plate portion.
 *
 * A barbell's plate portion (total − bar) hangs evenly off the two ends, so the
 * number the lifter actually acts on at the rack is half of it. Requires a known
 * bar weight: while the base is unset — or was explicitly answered `0` ("log the
 * total directly") — the displayed number still contains the bar itself, and
 * halving that would send the user off to load the wrong plates. Only `barbell`
 * qualifies: a dumbbell/kettlebell is the whole load, and machines/plate-loaded
 * sleds are too varied to assume two symmetric sides.
 */
internal fun usesPerSideDisplay(equipment: String?, baseResistanceKg: Float?): Boolean =
    equipment?.lowercase() == "barbell" && (baseResistanceKg ?: 0f) > 0f

/** Plate weight on each end of the bar: `(true total − bar) / 2`, floored at 0. */
internal fun perSideKg(totalKg: Float, baseKg: Float): Float =
    (totalKg - baseKg).coerceAtLeast(0f) / 2f

/** Inverse of [perSideKg]: the true total to store for [perSide] kg a side. */
internal fun totalFromPerSideKg(perSide: Float, baseKg: Float): Float =
    perSide.coerceAtLeast(0f) * 2f + baseKg

/**
 * Decimals for a per-side figure: one, like every other weight on the screen,
 * but two when halving lands on a quarter. The warmup ladder rounds plates to
 * the 2.5 kg microplate grid, so a 22.5 kg plate portion is 11.25 a side —
 * rendering that as "11.3" would name a load no plate set can make, and the
 * numpad would then store 22.6 kg the moment the user opened and confirmed it.
 */
internal fun perSideDecimals(kg: Float): Int =
    if (kotlin.math.abs(kg * 10f - kotlin.math.round(kg * 10f)) < 0.001f) 1 else 2

/** Bare number (no unit) for the big picker, at [perSideDecimals] precision. */
internal fun formatPerSideValue(kg: Float): String = "%.${perSideDecimals(kg)}f".format(kg)

/**
 * Initial reps for the LogSetScreen picker.
 *
 *  - PO normal sets: floor at 15 (the PO algorithm's qualifying rep count). A
 *    routine prescribing rep_range 10–15 still starts the picker at 15 so the
 *    user doesn't have to crank the picker up before logging a qualifying set.
 *  - Non-PO normal sets: keep prescribed reps, or default to 15 if unset.
 *  - Warmup / dropset / failure: keep prescribed reps, fall back to rep range
 *    so warmup pickers don't jump to 15.
 *
 * Pure / unit-testable.
 */
internal fun initialRepsForSet(set: ActiveSet, isPoRoutine: Boolean): Int = when {
    isPoRoutine && set.setType == SetType.NORMAL ->
        (set.reps ?: 15).coerceAtLeast(15)
    set.setType == SetType.NORMAL ->
        set.reps ?: 15
    else ->
        set.reps ?: set.repRangeStart ?: set.repRangeEnd ?: 0
}

private val SetType.displayName: String
    get() = when (this) {
        SetType.NORMAL  -> "Normal"
        SetType.WARMUP  -> "Warmup"
        SetType.FAILURE -> "Failure"
        SetType.DROPSET -> "Dropset"
    }

// Colors live in [com.example.hevywatch.ui.theme.color] so the dot indicator,
// chip subtitle, and timer hint can share one source of truth. NORMAL is the
// one plain-foreground case (white on dark, black on light), so it resolves
// from the theme's extended colours; the rest are scheme-independent accents.
// [setNormal] is the theme's foreground colour (white on dark, black on light),
// resolved once in the composable body and threaded in — the call sites live in
// the (non-composable) ScalingLazyColumn builder scope, so this can't read the
// CompositionLocal itself.
private fun SetType.dotColor(setNormal: Color): Color = when (this) {
    SetType.NORMAL -> setNormal
    else -> color()
}

@Composable
fun LogSetScreen(navController: NavController) {
    val activity = LocalActivity.current as ComponentActivity
    val vm: LogWorkoutViewModel = viewModel(activity)

    // Stem button 2 is collected once at the NavHost level (see MainActivity)
    // so a press with LOG_SET stacked over LOG_WORKOUT doesn't navigate twice.

    LaunchedEffect(vm.navigateTo) {
        vm.navigateTo?.let { route ->
            if (route == Screen.LOG_WORKOUT) {
                navController.popBackStack(Screen.LOG_WORKOUT, inclusive = false)
            } else {
                navController.navigate(route)
            }
            vm.onNavigated()
        }
    }

    var showWeightInputDialog by remember { mutableStateOf(false) }

    if (!vm.isHistoryLoaded) {
        // U2 — small loader + caption so a user who lands on LogSetScreen
        // before history finishes (a rare but real race when tapping a chip
        // fast on cold start) sees acknowledgement instead of a blank screen.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RotatingLogo(size = 28.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Loading history…",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSecondary,
                )
            }
        }
        return
    }

    val exercise = vm.currentExercise
    val set = vm.currentSet

    if (exercise == null || set == null) {
        navController.popBackStack()
        return
    }

    // Theme foreground for the NORMAL set-type colour, read here in composable
    // scope and captured by the ScalingLazyColumn builder below.
    val setNormalColor = hevyExtendedColors.setNormal

    // Base-resistance lens: stored weights are true total (base + plates); the
    // log screen shows/accepts the plate portion (total − base). baseKg is 0 for
    // free weights / not-yet-decided exercises, so the offset is a no-op there.
    val baseKg = exercise.baseResistanceKg ?: 0f
    val baseEligible = exercise.exerciseType.usesWeight && exercise.hasEquipment &&
        WarmupConstants.equipmentHasBaseResistance(exercise.equipment)

    // Per-side lens: for a barbell with a known bar weight the big number and
    // the weight numpad both work in "plates on each end", so the user never
    // has to halve the plate total in their head while loading. Applies to
    // every set type — warmup, normal, dropset and failure sets all load the
    // bar the same way. Stored weight stays TRUE TOTAL; only display and entry
    // change lens.
    val perSide = usesPerSideDisplay(exercise.equipment, exercise.baseResistanceKg)

    // Hoisted so dialogs can read/write these values. Kept in TRUE-TOTAL space;
    // the pickers/dialogs apply the base offset for display only. set.weightKg is
    // in the key so a base change (which re-ramps warmup weights) re-seeds it.
    var weightKg by remember(vm.currentExerciseIndex, vm.currentSetIndex, set.weightKg) {
        mutableFloatStateOf(set.weightKg ?: 0f)
    }
    val isPoRoutine = vm.workout?.progressiveOverload == true
    var reps by remember(vm.currentExerciseIndex, vm.currentSetIndex) {
        mutableIntStateOf(initialRepsForSet(set, isPoRoutine))
    }

    // First-engage base prompt: fires once when landing on a base-capable
    // exercise whose base hasn't been decided yet. Re-openable via the base
    // readout below (which flips this back on).
    var showBaseDialog by remember(vm.currentExerciseIndex) {
        mutableStateOf(exercise.baseResistanceKg == null && baseEligible)
    }

    if (vm.showExerciseDonePrompt) {
        ExerciseDoneDialog(
            exerciseName = exercise.title,
            onConfirm = { vm.confirmExerciseDone() },
            onCancel  = { vm.dismissExerciseDone() }
        )
        return
    }

    if (showBaseDialog) {
        BaseResistanceDialog(
            exerciseName = exercise.title,
            // Seed: prior decision → last-used for this template → 20 kg for a
            // barbell (bare Olympic bar) → 0 otherwise.
            current = exercise.baseResistanceKg
                ?: vm.lastBaseResistance(exercise.exerciseTemplateId)
                ?: defaultBaseFor(exercise.equipment),
            onConfirm = { value ->
                vm.setBaseResistance(vm.currentExerciseIndex, value)
                showBaseDialog = false
            },
            // Skip → record 0 (log total directly) so it doesn't nag again; the
            // base readout stays available to set it later.
            onCancel = {
                vm.setBaseResistance(vm.currentExerciseIndex, 0f)
                showBaseDialog = false
            }
        )
        return
    }

    if (showWeightInputDialog) {
        val entrySeed = if (perSide) perSideKg(weightKg, baseKg)
                        else (weightKg - baseKg).coerceAtLeast(0f)
        WeightInputDialog(
            current = entrySeed,
            currentDecimals = if (perSide) perSideDecimals(entrySeed) else 1,
            unitLabel = if (perSide) "kg/side" else "kg",
            onConfirm = { value ->
                weightKg = clampWeightKg(
                    if (perSide) totalFromPerSideKg(value, baseKg) else value + baseKg
                )
                showWeightInputDialog = false
            },
            onCancel  = { showWeightInputDialog = false }
        )
        return
    }

    val totalSets = exercise.sets.size
    val setCoord = FormatUtils.setCoordinates(vm.currentSetIndex, totalSets)

    val listState = rememberScalingLazyListState()

    // Whenever the displayed set changes (Prev/Next, returning from the rest
    // timer after Skip / auto-dismiss, or completing a set that advances the
    // cursor without a rest screen), reset the scroll back to the top so the
    // user lands on the exercise title instead of wherever they last scrolled.
    LaunchedEffect(vm.currentExerciseIndex, vm.currentSetIndex) {
        listState.animateScrollToItem(0)
    }

    AppScaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        onTimerClick = { navController.navigate(Screen.WORKOUT_CONTROL) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            autoCentering = null,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 28.dp, bottom = 16.dp, start = 8.dp, end = 8.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Exercise name — white + bold
            item {
                Text(
                    text = exercise.title,
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Offline strip — only visible while the watch has no Internet-capable network
            item { OfflineBanner() }
            // Token-refresh-error strip — only visible while a refresh has failed
            item { RefreshErrorBanner() }
            // Watch-→-companion sync strip — only visible when a watch-initiated
            // refresh succeeded but the push to the companion failed. Quieter
            // than RefreshErrorBanner because the watch itself is fully functional.
            item { CompanionSyncBanner() }

            // Set coordinate + tappable set-type label
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Set $setCoord",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSecondary
                    )
                    Text(
                        text = " \u00B7 ",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSecondary
                    )
                    Text(
                        text = set.setType.displayName,
                        style = MaterialTheme.typography.caption2,
                        color = set.setType.dotColor(setNormalColor),
                        modifier = Modifier
                            .then(if (!set.locked) Modifier.observableClickable {
                                val idx = (SET_TYPE_CYCLE.indexOf(set.setType) + 1) % SET_TYPE_CYCLE.size
                                vm.changeCurrentSetType(SET_TYPE_CYCLE[idx])
                            } else Modifier)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            if (set.locked) {
                // ── Locked set (from existing workout) — read-only display ──
                item {
                    Text(
                        text = "Logged previously",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
                if (exercise.exerciseType.usesWeight && exercise.hasEquipment) {
                    item {
                        val lockedTotal = set.weightKg ?: 0f
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (perSide) {
                                    val side = perSideKg(lockedTotal, baseKg)
                                    FormatUtils.formatKg(side, decimals = perSideDecimals(side))
                                } else {
                                    FormatUtils.formatKg((lockedTotal - baseKg).coerceAtLeast(0f))
                                },
                                style = MaterialTheme.typography.title3,
                                color = MaterialTheme.colors.onSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (perSide) {
                                Text(
                                    text = "per side",
                                    style = MaterialTheme.typography.caption2,
                                    color = MaterialTheme.colors.onSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                if (exercise.exerciseType.usesReps) {
                    item {
                        Text(
                            text = "${set.reps ?: 0} reps",
                            style = MaterialTheme.typography.title3,
                            color = MaterialTheme.colors.onSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                // ── Editable set — normal pickers + complete button ──
                // Value color: green if PO applied an increase, amber for a
                // similar-exercise suggested target weight (matches the chip
                // on LogWorkoutScreen and the rest-timer countdown — so the
                // user knows the displayed number is a guess they should
                // verify rather than a previously-logged value), otherwise
                // the set-type color.
                val valueColor = when {
                    set.poBaseWeightKg != null -> ChipPalette.ConfirmGreen
                    set.isSimilarSuggestion    -> ChipPalette.WarmupAmber
                    else                       -> set.setType.dotColor(setNormalColor)
                }

                // Weight picker — hidden for bodyweight/no-equipment exercises.
                // Shows the PLATE portion (total − base); ± and the numpad edit
                // plates, decrement floored at the base (can't go below an empty
                // bar/machine). The stored value stays true-total.
                if (exercise.exerciseType.usesWeight && exercise.hasEquipment) {
                    item {
                        ValuePicker(
                            // Per-side lens keeps the ± stepping the REAL load by
                            // 1 kg (0.5 kg a side), so the logged weight stays on
                            // the same grid it has always used.
                            label = if (perSide) "per side" else "kg",
                            value = if (perSide) formatPerSideValue(perSideKg(weightKg, baseKg))
                                    else "%.1f".format((weightKg - baseKg).coerceAtLeast(0f)),
                            valueColor = valueColor,
                            onIncrement = { weightKg = clampWeightKg(weightKg + 1f) },
                            onDecrement = { weightKg = clampWeightKg((weightKg - 1f).coerceAtLeast(baseKg)) },
                            onTap = { showWeightInputDialog = true }
                        )
                    }

                    // Base readout — visible for base-capable equipment. Names the
                    // lens (plates shown, true total alongside) and re-opens the
                    // base prompt so a wrong entry can be corrected. Distinguishes
                    // an explicit 0 (a valid "no base" decision) from a base not
                    // yet set, so a 0 reads as accepted rather than still-pending.
                    if (baseEligible) {
                        item {
                            Text(
                                // One label register across all three states:
                                // "Base: <x> · tap to edit" (+ total once a
                                // non-zero base makes the distinction useful).
                                // Previously each state used its own phrasing,
                                // which read as three unrelated rows.
                                text = when (val b = exercise.baseResistanceKg) {
                                    null -> "Base: not set · tap to edit"
                                    0f -> "Base: 0 kg · tap to edit"
                                    // Per-side lens: the big number is one end of
                                    // the bar, so spell the plate total out here —
                                    // otherwise neither the plates nor the loaded
                                    // total appears anywhere on the screen.
                                    else -> if (perSide) {
                                        "Base: ${FormatUtils.formatKg(b)} · tap to edit · " +
                                            "${FormatUtils.formatKg((weightKg - baseKg).coerceAtLeast(0f))} plates · " +
                                            "total ${FormatUtils.formatKg(weightKg)}"
                                    } else {
                                        "Base: ${FormatUtils.formatKg(b)} · tap to edit · total ${FormatUtils.formatKg(weightKg)}"
                                    }
                                },
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .observableClickable { showBaseDialog = true }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }

                    // PO reference: show previous weight when progressive overload
                    // increased it — offset by the base so it's plate-consistent
                    // with the picker above.
                    set.poBaseWeightKg?.let { poBase ->
                        item {
                            Text(
                                text = if (perSide) {
                                    val side = perSideKg(poBase, baseKg)
                                    "was ${FormatUtils.formatKg(side, decimals = perSideDecimals(side))} per side"
                                } else {
                                    "was ${FormatUtils.formatKg((poBase - baseKg).coerceAtLeast(0f))}"
                                },
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Reps picker
                if (exercise.exerciseType.usesReps) {
                    item {
                        ValuePicker(
                            label = "reps",
                            value = reps.toString(),
                            valueColor = valueColor,
                            onIncrement = { reps = clampReps(reps + 1) },
                            onDecrement = { reps = clampReps(reps - 1) }
                        )
                    }
                }

                // Complete button
                item {
                    Button(
                        onClick = observableClick {
                            if (exercise.exerciseType.usesWeight) vm.updateCurrentSetWeight(weightKg)
                            if (exercise.exerciseType.usesReps) vm.updateCurrentSetReps(reps)
                            vm.completeCurrentSet()
                        },
                        modifier = Modifier.fillMaxWidth(0.85f).slimButton,
                        colors = ButtonDefaults.primaryButtonColors()
                    ) {
                        Text(if (set.completed) "Update Set" else "Complete Set")
                    }
                }

                // Exercise notes from routine — shown directly below Complete Set
                val exerciseNotes = exercise.notes
                if (!exerciseNotes.isNullOrBlank()) {
                    item {
                        Text(
                            text = exerciseNotes,
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                        )
                    }
                }
            }

            // Prev / Next navigation. Set count is managed implicitly by the
            // "Done with this exercise?" prompt's "+1 Set" option; the workout
            // is finished from LogWorkoutScreen.
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactButton(
                        onClick = observableClick { vm.goToPreviousSet() },
                        enabled = !vm.isFirstSet,
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(40.dp)
                    ) { Text("‹") }

                    CompactButton(
                        onClick = observableClick { vm.goToNextSet() },
                        enabled = !vm.isLastSet,
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(40.dp)
                    ) { Text("›") }
                }
            }
        }
    }
}

@Composable
private fun ValuePicker(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colors.onSurface,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onTap: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Bucket-G item 35 — ± tap targets grown 12dp→16dp padding, glyph
        // bumped 22sp→24sp. Same finger-lift complaint as the numpad: the
        // ± symbols were visually close enough to the value column that a
        // rapid + tap after a value edit could catch the wrong glyph.
        Text(
            text = "−",
            fontSize = 24.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            modifier = Modifier
                .observableClickable(onDecrement)
                .padding(16.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(88.dp)
                .then(
                    Modifier
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .padding(vertical = 4.dp)
                )
                .then(if (onTap != null) Modifier.observableClickable(onTap) else Modifier)
        ) {
            Text(text = value, fontSize = 32.sp, color = valueColor, textAlign = TextAlign.Center)
            Text(text = label, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSecondary)
        }

        Text(
            text = "+",
            fontSize = 24.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            modifier = Modifier
                .observableClickable(onIncrement)
                .padding(16.dp)
        )
    }
}

@Composable
private fun WeightInputDialog(
    current: Float,
    onConfirm: (Float) -> Unit,
    onCancel: () -> Unit,
    // Names the lens the typed number is in — "kg/side" when the caller is
    // working per side of the bar, so a "20" reads as 20 a side, not 20 total.
    unitLabel: String = "kg",
    // Precision the seed is written at. Per-side values can carry a quarter
    // (11.25 a side); seeding those at one decimal would quietly round the
    // weight the moment the user confirmed without editing.
    currentDecimals: Int = 1
) {
    var text by remember {
        mutableStateOf(if (current > 0f) "%.${currentDecimals}f".format(current) else "")
    }

    // Confirm is disabled until the text parses to a real number AND fits
    // inside the upper bound. This is the only path that lets users type a
    // multi-digit weight on the watch, so a silently-ignored "✓" tap (the old
    // behavior — `text.toFloatOrNull()?.let { … }` did nothing on a bad parse)
    // left them stuck with no feedback. The disabled chip is the feedback.
    val parsedWeight: Float? = remember(text) {
        val v = text.toFloatOrNull() ?: return@remember null
        if (v.isNaN() || v.isInfinite() || v < 0f || v > MAX_WEIGHT_KG) null else v
    }
    val canConfirm = parsedWeight != null

    fun key(k: String) {
        text = when (k) {
            "⌫" -> if (text.isNotEmpty()) text.dropLast(1) else text
            "."  -> if ("." in text) text else "$text."
            else -> text + k
        }
    }

    val keyRows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫")
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned weight display — always visible at top
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.background)
                .padding(top = 20.dp, bottom = 6.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = text.ifEmpty { "0" },
                        fontSize = 28.sp,
                        color = MaterialTheme.colors.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unitLabel,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }

        // Scrollable numpad
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Numpad rows — dark buttons, compact
            keyRows.forEach { row ->
                item {
                    // Bucket-G item 35 — numpad key + gap sizing bumped
                    // (44dp @ 4dp gap → 48dp @ 6dp gap). Real miss-tap risk
                    // on the ~400px round display was one of the top
                    // usability complaints; the 4dp inter-key gap made
                    // "5" and "6" essentially touch on-screen.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            CompactButton(
                                onClick = observableClick { key(k) },
                                modifier = Modifier.size(48.dp),
                                colors = ButtonDefaults.secondaryButtonColors()
                            ) {
                                Text(
                                    text = k,
                                    fontSize = if (k == "⌫") 14.sp else 16.sp,
                                    color = if (k == "⌫") ChipPalette.SetFailure else MaterialTheme.colors.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Confirm / Cancel
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    CompactButton(
                        onClick = observableClick(onCancel),
                        modifier = Modifier.size(44.dp),
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) { Text("✕", color = ChipPalette.SetFailure) }
                    CompactButton(
                        onClick = observableClick {
                            parsedWeight?.let { onConfirm(clampWeightKg(it)) }
                        },
                        enabled = canConfirm,
                        modifier = Modifier.size(44.dp),
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text(
                            "✓",
                            color = if (canConfirm) ChipPalette.ConfirmGreen
                                    else ChipPalette.ConfirmGreen.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Base-resistance numpad. Same layout as [WeightInputDialog] but headed with the
 * exercise name + a "base resistance" caption, and an empty entry is a valid
 * confirm (0 = "no base / log total"). The confirmed value is the fixed weight of
 * the empty bar / Smith carriage / machine sled; the log screen then shows the
 * plate portion (total − base) and re-ramps warmups onto it.
 */
@Composable
private fun BaseResistanceDialog(
    exerciseName: String,
    current: Float,
    onConfirm: (Float) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf(if (current > 0f) "%.1f".format(current) else "") }

    // Empty parses to 0 (skip = no base). Any other text must be a valid,
    // in-range number to confirm.
    val parsed: Float? = remember(text) {
        if (text.isEmpty()) return@remember 0f
        val v = text.toFloatOrNull() ?: return@remember null
        if (v.isNaN() || v.isInfinite() || v < 0f || v > MAX_WEIGHT_KG) null else v
    }
    val canConfirm = parsed != null

    fun key(k: String) {
        text = when (k) {
            "⌫" -> if (text.isNotEmpty()) text.dropLast(1) else text
            "."  -> if ("." in text) text else "$text."
            else -> text + k
        }
    }

    val keyRows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫")
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned header + entered value — always visible above the scrollable
        // numpad (mirrors WeightInputDialog) so the number never scrolls out of
        // sight. Kept compact so the numpad stays reachable on the 320px screen.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.background)
                .padding(top = 18.dp, bottom = 4.dp, start = 12.dp, end = 12.dp)
        ) {
            Text(
                text = exerciseName,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Base resistance",
                style = MaterialTheme.typography.caption1,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(4.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = text.ifEmpty { "0" },
                        fontSize = 28.sp,
                        color = MaterialTheme.colors.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "kg",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }

        // Scrollable numpad + confirm/cancel.
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            keyRows.forEach { row ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            CompactButton(
                                onClick = observableClick { key(k) },
                                modifier = Modifier.size(48.dp),
                                colors = ButtonDefaults.secondaryButtonColors()
                            ) {
                                Text(
                                    text = k,
                                    fontSize = if (k == "⌫") 14.sp else 16.sp,
                                    color = if (k == "⌫") ChipPalette.SetFailure else MaterialTheme.colors.onSurface
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    CompactButton(
                        onClick = observableClick(onCancel),
                        modifier = Modifier.size(44.dp),
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) { Text("✕", color = ChipPalette.SetFailure) }
                    CompactButton(
                        onClick = observableClick { parsed?.let { onConfirm(clampWeightKg(it)) } },
                        enabled = canConfirm,
                        modifier = Modifier.size(44.dp),
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text(
                            "✓",
                            color = if (canConfirm) ChipPalette.ConfirmGreen
                                    else ChipPalette.ConfirmGreen.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseDoneDialog(exerciseName: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = exerciseName,
                style = MaterialTheme.typography.caption1,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Done with this exercise?",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = observableClick(onCancel),
                    modifier = Modifier.slimButton,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) { Text("+1 Set") }
                Button(
                    onClick = observableClick(onConfirm),
                    modifier = Modifier.slimButton
                ) { Text("Done") }
            }
        }
    }
}

