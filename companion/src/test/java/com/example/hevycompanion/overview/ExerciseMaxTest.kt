package com.example.hevycompanion.overview

import com.example.hevycompanion.data.ExerciseHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Strength Overview scoring rule: a workout qualifies on 3+ normal
 * sets of 15+ reps, and its value is the average of those sets' weights; the
 * exercise headline is the highest such average across history.
 */
class ExerciseMaxTest {

    private var seq = 0
    private fun set(
        workout: String,
        weight: Float?,
        reps: Int?,
        type: String = "normal",
        start: String? = "2026-03-28T10:00:00+00:00",
    ) = ExerciseHistoryEntry(
        workoutId = workout,
        workoutStartTime = start,
        weightKg = weight,
        reps = reps,
        setType = type,
    )

    @Test
    fun `three normal sets at same weight and 15 reps qualify`() {
        val best = ExerciseMax.highestQualifying(
            listOf(set("w1", 20f, 15), set("w1", 20f, 16), set("w1", 20f, 15))
        )
        assertEquals(20f, best!!.weightKg, 0.001f)
    }

    @Test
    fun `mixed weights average per the user's rule`() {
        // 20x15, 22.5x15, 25x15 -> average 22.5
        val best = ExerciseMax.highestQualifying(
            listOf(set("w1", 20f, 15), set("w1", 22.5f, 15), set("w1", 25f, 15))
        )
        assertEquals(22.5f, best!!.weightKg, 0.001f)
    }

    @Test
    fun `fewer than three qualifying sets does not qualify`() {
        val best = ExerciseMax.highestQualifying(
            listOf(set("w1", 30f, 15), set("w1", 30f, 15))
        )
        assertNull(best)
    }

    @Test
    fun `sets below the rep floor are excluded from the count and the average`() {
        // Two heavy 12-rep sets + three 15-rep sets at 18 -> qualifies on the 18s only.
        val best = ExerciseMax.highestQualifying(
            listOf(
                set("w1", 40f, 12), set("w1", 40f, 12),
                set("w1", 18f, 15), set("w1", 18f, 16), set("w1", 18f, 15),
            )
        )
        assertEquals(18f, best!!.weightKg, 0.001f)
    }

    @Test
    fun `non-normal sets are ignored`() {
        val best = ExerciseMax.highestQualifying(
            listOf(
                set("w1", 50f, 20, type = "warmup"),
                set("w1", 50f, 20, type = "dropset"),
                set("w1", 20f, 15), set("w1", 20f, 15), set("w1", 20f, 15),
            )
        )
        assertEquals(20f, best!!.weightKg, 0.001f)
    }

    @Test
    fun `highest qualifying workout wins across sessions`() {
        val best = ExerciseMax.highestQualifying(
            listOf(
                // session A -> avg 20
                set("a", 20f, 15, start = "2026-01-01T10:00:00+00:00"),
                set("a", 20f, 15, start = "2026-01-01T10:00:00+00:00"),
                set("a", 20f, 15, start = "2026-01-01T10:00:00+00:00"),
                // session B -> avg 25 (higher) on a later date
                set("b", 25f, 15, start = "2026-05-01T10:00:00+00:00"),
                set("b", 25f, 15, start = "2026-05-01T10:00:00+00:00"),
                set("b", 25f, 15, start = "2026-05-01T10:00:00+00:00"),
            )
        )
        assertEquals(25f, best!!.weightKg, 0.001f)
        assertEquals("2026-05-01T10:00:00+00:00", best.workoutStartTime)
    }

    @Test
    fun `sets with null weight are skipped`() {
        val best = ExerciseMax.highestQualifying(
            listOf(set("w1", null, 15), set("w1", 20f, 15), set("w1", 20f, 15))
        )
        // only two usable sets -> does not qualify
        assertNull(best)
    }

    @Test
    fun `empty history yields null`() {
        assertNull(ExerciseMax.highestQualifying(emptyList()))
    }

    // ---- grouping --------------------------------------------------------

    private fun row(muscle: String, equip: String, title: String, kg: Float = 10f, start: String? = null) =
        ExerciseMaxRow("id-${seq++}", title, muscle, equip, kg, start)

