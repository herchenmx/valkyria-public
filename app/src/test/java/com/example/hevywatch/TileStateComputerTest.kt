package com.example.hevywatch

import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.tile.TileColors
import com.example.hevywatch.tile.computeActiveTileState
import com.example.hevywatch.tile.formatWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down the tile's pure-state derivation. The full layout is awkward to
 * test (Tiles framework builders), but the parts that actually decide WHAT to
 * render — between-sets vs. between-exercises, big/small text, color choice —
 * live in computeActiveTileState() and are pure.
 */
class TileStateComputerTest {

    private fun mkSet(
        weight: Float? = null,
        reps: Int? = null,
        repRangeStart: Int? = null,
        completed: Boolean = false,
        po: Float? = null,
        suggestion: Boolean = false,
    ) = ActiveSet(
        weightKg = weight,
        reps = reps,
        repRangeStart = repRangeStart,
        completed = completed,
        poBaseWeightKg = po,
        isSimilarSuggestion = suggestion,
    )

    private fun mkExercise(title: String, sets: List<ActiveSet>) = ActiveExercise(
        exerciseTemplateId = "tpl_${title}",
        title = title,
        sets = sets,
    )

    private fun mkWorkout(exercises: List<ActiveExercise>) = ActiveWorkout(
        name = "T", exercises = exercises,
    )

    // ── formatWeight ───────────────────────────────────────────────────────

    @Test fun `formatWeight prints whole numbers without decimals`() {
        assertEquals("30", formatWeight(30f))
        assertEquals("100", formatWeight(100f))
    }

    @Test fun `formatWeight prints fractional with one decimal`() {
        assertEquals("33.5", formatWeight(33.5f))
        assertEquals("12.7", formatWeight(12.7f))
    }

    @Test fun `formatWeight returns dash for null or zero`() {
        assertEquals("–", formatWeight(null))
        assertEquals("–", formatWeight(0f))
    }

    // ── between-sets state (a current exercise has ≥1 completed set) ──────

    @Test fun `between sets shows next set weight and reps`() {
        val ex = mkExercise(
            "Bench",
            listOf(
                mkSet(weight = 60f, reps = 8, completed = true),
                mkSet(weight = 60f, reps = 8, completed = false),
                mkSet(weight = 60f, reps = 8, completed = false),
            )
        )
        val s = computeActiveTileState(mkWorkout(listOf(ex)))

        assertTrue("expected betweenSets", s.betweenSets)
        assertEquals("60kg", s.bigText)
        assertEquals("8 reps", s.smallText)
        assertEquals(TileColors.WHITE, s.bigColor)
    }

    @Test fun `between sets uses PO color when next set has poBase`() {
        val ex = mkExercise(
            "Squat",
            listOf(
                mkSet(weight = 80f, reps = 5, completed = true),
                mkSet(weight = 82.5f, reps = 5, po = 80f),
            )
        )
        val s = computeActiveTileState(mkWorkout(listOf(ex)))
        assertEquals(TileColors.WEIGHT_PO, s.bigColor)
        assertEquals("82.5kg", s.bigText)
    }

    @Test fun `between sets uses suggestion color when next set is similar-suggested`() {
        val ex = mkExercise(
            "Cable row",
            listOf(
                mkSet(weight = 30f, reps = 10, completed = true),
                mkSet(weight = 32.5f, reps = 10, suggestion = true),
            )
        )
        val s = computeActiveTileState(mkWorkout(listOf(ex)))
        assertEquals(TileColors.WEIGHT_SUGG, s.bigColor)
    }

    @Test fun `between sets prefers repRangeStart over reps`() {
        val ex = mkExercise(
            "OHP",
            listOf(
                mkSet(weight = 40f, reps = 10, completed = true),
                mkSet(weight = 40f, reps = 12, repRangeStart = 8),
            )
        )
        val s = computeActiveTileState(mkWorkout(listOf(ex)))
        assertEquals("8 reps", s.smallText)
    }

