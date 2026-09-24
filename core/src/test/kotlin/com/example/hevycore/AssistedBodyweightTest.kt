package com.example.hevycore

import com.example.hevycore.exercise.AssistedBodyweight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistedBodyweightTest {

    @Test
    fun `four assisted template ids are recognised`() {
        assertTrue(AssistedBodyweight.isAssisted("2C37EC5E"))  // Chest dip
        assertTrue(AssistedBodyweight.isAssisted("4B4BF8C2"))  // Tricep dip
        assertTrue(AssistedBodyweight.isAssisted("D23C609B"))  // Pull-up
        assertTrue(AssistedBodyweight.isAssisted("E9E4089F"))  // Chin-up
    }

    @Test
    fun `lower case template id is still recognised`() {
        // Some upstream sources shape the id as lowercase; the check must be
        // case-insensitive so consumers don't have to normalise.
        assertTrue(AssistedBodyweight.isAssisted("2c37ec5e"))
    }

    @Test
    fun `unknown template id is not assisted`() {
        assertFalse(AssistedBodyweight.isAssisted("ABCDEF12"))
        assertFalse(AssistedBodyweight.isAssisted(null))
        assertFalse(AssistedBodyweight.isAssisted(""))
    }

    @Test
    fun `effective work is bodyweight minus logged`() {
        assertEquals(30f, AssistedBodyweight.toEffective(loggedKg = 27f, bodyweightKg = 57f))
    }

    @Test
    fun `effective work floors at zero`() {
        // Logged > bodyweight means "the machine is taking off more than the
        // lifter weighs" — nonsensical but must not go negative (weights are
        // non-negative on the wire).
        assertEquals(0f, AssistedBodyweight.toEffective(loggedKg = 60f, bodyweightKg = 57f))
    }

    @Test
    fun `toLogged is inverse of toEffective`() {
        val bw = 57f
        val originalLogged = 27f
        val effective = AssistedBodyweight.toEffective(originalLogged, bw)
        val recovered = AssistedBodyweight.toLogged(effective, bw)
        assertEquals(originalLogged, recovered)
    }
}
