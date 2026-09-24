package com.example.hevycompanion.recents

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resume is the only submit path that takes a set type *from the server* and
 * sends it straight back: every other builder emits our own enum value, which
 * cannot be anything the API rejects. Echoing the raw `indicator` means any
 * value Hevy introduces after this build ships goes back out in the next
 * request and gets rejected — on both the private v2 POST and the public v1
 * PUT, and on both the watch and the phone, while a normal workout POST keeps
 * working because it never echoes.
 *
 * These lock in that an unrecognised indicator degrades to "normal" instead.
 */
class ResumeSetTypeSanitizeTest {

    @Test
    fun `known set types pass through unchanged`() {
        for (t in listOf("normal", "warmup", "dropset", "failure")) {
            assertEquals(t, ResumeRequestBuilder.sanitizeSetType(t))
        }
    }

    @Test
    fun `a set type Hevy adds later degrades to normal instead of being echoed`() {
        assertEquals("normal", ResumeRequestBuilder.sanitizeSetType("cooldown"))
        assertEquals("normal", ResumeRequestBuilder.sanitizeSetType("amrap"))
    }

    @Test
    fun `null and blank degrade to normal`() {
        assertEquals("normal", ResumeRequestBuilder.sanitizeSetType(null))
        assertEquals("normal", ResumeRequestBuilder.sanitizeSetType(""))
    }

    @Test
    fun `matching is exact, not case-insensitive or trimmed`() {
        // The API vocabulary is lower-case; anything else is not a value we can
        // safely assert, so it degrades rather than being normalised on a guess.
        assertEquals("normal", ResumeRequestBuilder.sanitizeSetType("Warmup"))
        assertEquals("normal", ResumeRequestBuilder.sanitizeSetType(" warmup "))
    }
}
