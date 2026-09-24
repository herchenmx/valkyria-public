package com.example.hevycompanion.overview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseMaxCsvTest {

    private fun row(
        muscle: String,
        equip: String,
        title: String,
        kg: Float,
        start: String?,
        oneRm: Float? = null,
    ) = ExerciseMaxRow("id-$title", title, muscle, equip, kg, start, oneRm)

    @Test
    fun `header is first line`() {
        val csv = ExerciseMaxCsv.toCsv(emptyList())
        assertEquals("Muscle Group,Exercise,Equipment,Highest KG,Est 1RM KG,Date\r\n", csv)
    }

    @Test
    fun `titles with commas are quoted and labels resolved`() {
        val csv = ExerciseMaxCsv.toCsv(
            listOf(row("abdominals", "machine", "Crunch (Egym, regular)", 19f, "2025-07-13T10:00:00+00:00"))
        )
        // comma inside the title forces quoting; equipment label capitalised; kg trimmed
        assertTrue(csv.contains("Abdominals,\"Crunch (Egym, regular)\",Machine,19,,\"Jul 13, '25\""))
    }

    @Test
    fun `estimated 1RM renders between weight and date`() {
        val csv = ExerciseMaxCsv.toCsv(
            listOf(row("shoulders", "machine", "Shoulder Press", 40f, null, oneRm = 60f))
        )
        assertTrue("expected 1RM column populated: $csv", csv.contains(",40,60,"))
    }

    @Test
    fun `missing 1RM leaves the column empty`() {
        val csv = ExerciseMaxCsv.toCsv(
            listOf(row("shoulders", "machine", "Shoulder Press", 40f, null, oneRm = null))
        )
        assertTrue("expected empty 1RM column: $csv", csv.contains(",40,,"))
    }

    @Test
    fun `rows follow muscle then name ordering`() {
        val csv = ExerciseMaxCsv.toCsv(
            listOf(
                row("shoulders", "machine", "Lateral Raise (Machine)", 18f, null),
                row("biceps", "dumbbell", "Bicep Curl (Dumbbell)", 14f, null),
                row("shoulders", "dumbbell", "Arnold Press", 12f, null),
            )
        )
        val lines = csv.trim().lines().drop(1).map { it.substringBefore(',') }
        // Biceps section first (alphabetical), then Shoulders rows in name order
        assertEquals(listOf("Biceps", "Shoulders", "Shoulders"), lines)
        val titles = csv.trim().lines().drop(1).map { it.split(',')[1] }
        assertEquals("Arnold Press", titles[1])
        assertEquals("Lateral Raise (Machine)", titles[2])
    }

    @Test
    fun `none equipment renders as Bodyweight`() {
        val csv = ExerciseMaxCsv.toCsv(
            listOf(row("abdominals", "none", "Hanging Leg Raise", 5f, null))
        )
        assertTrue(csv.contains(",Bodyweight,"))
    }
}
