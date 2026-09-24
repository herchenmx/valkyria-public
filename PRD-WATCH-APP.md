# valkyria — Watch App PRD

**Platform**: Wear OS 2 / API 28 (Kate Spade Scallop 2)
**User-facing app name**: **valkyria** (lowercase, `strings.xml::app_name`)
**Package / `applicationId`**: `com.example.hevywatch` — kept as-is post-rebrand because the companion's Wearable MessageAPI pairing is keyed off this exact id (see PRD-COMPANION-APP.md *Application identity*)
**Distribution**: Sideloaded only (not on Play Store due to Google Play's minimum API level policy)

**Shared code**: The watch and companion apps consume a common pure-JVM `:core` module (`core/src/main/kotlin/com/example/hevycore/`) that holds the wire-critical constants both sides used to duplicate: Wearable MessageAPI path strings (`WearMessagePaths`), the assisted-bodyweight template IDs + effective-work conversions (`AssistedBodyweight`), the warmup muscle-group tables + protocol table + rounding rules (`WarmupConstants`), and the progressive-overload look-back window / rep floor / increment (`PoConstants`). Any change to one of these constants now lands in one place, and the compiler enforces sync — before extraction, a rename on one side silently broke the wire protocol or produced different PO targets on the same input. `:core:auth` (SecurePrefs / AuthStore / AuthPrefs unification) is deferred.

> **Naming**: This app was previously called "HevyWatch", then "Barbell". The current user-facing rebrand to **valkyria** (lowercase, valkyria-silhouette-on-orange-disc icon, `BrandOrange #FE6A16` retained) ships the new name in `strings.xml`, the launcher icon, and the loading-screen title. In-source class/symbol names are still `Hevy*` (e.g. `HevyApp`, `HevyApiClient`, `HevyTileService`, `HevyWatchTheme`, `HevyMidGrey`, …) because every rebrand has been deliberately surface-only — re-naming every call site would have churned the entire codebase. Under the hood the app still talks to the **Hevy** REST API (the third-party fitness service): "the Hevy API", "Hevy's CDN", "Hevy history" all refer to that backend, while "valkyria" means the Wear OS app the user installs on their watch.

---

## What This App Does

valkyria is a custom Wear OS workout logging app that replicates and extends the functionality of the official Hevy fitness app. It connects to the Hevy API to fetch routines and exercise data, lets the user log workouts set-by-set on the watch, and posts completed workouts back to Hevy's servers. The app adds features the official Wear OS app doesn't have, including automatic progressive overload weight management and intelligent warmup set prescription.

---

## Key Features

### 1. Progressive Overload (PO)

**What it does**: Automatically increases the target weight for exercises in PO-enabled routines, based on recent performance.

**When PO applies**:
- The exercise must use weighted equipment (not bodyweight)
- The routine must be in a PO-enabled folder
- The lookback window is the **last 3 workouts** for that exercise (`PO_LOOKBACK_WORKOUTS` in `PoConstants.kt`). Kept short so a fast weight ramp isn't dragged down by older, much-lighter ramp-up sessions.
- **Any 1 qualifying workout** within that window triggers PO. There is no per-muscle threshold — biceps, quadriceps, shoulders, abdominals etc. all follow the same rule. The previous small-muscle 2-of-5 gate was retired when PO moved to a recency-weighted average: older sessions can't dominate the target, so the gate isn't needed to keep small muscles from over-progressing.
- "Successful" criterion (per individual workout, applied identically regardless of muscle group):
  - Workouts with 3+ normal sets: at least 3 of those sets hit 15+ reps. (Breakthrough sets at a heavier-than-target weight with fewer reps are allowed without disqualifying the workout.)
  - Workouts with 1–2 normal sets: every normal set must hit 15+ reps (legacy criterion preserved so single-set users still trigger PO).

**How the target weight is computed (recency-weighted average)**:
1. Across the last 3 workouts, identify **every** qualifying workout (per the success criterion above).
2. Reduce each qualifying workout to **one data point: its mean set weight.** Set count within a workout no longer matters — a high-volume day can't outvote the lifter's recency.
3. Combine those per-workout means with a **recency-weighted average**: the newest qualifying workout gets weight 1, each older one 1/`RECENCY_WEIGHT_DECAY` (= 3.0) of its successor. Over the 3-workout window that's weights **9 : 3 : 1** (newest : middle : oldest), so the most recent session leads while older ones still damp a one-off spike or drop. (`PoConstants.recencyWeightedMean`, shared by watch + companion.)
4. If every qualifying set across every qualifying workout was logged at the **same** weight, take that exact value as the base — no flooring. This preserves half-increment lifts (22.5kg machine pin, 12.5kg dumbbell) so PO doesn't snap them down to the nearest whole kg. The shortcut spans the *entire* pool.
5. Otherwise, **floor** the recency-weighted average to the 1 kg grid.
6. **Add** the 1 kg universal increment = the new target weight.

**Trade-off vs. the old flat rolling average**: recency weighting makes the target track the lifter's most recent work instead of lagging behind ramp-up weeks — a steady climb is no longer sunk by the lighter sessions still inside the window. The cost: because the newest session leads, a single bad *most-recent* week weighs heavily and can drag the target below the previous best (the old "heaviest qualifying" drop-protection remains intentionally dropped). The lifter is rewarded for *recent sustained* output rather than peaks.

**Counter-weight assisted exercises** (chest/tricep dip and pull-up/chin-up on the assisted machine — exercise template IDs `2C37EC5E`, `4B4BF8C2`, `D23C609B`, `E9E4089F`): the logged kg is the stack assistance, so progress means going **down** in logged kg. PO is computed in effective-work space (`bodyweight − logged_kg`) and the target is then converted back, which means PO **subtracts** one increment from the previous logged target instead of adding it. Floored at 0kg logged (= full bodyweight). Bodyweight is read from the Settings screen on the watch (default 57kg).

**Equipment increments** (`PoConstants.INCREMENT_KG` in `:core`):

| Equipment | Increment |
|---|---|
| All (barbell / dumbbell / kettlebell / machine / plate / other / null) | **1.0kg** |

PO uses a uniform 1.0kg increment across every equipment type — read directly from `PoConstants.INCREMENT_KG` in `:core`. The previous 2.5kg barbell special case was dropped after the user equipped 0.5kg / 1.25kg fractional plates across the bar set — a single small step gives PO a gentler cadence on heavy compounds without changing anything for the rest. The old wrapper `PoIncrement.forEquipment(equipment)` was removed since every case collapsed to the same value; the warmup advisor still has its own, separate per-equipment rounding table in [WarmupAdvisor.kt](app/src/main/java/com/example/hevywatch/presentation/workout/WarmupAdvisor.kt).

**Example (single workout, variable weights)**: One qualifying workout for Leg Press (machine) at sets of 100kg, 105kg, 107kg × 15 reps. Single workout → mean = 104kg, not all-same → floor to 1kg = 104. Target = 104 + 1 = **105kg**. The "was 104 kg" reference is shown on the LogSetScreen.

**Example (multiple qualifying workouts, uniform weight)**: Two qualifying machine sessions, each at 22.5kg × 15 × 3 sets. Every set is 22.5 → all-same shortcut applies (across the full pool), base = 22.5kg, target = **23.5kg**.

**Example (rising ramp — the torso-rotation case)**: Three qualifying sessions in the window (newest→oldest): means 45kg, 42.5kg, 35kg. Recency-weighted (9:3:1): (45·9 + 42.5·3 + 35·1) / 13 ≈ 43.65, floored to 1kg = 43. Target = **44kg**. A flat pool across all sets would have averaged ≈33 → target 34kg, silently below the last two sessions; recency weighting keeps the target tracking the recent climb.

**Example (drop-and-recover regression)**: Older qualifying workout at 22.5kg × 15 × 3 sets; most recent qualifying workout at 21kg × 15 × 4 sets (user dropped one stack pin because 25kg felt too heavy that day). Per-workout means [21, 22.5], recency-weighted (1:⅓) = (21·1 + 22.5·⅓) / (1 + ⅓) ≈ 21.38, floored to 1kg = 21. Target = **22kg**. Under the previous "heaviest qualifying" rule this would have targeted 23.5kg; recency weighting weights the recent drop heavily, sacrificing that drop-protection.

**When no workout in the lookback qualifies**: PO is not applied. Per-set weights from the most recent workout are used as-is so the picker shows the last actual weights (else branch in `ProgressiveOverload.kt`).

**Continue-incomplete-workout filter**: When resuming an incomplete workout (`ActiveWorkout.continuingWorkoutId != null`), the in-progress session is already on the server, so `/exercise_history` returns it as the most recent entry for every exercise it touched. For exercises where no normal set was logged yet in that incomplete session, the returned `historyNormal` would be empty and `computeProgressiveOverload` would short-circuit — leaving the remaining sets with no suggested weight. To avoid this, `WorkoutHistoryApplier.load()` strips entries whose `workoutId` matches `continuingWorkoutId` from the history map before running both `applyHistoryHints` and `applyProgressiveOverload`. PO then resolves against the prior *completed* session, as if the resumed workout didn't exist server-side yet. Pinned by `WorkoutHistoryApplierTest`'s `PO fires from older-completed history when in-progress workout is filtered out` (positive case) and `PO does NOT fire when raw map (in-progress workout) is used directly` (negative twin reproducing the bug without the filter).

### 2. Warmup Advisor

**What it does**: Automatically prescribes warmup sets before the working (normal) sets of each exercise, based on the muscle group, equipment, and working weight.

**When warmup is skipped**:
- Equipment is bodyweight (`none`), `resistance_band`, `suspension`, or `other`
- Primary muscle group is `abdominals`, `forearms`, `neck`, `cardio`, or `other`

**How many warmup sets** (determined by muscle group category + working weight):

| Primary muscle group | W < 30kg | W 30-50kg | W 50-80kg | W > 80kg |
|---|---|---|---|---|
| **Large** (quadriceps, hamstrings, glutes, lats, upper_back, lower_back) | 1 | 2 | 3 | 4 |
| **Medium** (chest, traps, full_body, abductors, adductors) | 0 | 1 | 2 | 3 |
| **Small** (biceps, triceps, calves, shoulders, abdominals)¹ | 0 | 1 | 2 | 3 |

¹ MEDIUM and SMALL share the same warmup-count table, so the partitioning between them only matters for documentation, not for the warmup output. `abdominals` is independently suppressed by `NO_WARMUP_GROUPS`, so abs never receive a warmup regardless of which bucket they sit in. The PO algorithm no longer uses this classification — every muscle group runs through the same recency-weighted-average path.

**Warmup protocols** (percentage of working weight x reps per set):

| Sets | Set 1 | Set 2 | Set 3 | Set 4 |
|---|---|---|---|---|
| 1 | 60% x 8 | | | |
| 2 | 50% x 10 | 70% x 5 | | |
| 3 | 40% x 10 | 60% x 6 | 80% x 3 | |
| 4 | 30% x 12 | 50% x 8 | 70% x 4 | 85% x 2 |

**Weight rounding** (always floor to nearest increment):
| Equipment | Rounds to |
|---|---|
| Dumbbell | 2.0kg |
| Kettlebell | 4.0kg |
| Machine | 1.0kg |
| Everything else (barbell / plate / other) | 2.5kg |

The implementation only special-cases `dumbbell`, `kettlebell`, and `machine`; barbell, plate, and any unknown equipment fall through to the 2.5kg default branch.

**Barbell minimum**: Barbell warmup sets are floored at **20kg** (the weight of the Olympic bar — a warmup with less weight than the bar itself is physically impossible). Any protocol percentage that rounds below 20kg is coerced up to 20kg. Other equipment uses its increment as the minimum. (When an explicit **base resistance** is set for the exercise — see §2b — the ladder ramps on the plate portion instead, and this floor becomes the base itself.)

**Unilateral exercises** (>= 6 normal sets AND the count is even — e.g. 6 sets for single leg press = 3 per leg): every warmup set is doubled — two at each weight level so each side gets a warmup. Same definition the rest-timer logic uses (see *Rest Timer Overrides*).

**Counter-weight assisted exercises** (chest/tricep dip and pull-up/chin-up on the assisted machine — template IDs `2C37EC5E`, `4B4BF8C2`, `D23C609B`, `E9E4089F`): the logged kg represents stack assistance, so a "warmup" needs **more** assistance than the working set, not less. The advisor mirrors the standard logic in effective-work space (`bodyweight − logged_kg`): each protocol percentage is applied to the effective working weight, then converted back to logged kg via `bodyweight − warmup_effective`. Result: warmup logged kg comes out **higher** than the target's logged kg, capped one increment below full bodyweight (so the warmup is always strictly easier than dropping into the assistance). Bodyweight defaults to 57kg, editable in Settings.

**Existing warmup handling**:
- No warmup sets in routine → advisor auto-injects warmups
- Routine already has warmup sets → prompts "Override prescribed warmups with advisor?" (N / Y)
- Per-exercise: a fresh exercise (no completed sets) gets warmups injected. An exercise that already carries completed sets (crash recovery / **resumed** workout) is not re-prescribed, but on a resume the advisor **appends the still-missing advised warmups** (`suggested.drop(existingWarmups.size)`) as fresh editable sets so you can log the warmups you skipped — completed data stays untouched and any warmup left unlogged is dropped at Finish. New sets go at the end to preserve the resume merge's "new sets follow the originals" invariant.
- Per-workout idempotency: the advisor runs once per workout. An `warmupAdvisorApplied` flag on the active workout is set after the advisor's decision (auto-inject, Override, or Keep) and persisted with the workout, so re-entering the log screen (app close/reopen, crash recovery) does not re-trigger the prompt on previously injected warmups.

### 2b. Base Resistance (bar / Smith / machine lens)

**Problem**: Machine and bar exercises have a fixed **base resistance** the lifter can't remove — the empty Olympic bar (20kg), a Smith carriage (~10kg), a leg-press sled (~50kg), a squat-machine sled (25–30kg, varying by make). The true weight moved is `base + plates`, and Hevy records should reflect that, but the number the lifter physically sets is only the **plate** portion. Doing `total − base` in your head for every warmup and working set (PO'd or not) is the friction this removes.

**Core invariant**: everything stored, PO'd, submitted to Hevy, and shown on Workout Detail stays in **true-total** space (`base + plates`), exactly as before. Base is a **display + warmup-ladder lens** applied only on the active-logging surfaces. Because storage is always total, there is **no "add the base back" step** — the log screen simply renders `total − base` and, when you type, stores `typed + base`.

**Model**: `ActiveExercise.baseResistanceKg: Float?` — `null` = not yet decided (prompt), a number (incl. `0` = "no base / log total") = decided. Rides in the persisted `ActiveWorkout`, so it survives crash-recovery and resume (Gson default `null` keeps older snapshots deserialising cleanly, like `wasSwapped`).

**Prompt** (`BaseResistanceDialog`, a numpad matching the weight dialog — the entered value is **pinned** above the scrolling keypad so it never scrolls out of view): fires as a blocking modal on **first engage** — i.e. when `LogSetScreen` mounts for a base-capable exercise whose base is still `null`. Only exercises you actually open ask, one at a time. Base-capable = `WarmupConstants.equipmentHasBaseResistance` (`barbell` / `machine` — Smith reports `machine` — / `plate`; `dumbbell` / `kettlebell` / bodyweight are excluded, no base to add). The entry is seeded from prior decision → **last-used for this template** (`BaseResistanceStore`, a per-template prefs map; re-confirmed every session because the same lift sits on a different machine at a different gym) → bare-bar `20kg` for a barbell → `0`. **`0` is a first-class value** — an explicit "no base / log total" decision, enterable directly or via an empty confirm; skipping (`✕`) records it too, so it won't nag again. A readout under the weight picker re-opens the prompt so a wrong entry can be corrected. All three states share one label register (they previously used three unrelated phrasings): `Base: not set · tap to edit` when still unset, `Base: 0 kg · tap to edit` for an explicit 0, and `Base: N · tap to edit · total M` once a non-zero base makes the total worth showing — on a barbell, where the picker counts per side (below), that row also names the plate total, `Base: N · tap to edit · P plates · total M`, so the plates and the loaded total both stay on screen.

**Display lens** (`total − base`, floored at 0): applied on the `LogSetScreen` weight picker, the weight numpad, the locked-set (resumed) readout, the PO "was" reference, and the `RestTimerScreen` next-set preview (you load the next set's plates during rest). `±` and the numpad edit **plates**; decrement is floored at the base (can't go below an empty bar/machine). Summary/record surfaces — the overview exercise chip, expanded last-session history, and Workout Detail — deliberately stay **true-total** (they sit next to historical totals, and total is the permanent record).

**Per-side lens (barbell)**: on a `barbell` exercise whose base is a **known, non-zero bar weight**, the lens goes one step further and shows the plates on **one end** of the bar — `(total − bar) / 2` — because that is the number the lifter actually acts on at the rack. The big picker value is halved and its unit label becomes `per side`; the numpad seeds and accepts per-side too (`kg/side` header, storing `typed × 2 + bar`); the locked-set readout, the PO `was` reference, and the `RestTimerScreen` next-set preview follow, each carrying a `per side` caption. Applies to **every set type** — warmup, normal, dropset and failure sets all load the bar the same way. `±` keeps stepping the **true** load by 1kg (0.5kg a side), so the logged weight stays on exactly the grid it has always used.

**Per-side gate**: `barbell` only — a dumbbell/kettlebell *is* the whole load, and machines / plate-loaded sleds are too varied to assume two symmetric ends — **and** base > 0. While the base is still `null` or an explicit `0`, the displayed number contains the bar itself, so halving it would send the user off to load the wrong plates; the lens stays off and the screen keeps showing the plate total. Setting a base on the readout turns it on.

**Per-side precision**: the ladder's 2.5kg microplate grid halves onto quarters — a 22.5kg plate portion is **11.25** a side, not 11.3. Per-side figures therefore render at two decimals when the second digit is real (one otherwise, matching every other weight on the screen), and the numpad seeds at that same precision: at one decimal, opening the numpad and confirming it unedited would silently round the set from 22.5kg to 22.6kg.

**Warmup interaction**: the warmup **count** is still decided on the **total** working weight (a 117kg-total leg press is a 4-warmup lift whether or not 50kg of it is the sled), so the count bucket is unchanged. The ladder **weights** ramp on the **plate portion** (`working − base`), then add the base back, floored at the equipment minimum — so a 30%-of-working warmup on a high-base machine can't come out below the empty sled (which would show as negative plates). With **base 0** (free weights, or before the user enters one) the plate portion equals the total and the ladder is **byte-for-byte identical** to before — zero regression for the non-base path. Because base is entered lazily *after* load-time warmup injection, entering or correcting it **re-ramps the not-yet-logged warmups** (`applyBaseResistance`): each non-completed, non-locked warmup slot is overwritten with the plate-ramped value; already-logged or resume-locked warmups keep the exact weight recorded. Normal sets are never rewritten — their stored weight is the true total either way.

**PO advisor**: completely unaffected — it reads history totals, computes a target total, and writes the total onto the normal sets; the lens only changes what the log screen *displays*.

### 3. Rest Timer Overrides

**What it does**: Dynamically adjusts rest timer durations based on set type (warmup vs normal), exercise laterality, and position within the set sequence. Overrides the routine's default rest time in specific scenarios.

**How laterality is detected**: An exercise with >= 6 normal sets AND an even count is classified as **unilateral** (alternating sides). Odd normal-set counts are **bilateral**.

**Bilateral exercises** (odd normal-set count):

| After completing | Next set | Rest |
|---|---|---|
| Warmup | Warmup | 45s |
| Warmup (last) | Normal (first) | 60s |
| Normal | Normal | Routine default |

**Unilateral exercises** (even normal-set count) — warmup rest:

| After completing | Next set | Rest | Reason |
|---|---|---|---|
| Warmup (odd #) | Warmup (even #) | 5s | Switch sides at same weight |
| Warmup (even #) | Warmup (odd #) | 30s | Moving to next weight level |
| Warmup (last) | Normal (first) | 60s | Transition to working sets |

**Unilateral exercises** — normal set rest:

| After completing | Next set | Rest | Reason |
|---|---|---|---|
| Normal (odd #) | Normal (even #) | 15s | Switch sides |
| Normal (even #) | Normal (odd #) | Routine default | Full rest after both sides |

Set numbering is 1-based within each type (warmup or normal), not counting the other type.

### 4. Exercise Notes Display

**What it does**: Shows the exercise's notes from the routine on the LogSetScreen, directly below the Complete Set button. Useful for recording machine-specific information (e.g., baseline weights for different machines at different studios).

**When shown**: Only if the routine exercise has a non-empty notes field. If notes are blank or null, the section is hidden entirely.

**Data flow**: `RoutineExerciseResponse.notes` → `RoutineExercise.notes` → `ActiveExercise.notes` → displayed on LogSetScreen.

### 5. Similar Exercise Suggestion


**What it does**: For exercises with no workout history, suggests a conservative starting weight derived from similar exercises the user has done before.

**How it works**:
1. Finds all other exercises with the **same equipment AND same primary muscle group**
2. Gets each one's last workout first normal set weight
3. Picks the **lowest** weight among them (safest reference for an untried exercise)
4. Scales it to **60%** and rounds to the **nearest 2.5kg** multiple
5. **Barbell floor**: if the target exercise is barbell, the final suggestion is coerced to at least **20kg** (matches the warmup advisor's bar-weight floor)

**Examples**:
- `Face Pull` (shoulders, machine), no history. Last `Rear Delt Reverse Fly (Machine)` = 18kg. → `round(18 * 0.60 / 2.5) * 2.5` = `round(4.32) * 2.5` = **10kg**.
- `Landmine Row` (upper_back, barbell), no history. Last `Bent Over Row (Barbell)` = 32.5kg. → `round(32.5 * 0.60 / 2.5) * 2.5` = **20kg** (rounds to 20 naturally; also at-or-above the barbell floor).
- `Barbell Reverse Curl` (biceps, barbell), no history. Last `Barbell Curl` = 25kg. → `round(15 / 2.5) * 2.5` = 15kg → **coerced to 20kg** by barbell floor.

**Prefetch (run order)**: Running this at the Log screen only works if the reference exercise's history is already cached. Since `exerciseHistoryCache` is populated per-routine when the user opens it, a never-worked exercise whose reference lives in a different routine would miss. Two countermeasures:
- `RoutineDetailViewModel.fetchData` kicks off `WorkoutDataLoader.prefetchSimilarExerciseHistory` in the background after its own history fetch — pulling history for any template that shares equipment+muscle with a never-worked exercise in the current routine AND appears in one of the user's cached routines (i.e. exercises they actually train). Non-blocking so the detail screen renders immediately.
- `LogWorkoutViewModel.fetchExerciseHistory` calls the same prefetch synchronously right before computing suggestions, as a backstop for cases where the detail-screen prewarm hadn't finished yet.

**Run order relative to warmup advisor**: Similar-exercise suggestion runs **before** the warmup advisor. The advisor needs a working weight on the normal sets to compute warmup percentages, and for a never-worked exercise the suggestion is the only source of that weight. Running it second would leave new exercises with no advisor-injected warmups.

**Display**: Shown in amber on LogWorkoutScreen (chip subtitle), RestTimerScreen (next-set countdown), and LogSetScreen (weight picker value). Pre-populated on normal sets (with `isSimilarSuggestion = true`) so the warmup advisor can compute warmup weights from it and so every screen that reads the next working weight can flag it as a guess the user should verify.

**Apply gate**: The suggestion overwrites every normal set's weight when no normal set has a prescribed working weight (`(weightKg ?: 0) > 0`). The pre-population was previously gated on `weightKg == null` only — routines whose API payload returned `weight_kg: 0` (rather than null) for un-prescribed sets ended up with the suggestion in the chip-display map but not in `set.weightKg`, so LogSetScreen's picker started at 0. The gate now treats null and 0 the same way and overwrites unconditionally inside the gate. See `exerciseNeedsSimilarSuggestion` / `applySimilarSuggestion` in `LogWorkoutViewModel.kt`.

### 5b. In-Workout Exercise Substitution

**What it does**: When the prescribed machine/exercise isn't available at the current gym, lets the user swap it for an acceptable substitute **during** the workout — the substitute is logged against its *own* `exercise_template_id` with its *own* progressive-overload target weight, so there's no "log against the wrong exercise then edit on web" dance, and no companion-app strength-overview lookup to find the substitute's working weight. Pairs with the post-workout **SUBSTITUTED** completion status (see *Workout Detail*).

**Flow**:
1. On LogWorkoutScreen, tapping an exercise chip routes through `LogWorkoutViewModel.onExerciseChipTap`. For a **swap-eligible** exercise it shows a `Swap exercise? Y/N` prompt; otherwise it opens the first set directly (legacy path).
2. **Y** → navigates to **Swap Exercise screen** (`SwapExerciseScreen`, route `swap_exercise`); **N** → logs the prescribed exercise unchanged.
3. The Swap screen lists every acceptable substitute, each with its **own computed PO target weight** + source badge (green `↑` PO bump / plain last-session / amber `~` similar-estimate). Tapping one replaces the exercise in place and jumps straight to LogSetScreen for the substitute. A **Keep prescribed** row logs the original.

**Swap eligibility** (`LogWorkoutViewModel.canSwap`): all three must hold — (a) the routine is in a PO folder (`ActiveWorkout.progressiveOverload`, which `toActiveWorkout`/`toDomain` set from folder membership), (b) the exercise has substitutes (`SubstitutionMap.substitutesFor` non-empty), and (c) it has **no completed sets yet** (you're starting it, not returning to finish). This keeps the prompt off un-swappable exercises and off re-entry.

**Candidate computation** (`WorkoutHistoryApplier.prepareSwapCandidates`): for each substitute in the exercise's `SubstitutionMap` group, a one-exercise temp `ActiveWorkout` is run through the **same full pipeline** as workout start (`load()`: history hints → progressive overload → similar-exercise fallback → **warmup advisor**). So the weight shown on the swap screen is exactly what the exercise receives on select, warmups included. The substitute carries the prescribed exercise's normal-set structure (count, reps, rep range, target RPE) with weights blanked for the pipeline to fill. Histories/templates for all candidates are batch-fetched up front so the per-candidate `load()` calls resolve from cache. **Candidates are ordered by recency of use** (`lastUsedEpochMsOf` — the most-recent session timestamp from the history cache, descending; never-used substitutes sort last, curated group order breaks ties), so the alternatives you reach for most recently sit at the top of the swap list.

**Apply** (`LogWorkoutViewModel.applySwap`): a synchronous splice — the substitute's `preparedExercise` (already weight-filled + warmup-injected) replaces the prescribed one in `ActiveWorkout.exercises`, the chip-badge maps (`weightIncreasedExercises`, `exerciseSuggestedWeights`) are re-keyed from the old template id to the new one, and the logger is pointed at the new exercise's first set. The mutation flows through the throttled saver so a mid-workout swap survives a crash. From there everything is normal: the Finish POST carries the substitute's id + `routine_id`, and the post-workout comparison labels it SUBSTITUTED.

**Names**: Hevy has no runtime exercise-name catalog (only equipment + muscle group are cached) and a substitute usually isn't in any loaded routine, so `SubstitutionMap.nameOf` supplies curated display names for the swap list + the swapped-in exercise's `title`.

**Warmups**: applied **silently** for the substitute (no separate Keep/adjust prompt mid-workout) — the user lands on LogSetScreen ready to lift.

### 6. Set Weight/Reps Carry-Forward

**What it does**: When completing a set, the next set is automatically pre-populated with weight and reps.

**Rules**:
- Normal -> Normal: carries forward both weight AND reps from the completed set
- Warmup -> next set: keeps prescribed weight (no override)
- If the next set is already completed: no carry-forward (preserves recorded values)
- If re-completing an already-done set (editing): no carry-forward, no rest timer, no cursor advance

### 7. Continue Incomplete Workout

**What it does**: Allows resuming a previously saved workout that was incomplete (missing exercises or sets compared to the routine prescription). Accessed from the Workout Detail screen's "Resume" button.

**How it works**:
1. Fetches the full workout via `GET /v1/workouts/{workoutId}` and the routine via `GET /v1/routines/{routineId}`
2. Matches each routine slot against what was actually logged, **substitution-aware** ([`WorkoutDetailViewModel.buildResumeExercises`](app/src/main/java/com/example/hevywatch/presentation/workout/WorkoutDetailViewModel.kt), mirroring the passes in `buildCompletionStatuses` so Resume agrees with Workout Detail): first by exact `exercise_template_id`, then — for PO-folder routines — a slot with nothing logged claims an unclaimed logged exercise from the same `SubstitutionMap` group (preferring one with logged normal sets, but falling back to a warmup-only in-progress swap so its logged warmups are preserved on resume, not dropped)
3. Builds an `ActiveWorkout` per slot:
   - **Recorded exercises**: ALL sets from the original workout (warmups, normals, dropsets, failures) are carried over as completed AND locked — they cannot be edited in the UI
   - **Incomplete exercises**: recorded sets are locked, remaining prescribed normal sets are appended as editable
   - **Swapped-in exercises**: a slot the user swapped (e.g. routine Deadlift → logged Romanian Deadlift) resumes as the **swapped** exercise — its id / title / recorded sets win, with remaining normal sets appended toward the prescribed count. Previously the naive exact-`exercise_template_id` match dropped the swap and re-prescribed the original from scratch (fixed bug); pinned by [`BuildResumeExercisesTest`](app/src/test/java/com/example/hevywatch/BuildResumeExercisesTest.kt)
   - **Missing exercises** (nothing logged, no swap): left as the prescribed routine template — still swap-eligible at resume — so the warmup advisor can inject warmups
   - **Extras** the user logged that are neither a slot nor a substitute aren't surfaced as editable rows but are preserved on submit (the merge builders pass them through from the original GET)
4. Navigates to LogWorkoutScreen where the user completes the remaining work

**Locked sets**: Sets from the original workout have `locked = true`. In LogSetScreen, locked sets show "Logged previously" with read-only weight/reps (no pickers, no Complete button, no set type cycling). This flag is ONLY set during Continue Workout — never during normal workout recording or crash recovery.

**Resume timer**: On Continue tap, `WorkoutDetailViewModel.continueWorkout()` computes `originalDurationMs = originalEnd − originalStart` from the GET response and slides the new `ActiveWorkout.startTimeMs` back by that amount: `startTimeMs = now − originalDurationMs`. `WorkoutAwareTimeText` reads the same field as for fresh workouts (`now − startTimeMs`), so on resume the timer reads the original duration (e.g. 46:00) and continues ticking forward. **This is display-only** — the saved workout's `[start_time, end_time]` are the original window verbatim (see *`end_time` + set `completed_at`* below), so `startTimeMs` no longer feeds the submitted duration. Pure helper: [`WorkoutDetailViewModel.computeAdjustedStartMs`](app/src/main/java/com/example/hevywatch/presentation/workout/WorkoutDetailViewModel.kt). Pinned by [`ComputeAdjustedStartMsTest`](app/src/test/java/com/example/hevywatch/ComputeAdjustedStartMsTest.kt).

**Saving — primary path is POST + DELETE on the private v2 API.** Public `PUT /v1/workouts/{id}` whitelists fields and rejects `biometrics`, `wearos_watch`, and `is_biometrics_public`, so it can't carry resumed-segment HR data. Instead:

1. On Continue, the watch also calls `GET /workout/{id}` (private v2) in the background and stashes the response on [`HevyApp.continuingWorkoutDetailV2`](app/src/main/java/com/example/hevywatch/HevyApp.kt). That response carries the original biometrics blob (`heart_rate_samples` + `total_calories`), which v1 GET strips.
2. The resumed segment samples HR like a fresh workout (sampler no longer skips on `continuingWorkoutId`).
3. On Finish, [`buildResumePostRequestV2`](app/src/main/java/com/example/hevywatch/presentation/workout/WorkoutRequestBuilder.kt) merges the original `WorkoutDetailResponseV2` with the active workout into a single new POST body — original exercises preserved verbatim, new completed sets appended; new exercises added; biometrics combined by [`BiometricsBuilder.mergeForResume`](app/src/main/java/com/example/hevywatch/sensors/BiometricsBuilder.kt) (chronologically sorted, `total_calories` recomputed via Keytel on the combined samples). The body's `workout_id` is a fresh UUID.
4. `POST /v2/workout` ships the merged body. On 2xx, `DELETE /workout/{originalId}` (private v2) removes the original best-effort. If DELETE fails, the user has a transient duplicate they can delete manually in the official app — POST already succeeded, the data is safe.

**`end_time` on both save paths — preserve the original window verbatim**: the resumed workout inherits the original session's `[start_time, end_time]` **verbatim** on *both* the v2 POST (`buildResumePostRequestV2`) **and** the v1 PUT fallback (`buildWorkoutPutRequest`). Hevy's displayed **duration is `end_time − start_time`** (verified against the public API, which reports the inflated span directly while every set's `completed_at` comes back `null`), so re-finishing an incomplete workout days later must not push `end_time` past the original window. The resumed-segment time the user spent is intentionally not added; the on-watch resume timer is display-only. As a belt-and-suspenders, every newly-logged set's `completed_at` is also **clamped to `original.endTime`** on the v2 POST so no set can claim an instant outside the window. Pinned by [`BuildResumePostRequestV2Test`](app/src/test/java/com/example/hevywatch/BuildResumePostRequestV2Test.kt) (`start_time and end_time inherit the original window verbatim`, `new-set completed_at is clamped to the original end`) and [`WorkoutPutRequestBuilderTest`](app/src/test/java/com/example/hevywatch/WorkoutPutRequestBuilderTest.kt) (`end_time is preserved verbatim`, `stale startTimeMs finished days later does not inflate end_time (90h regression)`).

> **Regression fixed (this build).** The v1 PUT fallback previously **recomputed** `end_time = originalStart + (endTimeMs − active.startTimeMs)`. When the private v2 GET/POST failed and the resume fell back to PUT, a resume finished in a later session left `active.startTimeMs` anchored near the original start, so `now − startTimeMs` spanned the whole gap and the workout saved with a wall-clock duration (a real 90-hour workout: started Jul 6, finished Jul 10). Commit `fe2397e` had fixed the v2 POST path but wrongly assumed the v1 PUT was "immune" (that assumption was based on the earlier — incorrect — `completed_at`-derives-duration theory). Now both paths preserve the original window. The companion's resume path (`ResumeRequestBuilder.buildPostV2` / `buildPutV1`) was already immune — both use `original.endTime` (see PRD-COMPANION-APP.md §"Recents → Resume").

**`is_private` is never sent — on any save path.** The API's `Workout` response schema carries no `is_private`, so neither `GET /v1/workouts/{id}` nor anything else lets a resume read back the workout's visibility. Every value the builders could put in the request body was therefore a guess, and the guess was a hardcoded `false` — which silently republished workouts the user had marked private, on every resume that took the v1 PUT path. The field is now **removed from the request models outright** ([`WorkoutPutBody`](app/src/main/java/com/example/hevywatch/data/api/model/WorkoutPutRequest.kt), [`WorkoutPostBodyV2`](app/src/main/java/com/example/hevywatch/data/api/model/WorkoutPostRequestV2.kt), [`WorkoutPostBody`](app/src/main/java/com/example/hevywatch/data/api/model/WorkoutPostRequest.kt)) rather than nulled — the private v2 client serializes nulls, so a null field would still have gone out on the wire as `is_private: null`. Consequences: the v1 PUT leaves the stored visibility untouched, and the v2 POST (which creates a replacement record) plus fresh-workout saves take the **account's default visibility** instead of forcing public. The app has never had a privacy control, so it has nothing to assert here. `WorkoutDetailResponse.isPrivate` is kept as a deserialize-only field (always null in practice) purely in case the API adds it later — it must not be used to populate a request body. Pinned by [`WorkoutPutRequestBuilderTest`](app/src/test/java/com/example/hevywatch/WorkoutPutRequestBuilderTest.kt) (`is_private is absent from the serialized PUT body`, `is_private stays absent even when the original reports it private`) and [`BuildResumePostRequestV2Test`](app/src/test/java/com/example/hevywatch/BuildResumePostRequestV2Test.kt) (`is_private is absent from the resume POST body`), which assert against the serialized JSON — not the model — so a reintroduced field can't slip through.

> **Regression fixed (this build).** An earlier attempt at this bug (`b4b4019`) fixed only the companion and did so by teaching its v1 `WorkoutDetail` model to *parse* `is_private` — a field the endpoint never sends. The builders kept `original.isPrivate ?: false`, so the guess survived and private workouts kept reverting to public; the tests passed because they pinned the builder against a hand-built `original` with the field set, never against a real response.

**Fallback to v1 PUT**: kicks in **either** when the `GET /workout/{id}` on Continue failed (no v2 detail → straight to PUT, e.g. network/server/pre-feature workout), **or** after the private POST attempts above are all exhausted. The watch then PUTs via the legacy path; resumed-segment HR is discarded but the workout itself is preserved. Same body builder as before — [`buildWorkoutPutRequest`](app/src/main/java/com/example/hevywatch/presentation/workout/WorkoutRequestBuilder.kt). (Previously a server-side POST failure on resume surfaced an error with no fallback — now it auto-falls-back, matching the companion.)

**Retries + visible progress** ([`SaveProgress`](app/src/main/java/com/example/hevywatch/presentation/workout/SaveProgress.kt), [`LogWorkoutViewModel.runSaveAttempts`](app/src/main/java/com/example/hevywatch/presentation/workout/LogWorkoutViewModel.kt)): each path is retried with bounded, **visible** attempts instead of a single silent try that could spin for minutes. Private POST: up to `PRIVATE_SAVE_ATTEMPTS` (3); public PUT fallback: up to `FALLBACK_SAVE_ATTEMPTS` (2). Only transient failures retry — `isRetryableSaveError` covers network errors + 408 / 429 / 5xx; auth (401/403) and other 4xx stop immediately (auth → fall to the public path; a 4xx validation error means the body is wrong, retrying is pointless). Backoff starts at 800 ms, ×3 each retry, capped at `NETWORK_RETRY_MAX_DELAY_MS` (30 s). The Finish spinner shows the phase (`Saving` vs `Backup save`), `Attempt x/y`, and the **reason the previous attempt failed including the HTTP code** (e.g. `Rate limited (429)`, `Server error (502)`) — pinned by [`SaveProgressTest`](app/src/test/java/com/example/hevywatch/SaveProgressTest.kt). The same runner drives the new-workout POST and the user-confirmed public fallback prompt.

**Tradeoff**: the resumed workout gets a new `workout_id` and `short_id`. Comments / likes / followers on the URL of the original workout will 404 after the DELETE. Accepted because: (a) resumes are rare; (b) social engagement on a mid-session workout that hadn't been finished yet is essentially never present.

**Required headers on the v2 client**: `Hevy-App-Version` + `Hevy-App-Build` (default `3.0.12` / `2032997` baked in at build time via `BuildConfig.DEFAULT_HEVY_APP_VERSION` / `..._BUILD`), `Hevy-Platform: wearos`. Without the first two, the v2 GET / DELETE routes return 404 indistinguishable from "no such route". Values mirror the official Hevy Wear OS app's APIClient.

The pair is **runtime-mutable** so the watch can advertise a freshly-released official Hevy version without a rebuild. Source of truth at runtime is [`HevyAppVersionStore`](app/src/main/java/com/example/hevywatch/data/store/HevyAppVersionStore.kt) (SharedPreferences `hevy_app_version`); the OkHttp interceptor in [`HevyApiClient`](app/src/main/java/com/example/hevywatch/data/api/HevyApiClient.kt) invokes a supplier on every request so a sharedPrefs write takes effect on the next call without restart. Two override surfaces:

- **ADB broadcast** ([`SetApiVersionReceiver`](app/src/main/java/com/example/hevywatch/util/SetApiVersionReceiver.kt)):
  ```bash
  adb shell am broadcast \
    -n com.example.hevywatch/.util.SetApiVersionReceiver \
    -a com.example.hevywatch.SET_API_VERSION \
    --es name "3.0.13" \
    --es code "2033100"
  ```
- **Companion DataClient push**: the companion fetches `api-versions/active.json` from the repo on every cold start and forwards the pair via the `/api_version` message path. Handled on the watch by [`PhoneAuthDispatcher.handleApiVersion`](app/src/main/java/com/example/hevywatch/PhoneAuthDispatcher.kt), gated on the trusted-node-id check (same protection as `/auth_tokens` and `/watch_seed`).

End-to-end pipeline: the [`.github/workflows/check-hevy-version.yml`](.github/workflows/check-hevy-version.yml) cron scrapes apkmirror weekly. On detection it archives the .apkm + decompiled smali as a GitHub Release, writes `api-versions/candidate.json`, and opens a probe-required issue. After manually verifying the new headers work, [`.github/scripts/promote-candidate.sh`](.github/scripts/promote-candidate.sh) moves the candidate into `active.json`, and the companion picks it up on the next launch.

**Safety net**: The serialized PUT request body is persisted to `PendingRequestStore` before sending. Cleared only after 2XX. On next app launch, if a pending request exists, the user is prompted to retry or discard.

**Resume from companion (`/resume_workout`)**: the companion's Recents → Workout Detail screen can hand a resume off to the watch instead of finishing it on the phone. The companion sends a `/resume_workout` Wearable message (`{"workout_id": "..."}`) via `WatchResumeSender`; the watch handles it in [`PhoneAuthDispatcher.handleResumeWorkout`](app/src/main/java/com/example/hevywatch/PhoneAuthDispatcher.kt), gated on the **same trusted-node-id check** as `/auth_tokens` / `/watch_seed` / `/api_version` (a hostile peer could otherwise pop the user into an arbitrary workout). The id is charset-validated (letters / digits / `_` / `-`) because it's interpolated into a nav route. On accept, [`PhoneAuthService`](app/src/main/java/com/example/hevywatch/PhoneAuthService.kt) launches `MainActivity` with the **Tile deep-link channel** — `navigate_to = workout_detail/{id}` (now whitelisted in [`Screen.sanitizeTileRoute`](app/src/main/java/com/example/hevywatch/presentation/navigation/Screen.kt) alongside `routine_detail/{id}`) — so the watch opens the Workout Detail screen where the user taps **Resume** to run the normal [`continueWorkout`](app/src/main/java/com/example/hevywatch/presentation/workout/WorkoutDetailViewModel.kt) flow. Confirming on the device that will do the logging is deliberate. Pinned by `PhoneAuthDispatcherTest`'s resume cases (valid forward, unsafe-id reject, untrusted-node reject, malformed-JSON swallow). See PRD-COMPANION-APP.md §"Recents → Resume" for the phone side.

### 8. Crash Recovery

**What it does**: Persists the active workout to disk on every state change so no data is lost if the watch crashes mid-workout.

**How it works**: The full workout (exercises, sets, completion states, weights, reps) is serialized to SharedPreferences as JSON via Gson. On next app launch, if a persisted workout exists but none is in memory, a prompt shows: "Resume workout? — [name] — N / Y".

**Save cadence**:
- **Throttled (`saveBlocking()`)** via `ThrottledSaver`: every UI mutation flips a dirty bit; a single coroutine ticks every 2 s and issues a `saveBlocking()` only when the bit is set. A fast weight-picker scroll producing dozens of mutations per second collapses to at most one save every 2 s. After `ThrottledSaver.IDLE_TICKS_BEFORE_STOP` (5) consecutive empty ticks the loop **quiesces entirely** and the next `touch()` restarts it — a 90-minute workout with long rest gaps used to burn ~2700 no-op CPU wakeups doing nothing. Worst-case data-loss on a process kill is ~2 s of scrolling state, unchanged.
- **Sync (`saveBlocking()`)** at two durable checkpoints, outside the throttle: **set completion** and **pause**. These bypass the throttle so a kill immediately after either action can't lose the just-confirmed state.

**When it clears**: After successful POST to API, or on explicit discard.

### 9. Tile

**What it does**: A Wear OS tile (swipeable from the watch face) that provides quick access to routines and workout progress.

**Idle state** (no active workout): Shows routines from the PO folder as tappable rows, sorted the same way as RoutineListScreen — by last workout date ascending, so never-worked routines appear at the top and the most recently completed routine sinks to the bottom. Tap launches the app directly to that routine's detail screen. Data from persisted cache (works after reinstall without opening app first).

**Active workout — between exercises** (current exercise has 0 completed sets):
- Outer ring: exercise completion progress (blue on gray track)
- Inner ring: hidden
- Big text (orange): next exercise name
- Small text (orange): first set weight x reps
- Tap: opens LogWorkoutScreen

**Active workout — between sets** (current exercise has >= 1 completed set):
- Outer ring: exercise completion progress
- Inner ring: set completion progress (lighter blue)
- Big text (colored): next set target weight + "kg" — green (PO), orange (advisor), white (previous)
- Small text (white): min reps
- Tap: opens LogSetScreen

### 10. Connectivity Check

Before attempting to POST or PUT a workout, the app checks for an active network connection. If the watch is disconnected from both phone and WiFi, an immediate error is shown without wasting time on a doomed request.

### 11. Heart-rate sampling & biometrics

**What it does**: during a fresh (non-resumed) workout, samples the watch's PPG sensor once per minute. At workout end, packs the samples plus a Keytel-formula calorie estimate into the `biometrics` blob on `POST /v2/workout`, which makes the HR chart, average BPM, and calorie number appear on the official Hevy app's workout-detail screen.

**Gating** ([`HeartRateAvailability`](app/src/main/java/com/example/hevywatch/sensors/HeartRateAvailability.kt)). Three independent layers, all must be true to sample:
1. **Hardware** — `PackageManager.FEATURE_SENSOR_HEART_RATE` AND `SensorManager.getDefaultSensor(TYPE_HEART_RATE) != null`. KSW2 (`ray`) passes, KSW1 (`shiner`) fails.
2. **Setting** — `UserProfileStore.heartRateEnabled == true`. Default false; toggle in Settings → "Heart rate".
3. **Runtime permission** — `BODY_SENSORS` granted. Prompted from Settings on toggle-on, and just-in-time on workout start if still missing.

**Sampling cadence** ([`HeartRateSampler`](app/src/main/java/com/example/hevywatch/sensors/HeartRateSampler.kt)): polls every 60 s. Each poll registers the listener, awaits the first reading with accuracy ≥ `SENSOR_STATUS_ACCURACY_MEDIUM` (or accepts `_LOW` once half the poll-timeout has elapsed to handle PPG warm-up jitter), captures `{bpm, timestamp_ms}`, then unregisters. Worst case 8 s of PPG-on per minute → ~13% sensor duty cycle, a significant battery save vs continuous registration on the Snapdragon Wear 2100. `SensorManager` + the HR `Sensor` handle are resolved once per sampler (`by lazy`) rather than re-fetched on every poll.

**Give-up-on-hardware-fault**: each poll returns a `PollResult` that distinguishes `RegistrationRefused` (`registerListener` returned false — the HAL won't enable the PPG) from `NoReading` (registered fine, but no acceptable-accuracy sample arrived in the budget: bad contact, cold PPG, arm movement). `NoReading` is transient and keeps polling. After `MAX_CONSECUTIVE_REFUSALS` (5) consecutive `RegistrationRefused` results the sampler **stops for the rest of the workout** — that pattern means a hardware fault (see the ray note below), and retrying every minute for the remaining 85 minutes of a session is pure battery burn with zero chance of success. The next `start()` (new workout, or resume after pause) re-arms in case the sensor was reseated.

> **HR-not-recording on ray was a HARDWARE fault, not software.** A report that ray (KSW2) stopped recording HR was traced — via temporary logcat instrumentation, since removed — to the HR sensor's HAL refusing to enable: `SensorManager.registerListener` returned **`false`** on every poll, so the PPG never turned on (`hrSamples=0` → `biometrics attached=false`). Ruled out as software by three proofs on-device: (1) the **accelerometer registered `true` from the identical code path**, (2) HR failed even with an explicit main-looper `Handler`, and (3) `dumpsys sensorservice` showed **zero** HR-sensor registrations by *any* app (ours, Google Fit, GMS, system) while every other sensor registered dozens of times. Root cause: the HR/PPG flex connector was left unseated/faulty after a third-party **battery replacement** on ray. No app-side change can enable a sensor the HAL won't activate; the only remaining app improvement is the `Log.w` in `HeartRateSampler` when `registerListener` returns false (the signature of a disconnected/faulty HR module). Chasing this in code is a dead end — verify the sensor hardware first (`dumpsys sensorservice` → the HR handle `0x13` should appear in *Previous Registrations* once reconnected). Two abandoned software hypotheses are recorded so they aren't retried: item 13's 1 s sampling period (reverted to `SENSOR_DELAY_NORMAL` anyway — the long-standing value — but it was **not** the cause) and the private→public save fallback (rarely hit; also not the cause).

**Lifecycle** (driven by `LogWorkoutViewModel`):
- `init()` calls `maybeStartHrSampler(active)` — starts sampling iff the workout is not a resume and gating passes.
- `pauseWorkout()` calls `stopHrSampler()` — no samples while paused (the user is explicitly not working out).
- `resumeWorkout()` re-calls `maybeStartHrSampler(active)`.
- `resetVmState()` / `onCleared()` stop the sampler.

### Post-pause abandonment nudge

A workout left paused doesn't expire on its own — it just sits there, and the next time the user notices it they have an incomplete session that needs the resume POST+DELETE cleanup. On returning to LogWorkoutScreen, `LogWorkoutViewModel.checkAbandonment()` evaluates the pure `shouldNudgeAbandonment(isPaused, pausedAtMs, nowMs)` predicate: fires when the workout is currently paused **and** the pause began more than `ABANDONMENT_THRESHOLD_MS` (30 min) ago. 30 minutes is well past any real rest period but catches a forgotten session the same day.

The prompt (`AbandonmentDialog`) offers three outcomes as stacked full-width chips rather than the usual `ConfirmDialog` Y/N pair — three rows read better than three squeezed buttons on a 320 px screen and each is a comfortable tap target:

| Option | Effect |
|---|---|
| **Finish now** | `resumeWorkout()` (so the elapsed clock excludes the dead time) then `finishWorkout()` |
| **Keep paused** | Dismisses; the workout stays paused exactly as it was |
| **Discard** | `clearWorkout()` and navigates back, same as the normal discard path |

Answering any option sets an internal `abandonmentNudgeAnswered` flag so re-entering the screen during the same pause doesn't re-prompt; the flag clears on `resumeWorkout()` and `resetVmState()`. The nudge takes priority over the finish/discard/fallback prompts. `AbandonmentNudgeTest` pins the threshold, both boundary sides, the null-timestamp case, and backwards clock skew.

Samples land in `ActiveWorkout.heartRateSamples` (new field), persisted through the existing throttled-save path so a crash mid-workout doesn't lose them.

**Resumed workouts DO sample.** Earlier the sampler short-circuited on `continuingWorkoutId != null` (the v1 PUT path couldn't carry biometrics, so samples would have been dropped server-side). The resume flow now uses POST + DELETE on the private v2 API (see *Continue Incomplete Workout*), which fully accepts biometrics, so resumed-segment samples are merged with the original session's samples into a single combined POST.

**Calorie formula** ([`KeytelCalories`](app/src/main/java/com/example/hevywatch/sensors/KeytelCalories.kt)). Keytel et al. (2005) regression. For each per-minute sample, contributes `kJ/min(HR, weight, age, sex) / 4.184` kcal; sum is rounded to a non-negative `Int`. For `Sex.UNSPECIFIED`, returns the mean of the male+female equations (sex-neutral fallback). Per-minute contributions are clamped ≥ 0 to handle very low HR where Keytel can predict negative kJ. Caveats live in the file header.

**Wire format**. The `biometrics` blob on POST: `{total_calories: Int?, heart_rate_samples: [{bpm: Double, timestamp_ms: Long}]}`. Field names match the decompiled smali exactly (no `@SerializedName` rewrites). Sibling `wearos_watch=true` and `is_biometrics_public=true` are set unconditionally on every v2 POST so the workout shows up correctly in the official app and HR is visible to followers. The server computes `average_heart_rate` itself from the samples — we never send it. Full schema reference: [`reference_biometrics_schema.md`](memory file).

**Composition**: `BiometricsBuilder.fromActiveWorkout(workout, weightKg, ageYears, sex)` (pure) constructs the body from the ActiveWorkout's samples + UserProfileStore demographics + BodyweightStore weight. Called from `LogWorkoutViewModel.buildPostRequest` and the recovery-webhook backup path. Returns `null` (omits the field) when the workout has no samples.

---

## Launcher icon

The launcher icon is the valkyria silhouette inside an orange disc. It's wired up as both a legacy raster and an adaptive icon so it renders correctly on every Wear OS launcher mask. Source assets (originals): `valkyria orange.png` and `valkyria greyscale light.png` — both 2072×2048 PNGs with a valkyria-shaped *transparent cutout* through the disc. The launcher icons are downscaled from those.

- **Legacy raster** — the orange source is downscaled into `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` and `ic_launcher_round.png` at the standard 48 / 72 / 96 / 144 / 192 px sizes. The Wear launcher (which always uses a circular mask) reads `ic_launcher_round.png` and shows the full disc; the figure cutout shows whatever's behind the icon (typically the watch face background) but on Wear the rendered backdrop is opaque, so practically the figure reads as transparent against the launcher's own surface. Small corner-case visual; legacy raster is mostly there for older Android.
- **Adaptive icon** — `mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml` reference `@drawable/ic_launcher_background` (a **solid white** vector) and `@drawable/ic_launcher_foreground` (the orange source as a 432-px raster at `drawable-xxxhdpi/`). The orange foreground has a valkyria-shaped transparent cutout, and the white background fills that cutout — so the rendered icon is an orange disc with a white valkyria figure inside. White also fills the corners on launchers whose mask extends past the inscribed orange circle (squircles, rounded squares).
- **Themed icon (Material You / Android 13+)** — `<monochrome>` in the adaptive icon points at `@drawable/ic_launcher_monochrome`, a 432-px raster from the greyscale source whose alpha channel is **opaque disc with valkyria-shaped cutout**. Themed-icon launchers tint **only the alpha mask** with the wallpaper colour and ignore RGB, so the disc-with-cutout shape is exactly what shows: a tinted disc with the valkyria figure as a negative cutout.

The previous barbell-graphic source (`barbell.png` at the repo root) and the chroma-segmentation-built monochrome have been retired.

**Cold-start splash** — on first composition `MainActivity` renders `SplashLogo` (full-bleed black with the valkyria foreground filling the viewport via `ContentScale.Fit` + `fillMaxSize()`; on the round Wear viewport the orange disc fills the inscribed circle edge-to-edge) for `SPLASH_DURATION_MS` (1.2 s) before mounting the NavHost. Backed by a `rememberSaveable` flag so a configuration change (ambient mode entry / exit) doesn't re-show the splash mid-session — it shows once per process launch. Source: `ui/components/SplashLogo.kt`.

**Loading spinner** — every indeterminate `CircularProgressIndicator` in the app has been replaced by `RotatingLogo` (`ui/components/RotatingLogo.kt`): the same orange-disc-with-valkyria-cutout foreground rotated clockwise on a 1.2 s linear infinite loop. Defaults to **fullscreen** (`Modifier.fillMaxSize()` + `ContentScale.Fit`) so the orange disc fills the inscribed circle of the watch viewport — the visual mirrors the splash and reads as one consistent "valkyria is doing something" affordance across cold-load, refresh, and busy states. Used fullscreen by `RefreshOverlay` and the cold-load states on `RoutineFolderListScreen` / `RoutineListScreen` / `RoutineDetailScreen` / `WorkoutDetailScreen` / `LogWorkoutScreen`. Pass an explicit `size: Dp` for inline-compact spinners that share their row with surrounding text — used by the "Loading history…" state in `LogSetScreen` (28 dp), the save state in `LogWorkoutScreen` (40 dp — captioned with the save phase, `Attempt x/y`, and the last failure reason from `SaveProgress`, not a bare "Saving…"), and the pending-resend / activate states in `ModeSelectionScreen` (40 dp), each of which sits in a column with title or caption text that a fullscreen logo would clobber. The determinate progress arc in `RestTimerScreen` is **not** swapped — it draws an arc proportional to time remaining and is not a loading indicator.

## Theme & colour tokens

The app ships **two colour schemes** — **dark** (default) and **light** — chosen by the user on the Settings screen (Settings → Theme, persisted to `ThemeSettingsStore` / `theme_prefs`, default `DARK`). The light scheme is a **greyscale inversion** of the dark one: black backgrounds become white, white text becomes black, and the light-grey muted text (`HevyMidGrey #8E8E93`) becomes a dark grey (`HevyDarkGrey #5E5E63`). **Accent foregrounds are shared across both schemes and never flip** — `BrandOrange`, `HevyRed`, `PoGreen`, `WarmupAmber`, `ChipPalette.ConfirmGreen`/`DeltaPositive`/`DeltaNegative`, the set-type failure/dropset tints, and the green Resume-chip (`ChipPalette.ResumeChipBg`, white label) all render identically in either theme (saturated mid-tones read on both black and white). The **dark-tinted** semantic surfaces DO flip — the status-chip tints, the PR badge pill, and the connectivity/alert banners each have a light-scheme counterpart, selected per theme via `HevyExtendedColors`. The branded cold-start splash stays black in both (it precedes theme resolution and the orange-disc logo is designed for a black field).

The scheme is applied at the activity root: `MainActivity` reads `ThemeSettingsStore.themeMode` (Compose state) inside `setContent` and passes it to `HevyWatchTheme(themeMode)`, so flipping the toggle recomposes the whole tree live. `HevyWatchTheme` selects `DarkColorPalette` / `LightColorPalette` for `MaterialTheme.colors`, provides a matching `HevyExtendedColors` (status-chip tints, NORMAL set-type colour, PR-badge fg/bg, and the three banner fg/bg pairs) via the `LocalHevyColors` composition local, **and pins `LocalContentColor` to `colors.onBackground`**.

**The `LocalContentColor` pin is the load-bearing fix for light mode.** Wear's default `LocalContentColor` is a fixed light value, and nothing overrode it — so the dark scheme worked *by accident* (light default on a black canvas) while any bare `Text`/`Icon` with no explicit `color=` (not inside a Chip/Button/Card, which set their own content colour) was invisible white-on-white in the light scheme. This hit the finish-workout recap stat values, the rest-timer countdown number, the "Done with this exercise?" prompt, "Workout not found", and others. Pinning `LocalContentColor = onBackground` inside `HevyWatchTheme` makes the default content colour track the scheme in one place (no effect on dark, where `onBackground` is already white). Bare Texts are therefore *intentionally* left without an explicit colour — they inherit the themed default.

A second class is the clock: Wear's `TimeText` ignores `LocalContentColor` and uses its own `TimeTextDefaults.timeTextStyle()` default (fixed light). `WorkoutAwareTimeText` therefore passes an explicit `timeTextStyle = TimeTextDefaults.timeTextStyle(color = MaterialTheme.colors.onBackground)` on **both** the active-workout (centre time-of-day) and idle branches. Every screen routes its clock through `WorkoutAwareTimeText` (including `ModeSelectionScreen`, which previously used a bare `TimeText()` in its own `Scaffold`).

The remaining hardcoded `Color.White` / `Color.Black` / `HevyWhite` call sites were routed through `MaterialTheme.colors.background` / `.onSurface` / `.onBackground` (or `LocalHevyColors`) so they track the scheme, and every scattered accent hex literal (`#66BB6A`, `#EF5350`, `#FFC107`, `#4CAF50`, `#F44336`, `#2E7D32`, the PR-badge and banner colours) was centralised into named `ChipPalette` tokens — no UI file hardcodes a raw colour any more (the only literals left are `SplashLogo`'s black field and the two Resume-chip white labels on green).

Concrete colour values live in `ui/theme/Color.kt`, `ui/theme/Theme.kt`, and `ui/theme/ChipPalette.kt`; surface code references symbolic names so the rebrands (Hevy blue → Barbell orange → valkyria orange — same #FE6A16) didn't churn every call site. Treat the symbol names as the source of truth; the hex values below are informational only.

**Brand & accents** (`ui/theme/Color.kt`)

| Token | Hex | Where it shows up |
|---|---|---|
| `BrandOrange` | `#FE6A16` | The primary accent — sampled from the bright top of the launcher icon. Wired to `MaterialTheme.colors.onPrimary`. Also used directly as the rest-timer progress ring, the active-workout session-timer text, the in-workout rest countdown next to the clock, and the post-workout "Nice Work!" heading. |
| `HevyMidGrey` | `#8E8E93` | **Dark scheme** `MaterialTheme.colors.onSecondary`. Subtitles, captions, paused session-timer text. |
| `HevyDarkGrey` | `#5E5E63` | **Light scheme** `MaterialTheme.colors.onSecondary` — the dark-grey counterpart to `HevyMidGrey` for muted text on a white canvas. |
| `HevyWhite` | `#FFFFFF` | Dark scheme `onBackground` / `onSurface` — chip *titles*, picker numbers, foreground text. (Light scheme uses `HevyBlack` here.) |
| `HevyBlack` | `#000000` | Dark scheme `background` / `surface`. Also the light scheme's `onBackground` / `onSurface` (foreground text). |
| `LightBackground` | `#FFFFFF` | Light scheme `background` (full-screen canvas). |
| `LightSurface` | `#F2F2F2` | Light scheme `surface` — chip/button fill, a hair off-white so chips separate from the white canvas. |
| `HevyRed` | `#E34B37` | Wired to `MaterialTheme.colors.error` (both schemes). Error banners, save-error text, the offline-banner background tint. |

**Chip semantics** (`ui/theme/ChipPalette.kt`)

| Token | Hex | Meaning |
|---|---|---|
| `ChipPalette.PoGreen` | `#4CAF50` | Subtitle / target-weight tint when progressive-overload increased the weight from last session. |
| `ChipPalette.WarmupAmber` | `#FFC107` | Subtitle tint for warmup-advisor-injected sets, similar-exercise suggested target weights, and the warmup set-type label. Amber rather than orange so it stays distinct from `BrandOrange`. The deprecated alias `WarmupOrange` resolves to the same value for source compatibility. |
| `ChipPalette.ConfirmGreen` | `#66BB6A` | PO-applied weight emphasis: the value picker number, the rest-timer next-weight hint, and the weight-dialog confirm ✓. (Brighter green than `PoGreen`; kept distinct to preserve the existing dark-theme look.) |
| `ChipPalette.DeltaPositive` | `#4CAF50` (= `PoGreen`) | RoutineDetail progress row — weight up vs last session. |
| `ChipPalette.DeltaNegative` | `#F44336` | RoutineDetail progress row — weight down vs last session. |
| `ChipPalette.ResumeChipBg` | `#2E7D32` | "Resume Workout" chip background (Folder/Routine lists). White label, both schemes. |
| `ChipPalette.StatusCompleteBg` | `#1B3A1F` | Dark green tint — exercise chip background when all prescribed normal sets are done. **(dark scheme)** |
| `ChipPalette.StatusInProgressBg` | `#1A2F3E` | Dark blue tint — exercise chip background when partially done. **(dark scheme)** |
| `ChipPalette.StatusMissingBg` | `#3E1A1A` | Dark red tint — Workout Detail screen, exercise prescribed in routine but not recorded. **(dark scheme)** |
| `ChipPalette.StatusSubstitutedBg` | `#312A4A` | Dark violet tint — **retired from Workout Detail** (swaps are now tinted by completion, like every other row); kept for any future swap-specific surface. **(dark scheme)** |
| `ChipPalette.StatusExtraBg` | `#2C2C2C` | Dark grey tint — Workout Detail screen, logged exercise not part of the routine (bonus work). **(dark scheme)** |
| `ChipPalette.StatusCompleteBgLight` | `#C8E6C9` | Pale-green light-scheme counterpart of `StatusCompleteBg`. |
| `ChipPalette.StatusInProgressBgLight` | `#BBDEFB` | Pale-blue light-scheme counterpart of `StatusInProgressBg`. |
| `ChipPalette.StatusMissingBgLight` | `#FFCDD2` | Pale-red light-scheme counterpart of `StatusMissingBg`. |
| `ChipPalette.StatusSubstitutedBgLight` | `#D1C4E9` | Pale-violet light-scheme counterpart of `StatusSubstitutedBg`. |
| `ChipPalette.StatusExtraBgLight` | `#E0E0E0` | Pale-grey light-scheme counterpart of `StatusExtraBg`. |

Status tints are selected per scheme through `HevyExtendedColors` (`DarkExtendedColors` / `LightExtendedColors` in `Theme.kt`), read via `LocalHevyColors`. `WorkoutDetailScreen.backgroundForStatus(status, colors)` and `LogWorkoutScreen`'s chip-colour `when` both take the current extended palette. The chip *title* on a status chip is `MaterialTheme.colors.onSurface`, which reads correctly on both the dark tints (white) and the pale light tints (black).

**Set-type colours** (`LogSetScreen.kt`)

| Set type | Colour | Source |
|---|---|---|
| Normal | theme foreground (`HevyExtendedColors.setNormal`: white on dark, black on light) | the only plain-foreground set-type colour, so it flips with the scheme; resolved once in `LogSetScreen` and threaded into `SetType.dotColor(setNormal)` since the call sites are in the non-composable `ScalingLazyColumn` builder scope |
| Warmup | `#FFC107` | matches `ChipPalette.WarmupAmber` |
| Failure | `#EF5350` | local constant |
| Dropset | `#CE93D8` | local constant |

**PR badge** (`ui/theme/ChipPalette.kt`, selected via `HevyExtendedColors` — theme-dependent)

| Token | Dark | Light | Use |
|---|---|---|---|
| `prBadgeFg` | `#FFC857` (brand gold) | `#7A5A00` (deep gold) | `1RM PR` / `Weight PR` / `Reps PR` badge text. |
| `prBadgeBg` | `#3E2A1A` (warm brown) | `#FFF1CC` (pale gold) | Badge pill background. |

**Connectivity / alert banners** (`ui/theme/ChipPalette.kt`, selected via `HevyExtendedColors` — theme-dependent). Dark scheme = dark saturated fill + pale text; light scheme inverts to a pale fill + dark text so the alert reads on a white canvas.

| Banner | Dark bg / fg | Light bg / fg | Source |
|---|---|---|---|
| Offline | `#5A1A1A` / `#FFD2D2` | `#FFDAD6` / `#5A1A1A` | `OfflineBanner` |
| Refresh-error | `#5A3A1A` / `#FFE4B5` | `#FFE8C7` / `#5A3A1A` | `RefreshErrorBanner` |
| Companion-sync | `#1A3A5A` / `#B5D4FF` | `#D6E8FF` / `#1A3A5A` | `CompanionSyncBanner` |

**Tile palette** (`tile/TileStateComputer.kt::TileColors`) — kept in lock-step with the in-app colours so a tap-into-tile transition doesn't visually flash:

| Token | Hex | Use |
|---|---|---|
| `TRACK_GRAY` | `#333333` | Tile ring background track. |
| `RING_OUTER` | `#4FC3F7` | Tile outer (exercise-progress) ring. |
| `RING_INNER` | `#81D4FA` | Tile inner (set-progress) ring. |
| `WEIGHT_PO` | `#66BB6A` | Tile weight readout when PO increased it. |
| `WEIGHT_SUGG` | `#FFA726` | Tile weight readout for advisor / similar-exercise suggestions. |
| `WHITE` | `#FFFFFF` | Default tile weight readout. |

## Screens

### App Entry (Loading Screen)
**Route**: `mode_selection`

Auto-activates the private API connection on launch. Shows a loading spinner, or an error with a Retry button if the connection fails. Three recovery checks run in order:

1. **Active workout in memory** → navigates directly to LogWorkoutScreen
2. **Crash-recovered workout on disk** (ActiveWorkoutStore) → prompts "Resume workout? — [name] — N / Y". Workouts started more than 24h ago are auto-discarded silently (treat as abandoned — battery died mid-set, user never came back).
3. **Unsent request body on disk** (PendingRequestStore) → prompts "Retry unsent workout? — A PUT/POST request failed to send — N / Y". Y deserializes the stored request body and re-sends to the original endpoint; N discards it.

Handles tile deep links (routes to the correct screen if launched from a tile tap).

### Routine Folder List
**Route**: `routine_folders`

Two-page layout with tappable page indicator dots:

**Page 1 — Folders**: Scrollable list of routine folders. The cap is **user-tunable** via Settings → Display (`DisplayLimitsStore.folderListLimit`, default 5, range [1, 20]). Folders beyond the cap stay reachable through the source app — the watch is a focused surface, not a folder browser. Dark chips show folder name with routine count ("N routines") as a grey subline. On a fresh install (or whenever `cachedRoutines` is empty) the routine count is fetched in the background by `RoutineFolderListViewModel.primeRoutineCounts` so subtitles populate on first paint — the user no longer has to tap into a folder once to prime the count. Green "Resume Workout" chip appears when a workout is active. Refresh chip near the bottom; **⚙ Settings chip** at the very bottom navigates to the Settings screen. See *Refresh policy* below for how the chip behaves on tap.

A synthetic **Uncategorized** folder is appended automatically when there are routines whose `folder_id` is null — without it those routines would be invisible from the watch's folder list. The folder uses the sentinel id `__uncategorized__`; its routine list filters to `folderId == null`. The "Use for tile" chip is hidden inside this folder (the tile filter is keyed off real folder ids). Counts against the folder cap: when there are ≥cap real folders + at least one orphan, Uncategorized is dropped from the watch view (still reachable via the source app).

When cached folders exist but the background refresh fails, an inline "⚠ Refresh failed — tap to retry" banner shows above the list; the user keeps the cached data while still seeing the failure signal.

**Page 2 — Recent**: Most recent workouts logged **from a PO-folder routine**, newest first. The cap is **user-tunable** via Settings → Display (`DisplayLimitsStore.recentWorkoutsLimit`, default 10, range [1, 25]). Each chip shows workout title and date/time. Tap opens Workout Detail screen. Refresh chip at bottom re-fetches and replaces the recent list. See *Refresh policy* below.

**PO-only scope**: The page shows only workouts whose `routine_id` belongs to a routine in a Progressive-Overload folder; workouts with no routine, or from a non-PO routine, are hidden. The PO routine-id set is derived from the routine cache (`Routine.progressiveOverload`, itself set from `ProgressiveOverloadStore.enabledFolderIds`) and recomputed by `applyRoutines()` on every routine (re)load. The filter is applied as a reactive computed property `RoutineFolderListViewModel.visibleRecentWorkouts` (= `poRecents(recentWorkouts, poRoutineIds)`), so the page updates as soon as either the fetched recents or the routine cache resolves. Pure helpers `poRoutineIds` / `poRecents` are companion-object and unit-tested in `RecentWorkoutsPoFilterTest`. Note: the filter runs over the already-fetched recents, so the page shows the PO subset *of the most recent `limit` logged workouts* — for this user that's effectively all of them, since they almost exclusively log PO workouts.

**Multi-page fetch when cap > 10.** `/v1/workouts` returns 10 workouts per page (`DisplayLimitsStore.WORKOUTS_PAGE_SIZE`). `RoutineFolderListViewModel.fetchRecents(limit)` walks `ceil(limit / 10)` pages. Page 1 reuses the existing 60-min in-memory cache (`HevyApp.cachedWorkoutsPage1OrFetch`); pages 2+ are uncached fresh fetches each time the screen loads or refreshes. The loop short-circuits when the API reports `pageCount` reached (so a user with fewer workouts than `limit` only triggers as many requests as actually pay off).

**Recent ordering**: The page is sorted client-side by `start_time` desc — chronological order of when each workout actually happened, which matches the date string shown on every row. We sort explicitly (rather than trust the API's natural order) so the page can never depend on server-side ordering quirks. The `created_at` field on `WorkoutSummaryResponse` is unused for this view. Helper: `RoutineFolderListViewModel.sortedRecents` (companion-object, pure, unit-tested in `RecentWorkoutsOrderingTest`).

### Workout Detail
**Route**: `workout_detail/{workoutId}`

Shows a completed workout with exercise-level completion status compared to the routine's prescription. The chip background color conveys **completion only** — not OG-vs-swap — so the one question this screen answers ("is this exercise done?") reads at a glance, swaps included. `backgroundForStatus(status, colors)` tints by `ExerciseCompletionStatus.isComplete` (see *Warmup-aware completeness* below):
- **Complete** (every prescribed normal set **and** every advised warmup set logged): dark-green-tint background
- **Partial** (some normal sets logged but not done, or normal sets done but advised warmups missing): dark-blue-tint background
- **Missing** (nothing logged for a prescribed slot): dark-red-tint background
- **Extra** (logged exercise that isn't part of the routine): neutral-grey-tint background — bonus work, not judged for completeness
- Swaps are no longer tinted violet — a swapped-in exercise flows through the same green/blue/red completion rule as any other row, surfaced by a dim **`swap`** kind tag on the chip title (the original slot name is not shown). The underlying `Status` enum still records SUBSTITUTED (it drives that tag, the sort order, and Resume eligibility); only the presentation changed. See *Chip styling* above and *Exercise substitution & extras* below.
- Sorted (by `Status` ordinal): complete → substituted → incomplete → missing → extra

#### Warmup-aware completeness

`ExerciseCompletionStatus.isComplete` is true iff `recordedNormalSets >= prescribedNormalSets` **and** `recordedWarmupSets >= expectedWarmupSets`. `expectedWarmupSets` is computed in `buildCompletionStatuses` by `expectedWarmupSetsForCompletion`, which judges warmups **only against a planned PO target weight** — how many warmups the live advisor would have prescribed when the session started. When a target exists (`poByTemplate[templateId]` non-null) it runs the existing [`suggestWarmupSets`](app/src/main/java/com/example/hevywatch/presentation/workout/WarmupAdvisor.kt) at that weight against the exercise's catalog metadata (equipment + primary muscle group from `HevyApp.exerciseEquipment` / `exerciseMuscleGroup`) + bodyweight. The target comes from `computePoTargets` (`computeProgressiveOverload` over the exercise's history windowed by `historyBefore` to sessions logged **strictly before the viewed workout's `start_time`**, and dropping the viewed workout itself). Reconstructing the target *as of that workout* is load-bearing: excluding only the viewed workout by id (the old behaviour) left **later, heavier** sessions in the recency-weighted PO base, so a past workout was judged against a **forward-looking** target — e.g. Leg Press (LARGE group, 80kg warmup-bucket step) was advised **3** warmups on 6 Jul and all 3 were done, but sessions logged *after* the 6th pushed the recomputed target over 80kg → **4** expected → the completed row read incomplete. Windowing to before-the-viewed-workout makes the recompute see exactly the history the live advisor saw. (Unparseable/absent stamps are kept — never worse than the id-only fallback; a null cutoff restores id-only.) **When there is NO PO target** (a first-time exercise, no usable prior normal history, or any off-routine substitute/extra — none of which get a target), `expectedWarmupSets` is held to **`recordedWarmupSets`** — i.e. the warmup gate is not asserted at all. **Why never the weight lifted:** the *only* other weight available is the working set the user just logged, and recomputing from it lets a heavier-than-planned lift retroactively demand more warmups than were recorded (the **adductor-at-50kg** bug: MEDIUM group, 1 warmup done at a ~40kg plan, working sets pushed to 55kg → recompute wants 2 → the completed exercise falsely reads incomplete). A heavier lift must only raise **next** session's warmup target, never un-complete this instance — so without an independent plan we accept the warmups that were logged. (Earlier this fell back to the logged weight and produced a *phantom* missing warmup — e.g. rear-delt logged at 30kg against a ~24kg target; that fallback is gone.) With a genuine target, an exercise with all working sets done but its genuinely-advised warmups skipped still reads **partial (blue)**; one done at-or-below its target reads **complete (green)**. Degrades safely: no catalog metadata or no weight → `expectedWarmupSets = 0`, so a row is never falsely marked incomplete.

**Engine parity with the companion (the chips must agree).** The companion's Workout Detail runs the *same* comparison over its own PO engine ([`ProgressiveOverload.compute`](companion/src/main/java/com/example/hevycompanion/recents/ProgressiveOverload.kt)), so the two must resolve the *same* PO target or the chips diverge (watch reading a phantom missing-warmup the phone doesn't). The algorithms are already identical (uniform 1.0 kg increment, same qualifying / recency-weighted-average / carry-forward). Two input/edge alignments close the gap: (1) **`fetchData` force-refreshes history** — it drops the `exerciseHistoryCache` entries for the workout's templates before `WorkoutDataLoader.fetchExerciseHistory`, because that loader short-circuits on populated cache, and a stale cache (viewing an older workout while newer sessions aren't cached) would compute a stale PO target while the phone always fetches fresh; (2) **`computePoTargets` returns `null` when there's no usable normal history** (empty history, or a most-recent session with no normal set), mirroring the companion's `PoOutcome.NONE` — otherwise `computeProgressiveOverload` leaves the routine's placeholder weight on the set and the watch would judge warmups against a phantom target the phone never sees. When both resolve `null` they now agree exactly: `expectedWarmupSetsForCompletion` holds the exercise to its recorded warmups on both apps (no logged-weight recompute on either side). **`canContinue` / Resume is warmup-aware**: offered whenever any in-scope slot isn't fully done — missing normal sets **or** missing advised warmups — so you can resume to log warmups you skipped (`statuses.any { it.status != EXTRA && !it.isComplete }`). Covered by `SubstitutionCompletionTest` + `WorkoutDetailScreenLogicTest`.
- "Resume" button (for incomplete workouts): rebuilds an ActiveWorkout from the routine, pre-filling ALL recorded sets from the original workout (including warmups, dropsets, failure sets, exercise notes — everything from the GET response). Remaining prescribed normal sets are appended as uncompleted, **and the warmup advisor appends any still-missing advised warmups** (for started exercises too) so skipped warmups are loggable on resume. Navigates to LogWorkoutScreen. On Finish: the watch POSTs a **merged** workout to `/v2/workout` (combining the original exercises + biometrics with newly recorded sets + HR samples) and then DELETEs the original via `/workout/{id}`. Falls back to v1 PUT only if the private v2 GET on Continue tap failed (biometrics dropped in that case). See the *Continue Incomplete Workout* feature section above for the full flow + tradeoffs. **Resume is offered whenever a slot isn't fully done** (missing normal sets or missing advised warmups, per `isComplete`); EXTRA rows are bonus work and don't re-trigger the button.

#### Exercise substitution & extras

When a prescribed machine isn't available, the user logs a similar exercise instead (e.g. *Lateral Raise (Dumbbell)* for a prescribed *Lateral Raise (Machine)* slot) and later edits the stored workout to swap it in — deliberately **without** changing the routine, since the next session may be at a gym that *does* have the machine. Plain exact-`exercise_template_id` matching then mislabels the slot as MISSING and hides the swapped-in exercise entirely. [`SubstitutionMap`](app/src/main/java/com/example/hevywatch/data/SubstitutionMap.kt) fixes both:

- **Substitution map**: 15 hand-curated groups of mutually-interchangeable template IDs (derived empirically from the user's logged PO history). The map is the *sole* authority — it intentionally crosses muscle-group boundaries (e.g. the "Unilateral Glutes" group pairs a hamstring hinge with quad lunges), so no anatomical heuristic guards it. Edit the list + rebuild to change it. The **Chest/Bench-Press** group is deliberately *flat*: every horizontal/incline/decline chest- & bench-press variant (machine, Smith, cable, barbell, dumbbell, band — including Iso-Lateral & Incline Chest Press) is mutually interchangeable. *Close-Grip Bench (`35B51B87`) is excluded* — it's a triceps-primary movement, not a chest swap.
- **Matching passes** in `WorkoutDetailViewModel.buildCompletionStatuses(workout, routine, poFolderIds)`: (1) **exact** matches claim their workout exercise first, so an exact match always beats a substitution; (2) a still-MISSING slot whose exercise is in a group claims any *unclaimed* workout exercise from the same group → status **SUBSTITUTED**, the row showing the exercise *actually done* (chip tagged `swap`; `prescribedTitle` still records the slot it filled for sort/Resume logic). A substitute with logged normal sets is preferred, but a **warmup-only in-progress swap still fills the slot** (as an incomplete SUBSTITUTED row) rather than leaving it MISSING and surfacing itself as EXTRA; (3) workout exercises left unclaimed and not themselves a prescribed slot render as status **EXTRA** (chip tagged `extra`). A claimed-set guard prevents one logged exercise from satisfying two slots.
- **Scope**: substitution + extras apply **only** to routines in a Progressive-Overload folder (`ProgressiveOverloadStore.enabledFolderIds`, default `{2525049}`), gated on `routine.folderId`. Outside PO folders the comparison is the plain exact-match behaviour (no substitution, no extras) — pass an empty `poFolderIds` to disable entirely. Covered by `SubstitutionCompletionTest`.

Tap a chip to expand last session's set-by-set breakdown — same content and layout as the tap-to-expand on RoutineDetailScreen (Last session header + an `avg NNkg` working-weight summary + per-set rows of weight × reps × duration × distance, with W/D/F suffix on warmup/dropset/failure indices). The `avg` line is `LastSessionStats.avgNormalWeightKg(...)` — the mean of the last session's **normal** sets (warmup/drop/failure and zero-weight excluded), omitted for bodyweight-only exercises with no weighted normal set. The **same avg line renders on the LogWorkoutScreen chip expansion** while recording/resuming, so the prior working weight is visible in-workout, not just post-workout. Only one exercise can be expanded at a time; tapping another collapses the previous, tapping the same one collapses it. Entries come from `LastSessionStats.latestSessionEntries(...)`, the same helper RoutineDetailScreen and LogWorkoutScreen use; history is fetched on screen entry alongside the workout detail and re-fetched on user-tapped Refresh. Works in both render paths: the routine-comparison path (status chips ordered complete → incomplete → missing) and the no-routine fallback path (raw workout exercises with default chip background).

Refresh chip + `FreshnessLine` at the bottom — same pattern as Routine Detail. Tap re-fetches the workout (`GET /v1/workouts/{id}`), the routine for completion comparison if applicable, and exercise history for the rendered chips; the `RefreshOverlay` covers the still-mounted column for the duration. Stamps `HevyApp.workoutDetailRefreshedAtMs` on success so the freshness line ticks. See *Refresh policy* below for the full pattern.

### Routine List
**Route**: `routine_list/{folderId}`

Routines in the selected folder. Each chip shows routine name + exercise count + last workout date, where the date is rendered in the shared `"MMM d, ''yy"` form (e.g. *May 3, '26*) via `RoutineProgressComputer.parseInstant` + `DateFormatUtils.formatListDate` — the same path Recent (Folder List Page 1) and Progress (Routine Detail Page 1) take, so every workout-date subtitle in the app reads identically. Green "Resume Workout" chip when workout active.

A **"Use for tile"** chip at the bottom (after the Refresh chip) sets `TilePreferenceStore.tileFolderId` to the current folder; the chip flips to "✓ Tile folder" and disables when this folder is already the tile's folder. Hidden in the synthetic Uncategorized folder. The home tile re-renders immediately via `requestTileUpdate()`.

The synthetic folder id `__uncategorized__` is recognised here and filters routines whose `folderId` is null.

### Routine Detail
**Route**: `routine_detail/{routineId}`

Simplified exercise list for the routine, rendered as dark chips. Each exercise shows:
- **Label (line 1)**: exercise name.
- **Secondary label (line 2, onSecondary grey)**: `avg XX.Xkg` — the arithmetic mean of the **normal** sets from the last session (warmups, dropsets, failure sets excluded; null/non-positive weights excluded). The subline is omitted entirely when there's no qualifying data (new exercise, bodyweight-only with zero weight logged, or no session history cached yet). Computation lives in the pure `LastSessionStats.avgNormalWeightKg()` helper.

Tap a chip to expand last session's set-by-set breakdown — only one exercise can be expanded at a time, so tapping another collapses the previous and expands the new one; tapping the expanded chip again collapses it. The latest-session entries come from `LastSessionStats.latestSessionEntries(...)`, which keeps only the rows whose `workoutId` matches the first (newest) entry returned by the API; the same helper feeds LogWorkoutScreen's long-press expansion. Start workout via double-tap on the routine title. "Resume Workout" button shown when a workout is already active. Refresh chip at bottom.

**Progress page (PO routines only)**: For routines in a PO-enabled folder, a second page is available via tappable page indicator dots. Shows routine-level volume progress over the last 3 months:
- "Progress" header with average volume delta subtitle
- Chronological list of workouts (newest first), each showing date and volume delta from the preceding workout
- Green for positive deltas, red for negative, gray "baseline" for the oldest entry
- Volume = sum of (weight_kg x reps) across all sets of all exercises, fetched via `GET /v1/workouts/{workoutId}` for each workout belonging to this routine
- **Assisted-bodyweight routine** (id `1d8f11d4-d533-442e-8d47-780d25d07964`): for sets of the four assisted-machine exercises (chest/tricep dip, pull-up, chin-up — IDs `2C37EC5E`, `4B4BF8C2`, `D23C609B`, `E9E4089F`) the contribution becomes `(bodyweight − weight_kg) × reps`. Bodyweight is read from the Settings screen. Other exercises in the same routine still use the conventional formula.
- Workout IDs are looked up from the locally cached routine-to-workout-ID map (populated during the workout history fetch on RoutineListScreen)
- Non-PO routines show a single page with no pager or dots

### Log Workout (Exercise List)
**Route**: `log_workout`

The main workout screen showing all exercises as chips. Each chip shows exercise name, set counts (orange subline if warmup advisor active), and target weight (green if PO increased it, orange if suggested by warmup advisor, grey otherwise). Tap an exercise to open its first set. Chip background reflects live progress: dark-green when all sets are done, dark-blue when some but not all, default dark before anything's logged. Fallback prompt if private API POST fails. Warmup advisor override prompt for routines with existing warmup sets. Finish Workout and Discard buttons at bottom.

**Long-press a chip to expand last session's set-by-set breakdown** — same content and layout as the tap-to-expand on RoutineDetailScreen (Last session header + per-set rows of weight × reps × duration × distance, with W/D/F suffix on warmup/dropset/failure indices), but the trigger is long-press here so tap stays bound to opening the first set. Only one exercise can be expanded at a time; long-pressing another collapses the previous, long-pressing the same one collapses it. Entries come from `LastSessionStats.latestSessionEntries(hevyApp.exerciseHistoryCache[templateId]?.exerciseHistory)`, the same helper RoutineDetailScreen uses.

Implementation note — long-press has to be detected on the **Initial pointer-event pass**, not the default Main pass. The Wear `Chip` wraps its content in its own `Modifier.clickable`, which consumes the down event on the Main pass for press indication; a sibling `detectTapGestures` (which uses `awaitFirstDown(requireUnconsumed = true)` by default on the Main pass) never wakes up and `onLongPress` silently never fires. The chip's `pointerInput` therefore runs a manual `awaitEachGesture { … }` that calls `awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)` and then `withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(pass = PointerEventPass.Initial) }`. On the timeout exception, it fires haptic feedback + `onLongClick`, then drains and consumes subsequent Initial-pass events until up so the Chip's own clickable sees a cancelled press and skips its `onClick` when the user lifts. Quick taps don't time out and don't consume, so the Chip's `onClick` (= `vm.openSet(exIdx, 0)`) still fires normally.

Accessibility-event note — Compose's `Modifier.clickable` (and every Wear wrapper built on it: `Chip`, `Button`, `CompactButton`) declares its click semantically so TalkBack's `performAction(ACTION_CLICK)` works, but does NOT call `view.sendAccessibilityEvent(TYPE_VIEW_CLICKED)` on plain touch input — the framework only synthesizes that event when an assistive tool invokes the action. External `AccessibilityService`s that filter on `TYPE_VIEW_CLICKED` (e.g. WearControl's SCENE_2 power-profile telemetry sitting on the same watch) therefore see zero events during a Compose-only workout flow. Every tappable in the active-workout screens (`LogWorkoutScreen`, `LogSetScreen`, `RestTimerScreen` — exercise chips, Finish / Discard, set-type cycle, weight / reps ±, weight numpad + Cancel/Confirm, Complete Set, Prev/Next, +1 Set / Done, −15 / +15 / Skip, error-banner dismiss, ConfirmDialog Y/N) is therefore wired through one of two helpers in `ui/components/AccessibleClick.kt`: `observableClick { … }` for `onClick = …` parameters of Wear components, and `Modifier.observableClickable { … }` for raw clickables. Both dispatch `TYPE_VIEW_CLICKED` via `LocalView.current` before invoking the underlying click. The long-press path separately dispatches `TYPE_VIEW_LONG_CLICKED` from inside the chip's `pointerInput` catch block (see preceding note) — because that path consumes the up event on the Initial pass, the Chip's clickable never sees release and even Compose's semantic announcement wouldn't fire. Pinned by `ObservableClickTest` (helper contract) and `ExerciseChipLongPressTest` (long-press contract) — both Robolectric + Compose UI tests.

The screen swallows the back gesture while a workout is active (`BackHandler(enabled = vm.workout != null) {}`) — accidental swipe-back used to pop the screen and leave logged sets behind a routine list. Finish / Discard chips remain the only way out. The "Finish" path also short-circuits with a "log at least one set or discard" message when no sets have been completed; an empty workout would otherwise post a 0-volume entry to Hevy history.

### Chip styling (app-wide convention)

All Wear Compose `Chip`s in the app follow the same rules, defined by `com.example.hevywatch.ui.theme.ChipPalette`:

- **Title** (`label`) — `MaterialTheme.colors.onSurface` (white on the dark scheme, black on the light scheme). State is never encoded in the title color; the title tracks the theme so it reads on both the default chip fill and the status tints (dark tints + white, pale tints + black). The one exception is the green **Resume Workout** chip (`#2E7D32` accent background), whose title stays `Color.White` in both schemes. The **Refresh** action chip (Folder List, Routine List, Routine Detail) additionally center-aligns its label; all other chip titles stay left-aligned.
- **Subtitle** (`secondaryLabel`) — `MaterialTheme.colors.onSecondary` (mid-grey `#8E8E93`) by default, with two semantic overrides:
  - `ChipPalette.PoGreen` (`#4CAF50`) — progressive-overload target weights (weight auto-increased from last session).
  - `ChipPalette.WarmupAmber` (`#FFC107`) — items surfaced by the warmup advisor: injected warmup sets in a set-count line, or a target weight suggested from a similar exercise when no direct history exists. Originally `WarmupOrange` (`#FF9800`); switched to amber when the brand colour was changed to `BrandOrange` (`#FE6A16`) so the advisor accent stays visually distinct from `MaterialTheme.colors.onPrimary`. The `WarmupOrange` symbol is kept as a deprecated alias for source-compatibility.
- **Status background** — chips that convey exercise completedness use a tinted background while the title/subtitle colors stay as above:
  - `ChipPalette.StatusCompleteBg` (dark green tint, `#1B3A1F`) — fully complete: all prescribed normal sets **and** all advised warmups recorded (post-workout, per `isComplete`), or all sets completed (live workout).
  - `ChipPalette.StatusInProgressBg` (dark blue tint, `#1A2F3E`) — partial: some normal sets recorded but not done, or normal sets done with advised warmups still missing, or partial set completion during a live workout.
  - `ChipPalette.StatusMissingBg` (dark red tint, `#3E1A1A`) — nothing recorded yet (a still-missing prescribed slot, or an exercise not started during a live workout).
  - There is **no** separate tint for swaps or extras — both flow through the green/blue/red completion rule and are distinguished by the chip's kind tag (below). `StatusSubstitutedBg` / `StatusExtraBg` are retired from these screens (kept in `ChipPalette` for any future surface).

**Unified exercise chip** (`ExerciseChipUi.kt`): WorkoutDetailScreen and LogWorkoutScreen share one chip contract (`ExerciseChipStats` + `ExerciseChipLabel` + `ExerciseChipStatsRow`) so a chip means the same thing whether you're reviewing a recorded workout or recording/resuming one. The chip *data* (`ChipState` / `ChipKind` / `WeightKind` / `ExerciseChipStats` / `label()`) now lives in `:core` (`com.example.hevycore.chip.ExerciseChipData`) so the companion's Material3 renderer reads from the same shape. Each renderer keeps its own composables per toolkit; the pure data + state machine is regression-pinned by `com.example.hevycore.chip.ExerciseChipDataTest`. (RoutineDetailScreen keeps its own simpler "avg" row.) Each chip shows:
  - **state** via background tint (green/blue/red, above);
  - an optional **kind tag** as dim trailing text on the title — `swap` (a substituted/swapped-in exercise) or `extra` (off-routine bonus work). The original slot name is *not* shown. Live swaps are tagged via `ActiveExercise.wasSwapped` (set on `applySwap` + resumed substitutes); post-workout swaps via the SUBSTITUTED status.
  - **`X/Y W`** — warmups done / total, in `WarmupAmber` (hidden when there are no warmups);
  - **`X/Y N`** — normal sets done / total;
  - the **weight** for the normal sets — `PoGreen` when progressive-overload-bumped, `WarmupAmber` (`~` prefix) when only a similar-exercise estimate, mid-grey otherwise. On WorkoutDetail the weight is the **PO target** until the first normal set is logged (a still-missing slot — computed via `computePoTargets` → `computeProgressiveOverload`), then the **logged working weight**; on LogWorkout it's the live target.

Non-status chips (folder, routine, recent workout, refresh / resume action chips, exercise rows on RoutineDetailScreen) keep the default Wear secondary chip background.

**Screen titles** — the top-of-screen heading and in-screen dialog titles (e.g. "Folders", "Routines", "Progress", "Nice Work!", "Finish workout?", "Discard workout?", "Pause/Resume workout?", "valkyria" on the loading screen, the routine/workout name on their detail screens) always render in `MaterialTheme.colors.onPrimary`. The accent value, the rest-timer indicator, and the launcher icon all share the same colour — see *Theme & colour tokens*. Pairs with the all-white chip titles so titles are visually distinct from list items.

**Prompt conventions**: All confirmation prompts across the app (Finish workout?, Discard workout?, Override prescribed warmups with advisor?, Save via public API?, Start workout?, Resume workout?, Retry unsent workout?) phrase the question so the answer is yes/no, and use single-letter **N** / **Y** buttons (secondary / primary). The only exception is the "Done with this exercise?" prompt on LogSetScreen, which retains its bespoke **+1 Set** / **Done** buttons because the affirmative and negative actions are not symmetric.

### Swap Exercise
**Route**: `swap_exercise`

In-workout substitute picker, reached only via the `Swap exercise? Y` prompt on Log Workout for swap-eligible exercises (PO folder · has substitutes · not yet started). Lists each acceptable substitute from the exercise's `SubstitutionMap` group with its own pre-computed PO target weight (green `↑` PO bump / plain last-session / amber `~` similar-estimate). Tapping a substitute splices it in (warmups + PO weight applied) and navigates straight to Log Set with `popUpTo(log_workout)` so a swipe-back lands on the workout, not the picker. A **Keep prescribed** row logs the original. Shows a spinner while candidate weights are computed. State (which exercise, the candidate list) lives in the activity-scoped `LogWorkoutViewModel`; see feature §5b *In-Workout Exercise Substitution*.

### Log Set
**Route**: `log_set`

The core set logging screen. Shows:
- Exercise name + set position (e.g. "Set 2/4")
- Set type label: "Warmup" (orange), "Normal" (white), "Failure" (red), "Dropset" (purple) — tap to cycle
- Weight picker with subtle dark background, dimmer +/- buttons (24 sp glyph, 16 dp tap padding — bumped in Bucket-G item 35). Tap number to open numpad (pinned display at top, dark 48 dp buttons at 6 dp gaps — bumped in Bucket-G item 35, previously 44 dp @ 4 dp; red backspace, green confirm / red cancel)
- Reps picker with matching style
- Weight/reps numbers colored to match set type; green overrides when PO applied
- "was X kg" reference when PO increased the weight
- Rest countdown shown in the curved TimeText area at the top of the screen while a rest timer is running (rendered as `endLinearContent` / `endCurvedContent` of the system `TimeText` by `WorkoutAwareTimeText`, in `BrandOrange`). Tapping the TimeText strip opens **Workout Control** (pause/resume) — *not* the rest timer. There is no separate "Rest" chip.
- Complete Set button — fires a single short haptic tick the first time a set is marked complete (re-completes after edit stay silent)
- Exercise notes from routine (if present) — shown below Complete Set for machine/equipment reminders
- Set navigation arrows (constrained to the current exercise — arrows disable at the first/last set of that exercise rather than flowing into the neighbouring exercise). The −S / +S add/remove-set buttons and the Finish Workout button were removed: set count is managed implicitly by the "Done with this exercise?" prompt's "+1 Set" branch, and the workout is finished from `LogWorkoutScreen` only.
- **Reps default for normal sets**: when the picker first opens for a normal set with no prior value, reps defaults to **15** (the rep-range top our PO algorithm treats as "qualifying"). Warmup / dropset / failure pickers still fall back to the routine's `repRangeStart` / `repRangeEnd`. The user can decrement freely if they couldn't hit 15.
- **PO normal-set rep floor**: in a PO routine, the normal-set picker also *floors* at 15 — a routine prescribing `rep_range 10–15` (so the active-workout mapper picked `reps = 10`) still opens the picker at 15. Logging 15 reps is what makes a session count as qualifying for the PO target increase, so this saves a few +1 clicks every set. If the routine prescribes more than 15 reps, the higher prescription wins (it's a floor, not a clamp). Non-PO routines keep the existing behaviour. Implemented as the pure `initialRepsForSet(set, isPoRoutine)` helper next to the composable for unit testing — also shared with the rest-timer screen so its next-set preview shows the same rep count the picker will land on (previously it read `set.reps` raw and displayed 10 for the first normal set, then carried each logged rep count forward).
- **Suggested target weight on LogSetScreen**: for a never-worked exercise the suggested target weight (similar-exercise scaling, §5) is pre-populated on the normal-set weight picker and rendered in `WarmupAmber`. This matches the chip subtitle on LogWorkoutScreen and the rest-timer countdown so the same number is the same color across screens, signalling to the user that it's a guess they should verify before logging.
- **Scroll reset** — every time the displayed set changes (Prev/Next, returning from the rest timer after Skip / auto-dismiss, or completing a set that advances the cursor without a rest screen), the screen scrolls back to the top so the user lands on the exercise title rather than wherever they last scrolled.
- **Offline strip** — slim dark-red banner reading "Offline — Finish will queue" appears at the top of LogSet and LogWorkout while no Internet-capable network is available (observed via `ConnectivityManager.registerDefaultNetworkCallback`). Implemented by `OfflineBanner` (defined in `ui/components/ConnectivityBanner.kt`, alongside the sibling `RefreshErrorBanner`); auto-hides as soon as the watch reconnects.

### Rest Timer
**Route**: `rest_timer/{seconds}`

Circular countdown with a `BrandOrange` progress ring (see *Theme & colour tokens*). Center shows time remaining + the next set's preview — formatted as `WkgxR` when both weight and reps are known (e.g. `22.5kg × 12`), or just the weight or just the reps when one is missing. Colour follows the same source rules as the weight picker: green for PO-applied targets, amber for warmup-advisor / similar-exercise suggestions, theme foreground (`MaterialTheme.colors.onSurface` — white on dark, black on light) otherwise. −15/+15 second adjustment buttons. Skip button. Auto-pops back to Log Set when timer expires.

`FLAG_KEEP_SCREEN_ON` and the dim-display policy are now driven app-wide by `WorkoutDisplayController` (see *Workout-active display policy* below), not by Rest Timer. Tapping anywhere on the rest screen counts as a brightness-bump under that policy.

**Haptics**: a short one-shot pulse fires at the three-, two-, and one-second marks, followed by a two-pulse waveform at expiry — so the wrist knows it's time without the user having to look. Driven by `RestTimerViewModel.vibrateShort` / `vibrate`.

### Workout Control
**Route**: `workout_control`

Simple yes/no gateway for pausing or resuming the active workout. Shows workout name. Accessed via stem button 2 from any workout screen. Follows the app-wide N/Y prompt convention:
- Running workout → "Pause workout?" — N closes the prompt without doing anything, Y pauses the workout and returns to the previous screen.
- Paused workout → "Resume workout?" — N closes the prompt without doing anything, Y resumes the workout and returns to the previous screen.

Discarding a workout is reached exclusively via the **Discard** button at the bottom of LogWorkoutScreen, which renders an inline confirm dialog within the same screen (no separate route). It used to be accessible from Workout Control too, but was removed so this screen can follow the consistent N/Y prompt style.

### Discard confirm dialog (inline)

Tapping **Discard** on LogWorkoutScreen flips a local `showDiscardDialog` flag and renders an inline `ConfirmDialog` overlay: title-only "Discard workout?" + N / Y buttons. On Y, clears the active workout and navigates back to Routine Detail if the workout was started from a routine, or to the Folder List if it was ad-hoc.

> A separate `DiscardConfirmScreen` / `discard_confirm` route exists in `MainActivity`'s nav graph but is no longer reached from anywhere in the app — dead code, kept temporarily but slated for removal.

### Congrats
**Route**: `congrats`

"Nice Work!" + workout summary: name, duration, volume (kg), total sets, per-exercise breakdown. Done button returns to Folder List.

**Volume on the assisted-bodyweight routine** (routine id `1d8f11d4-d533-442e-8d47-780d25d07964`): for the four assisted-machine exercises (chest/tricep dip, pull-up, chin-up — IDs `2C37EC5E`, `4B4BF8C2`, `D23C609B`, `E9E4089F`) the contribution to total volume is `(bodyweight − weight_kg) × reps` instead of `weight_kg × reps`. Logged kg there is the stack assistance, so the effective work is the bodyweight portion the lifter actually moved. All other exercises (and all other routines) use the conventional `weight × reps` sum. UI numbers on the per-set last-session breakdown remain the raw logged kg the user entered.

**PR badges**: Each exercise row shows a PR badge (rendered with `PrBadgeFg` on `PrBadgeBg` — see *Theme & colour tokens*) when the just-completed workout set a new personal record for that exercise. Priority: `1RM PR` > `Weight PR` > `Reps PR` (only one badge per exercise, the strongest). A double-pulse haptic (`Haptics.celebrate`) fires once if the workout produced at least one PR. Detection is performed by the pure helpers in `PrDetector.kt` (`historyToBest` builds an `ExerciseBest` snapshot from the pre-workout history cache, `findBestPrInWorkout` scans completed normal sets). Warmup / dropset / failure sets and first-ever sessions of an exercise are ineligible for PR badges. For the four assisted-bodyweight exercises (regardless of routine) the PR comparison is performed in effective-work space so a *lower* logged kg at the same reps correctly counts as a Weight / 1RM PR.

### Settings
**Route**: `settings`

Multi-section settings screen, all sections sharing the same ± row pattern for muscle-memory consistency:

- **Bodyweight (kg)** — used by the assisted-bodyweight computations (warmup advisor, progressive overload, routine progress volume on the assisted routine, PR detection). −/+ buttons step in 0.5kg increments and persist immediately to `BodyweightStore` (`bodyweight_prefs` SharedPreferences). Default 57.0kg, clamped to [30, 200]kg in both the Settings UI and the store setter.
- **Profile** — Birth year (Int, ±1 per tap, default 1990, clamped to [1900, currentYear − 13]) and Sex (`MALE` / `FEMALE` segmented buttons, default `UNSPECIFIED`). Persisted to `UserProfileStore` (`user_profile_prefs`). Read by the Keytel calorie formula on workout submission (see *Heart-rate sampling & biometrics*); UNSPECIFIED → sex-neutral averaging of male+female formulas.
- **Heart rate** — `Off` / `On` toggle. Only renders when the device has the heart-rate sensor (KSW2 yes, KSW1 no). Persists to `UserProfileStore.heartRateEnabled` (default false). Flipping to On from this screen triggers the `BODY_SENSORS` runtime permission prompt; if denied the toggle stays On (user intent) but no samples are collected until next prompt cycle.
- **Display** — Folders on page 1 (±1, default 5, [1, 20]) and Recent workouts on page 2 (±1, default 10, [1, 25]). Persisted to `DisplayLimitsStore` (`display_limits_prefs`). Picking >10 for recents costs `ceil(limit/10)` API calls per refresh — see *Routine Folder List* for the multi-page fetch.
- **Theme** — `Dark` / `Light` segmented buttons (same visual language as the Sex / Heart-rate rows). Persisted to `ThemeSettingsStore` (`theme_prefs`), default `DARK`. Switching takes effect immediately and app-wide — `MainActivity` reads the store's Compose state, so the whole tree recomposes with the new palette on tap. See *Theme & colour tokens* for what flips (greyscale only) and what stays (accents).
- **Brightness** — Default / Max / Idle seconds — see *Brightness* below.

Reached via the **⚙ Settings** chip at the bottom of the Folder List page (the `settings` route is preserved).

A `commit <short-hash>` caption is rendered at the bottom of the screen as a sideloaded-build version indicator — see *Build / commit indicator* below for the post-install ADB stamp flow.

### Workout-active display policy

While `HevyApp.activeWorkout` is non-null **and** `HevyApp.workoutPausedAt == null` (actively logging), a top-level `WorkoutDisplayController` (wired into `MainActivity` around the `SwipeDismissableNavHost`, *outside* `BrightnessCoordinator`) holds `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` so the watch never sleeps mid-set. **Pausing the workout releases the flag** so the OS ambient timeout takes over — a workout paused to answer the phone (or one the user walked away from) no longer silently drains the battery holding the screen on. The gate is a pure predicate `shouldKeepScreenOn(workoutActive, isPaused)` extracted for unit tests. When the workout ends (Finish or Discard → `activeWorkout` becomes null) the controller clears the flag in `onDispose`, so the override never leaks into non-workout screens. Lives in `ui/components/WorkoutDisplayController.kt`.

**Brightness is owned exclusively by `BrightnessCoordinator` (the inner wrapper).** `WorkoutDisplayController` no longer touches `screenBrightness` at all — the two controllers used to both write `window.attributes.screenBrightness` and race each other, which caused the Settings-configured brightness sliders to be silently overridden during active workouts. Now `BrightnessCoordinator` drives the user-tunable default / max / idle-seconds values from `BrightnessSettingsStore` for every Hevy-foreground screen (workout or not), applying the per-cohort rules described in *P7 — App-wide brightness coordinator* below (WARMUP set or warmup→rest-timer = `AlwaysBright`, everything else = `AllowDim` with touch-to-bright + idle-ramp-to-default).

---

## Navigation Flow

```
App Launch → Loading Screen (ModeSelectionScreen)
  |
  |-- active workout in memory ────────────── Log Workout
  |-- persisted workout on disk ──────────── "Resume workout? N / Y" prompt
  |-- unsent request on disk ──────────────── "Retry unsent workout? N / Y" prompt
  |-- tile deep link (navigate_to extra) ──── jumps to that route after activate
  |-- normal ── activate private mode ─────── Routine Folder List
                                                        │
                                ┌───────────────────────┴───────────────────────┐
                                │                                               │
                          [Folders tab]                                   [Recent tab]
                                │                                               │
                          Routine List ─── ⚙ Settings chip ─── Settings   Workout Detail
                                │                                               │
                          Routine Detail                                       Resume
                          │  (PO routines: 2-page pager —                        │
                          │   Exercises | Progress)                              │
                          │                                              Log Workout (PUT)
                          ▼
                      Start / Resume Workout
                                │
                                ▼
                          Log Workout (POST) ─── Discard (inline dialog) ─→ Folder List / Routine Detail
                          │            │
                  tap chip │            │ Finish ─→ Congrats ─→ Done ─→ Folder List
                          ▼
                       Log Set
                       │
                Complete Set
                 │           │
                 ▼           ▼
            Rest Timer    next set ─→ Log Set
                 │
                 ▼
              Log Set
```

**Stem buttons**: Stem button 2 opens **Workout Control** (pause workout? / resume workout? — N/Y) from any workout screen (LogWorkout, LogSet, RestTimer). Stem button 1 is unwired.

**Tile deep linking**: A tap on the home tile fires a `LaunchAction` that starts `MainActivity` with a `navigate_to` string extra. `MainActivity.onCreate` / `onNewIntent` reads it into `tileNavigateTo`, and `ModeSelectionScreen` consumes it after the API activate step — routing straight to `LOG_WORKOUT` (between exercises) or `LOG_SET` (between sets) for an active workout, or to `routine_detail/{routineId}` for a tap on an idle-tile routine row.

**Ambient mode**: `MainActivity` registers an `AmbientModeSupport` callback so the activity stays in the foreground when the display goes ambient (dim) — on wake the user lands back in valkyria rather than on the watch face. The callback intentionally does no extra rendering; whichever theme the user has selected reads correctly under ambient (the OS dims the panel either way).

Green "Resume Workout" chip appears on Folder List, Routine List, and Routine Detail when a workout is active, providing a one-tap path back.

---

## API Integration

| Endpoint | Method | Auth | Purpose |
|---|---|---|---|
| `v1/routine_folders` | GET | Public | Fetch folders |
| `v1/routines?page=N` | GET | Public | Fetch routines |
| `v1/routines/{routineId}` | GET | Public | Single routine detail (for workout completion comparison) |
| `v1/exercise_templates?page=N` | GET | Public | Equipment + muscle group data (LogWorkoutScreen only) |
| `v1/exercise_history/{id}?page=N` | GET | Public | Per-exercise set history (RoutineDetailScreen + LogWorkoutScreen) |
| `v1/workouts?page=N` | GET | Public | Workout history sync |
| `v1/workouts/{workoutId}` | GET | Public | Full workout detail (exercises + sets) for progress |
| `v1/workouts` | POST | Public | Submit workout (v1 fallback) |
| `v1/workouts/{workoutId}` | PUT | Public | Update existing workout (continue incomplete workout) |
| `v1/user/info` | GET | Public | Fetch the authenticated user's profile (currently used to validate the api-key path) |
| `v2/workout` | POST | Private | Submit workout (primary path) |
| `auth/refresh_token` | POST | Private (auth) | Mint a new access/refresh token pair. Called via `HevyApiClient.createAuth()` (X-Api-Key + Hevy-Platform headers, no Bearer). |

**Authentication**: Public API uses an `api-key` header. Private API uses `Authorization: Bearer` + platform-specific headers (`X-Api-Key`, `Hevy-Platform: wearos`). The auth-only client (`createAuth()`) sends just the platform headers without a Bearer — used for the refresh-token call where the existing access token is expired.

**Dual-refresh architecture (and the watch→companion sync path)** — both the watch *and* the companion call `auth/refresh_token` independently against the same Hevy server. The server rotates the refresh token on every successful call: the old RT is retired and a new one is returned in the response. Without a way to share the rotated RT, whichever side refreshed last is the only side holding a valid RT, and the other side's next refresh attempt comes back 401. To prevent that drift, after every successful **watch-initiated** refresh the watch pushes the fresh `(access_token, refresh_token, expires_at)` triple back to the companion via the Wearable MessageAPI on `/tokens_from_watch`. The companion's `WatchBridgeService` listens for that path, parses + validates the payload, and writes the new triple into `AuthPrefs` with `markRefreshSuccess(now)` — which also clears any stale "Sign in again" error stamp on the home-screen widget. See [companion's `TokensFromWatchHandler`](companion/src/main/java/com/example/hevycompanion/wear/TokensFromWatchHandler.kt) for the parse/store contract.

The opposite direction (companion-→-watch) already existed via `/auth_tokens`. With both directions implemented, the two sides stay in sync regardless of which one initiates a refresh.

**Token-refresh trigger paths** — `auth/refresh_token` is hit from two places, both in `HevyApp`:
- `activatePrivateMode()` (called on every cold start by ModeSelectionScreen): if no tokens are stored yet, asks the companion via `/request_auth` and waits up to 15s for them to arrive. Once tokens are present, refreshes only when the access token is expired or expiring within 60s; otherwise just wires up the private service and continues. On a successful refresh, pushes the new triple to the companion via `CompanionTokenSender.push(...)`.
- `refreshTokenIfNeeded()` (called proactively before private-API writes): same 60s "expiring soon" window, but additionally throttled to one refresh per 20s and wrapped in a mutex so concurrent callers serialise. On a successful refresh, also pushes the new triple to the companion. On *failure* (typically a refresh-token rotation that already invalidated the watch's stored token — for instance because the companion refreshed it first) it re-asks the companion via `/request_auth`, waits 15s, and re-throws if no fresh tokens arrive — surfacing a `Token refresh failed — open companion to re-auth` message via the `tokenRefreshError` banner.

**Watch-→-companion sync error surface** — `CompanionTokenSender.push(...)` returns one of `Pushed(nodeCount)` / `NoCompanionConnected` / `Failed(message)`. `HevyApp.pushTokensToCompanion(...)` consumes the result and updates a separate mutable state field, `companionSyncError: String?`, with one of:
- `"Companion unreachable — open it to sync"` — `NoCompanionConnected` (phone off, BT off, never paired).
- `"Companion sync failed: <message>"` — `Failed` (MessageAPI timeout, transport exception).
- `null` (cleared) — on next successful push.

`companionSyncError` is **independent** of `tokenRefreshError`: the watch's own tokens are valid in this state and every API call from the watch keeps working. The only thing that's stale is the companion's stored RT, which will manifest as a 401 on the next companion-initiated refresh until the user opens the companion (which triggers a fresh `/request_auth` flow) or the next watch refresh succeeds in pushing. Surfaced via [`CompanionSyncBanner`](app/src/main/java/com/example/hevywatch/ui/components/ConnectivityBanner.kt) — a muted blue strip sibling to `RefreshErrorBanner`, mounted on `LogSetScreen` and `LogWorkoutScreen`.

**Read-endpoint retries**: All read paths (folders, routines, workouts list, workout detail, exercise history, exercise templates) are wrapped in `withNetworkRetry` — up to 3 attempts with two between-attempt delays of 500 ms and 1.5 s (worst-case ~2 s before the error surfaces). `CancellationException` is re-thrown immediately. Writes are **not** retried here; workout saves use `PendingRequestStore` instead (idempotent by workout id, user-prompted on next launch).

**Workout save flow (new workouts)**: Check connectivity → persist request body to PendingRequestStore → proactive token refresh → try private API POST → on 2XX, clear PendingRequestStore → on failure, classify the error:
- **Auth (401/403)**: inline message asking the user to refresh tokens via the companion app.
- **Network (IOException)**: inline retry message.
- **Server (other HTTP error) or unknown**: prompt "Save via public API?" → on confirm, try the public API POST with the same error classification.

A short double-pulse haptic (`Haptics.error`) fires on every save-error path (connectivity precheck failure, private API exception, public API fallback exception) so the user feels the failure immediately even when looking away from the watch.

**Workout save flow (continued workouts — PUT)**: Check connectivity → persist request body to PendingRequestStore → PUT /v1/workouts/{id} via public API (no private API, no token refresh needed) → on 2XX, clear PendingRequestStore → on failure:
- **Auth/Network**: inline retry message ("Tap Finish to retry").
- **Server/Unknown**: inline error message with retry (no private→public fallback since PUT is already public).

The PendingRequestStore ensures the serialized request body survives app crashes and network failures. On next app launch, if a pending request exists, the user is prompted to retry or discard.

---

## Refresh policy

Every screen with a Refresh chip — Folder List Page 0 (Folders), Folder List Page 1 (Recent), Routine List, Routine Detail (Exercises and Progress pages), Workout Detail — follows the same pattern:

1. **Tap → full-screen spinner overlay.** Tapping the chip layers a `RefreshOverlay` (full-bleed black `Box` with a centered `RotatingLogo` — the valkyria icon rotated clockwise; see *Launcher icon → Loading spinner* — pointer events absorbed) on top of the page's `ScalingLazyColumn`. The column itself **stays mounted** underneath — the overlay just hides it. There is no half-state where the user sees stale data alongside a refresh affordance.
2. **The column stays mounted on purpose.** Unmounting it during refresh (early-returning past the column) detaches `ScalingLazyListState` from any laid-out items, so the post-refresh `animateScrollToItem(0)` call has nothing to scroll until a future layout pass — by which point the call has already no-op'd. Keeping the column mounted means the listState is always attached, so the scroll-to-top fires reliably. It also keeps in-composable state (`currentPage` on paged screens, `expandedExerciseId`, etc.) alive across the refresh — early-return would lose any `remember` slot allocated *after* the return point.
3. **The data arrays stay populated on purpose.** The view-models do **not** blank `folders` / `routines` / `recentWorkouts` mid-refresh. They re-assign atomically when the fetch returns. This is why an empty-state branch like `folders.isEmpty()` does not flash the "No folders found" message during a refresh — there's nothing empty to flash. Disk caches *are* replaced on success (the setter on `cachedFolders` / `cachedRoutines` writes through). Per-exercise `exerciseHistoryCache` entries *are* dropped at the start of `RoutineDetailViewModel.refresh` so `WorkoutDataLoader.fetchExerciseHistory` doesn't short-circuit on the cached value.
4. **Spinner ends on the first 2XX.** For multi-endpoint refreshes (Folders Page 0 fetches both `/v1/routine_folders` and `/v1/routines`) the overlay hides as soon as the *primary* endpoint returns. Any secondary endpoint runs in the background after the spinner is gone and updates derived UI (e.g. folder-chip subtitle counts) reactively.
5. **Scroll to top.** A `LaunchedEffect` per page observes the page's `isRefreshing*` state via `snapshotFlow`, with `drop(1)` to skip the initial-composition emission, and `animateScrollToItem(0)`s on every `true → false` transition. **Subtle gotcha**: when `isRefreshing*` is passed into a sub-composable as a `Boolean` parameter (rather than read off a stable view-model reference), `snapshotFlow` captures the value *once* at coroutine launch and never re-evaluates. The pattern there is `rememberUpdatedState(isRefreshing).value` so the flow tracks a real `State<Boolean>` that's updated on each recomposition. RecentWorkoutsPage uses this; FoldersPage / ExercisesPage / ProgressPage / RoutineListScreen all read `viewModel.isRefreshing*` directly through their stable view-model parameter and don't need the wrapper.
6. **Freshness indicator.** Each screen with a Refresh chip renders a single `FreshnessLine` directly below the chip — the bare relative time, e.g. `"just now"` / `"5 mins ago"` / `"2 hrs ago"` / `"yesterday"` / `"3 days ago"` — re-evaluated every 30 s so it ticks live without a re-navigation. Sourced from per-screen `*RefreshedAtMs` timestamps on `HevyApp` (`foldersRefreshedAtMs`, `recentRefreshedAtMs`, `routinesRefreshedAtMs`, `routineDetailRefreshedAtMs`, `workoutDetailRefreshedAtMs`). The `FreshnessLine` signature is `vararg refreshedAtMs: Long` because some screens paint from more than one source: Folders Page 0 passes `foldersRefreshedAtMs + routinesRefreshedAtMs` (folder counts are derived from the routines list); Routine List passes `routinesRefreshedAtMs + recentRefreshedAtMs` (the per-routine last-worked subtitle is derived from the workout-history cache). When the supplied stamps disagree by more than `DIVERGENCE_TOLERANCE_MS` (60 s) the caption renders `"various"` rather than a single time, so the user isn't misled into thinking the whole screen is from one fetch. Before any source has refreshed in this session the line shows `"—"`.
7. **Routine Detail's `currentPage` is hoisted above any early-return.** When the screen has nothing to render yet (no cached routine on first ever load), the early-return spinner branch is reached *before* the `if (showProgress)` block. If `currentPage` is declared inside that block, the slot for `remember { mutableIntStateOf(0) }` never gets allocated on the early-return pass and is initialized fresh next time — silently dropping the user's page choice back to 0. Hoisting it to the top of the composable allocates the slot on first composition and lets it survive every later early-return.
8. **Routine Detail and Workout Detail item rows subscribe to the refresh timestamp.** `hevyApp.exerciseHistoryCache` is a plain `MutableMap`, not Compose state. Reads from it inside `items(...) { ... }` don't subscribe, so a refresh that mutates the map doesn't recompose the visible rows — they kept showing stale "Last session" data until the user scrolled them off-screen and back. Solving this without making the map observable: `val refreshKey = hevyApp.routineDetailRefreshedAtMs` (or `hevyApp.workoutDetailRefreshedAtMs` on Workout Detail) *inside* the `items { }` block subscribes the row to a state that *does* update on refresh, and `remember(exerciseTemplateId, refreshKey) { … }` re-derives the lookup whenever the key changes.

The `FreshnessLine` is the **only** staleness signal in the app. The previous markers — `"From cache — pull to refresh"` / `"Updated …"` caption above the folders list, the title-bar `"↻ Syncing…"` swap on Folder List Page 0 and Page 1, the `"↻ Syncing routines…"` line under the Folders title, the `"↻ Syncing history…"` line on Routine List, the inline chip spinner — were all removed in favour of this single uniform pattern. The earlier `"Updated …"` prefix on the line itself was dropped too — the bare relative time scans faster when the same caption appears on every Refresh chip. Lives in `ui/components/FreshnessLine.kt`. The shared overlay lives in `ui/components/RefreshOverlay.kt`.

The `RoutineDetailViewModel.refresh()` always force-refetches the routine list (no age-based gate); the user explicitly tapped Refresh, so the cost of paginating routines is intentional.

---

## In-memory caches and prefetch

Caches live on `HevyApp`. Some are session-scoped; others back a disk store and survive process death.

| Cache | Key | Populated by | Invalidated |
|---|---|---|---|
| `exerciseHistoryCache` | exerciseTemplateId | `fetchExerciseHistory` (RoutineDetailViewModel, LogWorkoutViewModel) | Per-exercise on user-initiated `refresh()`; on successful workout save for every exercise in the just-completed workout; process death. **24-hour freshness gate** (Bucket-C item 16): `fetchExerciseHistory` treats a cached entry older than `HevyApp.EXERCISE_HISTORY_TTL_MS` as a cache miss and refetches. Timestamps live in the parallel `exerciseHistoryFetchedAtMs` map; direct readers (long-press expand, similar-exercise suggestions) still see the stale value until the fetch below refreshes it. |
| `exerciseBestWeights` | exerciseTemplateId | `fetchExerciseHistory` | Invalidated alongside `exerciseHistoryCache` |
| `exerciseEquipment` / `exerciseMuscleGroup` | exerciseTemplateId | `fetchExerciseTemplates` (lazy, per-workout) *and* `warmExerciseTemplateCache` (eager, on app start) | **Persisted via `ExerciseTemplateStore` with a 7-day TTL** — loaded from disk on `HevyApp.onCreate` so cold starts skip the paginated fetch when fresh. Process death without a stale snapshot triggers a warm-up refetch on next launch. |
| `cachedFolders` | — | `RoutineFolderListViewModel.loadFolders` | **Persisted via `FolderCacheStore`** — Folders tab paints from disk instantly on relaunch. Refreshed by the Folders Refresh chip. |
| `cachedRoutines` | — | `RoutineListViewModel.loadRoutines`, `RoutineDetailViewModel.refresh` | Persisted via `RoutineCacheStore`. Cleared and re-fetched whenever the user taps a Refresh chip on Folder List Page 0, Routine List, or Routine Detail (no age-based gate — explicit tap always wins). |
| `cachedWorkoutsPage1` | — | First call to `cachedWorkoutsPage1OrFetch` | In-memory only, **5-minute TTL**, mutex-protected. Shared between `RoutineListViewModel.populateLastWorkoutDates` and `RoutineFolderListViewModel.loadRecentWorkouts` so the two don't double-fetch GET /v1/workouts?page=1 when the user lands on the folder screen. Invalidated on workout save and by `refreshWorkoutHistory()`. |

**Eager prefetch on app start**: `HevyApp.onCreate` kicks off `WorkoutDataLoader.warmExerciseTemplateCache` in a `Dispatchers.IO` + `SupervisorJob` background coroutine **only when the disk snapshot is missing/stale** (i.e. `exerciseEquipment` is still empty after the disk load). The call is a no-op if already populated; failures are absorbed — the lazy path in `fetchExerciseTemplates` still runs if the warm-up didn't complete. **Bucket-C item 10**: the warm coroutine now sleeps for `WARM_CACHE_STARTUP_DELAY_MS` (3 s) before fetching, then re-checks the cache — so the first-frame paint doesn't compete with the paginated JSON parse on the Snapdragon Wear 2100.

**Post-save invalidation**: After a workout saves successfully (`recordAndNavigateCongrats`):
- Entries in `exerciseHistoryCache` and `exerciseBestWeights` are removed for every exercise in the completed workout.
- `routineLastWorkoutAt[routineId]` is **advanced forward only** — the candidate is the workout's start_time (the original server-side `start_time` for continuing workouts, `w.startTimeMs` for fresh workouts). Rule: update only if `newStartIso > existing`. This preserves the server's `created_at` when resuming a days-old incomplete workout (the session is conceptually days ago, not today) and prevents a resumed older workout from overwriting a more recent completion of the same routine. It also keeps this map consistent with `populateLastWorkoutDates`, which reads `w.startTime` from the API.
- For continuing workouts (PUT), the workout id is appended to `routineWorkoutIds` (and persisted) via `HevyApp.appendWorkoutToRoutineHistory`.
- `cachedWorkoutsPage1` is invalidated.
- `workoutHistoryPopulatedAtMs` is reset to `0` so the 12-hour throttle doesn't hide the just-saved workout from the Progress tab on the next Routine List navigation.

**Workout-history populate throttle**: `RoutineListViewModel.populateLastWorkoutDates` records the last successful run on `HevyApp.workoutHistoryPopulatedAtMs`. Subsequent VM inits skip the populate if it ran within the last **12 hours** — tuned for the one-workout-per-day cadence, so the only case it protects against is repeated folder navigation within a day. The throttle is bypassed on workout save (see above), so just-finished sessions always appear in Progress / Recent immediately.

**Incremental workout-history page cap**: When a full fetch isn't due (30-day TTL intact), `populateLastWorkoutDates` only fetches the newest **3 pages** of GET /v1/workouts (≈30 newest workouts). Workouts are returned newest-first so this reliably catches anything saved since the last sync without paying for hundreds of old pages.

**Server-fetch reconciliation (incremental)**: For each routine that appears in the freshly fetched pages, the cached "last performed" date is **overwritten** with the newest `start_time` found there — not max-merged. This prevents a stale local optimistic write (e.g. a watch-only test save whose backing server workout was later deleted) from winning forever against the real server state. Routines absent from the fetched pages keep their cached date, since their true latest workout may be older than ~30 newest workouts and we can't verify it without paying for a full fetch. The local optimistic write on save (`LogWorkoutViewModel.recordAndNavigateCongrats`) keeps its forward-only `max(existing, newStart)` rule — a deliberately different policy that protects against resuming an old incomplete workout regressing the date.

**Cold-start visibility**: On a fresh install every primary cache is empty. Folder-chip subtitle counts and "last worked on" dates simply stay blank until the relevant background fetch lands; the previous "↻ Syncing routines…" / "↻ Syncing history…" lines were removed when the *Refresh policy* was unified — the only staleness signal anywhere in the app is now the per-screen `FreshnessLine` (`"5 mins ago"` / `"—"`).

---

## Companion seed (fresh-install fast-path)

On a sideloaded `adb uninstall` + install the watch's SharedPreferences are wiped, so every cache starts empty. To avoid a full paginated cold-start fetch whenever the dev reinstalls (or a user re-flashes the watch), the companion phone app holds a blob-stored snapshot of the watch's caches. The watch pushes its current caches to the companion after every successful fetch, and requests the last stored snapshot on cold start.

**Protocol (Wearable MessageClient)**:
| Path | Direction | Payload |
|---|---|---|
| `/watch_snapshot` | watch → phone | JSON of `WatchSnapshot` |
| `/request_seed` | watch → phone | empty |
| `/watch_seed` | phone → watch | last-stored snapshot bytes (empty if companion has none) |

**Payload** (`com.example.hevywatch.wear.WatchSnapshot`): folders, routines, `routineLastWorkoutAt`, `routineWorkoutIds`, plus a `snapshotVersion` (currently `1`) — mismatched versions are ignored on the watch side.

**Push triggers** (`HevyApp.pushSnapshotToCompanion`):
- `RoutineFolderListViewModel.loadFolders` success
- `RoutineFolderListViewModel.syncRoutineCounts` (background fetch) success
- `RoutineListViewModel.loadRoutines` success
- `RoutineListViewModel.populateLastWorkoutDates` success

The companion is a **dumb blob store** — it never parses the JSON. All serialization lives in `WatchSnapshotSender` on the watch; the receiver (`PhoneAuthService`) Gson-decodes it and calls `HevyApp.applySeed`, which only populates caches that are still empty so it never clobbers user-produced data.

**Seed-request trigger**: `HevyApp.onCreate` calls `WatchSnapshotSender.requestSeed` if (and only if) all three primary caches are empty — i.e. the app has no disk-persisted folders, routines, or last-workout map. On warm starts this is a no-op.

**Limitations**:
- Wearable MessageClient payloads are soft-capped around ~100 KB; very heavy users (many dozens of routines) may see the push fail. The fallback is the normal API path.
- If both watch and companion are freshly installed, the companion has nothing to send back. Normal API fetches still populate everything within a few seconds.

---

## Data Stores (SharedPreferences)

| Store | Purpose |
|---|---|
| **AuthStore** | API key, access/refresh tokens, expiry. Backed by `EncryptedSharedPreferences` (`hevy_auth_enc`) with one-shot migration from the legacy plaintext file (`hevy_auth`). Falls back to plaintext if the device's keystore is in a bad state. Excluded from auto-backup and device-transfer via `backup_rules.xml` / `data_extraction_rules.xml`. |
| **ActiveWorkoutStore** | Serialized workout JSON for crash recovery. A `ThrottledSaver` issues a `saveBlocking()` at most once every 2 s while a mutation has occurred since the previous tick (idle workouts produce zero I/O). The two explicit `saveBlocking()` checkpoints — **set completion** and **pause** — bypass the throttle so a kill immediately after either is safe. Workouts older than 24h on the next launch are auto-discarded. |
| **WorkoutHistoryStore** | Last workout date per routine, workout IDs per routine, last full fetch timestamp |
| **RoutineCacheStore** | Serialized routine list for tile persistence |
| **FolderCacheStore** | Serialized folder list — lets the Folders tab paint instantly on relaunch |
| **ExerciseTemplateStore** | Serialized `equipment` + `muscle_group` maps per exerciseTemplateId with a 7-day TTL — skips the paginated `/exercise_templates` warm-up on subsequent cold starts |
| **ProgressiveOverloadStore** | Set of PO-enabled folder IDs |
| **TilePreferenceStore** | User-chosen folder id whose routines populate the idle tile. Defaults to `2525049`; configurable from any routine list via the "Use for tile" chip. |
| **PendingRequestStore** | Serialized POST/PUT request body, persisted before sending (synchronous `commit()` so the disk write is durable before the network send), cleared after 2XX — ensures workout data is never lost even if the network fails or the watch dies mid-flight. Also excluded from auto-backup. |
| **BodyweightStore** | User bodyweight in kg (`bodyweight_prefs`). Default 57.0kg, clamped to [30, 200]. Read by warmup advisor, progressive overload, routine progress volume, and PR detection for the four counter-weight assisted exercises. Edited via the Settings screen. |
| **UserProfileStore** | User demographics + HR opt-in (`user_profile_prefs`). `birthYear: Int` default 1990, clamped to [1900, currentYear − 13]. `sex: Sex` enum (`MALE` / `FEMALE` / `UNSPECIFIED`), default `UNSPECIFIED` (KeytelCalories falls back to a sex-neutral average). `heartRateEnabled: Boolean` default false — user opt-in gate for per-minute HR sampling on fresh workouts. All three are read by [`BiometricsBuilder`](app/src/main/java/com/example/hevywatch/sensors/BiometricsBuilder.kt) on workout submission. |
| **DisplayLimitsStore** | User-tunable list caps for the Routine Folder List screen (`display_limits_prefs`). `folderListLimit: Int` default 5, clamped to [1, 20]. `recentWorkoutsLimit: Int` default 10, clamped to [1, 25]. Read by `RoutineFolderListScreen` (folder cap) and `RoutineFolderListViewModel.fetchRecents` (multi-page recents fetch when > 10). Editable via Settings → Display. |
| **ThemeSettingsStore** | Chosen colour scheme (`theme_prefs`). `themeMode: ThemeMode` (`DARK` / `LIGHT`), default `DARK`, persisted as the enum name and parsed back via `ThemeMode.fromName` (unknown/absent → `DARK`). Exposed as Compose state; read by `MainActivity` to pick the palette in `HevyWatchTheme`. Editable via Settings → Theme. See *Theme & colour tokens*. |

## Tile

The home tile shows the routines from `TilePreferenceStore.tileFolderId` (default `2525049`) when no workout is active, and a dual-ring active-workout view otherwise. The pure layout-decision logic lives in `tile/TileStateComputer.kt` (and is unit-tested in `TileStateComputerTest`). The service caches the rendered `LayoutElement` keyed by an input fingerprint so swipe-back / swipe-to renders skip the rebuild when state hasn't changed.

## Networking

All three `HevyApiClient` create paths (`create` for the api-key client, `createAuth` for the refresh-token client, `createPrivate` for the Bearer client) configure OkHttp with an explicit 10s connect / 15s read+write timeout — Wear OS BT-tethered networks otherwise stall the UI for the OkHttp default of 60s per phase. `HttpLoggingInterceptor` is installed at `BASIC` in debug builds and `NONE` in release (gated by `BuildConfig.DEBUG`) on `create` and `createAuth`. `createPrivate` deliberately omits the logger so Bearer tokens are never written to logcat.

`withNetworkRetry` (the read-side retry helper) defaults to skipping 401/403 — a stale token will not fix itself by waiting, and burning two extra round-trips before falling through to the refresh-token path is wasted battery. Server errors and IO errors still get the original exponential 500ms→1.5s→4.5s backoff, **capped at 30s** (`NETWORK_RETRY_MAX_DELAY_MS`) so a tweaked factor or larger attempts count can never wedge the UI for minutes — failing fast and surfacing a refresh banner is the better wear-OS UX than a multi-minute spinner.

`WorkoutDataLoader` no longer swallows fetch errors silently — the three `try { … } catch (_: Exception)` blocks (history fetch, template fetch, warm-cache) now log a `Log.w(…, e)` so a failed prefetch is visible in logcat instead of disappearing. Behaviour is otherwise unchanged: failures stay non-fatal because the data they fetch is best-effort hint material.

## Token-refresh error surface

A failed token refresh now sets `HevyApp.tokenRefreshError`. The amber `RefreshErrorBanner` (sibling to `OfflineBanner`) appears on `LogWorkoutScreen` and `LogSetScreen` so the user finds out before their next Finish tap fails. The banner clears automatically when the next refresh succeeds or the companion pushes fresh tokens.

---

## Error Handling

Every user-visible error string in the watch app, where it appears, and what triggers it. Strings that include a placeholder are written `… <var>` for clarity.

### Persistent banners on workout screens (`LogWorkoutScreen`, `LogSetScreen`)
Slim coloured strips at the top of the list, both rendered as items above the chips so they don't get cut off by Wear's bezel.

Banner colours are theme-dependent (dark fill + pale text on dark; pale fill + dark text on light) — see the *Connectivity / alert banners* table under *Theme & colour tokens* for both schemes; the dark-scheme values are shown below for reference.

| Banner | Color (dark scheme) | Trigger |
|---|---|---|
| `Offline — Finish will queue` | dark red `#5A1A1A` / text `#FFD2D2` | `OfflineBanner` — `ConnectivityManager` reports no network with `NET_CAPABILITY_INTERNET`. The Finish tap will fail the connectivity precheck. |
| `Token refresh failed — open companion to re-auth` | amber `#5A3A1A` / text `#FFE4B5` | `RefreshErrorBanner` — `HevyApp.refreshAccessToken()` failed AND the 15 s wait for fresh tokens from the phone (`requestAuthFromPhone()` + `authTokensReceived.first()`) timed out. Cleared automatically when the next refresh succeeds or the companion pushes new tokens via `/auth_tokens`. |
| `Companion unreachable — open it to sync` / `Companion sync failed: <message>` | muted blue `#1A3A5A` / text `#B5D4FF` | `CompanionSyncBanner` — the watch's own refresh succeeded but `CompanionTokenSender.push(...)` returned `NoCompanionConnected` or `Failed`. The watch keeps working; only the home-screen widget on the phone is temporarily out of sync. Cleared on the next successful push. See *Dual-refresh architecture* under §Networking. |

### Finish-workout errors (`LogWorkoutViewModel.saveError` / `fallbackError`)
Shown inline below the chip column on `LogWorkoutScreen`. Triggered by `classifyFinishError(e)` after a Finish tap fails.

| Message | Trigger |
|---|---|
| `No completed sets — log at least one set or discard the workout.` | Finish tap with zero completed sets across all exercises (the empty-workout guard) — saving would pollute Hevy history with a 0-volume entry. |
| `No connection — connect to phone or WiFi before saving.` | Connectivity precheck (`ConnectivityManager.activeNetwork.NET_CAPABILITY_INTERNET`) returned false at the moment of Finish. Distinct from the persistent `OfflineBanner` — this one is a one-shot error written into `saveError` only when the user actually tries to save. |
| `Authentication failed — open the companion app to refresh tokens, then try again.` | `FinishError.AuthFailed` on a fresh-workout save — the watch's access token is stale and the watch's own refresh attempt also failed (companion-side rotation has not reached the watch yet). |
| `Authentication failed — check your API key and try again.` | Same `FinishError.AuthFailed` but during a *continuing* workout save (PUT). Continuing workouts use the public-API `api-key`, not the bearer, so the user-facing remediation is different. |
| `Network error — check your connection and tap Finish again.` | `FinishError.NetworkError` — OkHttp threw before getting an HTTP status (transient connectivity drop, BT ACL teardown, TLS reset). |
| `Save failed (<code>). Tap Finish to retry.` | `FinishError.ServerError` or `Unknown` on a *continuing* workout (PUT) — public API already, no further fallback to offer. |
| `Private API failed (<code>). Save via public API instead?` | `FinishError.ServerError` on a *fresh* workout. Drives the `fallbackError` confirm dialog with title set to this string and an "Use public API" / "Cancel" pair. Most common code is 5xx — the private API service is sometimes degraded while the public one stays up. |
| `Private API error. Save via public API instead?` | `FinishError.Unknown` on a *fresh* workout — same fallback dialog path as above. |

### Routine list / folder list (`RoutineListViewModel.error`, `RoutineFolderListViewModel.error`)
Rendered as the empty-state body when the list is null and an error is set. Has a "Retry" button below.

| Message | Trigger |
|---|---|
| `Failed to load routines` (or the OkHttp exception text if richer) | `GET /v1/routines` failed during list load. |
| `Failed to load folders` (or the OkHttp exception text) | `GET /v1/routine_folders` failed during folder load. |

### Mode selection screen (`ModeSelectionScreen`)
The first screen shown to a fresh-installed watch app. Errors render in `MaterialTheme.colors.error` with a Retry button.

| Message | Trigger |
|---|---|
| `Turn on Bluetooth` | `wear/PhoneLink.currentState()` returned `BluetoothOff` — the local `BluetoothAdapter` is missing or `.isEnabled == false`. The first API call is hard-gated on Bluetooth being ON, so the watch shows this message and polls every 1 s; as soon as the user turns the radio on, the screen auto-advances. |
| `Connect your phone` | `wear/PhoneLink.currentState()` returned `PhoneNotConnected` — Bluetooth is ON but no Wear `Node` with `isNearby == true` is reachable. Same poll-and-advance behaviour as the Bluetooth-off case. |
| `Connection failed` (or the underlying exception text) | The watch's bridge handshake to the phone (`/check_connection` ping) timed out or the phone reported failure. Most common cause: companion app not installed / not running, or Bluetooth not paired. |

#### First-API-call gate (`wear/PhoneLink`)
`HevyApp.activatePrivateMode()` (the first HTTP call on launch: POST `/auth/refresh_token`) and the pending-retry path (`retrySendPending` in `ModeSelectionScreen.kt`) are both gated on `PhoneLink.currentState()` returning `Ready`. **Ready** means:

1. `BluetoothAdapter.getDefaultAdapter()?.isEnabled == true`, AND
2. `Wearable.getNodeClient(ctx).connectedNodes` contains at least one node with `Node.isNearby == true` (directly reachable, not via cloud sync).

`activatePrivateMode()` calls `PhoneLink.awaitReady(this)` and suspends until both conditions hold — the user's tap on Retry / pending-Y eventually proceeds without further interaction once the radio is back. The `ModeSelectionScreen` runs an independent 1 Hz poll so the user sees the right copy while that suspend is parked. The pending-retry path uses a single-shot `requirePhoneLinked()` throw (not the polling `awaitReady`) so the existing `pendingSendError` surface stays the primary feedback channel for that branch. Requires `android.permission.BLUETOOTH` in the manifest (normal permission, auto-granted on API 28).

### What you do NOT see as a watch UI error
- A failed `/auth_tokens` push from the phone is silent on the watch — the watch only finds out indirectly via a subsequent 401 on a real API call, which then surfaces as `Authentication failed …` via the Finish path. The phone-side widget is the canonical surface for refresh failures.
- A failed `/tokens_from_watch` push **the other direction** (watch refreshed OK but couldn't tell the companion) is NOT silent: `CompanionSyncBanner` surfaces it on `LogSetScreen` and `LogWorkoutScreen` so the user knows the home-screen widget will be temporarily out of sync until they open the companion or the next push succeeds.
- A `PhoneAuthDispatcher` rejection (untrusted node id) logs an `Log.e` and silently drops the message — the watch shows no banner. This is by design: the user sees nothing happen rather than a confusing error from a sender they don't recognize, and the trusted phone's next push will succeed.
- A successful `/v1/workouts` POST followed by Wear MessageAPI failure to clear the in-memory workout is logged but not surfaced; the next Finish tap will idempotently re-POST against the same UUID.

### Sources of truth
- All Finish-path strings live in `LogWorkoutViewModel.saveError` and `fallbackError` (mutable Compose state) and are written exclusively from the `try { saveWorkout(...) } catch` block. The string-to-trigger mapping is pinned by `LogWorkoutViewModel`-adjacent tests under `app/src/test`.
- `tokenRefreshError` is set only by `HevyApp.refreshAccessToken()` after the phone-fallback timeout; it is cleared on every successful token install (refresh or `/auth_tokens` push from the companion).

## Rest timer

The rest timer references `SystemClock.elapsedRealtime()` (monotonic since boot) rather than `System.currentTimeMillis()` — the timer is now immune to NTP corrections, timezone changes, and DST shifts mid-rest. The watch sleeping/waking is still handled correctly because the loop catches up on the next iteration.

### Countdown display sync (screen ↔ in-clock)

Two countdowns read the same `restTimerEndMs`: the big `RestTimerScreen` ring and the small in-clock readout to the right of the system clock (`WorkoutAwareTimeText`). They must always show the same number. Two things keep them in lockstep:

- **Shared rounding (`restRemainingSeconds`)** — both call `restRemainingSeconds(endMs, nowMs)` in `RestTimerLogic.kt`, which rounds **up** (`ceil`). A countdown should display "1" for the whole final second and only read "0" once it is genuinely up. The in-clock readout previously used plain integer division (floor), so it read 1s *lower* than the screen for the entire rest and showed "0:00" while a full second was still running. Pinned by `RestTimerLogicTest`.
- **Boundary-aligned tick** — `WorkoutAwareTimeText` re-ticks at the next whole-second boundary (`remMs % 1000`, +20 ms cushion) instead of a fixed 1 s cadence. A ±15 s tap shifts `endMs` to an arbitrary sub-second phase; a fixed-cadence loop would then flip the in-clock value up to ~1 s out of step with the screen. The screen itself keeps its fast 250/500 ms tick (it also drives the progress ring and the final-seconds haptic), so it catches each boundary within ≤500 ms — close enough that the two read identically on a wrist glance.

### Rest duration policy (`computeRestSecondsForSet`)

Pure logic in `presentation/workout/RestTimerLogic.kt`, fully unit-tested:

- **Bilateral (odd normal-set count)** — warmup→warmup: 45s, last warmup→first normal: 60s, normal→normal: routine default.
- **Unilateral (even normal-set count, ≥ 6 normal sets)** — both warmups and normals alternate:
  - Odd → even (switch sides): **0s**, no rest screen, the cursor advances directly to the next set so the user keeps the bell/dumbbell in hand and just changes which side is working.
  - Even warmup → odd warmup (next weight load): 30s.
  - Even normal → odd normal: routine default.
- Last warmup → first normal is always 60s, regardless of unilateral/bilateral.
- A return value of `0` means "skip the rest screen entirely" — `LogWorkoutViewModel.completeCurrentSet` checks `restSeconds > 0` before navigating to `RestTimer`, otherwise it leaves the user on `LogSet` with the cursor already advanced.

### `navigateBack` re-mount guard

`RestTimerViewModel` is activity-scoped (was previously NavBackStackEntry-scoped) so its tick coroutine survives navigation away from `RestTimerScreen` and the elapse haptic fires even if the user is on `LogSetScreen` / `WorkoutControl` when rest expires. The side-effect: `onComplete()` sets `navigateBack = true` even when no screen is currently mounted to observe it via `LaunchedEffect(viewModel.navigateBack)`. Without intervention, the next time the screen mounts (user completes another set → navigate to `RestTimer`) the latched `true` causes the LaunchedEffect to call `popBackStack()` immediately — leaving the user on `LogSetScreen` with the new timer running invisibly in the top bar.

Fix: `RestTimerViewModel.start()` clears `navigateBack = false` at the top, before any branching. A stale "go back" signal from a prior completion is always obsolete when a new timer is starting. Pinned by `RestTimerViewModelNavigateBackTest`.

## Workout finish guards

`finishWorkout()` short-circuits with a user-facing message when the active workout has zero completed sets across all exercises — saving an empty workout would pollute Hevy history with a 0-volume entry. Discard is the right exit when nothing was logged.

## Phone-bridge trust (trust-on-first-use)

`PhoneAuthService` is `exported="true"` and accepts `MESSAGE_RECEIVED` events on `pathPrefix="/"`, but `PhoneAuthDispatcher` whitelists exactly three paths (`/auth_tokens`, `/on_phone_authenticated`, `/watch_seed`) and gates the two sensitive ones on a TOFU node-id check:

- The first `/auth_tokens` after install pins the sender's `sourceNodeId` into `AuthStore.trustedPhoneNodeId` (encrypted-at-rest alongside the tokens themselves).
- Subsequent `/auth_tokens` and `/watch_seed` messages from any other node are rejected with a `Log.e` and a no-op.
- `AuthStore.clear()` (logout) wipes the pinned node id, so the next phone to auth becomes the new trusted peer.

The Wearable framework already authenticates the peer at the transport layer (only paired phones can deliver messages), so this is defense-in-depth — it shuts the door on a sideloaded malicious app on the watch and on a misconfigured second phone overwriting credentials.

`MainActivity` likewise sanitises the tile's `navigate_to` intent extra through `Screen.sanitizeTileRoute(...)`. The whitelist allows only the literal screens the tile deep-links to (`ROUTINE_FOLDERS`, `LOG_WORKOUT`, `LOG_SET`, `WORKOUT_CONTROL`) plus `routine_detail/<id>` where the id is `[A-Za-z0-9_-]+`, so a forged Intent can't smuggle a path-traversal segment or land on an unintended screen.

## Display formatting

Weight rendering on the watch goes through `FormatUtils.formatKg(kg, decimals = 1, compact = false)` (and the integer-aware `formatKgSmart`) instead of inline `"%.1f".format(...)` calls. Single source of truth for the kg→lbs swap if it ever happens; the same helpers cover the workout chip subtitles, the rest-timer hint, the log-set value picker, the routine-detail "avg" line, and the bodyweight setting. `FormatUtils.relativeTimeAgo(epochMs)` powers the per-screen `FreshnessLine` shown below every Refresh chip — see *Refresh policy* for the wiring.

Set-type accent colors (warmup amber, failure red, dropset purple) live on `ChipPalette` with a `SetType.color()` extension — `LogSetScreen.dotColor` now delegates to that single source so future palette changes don't need to be hunted across screens.

## Browse view-models (companion)

`HevyExerciseListViewModel.filtered`, `knownMuscleGroups`, `knownEquipment`, and `knownExerciseTypes` are now backed by `derivedStateOf` so they don't re-walk the 500-template list on every recomposition (search-box keystrokes used to recompute the full filter pass per character). Filter state is persisted to `BrowsePrefs` (parallel to `GeneratorPrefs`) so re-opening the browser keeps the user's chip selection. The M&M browse view-model picked up the same treatment.

**P3 — TimeText recompose cadence**: `WorkoutAwareTimeText` previously ticked the workout-duration text every 1 s. Wrist-glance resolution can't distinguish "5:21" from "5:22", so the tick was dropped to **5 s** for the duration text. The in-clock rest-countdown was first tightened from 500 ms to 1 s and is now **boundary-aligned** — it sleeps until the next whole-second edge rather than on a fixed cadence (see *Countdown display sync* above), so it recomposes ~once per displayed second (~360 times over a one-hour workout) while staying in lockstep with the rest-timer screen.

**P4 — Rest-timer haptic survives navigation**: `RestTimerViewModel` is now activity-scoped (was NavBackStackEntry-scoped), so its tick coroutine — and the countdown haptic it fires — keeps running when the user navigates away from `RestTimerScreen` to e.g. `LogSetScreen`. Previously the VM's `onCleared()` cancelled the tick the moment the screen popped, and no haptic ever fired unless the user happened to be sitting on the rest-timer screen. Haptic firing is gated by `shouldFireHaptic()`: only when `ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(STARTED)` (Hevy in foreground) AND `hevyApp.activeWorkout != null` (workout still recording) — so a backgrounded or post-workout state can't trigger a phantom buzz.

**P5 — Mid-workout haptics dropped**: `Haptics.tick` on set completion and `Haptics.error` on save-failure / precondition warnings have been removed. Per user research: the small vibration motor on the Scallop 2 isn't free to fire, the user wasn't noticing those buzzes, and the visible Compose state change (set marked complete, error banner) is the actual confirmation channel. `Haptics.celebrate` on PR detection is **kept** — that's a meaningful event the user explicitly wants the buzz for. The rest-timer countdown haptic is also kept (it's the only haptic that lives on the user's reaction loop during a workout).

**P8 — Wi-Fi suppressed during workout**: `WifiSuppressor` turns Wi-Fi off when a workout becomes active and restores prior state on Finish / Discard / connectivity-warning exit. Bluetooth-tether to the paired phone keeps `NET_CAPABILITY_INTERNET` available for the final Finish POST, and is significantly cheaper than associated Wi-Fi. State (suppressed-bit + prior-enabled) lives in SharedPreferences so a mid-workout crash doesn't strand the user offline: `HevyApp.onCreate` calls `restoreIfStaleFromCrash()` to put Wi-Fi back if the persisted bit is set but no recoverable workout exists.

The Finish flow now polls connectivity every 500 ms for 10 s before declaring failure (the BT-tether bring-up can take a beat). During the poll, a full-screen `Connecting…` spinner blocks UI. If the window expires, `showNoConnectivityWarning` triggers a dialog; the user dismisses, `HevyApp.endWorkoutKeepingRecovery()` exits the in-memory workout but preserves the recovery file — so the next launch can offer "Resume workout?" and the user retries Finish then.

Two requirements pin the watch app to `targetSdk = 28`:
1. `WifiManager.setWifiEnabled(false)` silently fails for apps with `targetSdk >= Q`. The Kate Spade Scallop 2 runs API 28; this Phase needs the call to actually disable Wi-Fi.
2. The hardware constraint already pinned `minSdk = 28`.

`targetSdk` had drifted up to 35 at some point and is now restored to 28. The Play-Store-policy lint check `ExpiredTargetSdkVersion` is suppressed in the watch module's lint config — the app is sideloaded, not published. `WifiSuppressorTest` (Robolectric) pins idempotent suppress, prior-state-preserving restore, the don't-flip-if-user-had-wifi-off case, the stale-from-crash recovery, and the no-op restore.

**Phase G — priority SSID steering on restore.** After `setWifiEnabled(true)` the OS would auto-pick whichever saved network has the strongest signal in range. `WifiSuppressor.steerToPriorityNetwork` lets the user disambiguate: it walks `wifi.configuredNetworks` (only `ACCESS_WIFI_STATE` needed — **no location permission**), finds the first priority entry that has a saved-network match, and calls `enableNetwork(id, disableOthers=true)`. The disable side-effect is immediately undone by re-enabling every other saved network with `enableNetwork(otherId, false)` so the user keeps auto-roam for the next time they're out of range of the priority AP.

The priority list is stored at **runtime** in `WifiPriorityStore` (SharedPreferences-backed, Compose state for live read-through) and mutated via the **`SET_WIFI_PRIORITY` broadcast** — the user can change the priority order without rebuilding:

```bash
adb shell am broadcast \
  -n com.example.hevywatch/.util.WifiPriorityReceiver \
  -a com.example.hevywatch.SET_WIFI_PRIORITY \
  --es priority "FRITZ!Box 7520 DU,WLAN-077848,FRITZ!Box 7590 JR,TP-Link_C56C"
```

Pass an empty `priority` extra to clear and revert to system-default behaviour. The receiver is exported (so `adb shell` — running as the non-privileged shell user — can deliver the broadcast) but the blast radius is bounded: a hostile broadcast can only switch *which already-saved network the watch prefers post-workout*, never join a new one (no credential surface).

`pickPrioritySsid` is a pure helper (normalises quoted vs unquoted SSIDs, handles empty/blank entries) and is unit-tested independently. Empty priority list = no steering, system default selection. The wifi-config password lives on the watch — saved networks are joined normally via Settings → Wi-Fi; the priority list is purely a *selector* among already-saved networks, never a credential carrier.

**P7 — App-wide brightness coordinator (per-window)**: `BrightnessCoordinator` (wrapping the NavHost) overrides `window.attributes.screenBrightness` whenever Hevy is in the foreground — *not* limited to workouts. The default (resting) and max (on-touch) levels, plus the idle-to-dim duration, are **user-tunable on the Settings screen** (`BrightnessSettingsStore`, persisted to `brightness_prefs`). Defaults: 0.25 / 0.50 / 3 s.

Two cohorts:
- **AlwaysBright** (workout-only exception): LogSetScreen on a WARMUP set, or rest-timer screen when the just-completed set was a WARMUP (covers warmup → warmup rest and warmup → first-normal rest). Warmup choreography needs sharp visibility for plate-loading / dumbbell selection — no idle ramp.
- **AllowDim** (everything else, workout or not): touch ramps to max, then drops back to default after the configured idle window. Any pointer event (including weight-picker scrolls) resets to bright via a `PointerEventPass.Initial` observer that doesn't consume events. Covers ModeSelection, RoutineFolders, RoutineList, RoutineDetail, LogWorkout overview, LogSet on a normal set, RestTimer between normal sets, Congrats, Settings.

On dispose the override is reset to `BRIGHTNESS_OVERRIDE_NONE` so the system brightness setting governs once Hevy leaves the foreground. Per-window control: no `WRITE_SETTINGS` permission required. `brightnessCohort()` is a pure function (Cohort = AlwaysBright / AllowDim) so the per-screen rules are unit-tested without instantiating the Compose tree. `BrightnessCohortTest` pins all branches; `BrightnessSettingsStoreTest` pins persistence + clamping for the user-tunable values. `LogWorkoutViewModel.lastCompletedSetType` is populated by `completeCurrentSet` so the rest-timer rule can read which set type the rest is following.

The Settings screen — accessible from the **⚙ Settings** chip at the bottom of the Folder List (renamed from the previous **⚖ Bodyweight** chip) — now hosts three sections: Bodyweight (kg, ±0.5), Profile (Birth year ±1, Sex segmented M/F — reserved for HR-driven calorie estimation, not yet wired in), and Brightness (Default / Max / Idle seconds), each with ± buttons (or segmented buttons for Sex) for muscle-memory consistency with the bodyweight UI.

**Build / commit indicator.** At the very bottom of Settings the screen renders a `commit <short-hash>` caption in `onSecondary` grey. The hash is *not* baked into `BuildConfig` — the user's build flow is (1) `assembleRelease`, (2) `git commit && git push`, (3) install APK + stamp the freshly-pushed hash via ADB broadcast. So the hash that produced the APK is unknown at compile time; instead a runtime-mutable `CommitInfoStore` (SharedPreferences `commit_info` / key `commit_hash`, Compose state for live read-through) holds it, and `CommitInfoReceiver` writes it from an exported broadcast:

```bash
adb shell am broadcast \
  -n com.example.hevywatch/.util.CommitInfoReceiver \
  -a com.example.hevywatch.SET_COMMIT_HASH \
  --es hash "$(git rev-parse --short HEAD)"
```

Pass an empty `hash` extra to clear; Settings then renders `commit —`. Receiver is exported for the same reason as `WifiPriorityReceiver` (shell user delivers the broadcast); blast radius is purely cosmetic — a hostile broadcast can only change a label in Settings. `CommitInfoStoreTest` pins persistence, trimming, receiver wiring, blank-clear, and wrong-action no-op.

## Shared Gson instance

A single process-wide `Gson` lives at `com.example.hevycore.util.GsonHolder.gson` in `:core`; the watch's `com.example.hevywatch.util.GsonHolder` and the companion's `com.example.hevycompanion.util.GsonHolder` are thin `typealias` re-exports so watch + companion literally share one instance's contract. Every store/sender/dispatcher reads from that holder instead of constructing its own `Gson()` — Gson is thread-safe and stateless after configuration, so the per-class instances we used to ship were just heap pressure on a watch with little budget.

---

## Dependency upgrade policy

The watch app targets Wear OS 2.5 / API 28 (Kate Spade Scallop 2). `minSdk = 28` in `app/build.gradle.kts` is the hard constraint. `compileSdk` / `targetSdk` are current (36 / 35), but a few library families are intentionally pinned below latest because bumping them risks on-device incompatibility or source-level breakage that's hard to validate without a Wear OS 2.5 test device:

- **Kotlin 2.2.x** (→ 2.3 is a breaking-change window; wait for ecosystem settle).
- **androidx.compose:compose-bom 2024.09.00** — newer BOMs track newer Kotlin compiler plugins and may deprecate APIs we use.
- **androidx.wear.compose 1.3.1** — 1.4+ shifts emphasis to Material 3 for Wear; our UI is Material 2.
- **com.google.android.horologist 0.6.x** — tracks `wearCompose`; upgrade together.
- **androidx.wear.tiles 1.1.0** — 1.5+ reshaped the builder APIs.
- **com.google.android.gms:play-services-wearable 18.x** — major bumps may require a newer Play Services version on the device, which can't be guaranteed on frozen Wear OS 2.5.
- **com.squareup.retrofit2 2.x / com.squareup.okhttp3 4.x** — 3.x and 5.x respectively are major-version bumps.

`gradle/libs.versions.toml` carries the rationale inline for future-me. Safe minor-version bumps (core-ktx, lifecycle, coroutines, gson, work-runtime, wear-input, wear legacy, test libraries) are applied opportunistically.

---

## Code-Review Pass — 2026-05-11

Output of the scheduled weekly-code-review action plan. Items by category:

### Security
- **S1** — `HevyApiClientTest` locks in the bearer-redaction invariant: `createPrivate` (the only path that carries an `Authorization: Bearer` header) MUST NOT attach a logging interceptor, ever. Test fails if a future edit adds one.
- **S2** — `PhoneAuthDispatcher` now rejects `/auth_tokens` and `/watch_seed` with `sourceNodeId == null` after the trusted node is pinned. Previously a null source bypassed the trust check. `PhoneAuthTrustTest` updated with the new invariant.
- **S3** — `HEVY_PRIVATE_API_KEY` moved from a hardcoded `HevyApiClient` constant into `secrets.properties` → `BuildConfig`, consistent with `HEVY_PUBLIC_API_KEY`.
- **S4** — `LogSetScreen` input clamps: reps 0..999, weight 0..999.9 kg with NaN/Infinity rejected. `WeightInputDialog` ✓ button disables (with dimmed glyph) until the numpad parses to a real value inside the bound.
- **S5** — `PendingRequestStore.save` caps serialized body at 256 KB and returns `false` on overflow. Real workout bodies are < 64 KB; the cap is a runaway-growth guard.

### Bug fixes
- **B1** — pre-fill guard in `WorkoutHistoryApplier.applyHistoryHints` honors any user-entered weight (not just completed sets). The B1 invariant is now exercised by a dedicated test in `WorkoutHistoryApplierTest`.
- **B2** — verified `WatchSnapshot.snapshotVersion` is checked before `applySeed`; `WatchSnapshotSerializationTest` already covers the version-mismatch path.
- **B3** — `HevyApp.refreshTokenIfNeeded` now uses a `CompletableDeferred` so concurrent callers share a single in-flight refresh. The 20 s throttle stays as a backstop for the leader path; followers await the leader's result instead of silently bailing and proceeding with a stale token.
- **B4** — `LogWorkoutViewModel.pauseWorkout` adds a `saveBlocking` flush at the pause boundary. Normal mutations still use `apply()` (write-coalesced).
- **B5** — `RoutineDetailScreen`'s 'Routine not found' state now shows Back + Retry buttons instead of a dead-end label.
- **B6** — `retrySendPending` catches Gson `JsonSyntaxException` on a corrupted pending body, clears the entry, and throws a friendly `IllegalStateException` (`RetrySendPendingCorruptionTest` pins the behavior).
- **B7** — `RestTimerViewModel.endTimeMs` is now an `AtomicLong`, with `addTime` / `subtractTime` using `addAndGet` and `accumulateAndGet` so two rapid ± taps can't lose the second one.
- **B8** — Active-workout persistence reworked for power: `ThrottledSaver` replaces both the per-mutation `apply()` and the 10 s `IdleFlushScheduler` debounce. The prior scheme did a full Gson serialise on every weight-picker scroll tick (dozens per second under active use) plus a coroutine that re-armed itself every mutation. The new throttled saver coalesces touches into one `saveBlocking()` per 2 s tick when dirty, zero work when idle. Worst-case data-loss after a kill stays ~2 s of scrolling. `ThrottledSaverTest` pins coalescing, idle no-op, cancel, and re-arm under virtual time. `HevyApp.setActiveWorkoutInMemory` is the new VM-facing call that mirrors live state into HevyApp + tile without saving — the VM owns persistence cadence.
- **B9** — `computeProgressiveOverload` no longer overwrites a completed normal set's `weightKg`. The pipeline reruns on VM init for crash-recovered workouts; previously the PO step broadcast a single target value across every normal set, silently replacing the per-set weights the user had actually logged before the crash. The user-visible symptom was "reps are right but every set's weight is identical" after a recovery → Finish. Both branches (PO applied and the no-qualifying-history fallback) now skip completed sets, matching the B1 invariant used in `applyHistoryHints`. `ProgressiveOverloadTest` adds three B9 regressions: completed-set preservation under the machine drop pattern, mixed completed + pending sets, and the no-PO fallback branch.

### Performance / battery
- **P1** — killed the unused `LogWorkoutViewModel.formattedDuration` field + its 1 Hz `startTimer` loop (dead state, never read by any composable). `WorkoutAwareTimeText` has been the real source of the visible duration all along.
- **P2** — `RestTimerViewModel.tick` polls every 500 ms during the bulk of the rest and tightens to 250 ms only in the final 5 s (where vibration timing matters). Previously 100 ms throughout.
- **P3** — `ExerciseChip` subtitle was already `remember`-memoized on its real inputs; with P1's per-second VM tick gone, the chip no longer recomposes on a regular interval.
- **P4** — `WorkoutDataLoader.fetchExerciseHistory` now fans out through a `Semaphore(3)` so up to three `/exercise_history` requests fly in parallel. Cached entries short-circuit before the fan-out.
- **P5** — `WORKOUTS_PAGE1_TTL_MS` raised from 5 min → **60 min**, and the cache lookup wraps the mutex in a 10-second timeout so a stuck fetcher can't freeze other screens. Justification: every screen now shows a `FreshnessLine` plus a tap-to-refresh chip, so staleness is visible to the user and the TTL is no longer the staleness signal.
- **P6** — companion's `HevyCompanionApp` is a new `ImageLoaderFactory` that pins Coil at 32 MB memory / 64 MB disk with tight network timeouts. Default was ~25 % of heap and 256 MB on disk.
- **P7** — `HevyExerciseListFilterEngine` / `MmExerciseListFilterEngine` already lifted the lowercased query out of the filter loop. Verified in code review; no further change needed.
- **P8** — `HevyApp.applicationScope` is a process-scoped `CoroutineScope` (Dispatchers.IO + SupervisorJob) cancelled in `onTerminate`. Replaces the orphan `CoroutineScope(...)` that used to live forever.
- **P9** — added `MainActivity.onResume` tile refresh + `HevyApp.cachedFolders` setter tile refresh. Combined with the existing setters this covers every state change that should propagate to the Tile.
- **P10** — **R8 minify + resource shrinking + non-debuggable** on the release build (`app/build.gradle.kts`). The watch runs Snapdragon Wear 2100-class hardware (~512 MB RAM), where the prior un-minified build was the dominant cost: APK **42 MB → 5.3 MB**, dex **~41.6 MB across 14 files → a single 4.87 MB `classes.dex`** (most of Guava / Compose tooling / play-services stripped), so far fewer classes load and ART has far less to JIT. Flipping `isDebuggable = false` lets ART **AOT-compile** the app instead of running interpreter/JIT-only — the single biggest CPU win on the old watch. Tradeoff (accepted): `run-as` / JDWP no longer work on the installed watch APK (build a throwaway debuggable variant if you ever need to dump the bearer token); the companion stays debuggable. Keep rules live in [`app/proguard-rules.pro`](app/proguard-rules.pro) — Gson is a plain jar with no consumer rules, so the `data.api.model.**` + `data.model.**` packages and every `@SerializedName` member are kept (else R8 renames fields → wrong JSON keys). Verified end-to-end on shiner (KSW1): routines list, routine detail, and exercise-history all deserialize and render; no R8 runtime errors. The rules also carry `-assumenosideeffects` for `android.util.Log.d/v/i`, so those call sites (and the string concatenation building their arguments) are pruned from the release DEX — each was a JNI hop plus a `String` alloc even when nothing was listening. `Log.w` / `Log.e` survive because they're what shows up in `adb logcat` after a field crash.
- **P11** — `BrightnessCoordinator` idle-to-dim was a `delay(250 ms)` polling loop running the *entire* foreground session (4 Hz × ~1 h workout). Replaced with a reactive `snapshotFlow { lastTouchMs }.collectLatest { … }` one-shot: arms a single delay per touch and **costs zero wakeups while the screen sits untouched** (the common case mid-set/rest). `snapshotFlow` observes the touch timestamp without forcing recomposition, so it's strictly cheaper than keying a `LaunchedEffect` on it.
- **P12** — `HevyApp.exerciseHistoryCache` is now a **bounded LRU** ([`boundedLruMap`](app/src/main/java/com/example/hevywatch/data/BoundedLru.kt), cap 40) instead of an unbounded `mutableMapOf`. A long browsing session used to retain every viewed exercise's full set history for the process lifetime; the LRU caps that (an evicted entry just re-fetches on next access). All call sites are point get/put/remove, so the synchronized access-ordered map is safe. Pinned by [`BoundedLruTest`](app/src/test/java/com/example/hevywatch/BoundedLruTest.kt).

### Refactors
- **R1** — `LogWorkoutViewModel` split: `WorkoutHistoryApplier` owns the fetch-stamp-PO-similar-warmup pipeline; `WorkoutCompletionRecorder` owns the post-save bookkeeping (PR badges, summary, routine-last-worked map, history-cache invalidation). VM down from 808 → 614 LOC.
- **R2** — generic `JsonPrefsStore<T>` base. `FolderCacheStore`, `RoutineCacheStore`, `ActiveWorkoutStore` now extend it; `saveBlocking` lives on the base for stores that need a synchronous flush.
- **R3** — single `util/DateFormatUtils.kt` for every user-facing date string on the watch. `RoutineFolderListScreen` / `RoutineDetailScreen` / `WorkoutDetailScreen` switched over.
- **R4** — `RoutineListScreen`'s `formatWorkoutDate` now uses `RoutineProgressComputer.parseInstant` + `DateFormatUtils.formatListDate` (`MMM d, ''yy`, e.g. *May 3, '26*) — same path as Recent and Progress. The earlier revision called `DateFormatUtils.formatShortDate`, whose stricter parser routinely fell through to the raw `yyyy-MM-dd` fallback on the timestamp shape the API actually returns; cards then read as a debug string next to the human-formatted dates on the other screens.
- **R5** — `PageIndicator` lives in `ui/components/`; both pagers reuse it.
- **R6** — `HevyApp.tokenState` is a derived view backed by a sealed `TokenState` (`Missing` / `Valid` / `ExpiringSoon` / `Refreshing` / `Failed`). The persisted scalars remain the source of truth on disk; this just gives callers a pattern-matchable surface. `HevyAppTokenStateTest` pins it.
- **R7** — `HevyHttpClientFactory` on companion. Both `HevyAuthApi` and `HevyPublicApi` now build their OkHttp client through the shared factory; future hardening (SSL pinning, network security config) updates both call sites at once.
- **R8** — `LoopingVideoPlayer` lives at `com.example.hevycompanion.ui.LoopingVideoPlayer`. `ExerciseAvatar` and `MmExerciseDetailScreen` both delegate to it.
- **R9** — kept the existing `HevyExerciseListFilter` / `MmExerciseListFilter` decoupled per the explicit "don't extract a shared abstraction" comment at the filter site. No code change.

### Test coverage
- **T1** — `HevyApiClientTest` (S1).
- **T2** — `RefreshTokenInteractorTest` already covers mutex serialization, 401 handling, network errors, and missing-field bodies. Verified, no change.
- **T3, T4, T8, T9, T10** — left for a follow-up pass. `MainViewModel` / `WatchBridgeService` / `RoutineListViewModel` / `RoutineFolderListViewModel` need either dispatcher-style splits or Robolectric harnesses to test cleanly; the cost outweighs the benefit at this rev.
- **T5** — `WorkoutHistoryApplierTest` covers the pure transforms (`applyHistoryHints`, `applyProgressiveOverload`, `applyWarmupAdvisor`). The orchestration in `completeCurrentSet` is exercised through these and through the existing `SetNavigationTest`.
- **T6, T7** — pause/resume during rest timer and `continueWorkout` malformed-payload handling left as follow-up; both need a Robolectric VM scaffold.

### UX polish
- **U1** — `LogWorkoutScreen` loading state has a 15 s timeout that swaps the spinner for an error + Retry chip.
- **U2** — `LogSetScreen` shows a small spinner + 'Loading history…' caption while history is still loading (previously a blank screen).
- **U3** — `saveError` banner on `LogWorkoutScreen` auto-dismisses after 10 s and is tap-to-dismiss; `LogWorkoutViewModel.dismissSaveError` is the new hook.
- **U4** — rest-timer fallback when there's no next weight: 'ready?' instead of 'rest' (reads as a prompt, not as a noun).
- **U8** — `RoutineDetailScreen` 'Routine not found' state has Back + Retry buttons (same change as B5).

## Notes for next pass

- F-items (new features) were intentionally deferred — none implemented this rev.
- R9 is a 'discuss before doing' item; revisit if a third exercise catalog ever lands.

---

## Code-Review Pass — 2026-08-03

Output of the scheduled weekly-code-review action plan. Watch-module (`:app`)
items by category. Companion items are in PRD-COMPANION-APP.md. Verified: full
`:core`/`:app`/`:companion` unit suites + both release APK builds green.

### Bug fixes
- **B1** — `HevyApp.cachedActiveTileState` was invalidated **only** in
  `setActiveWorkoutInMemory()`. Every other `activeWorkout` write path
  (`startWorkout`, `updateActiveWorkout`, `adjustWorkoutStartTime`,
  `clearActiveWorkout`, `endWorkoutKeepingRecovery`) mutated the workout
  without clearing the memoized tile state, so `activeTileStateOrCompute()`
  kept returning the previous value and `HevyTileService.onTileRequest`'s
  recompute fallback never ran. A tile swipe after finishing workout A and
  starting/continuing B — before `LogWorkoutViewModel`'s first in-memory
  mutation — could paint A's rings/weight (self-heals on the next mutation,
  hence intermittent). Fixed by converting `activeWorkout` to a backing field
  with a custom setter that clears `cachedActiveTileState` on **every** write,
  now and future.

### Performance / battery
- **P1** — `LogWorkoutViewModel`'s `ThrottledSaver` flush ran on
  `viewModelScope` (`Dispatchers.Main`) and called `saveBlocking()` =
  `SharedPreferences.commit()`, i.e. a **synchronous disk write on the main
  thread** every ~2 s during active weight-picker scrolling — real jank on the
  Snapdragon Wear 2100. Switched the throttle flush to async `save()` (apply);
  the two explicit `saveBlocking()` durability checkpoints (set completion +
  pause) still guarantee a synchronous flush at the points that matter, so
  worst-case crash data-loss is unchanged (~2 s of scrolling).

### Refactors / prunes
- **R1** — deleted `DateFormatUtils.SHORT_DATE` + `formatShortDate`: zero
  callers, and its guard `iso[10] == 'T'` threw an **uncaught**
  `StringIndexOutOfBoundsException` on a bare 10-char `yyyy-MM-dd` (the catch
  only handled `DateTimeParseException`), contradicting its own doc. Dead code
  that also hid a latent crash.
- **R2** — hoisted a single shared `BODYWEIGHT_EQUIPMENT` set
  ([`BodyweightEquipment.kt`](app/src/main/java/com/example/hevywatch/data/BodyweightEquipment.kt)).
  `SimilarExerciseSuggestion.find` had gated on a **case-sensitive** inline
  `setOf(...)` while `WorkoutDataLoader.prefetchSimilarExerciseHistory` gated
  on `equipment.lowercase() in BODYWEIGHT_EQUIPMENT`; a non-lowercase API value
  would have made the two skip paths disagree. Both now compare on
  `equipment.lowercase()` against the shared set.

### Security
- **S1** — added an explicit `res/xml/network_security_config.xml`
  (`base-config cleartextTrafficPermitted="false"`) referenced from the
  manifest. The watch targets SDK 28, where cleartext defaults to **permitted**
  — so a future dependency issuing an `http://` call would have been silently
  allowed. All current endpoints are HTTPS; this is defense-in-depth. (Same
  config added on the companion; see its PRD.)
- **S2** — deleted `AuthStore.tokenSeedVersion` + `KEY_TOKEN_SEED_VERSION`:
  written/read nowhere in production, and its doc falsely claimed "hardcoded
  seed tokens … the constant in HevyApp" (no such constant — "seed" now means
  the routine/folder snapshot, not credentials). Removing it also removes a
  doc that implied credentials are baked into source.

### Test coverage
- **T1** — `RecentsFormatTest` (companion) pins the Recents/Workout-Detail
  date + weight formatters (offset handling, fallbacks, whole-number `kg()`
  collapse).
- **T2** — `ExerciseAdvisorTest` (companion) pins the equipment gate
  `ExerciseAdvisor` itself owns: bodyweight (`""`/`none`/`None`/null) → no PO
  target, no warmups; real equipment opens the PO carry; `avgLastNormalKg`
  passes through.
- **T3** — `RefreshTokenModelsTest` (`:core`) pins the `@SerializedName` wire
  keys (`refresh_token`/`access_token`/`expires_at`) that must not drift
  between watch and companion, plus the nullable-superset missing-field
  contract.

### Proposed — not implemented, needs your call
- **X1 (security, ambiguous)** — the **watch-side TOFU window**: while
  `trustedPhoneNodeId == null` (fresh install / post-logout), `PhoneAuthDispatcher`
  auto-pins the first `/auth_tokens` sender, and accepts `/watch_seed`,
  `/api_version`, `/resume_workout` from **any** paired node without pinning.
  Asymmetric with the companion, which requires explicit user approval. A
  rogue paired peer delivering before the real phone could pin itself. Mitigated
  by the Wearable transport only delivering from paired peers. Fix (design
  call): mirror the companion's approve-on-first-contact, or at least stop
  honoring `/watch_seed` + `/api_version` while `trusted == null`.
- **X2 (security, ambiguous)** — the three **exported ADB receivers**
  (`SET_WIFI_PRIORITY`, `SET_COMMIT_HASH`, `SET_API_VERSION`) have no
  `android:permission`, so any installed app can broadcast them. `SET_API_VERSION`
  is the sharp one: a bogus version makes the spoofed `Hevy-App-Version` headers
  invalid and Hevy's private v2 routes 404 — a silent functional DoS. They must
  stay `adb shell am broadcast`-reachable (a signature permission would block
  adb), so the fix is a judgment call: gate the two cosmetic ones behind
  `BuildConfig.DEBUG`, and/or validate `SET_API_VERSION` against an allowlist.
- **X3 (battery, tradeoff)** — `PhoneLink.awaitReady` polls `currentState()`
  (a `BluetoothAdapter.isEnabled` check + a GMS `connectedNodes` round-trip)
  every 1 s with **no upper bound**, and `ModeSelectionScreen` runs a second
  independent 1 s poller. With no phone connected that's up to two GMS
  round-trips/second indefinitely. Backing the cadence off (1 s → capped ~8 s)
  and/or sharing one poller would cut the drain, but trades reconnect-detection
  latency on the mode-selection screen — deferred for your call. No test pins
  the cadence.
- **X4 (security, inherent)** — client secrets ship as plaintext `BuildConfig`
  constants in the release APK: `HEVY_PRIVATE_API_KEY` (already extractable from
  Hevy's own APK — no net change) and the GitHub PAT for the api-version fetch
  (`Contents:Read` on the private version repo; blast radius = version numbers).
  Correctly kept out of git. Only fully removable by moving the api-version
  fetch behind a server you control.
- **X5 (security, design)** — `DebugWebhookInterceptor` mirrors verbatim
  workout request+response **bodies** (training data / PII) to a third-party URL,
  gated on the URL being non-empty rather than `BuildConfig.DEBUG`, so it ships
  in release when the secret is set. Off by default, never forwards
  headers/tokens. Consider additionally gating on `BuildConfig.DEBUG`.
- **X6 (test, effort)** — `WorkoutCompletionRecorder.record` /
  `updateRoutineHistory` and `WorkoutDataLoader.prefetchSimilarExerciseHistory`
  hold subtle rules (PR-before-cache-bust; last-worked only moves forward and
  uses the original start time for continues; the candidate-selection filter)
  but are untested. Both need a fake/Robolectric `HevyApp` seam — deferred.
- **X7 (micro-opt)** — `DebugWebhookInterceptor.readBody` buffers the entire
  request body before truncating to `BODY_LIMIT`; on a resumed workout that can
  be hundreds of KB. Read at most `BODY_LIMIT` bytes instead. Debug-only path,
  low value.
- Several test gaps (T3, T4, T6, T7, T8, T9, T10) intentionally deferred. Each one needs Robolectric scaffolding or a dispatcher-style split before it can be tested cleanly. Captured in this PRD as known follow-ups.

## Code-Review Pass — 2026-09-21

Output of the scheduled weekly-code-review action plan. Watch-module (`:app`)
items. Companion items are in PRD-COMPANION-APP.md. Verified: full
`:core`/`:app`/`:companion` unit suites + both release APK builds green.

This pass found the module in strong shape — no committed secrets, no unused
string resources, no `TODO`/`FIXME` markers, correct immutable `PendingIntent`
flags, and the cross-module `SubstitutionMap` "duplication" already resolved to
thin `:core`-delegating wrappers. The only production-code candidates
(pruning the symmetric-but-unused `AssistedBodyweight.toLogged`, gating the
debug webhook on `BuildConfig.DEBUG`) are judgment calls and are left in the
Proposed list rather than actioned unattended. Implemented changes this pass are
therefore test-only (zero production-behaviour change).

### Test coverage
- **T1** — `TileStateComputerTest`: an **empty** `ActiveWorkout` (no exercises)
  now has a pinned contract — `computeActiveTileState` degrades to the terminal
  `"Done!"` state with both arcs at 0° rather than dividing by zero / NPE-ing on
  the empty list. The tile paints from persisted state that can momentarily be
  an empty shell and has no error surface of its own, so this degenerate input
  must stay crash-safe.
- **T2** — `TileStateComputerTest`: pinned `weightColor`'s precedence when a set
  carries **both** `poBaseWeightKg` and `isSimilarSuggestion` (a PO target that
  also came from a similar-exercise scale) — PO green wins over advisor orange.
- **T3** — `KeytelCaloriesTest`: pinned that the live-readout
  `kcalPerMinute` path applies the same non-negative clamp as the total path,
  so a single low-HR reading (male formula goes negative at HR≈50) floors at
  `0.0` instead of surfacing a negative cal/min rate.

### Proposed — not implemented, needs your call
- **Y1 (prune, ambiguous)** — `AssistedBodyweight.toLogged` (`:core`) has no
  production caller; it is exercised only by its own inverse-property test
  against `toEffective`. It is a documented, symmetric counterpart to
  `toEffective` and the class comment anticipates a future companion
  routine-summary port, so it may be deliberate API surface. Pro: removes ~3
  lines of dead code. Con/risk: deletes an intentional symmetric API and its
  regression test; a future port would re-add it. Low value either way — left
  for your call.
- Carried forward from the 2026-08-03 pass and still open: **X1** (watch-side
  TOFU asymmetry), **X2** (unpermissioned exported ADB receivers), **X3**
  (`PhoneLink.awaitReady` unbounded 1 s polling), **X4** (plaintext client
  secrets in `BuildConfig`), **X6** (`WorkoutCompletionRecorder` /
  `prefetchSimilarExerciseHistory` need a fake-`HevyApp` seam to test). Each
  remains a design decision or needs Robolectric scaffolding, so all stay
  deferred pending your direction. (X5/X7 from that pass — debug-webhook
  time-boxing and bounded request capture — were subsequently implemented in
  commit `d5b1cda`.)
