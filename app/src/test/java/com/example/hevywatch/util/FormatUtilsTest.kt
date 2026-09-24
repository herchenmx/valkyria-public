package com.example.hevywatch.util

import com.example.hevycore.exercise.AssistedBodyweight
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ExerciseType
import com.example.hevywatch.data.model.SetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatUtilsTest {

    // ── formatDuration ──────────────────────────────────────────────────────

    @Test fun `formatDuration with zero elapsed renders 0_00`() {
        assertEquals("0:00", FormatUtils.formatDuration(startTimeMs = 1000L, asOfMs = 1000L))
    }

    @Test fun `formatDuration with negative elapsed clamps to zero rather than throwing`() {
        // System clock can occasionally jump backwards (NTP correction); the
        // workout timer must keep painting instead of going negative.
        assertEquals("0:00", FormatUtils.formatDuration(startTimeMs = 5000L, asOfMs = 1000L))
    }

    @Test fun `formatDuration under one hour drops the hours field`() {
        assertEquals("1:30", FormatUtils.formatDuration(0L, 90_000L))
    }

    @Test fun `formatDuration past one hour renders h_mm_ss`() {
        assertEquals("1:02:03", FormatUtils.formatDuration(0L, 3_723_000L))
    }

    // ── formatVolume ─────────────────────────────────────────────────────────

    private fun mkSet(weight: Float, reps: Int, completed: Boolean = true) = ActiveSet(
        weightKg = weight, reps = reps, completed = completed,
        setType = SetType.NORMAL,
    )

    private fun ex(templateId: String, vararg sets: ActiveSet) = ActiveExercise(
        exerciseTemplateId = templateId,
        title = "ex",
        exerciseType = ExerciseType.WEIGHT_AND_REPS,
        sets = sets.toList(),
    )

    @Test fun `formatVolume sums weight times reps for completed sets only`() {
        val exercise = ex(
            "x",
            mkSet(weight = 50f, reps = 5, completed = true),
            mkSet(weight = 50f, reps = 5, completed = false), // not counted
            mkSet(weight = 60f, reps = 3, completed = true),
        )
        // 50*5 + 60*3 = 430
        assertEquals("430 kg", FormatUtils.formatVolume(listOf(exercise)))
    }

    @Test fun `formatVolume returns zero for an all-incomplete workout`() {
        val exercise = ex("x", mkSet(weight = 100f, reps = 10, completed = false))
        assertEquals("0 kg", FormatUtils.formatVolume(listOf(exercise)))
    }

    @Test fun `formatVolume converts assisted-bodyweight load when routineId matches`() {
        // A lat-pulldown style assisted-machine logged as 30 kg of stack
        // assist for a 70 kg user contributes (70 - 30) * reps to volume,
        // not (30 * reps) — that's the whole point of the assisted branch.
        val assistedTemplateId = AssistedBodyweight.TEMPLATE_IDS.first()
        val exercise = ex(
            assistedTemplateId,
            mkSet(weight = 30f, reps = 10, completed = true),
        )
        val out = FormatUtils.formatVolume(
            exercises = listOf(exercise),
            routineId = AssistedBodyweight.ROUTINE_ID,
            bodyweightKg = 70f,
        )
        // (70 - 30) * 10 = 400
        assertEquals("400 kg", out)
    }

    @Test fun `formatVolume floors negative effective load to zero on assisted exercise`() {
        // User loads more assist than they weigh — shouldn't credit negative volume.
        val assistedTemplateId = AssistedBodyweight.TEMPLATE_IDS.first()
        val exercise = ex(
            assistedTemplateId,
            mkSet(weight = 100f, reps = 5, completed = true),
        )
        val out = FormatUtils.formatVolume(
            exercises = listOf(exercise),
            routineId = AssistedBodyweight.ROUTINE_ID,
            bodyweightKg = 60f,
        )
        assertEquals("0 kg", out)
    }

    // ── setCoordinates ──────────────────────────────────────────────────────

    @Test fun `setCoordinates renders 1-based index and total`() {
        assertEquals("1/3", FormatUtils.setCoordinates(0, 3))
        assertEquals("3/3", FormatUtils.setCoordinates(2, 3))
    }

    // ── formatKg / formatKgSmart ─────────────────────────────────────────────

    @Test fun `formatKg defaults to one decimal with space`() {
        // 12.5 kg uses comma in some locales — accept either separator so the
        // suite passes on every test machine.
        val out = FormatUtils.formatKg(12.5f)
        assertTrue("expected '12.5 kg' or '12,5 kg' but got '$out'",
            out == "12.5 kg" || out == "12,5 kg")
    }

    @Test fun `formatKg compact drops the space`() {
        val out = FormatUtils.formatKg(12.5f, compact = true)
        assertTrue(out == "12.5kg" || out == "12,5kg")
    }

    @Test fun `formatKg honors decimals override`() {
        val out = FormatUtils.formatKg(12.5f, decimals = 0)
        assertEquals("13 kg", out)
    }

    @Test fun `formatKgSmart drops decimals on integer-valued weight`() {
        assertEquals("30 kg", FormatUtils.formatKgSmart(30f))
    }

    @Test fun `formatKgSmart keeps one decimal on fractional weight`() {
        val out = FormatUtils.formatKgSmart(30.5f)
        assertTrue(out == "30.5 kg" || out == "30,5 kg")
    }

    // ── relativeTimeAgo ──────────────────────────────────────────────────────

    @Test fun `relativeTimeAgo within a minute reads as just now`() {
        assertEquals("just now", FormatUtils.relativeTimeAgo(epochMs = 0L, nowMs = 30_000L))
    }

    @Test fun `relativeTimeAgo singular minute uses min not mins`() {
        assertEquals("1 min ago", FormatUtils.relativeTimeAgo(epochMs = 0L, nowMs = 60_000L))
    }

    @Test fun `relativeTimeAgo past a minute renders minutes`() {
        assertEquals("5 mins ago", FormatUtils.relativeTimeAgo(epochMs = 0L, nowMs = 5L * 60 * 1000))
    }

    @Test fun `relativeTimeAgo singular hour uses hr not hrs`() {
        assertEquals("1 hr ago", FormatUtils.relativeTimeAgo(epochMs = 0L, nowMs = 3_600_000L))
    }

    @Test fun `relativeTimeAgo past an hour renders hours`() {
        assertEquals("3 hrs ago", FormatUtils.relativeTimeAgo(epochMs = 0L, nowMs = 3L * 3_600 * 1000))
    }

    @Test fun `relativeTimeAgo 24-48 hours reads as yesterday`() {
        assertEquals("yesterday", FormatUtils.relativeTimeAgo(epochMs = 0L, nowMs = 24L * 3_600 * 1000))
        assertEquals(
            "yesterday",
            FormatUtils.relativeTimeAgo(epochMs = 0L, nowMs = 47L * 3_600 * 1000),
        )
    }

    @Test fun `relativeTimeAgo past 48 hours renders days`() {
        assertEquals("2 days ago", FormatUtils.relativeTimeAgo(epochMs = 0L, nowMs = 2L * 86_400 * 1000))
        assertEquals("5 days ago", FormatUtils.relativeTimeAgo(epochMs = 0L, nowMs = 5L * 86_400 * 1000))
    }

    @Test fun `relativeTimeAgo with future epoch never goes negative`() {
        // Wall-clock skew shouldn't print "-3 mins ago".
        assertEquals("just now", FormatUtils.relativeTimeAgo(epochMs = 1_000L, nowMs = 0L))
    }
}