    @Test fun `between sets prefers PO color when next set is both PO and similar-suggested`() {
        // A set can legitimately carry both flags (a PO target that also came
        // from a similar-exercise scale). weightColor()'s precedence is PO
        // first, so the green PO readout must win over the orange advisor one.
        val ex = mkExercise(
            "Row",
            listOf(
                mkSet(weight = 40f, reps = 8, completed = true),
                mkSet(weight = 42.5f, reps = 8, po = 40f, suggestion = true),
            )
        )
        val s = computeActiveTileState(mkWorkout(listOf(ex)))
        assertEquals(TileColors.WEIGHT_PO, s.bigColor)
    }

    // ── between-exercises state (current exercise has 0 completed sets) ───

    @Test fun `between exercises shows next exercise name and target`() {
        val done = mkExercise("Bench", listOf(mkSet(weight = 60f, reps = 8, completed = true)))
        val next = mkExercise(
            "Squat",
            listOf(
                mkSet(weight = 100f, reps = 5),
                mkSet(weight = 100f, reps = 5),
            )
        )
        val s = computeActiveTileState(mkWorkout(listOf(done, next)))

        assertFalse("expected between-exercises", s.betweenSets)
        assertEquals("Squat", s.bigText)
        assertEquals("100kg ×5", s.smallText)
        assertEquals(TileColors.WEIGHT_SUGG, s.bigColor)
    }

    @Test fun `between exercises drops weight chunk if target weight is missing`() {
        val done = mkExercise("Bench", listOf(mkSet(weight = 60f, reps = 8, completed = true)))
        val next = mkExercise(
            "Plank",
            listOf(mkSet(reps = 30))
        )
        val s = computeActiveTileState(mkWorkout(listOf(done, next)))
        assertEquals("Plank", s.bigText)
        assertEquals("×30", s.smallText)
    }

    // ── done state (no incomplete sets remain) ────────────────────────────

    @Test fun `all sets complete renders Done`() {
        val ex = mkExercise(
            "Bench",
            listOf(
                mkSet(weight = 60f, reps = 8, completed = true),
                mkSet(weight = 60f, reps = 8, completed = true),
            )
        )
        val s = computeActiveTileState(mkWorkout(listOf(ex)))
        assertEquals("Done!", s.bigText)
        assertEquals("", s.smallText)
        assertFalse(s.betweenSets)
    }

    @Test fun `empty workout renders Done without throwing`() {
        // Defensive contract: the tile paints from the persisted ActiveWorkout,
        // which can momentarily be an empty shell (just cleared, or restored
        // before exercises are populated). computeActiveTileState must degrade
        // to the terminal "Done!" state rather than divide-by-zero or NPE on
        // the empty exercise list — the tile has no error surface of its own.
        val s = computeActiveTileState(mkWorkout(emptyList()))
        assertEquals("Done!", s.bigText)
        assertEquals("", s.smallText)
        assertFalse(s.betweenSets)
        assertEquals(0f, s.outerProgressDeg, 0.01f)
        assertEquals(0f, s.innerProgressDeg, 0.01f)
    }

    // ── progress arcs ─────────────────────────────────────────────────────

    @Test fun `outer arc reflects completed exercise fraction`() {
        // 2 of 4 exercises fully done → 180 deg
        val full = mkExercise(
            "X",
            listOf(mkSet(weight = 1f, reps = 1, completed = true))
        )
        val empty = mkExercise(
            "Y",
            listOf(mkSet(weight = 1f, reps = 1, completed = false))
        )
        val workout = mkWorkout(listOf(full, full, empty, empty))
        val s = computeActiveTileState(workout)
        assertEquals(180f, s.outerProgressDeg, 0.01f)
    }

    @Test fun `inner arc reflects completed-sets fraction of current exercise`() {
        // 1 of 4 sets done in current exercise → 90 deg
        val ex = mkExercise(
            "Bench",
            listOf(
                mkSet(weight = 60f, reps = 8, completed = true),
                mkSet(weight = 60f, reps = 8, completed = false),
                mkSet(weight = 60f, reps = 8, completed = false),
                mkSet(weight = 60f, reps = 8, completed = false),
            )
        )
        val s = computeActiveTileState(mkWorkout(listOf(ex)))
        assertEquals(90f, s.innerProgressDeg, 0.01f)
    }
}
