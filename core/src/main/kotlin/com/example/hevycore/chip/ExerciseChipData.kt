package com.example.hevycore.chip

/**
 * Compose-free chip data shared between the watch (`ExerciseChipUi` for Wear
 * Material) and the companion (`ExerciseChipUi` for Material3). Each side
 * keeps its own renderer, but the state enums, the tint decision, and the
 * `ExerciseChipStats` shape all live here so the two chip surfaces can't
 * drift again (this is the third phase of the chip-alignment work — see
 * `project_chip_alignment_plan`).
 *
 * A chip conveys, top to bottom:
 *  - **state** via background tint — green complete / blue partial / red missing;
 *  - an optional **kind** tag (`swap` / `extra`) as dim trailing text on the title;
 *  - warmup progress `X/Y W` (amber, hidden when there are no warmups);
 *  - normal-set progress `X/Y N`;
 *  - the **weight** for the normal sets — green when progressive-overload-bumped,
 *    amber (`~`) when only a similar-exercise estimate, plain otherwise.
 */

enum class ChipState { COMPLETE, IN_PROGRESS, MISSING }

enum class ChipKind { SWAP, EXTRA }

/** Why the weight shown is what it is — drives its colour. */
enum class WeightKind { PO, ESTIMATE, PLAIN }

data class ExerciseChipStats(
    val warmupDone: Int,
    val warmupTotal: Int,
    val normalDone: Int,
    val normalTotal: Int,
    /** Pre-formatted weight (e.g. "60 kg" / "~40 kg"), or null to omit. */
    val weightText: String?,
    val weightKind: WeightKind,
    val kind: ChipKind?,
) {
    /**
     * Completion state, used for the background tint. COMPLETE once every
     * prescribed normal set AND every advised warmup is logged; MISSING when
     * nothing's logged at all; IN_PROGRESS in between. EXTRA rows (no
     * prescription) are still tinted by this rule — their off-routine nature
     * is conveyed by the [kind] tag, not a separate colour.
     */
    val state: ChipState
        get() = when {
            normalDone >= normalTotal && warmupDone >= warmupTotal -> ChipState.COMPLETE
            normalDone == 0 && warmupDone == 0 -> ChipState.MISSING
            else -> ChipState.IN_PROGRESS
        }
}

/** Human label for the trailing tag on a chip title. */
fun ChipKind.label(): String = when (this) {
    ChipKind.SWAP -> "swap"
    ChipKind.EXTRA -> "extra"
}