    @Test
    fun `groupByMuscle sorts sections alphabetically and exercises by name within a group`() {
        val sections = groupByMuscle(
            listOf(
                row("shoulders", "machine", "Lateral Raise (Machine)"),
                row("biceps", "dumbbell", "Bicep Curl (Dumbbell)"),
                row("shoulders", "dumbbell", "Lateral Raise (Dumbbell)"),
                row("shoulders", "dumbbell", "Arnold Press"),
            )
        )
        assertEquals(listOf("biceps", "shoulders"), sections.map { it.muscleGroup })
        val shoulders = sections.first { it.muscleGroup == "shoulders" }
        assertEquals(3, shoulders.exerciseCount)
        // flat list, equipment is NOT a grouping anymore — pure NAME_ASC order
        assertEquals(
            listOf("Arnold Press", "Lateral Raise (Dumbbell)", "Lateral Raise (Machine)"),
            shoulders.rows.map { it.title },
        )
    }

    @Test
    fun `DATE_DESC orders a muscle group by highest-weight date, newest first`() {
        val sections = groupByMuscle(
            listOf(
                row("shoulders", "dumbbell", "Arnold Press", start = "2026-01-10T10:00:00+00:00"),
                row("shoulders", "machine", "Lateral Raise (Machine)", start = "2026-05-20T10:00:00+00:00"),
                row("shoulders", "dumbbell", "Rear Delt Fly", start = "2026-03-01T10:00:00+00:00"),
            ),
            sort = OverviewSort.DATE_DESC,
        )
        assertEquals(
            listOf("Lateral Raise (Machine)", "Rear Delt Fly", "Arnold Press"),
            sections.single().rows.map { it.title },
        )
    }

    // ---- formatting ------------------------------------------------------

    @Test
    fun `kg trims trailing zeros`() {
        assertEquals("40", OverviewFormat.kg(40f))
        assertEquals("22.5", OverviewFormat.kg(22.5f))
        assertEquals("18.8", OverviewFormat.kg(18.75f))
    }

    @Test
    fun `date renders short form and tolerates null`() {
        assertEquals("Mar 28, '26", OverviewFormat.date("2026-03-28T10:00:00+00:00"))
        assertEquals("", OverviewFormat.date(null))
        assertEquals("", OverviewFormat.date("garbage"))
    }

    // ---- estimated 1RM (Epley) -------------------------------------------

    @Test
    fun `epley formula matches w times one plus reps over thirty`() {
        // 100 kg × 10 reps → 100 × (1 + 10/30) = 133.33
        assertEquals(133.333f, ExerciseMax.epley1rm(100f, 10)!!, 0.01f)
        // A single rep is its own 1RM — the raw Epley expression would give
        // 103.33 here, which is why reps == 1 short-circuits.
        assertEquals(100f, ExerciseMax.epley1rm(100f, 1)!!, 0.01f)
        // The app's usual 15-rep case: ×1.5.
        assertEquals(60f, ExerciseMax.epley1rm(40f, 15)!!, 0.01f)
    }

    @Test
    fun `epley rejects non-positive inputs`() {
        assertNull(ExerciseMax.epley1rm(0f, 10))
        assertNull(ExerciseMax.epley1rm(-5f, 10))
        assertNull(ExerciseMax.epley1rm(50f, 0))
        assertNull(ExerciseMax.epley1rm(50f, -1))
    }

    @Test
    fun `best estimate comes from the hardest set not the average`() {
        // 20×15, 25×15, 30×20 → headline average is 25, but the 1RM estimate
        // should follow the 30 kg × 20 rep set (30 × (1 + 20/30) = 50), not
        // the average (25 × 1.5 = 37.5).
        val best = ExerciseMax.highestQualifying(
            listOf(set("w1", 20f, 15), set("w1", 25f, 15), set("w1", 30f, 20))
        )
        assertEquals(25f, best!!.weightKg, 0.001f)
        assertEquals(50f, best.estimated1rmKg!!, 0.01f)
    }

    @Test
    fun `estimate tracks the winning workout, not an earlier heavier one`() {
        // w1 qualifies at avg 20; w2 qualifies at avg 30 and wins the headline,
        // so the estimate must come from w2's sets even though w1 also had a
        // valid estimate.
        val best = ExerciseMax.highestQualifying(
            listOf(
                set("w1", 20f, 15), set("w1", 20f, 15), set("w1", 20f, 15),
                set("w2", 30f, 15), set("w2", 30f, 15), set("w2", 30f, 15),
            )
        )
        assertEquals(30f, best!!.weightKg, 0.001f)
        assertEquals(45f, best.estimated1rmKg!!, 0.01f)   // 30 × 1.5
    }

    @Test
    fun `no qualifying workout means no estimate`() {
        // Only two qualifying sets — below MIN_SETS.
        assertNull(ExerciseMax.highestQualifying(listOf(set("w1", 20f, 15), set("w1", 20f, 15))))
    }
}
