package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExerciseAdvisor's own logic is the equipment gate and the pass-through of the
 * pieces it composes (ProgressiveOverload / WarmupAdvisor / LastSessionStats,
 * each tested separately). These pin the gate — the bit most likely to drift —
 * without re-deriving the PO/warmup math.
 */
class ExerciseAdvisorTest {

    private fun set(workoutId: String, weight: Float?, reps: Int = 10, type: String = "normal") =
        ExerciseHistoryEntry(workoutId = workoutId, weightKg = weight, reps = reps, setType = type)

    private val historyOneNormalSet = listOf(set("w1", 50f))

    private fun advise(equipment: String?, history: List<ExerciseHistoryEntry> = historyOneNormalSet) =
        ExerciseAdvisor.adviseFor(
            history = history,
            equipment = equipment,
            primaryMuscleGroup = "chest",
            exerciseTemplateId = "tpl-1",
            normalSetCount = 3,
            bodyweightKg = 80f,
        )

    @Test fun `equipment present opens the PO gate (carry target from the last normal set)`() {
        // With real equipment and a logged normal set, ProgressiveOverload's
        // carry fallback yields a non-null target (the last working weight).
        val advice = advise(equipment = "barbell")
        assertEquals(50f, advice.po.targetKg!!, 0.001f)
    }

    @Test fun `bodyweight equipment values gate PO off (no target, no warmups)`() {
        // The same rich history must NOT produce a PO target or warmups when the
        // exercise carries no external load — that is ExerciseAdvisor's job, not
        // the caller's. "" / "none" / "None" (case) / null all mean bodyweight.
        for (eq in listOf("", "none", "None", "NONE", null)) {
            val advice = advise(equipment = eq)
            assertNull("equipment=$eq should gate PO off", advice.po.targetKg)
            assertTrue("equipment=$eq should have no warmups", advice.warmups.isEmpty())
        }
    }

    @Test fun `avgLastNormalKg is passed through from LastSessionStats`() {
        // Newest-first: w2 is the last session (60/70 → 65), w1 (100) is older.
        val history = listOf(
            set("w2", 60f), set("w2", 70f),
            set("w1", 100f),
        )
        val advice = advise(equipment = "barbell", history = history)
        assertEquals(65f, advice.avgLastNormalKg!!, 0.001f)
        // Independent of equipment — the last-session average is a raw history stat.
        val bodyweightAdvice = advise(equipment = "none", history = history)
        assertEquals(65f, bodyweightAdvice.avgLastNormalKg!!, 0.001f)
    }
}
