package com.example.hevywatch

import com.example.hevywatch.presentation.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tile is an exported component — any process on the device can craft an
 * Intent at MainActivity with a chosen `navigate_to` extra. The sanitizer is
 * the gate that keeps the deep-link surface to known routes only.
 *
 * Pinning each accept/reject case here so adding a new tile destination
 * forces an explicit add to the allow-list (and a new test) rather than
 * silently widening the surface.
 */
class TileRouteSanitizerTest {

    @Test fun `null input returns null`() {
        assertNull(Screen.sanitizeTileRoute(null))
    }

    @Test fun `empty input returns null`() {
        assertNull(Screen.sanitizeTileRoute(""))
    }

    @Test fun `literal allow-list routes round-trip`() {
        listOf(
            Screen.ROUTINE_FOLDERS,
            Screen.LOG_WORKOUT,
            Screen.LOG_SET,
            Screen.WORKOUT_CONTROL,
        ).forEach { route ->
            assertEquals(route, Screen.sanitizeTileRoute(route))
        }
    }

    @Test fun `routine_detail with safe id is accepted`() {
        assertEquals(
            "routine_detail/abc123",
            Screen.sanitizeTileRoute("routine_detail/abc123"),
        )
        assertEquals(
            "routine_detail/UUID-WITH-DASH_AND_UNDERSCORE",
            Screen.sanitizeTileRoute("routine_detail/UUID-WITH-DASH_AND_UNDERSCORE"),
        )
    }

    @Test fun `routine_detail with empty id is rejected`() {
        assertNull(Screen.sanitizeTileRoute("routine_detail/"))
    }

    @Test fun `routine_detail with path traversal characters is rejected`() {
        // Anything outside [A-Za-z0-9_-] for the id segment is rejected,
        // including slashes that would let the id smuggle a second segment.
        assertNull(Screen.sanitizeTileRoute("routine_detail/../settings"))
        assertNull(Screen.sanitizeTileRoute("routine_detail/abc/extra"))
        assertNull(Screen.sanitizeTileRoute("routine_detail/abc?"))
        assertNull(Screen.sanitizeTileRoute("routine_detail/a b c"))
    }

    @Test fun `unknown literal route is rejected`() {
        assertNull(Screen.sanitizeTileRoute("settings"))
        assertNull(Screen.sanitizeTileRoute("congrats"))
        assertNull(Screen.sanitizeTileRoute("anything_else"))
    }

    @Test fun `routine_list deep-link is rejected`() {
        // Tiles never deep-link into the folder's routine list — the tile
        // is itself the routine list. Block the prefix so a forged Intent
        // can't do an end-run around the navigation choices.
        assertNull(Screen.sanitizeTileRoute("routine_list/123"))
    }
}
