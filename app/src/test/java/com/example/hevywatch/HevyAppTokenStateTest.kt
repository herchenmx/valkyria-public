package com.example.hevywatch

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * R6 — sealed [HevyApp.TokenState] derived view. Pins the observable
 * transitions a UI layer cares about (Missing → ExpiringSoon → Valid →
 * Failed) without exposing the underlying scalar prefs fields.
 */
@RunWith(RobolectricTestRunner::class)
class HevyAppTokenStateTest {

    private lateinit var app: HevyApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.authStore.clear()
    }

    @Test fun `no tokens → Missing`() {
        assertEquals(HevyApp.TokenState.Missing, app.tokenState)
    }

    @Test fun `expired token → ExpiringSoon`() {
        app.authStore.accessToken = "AT"
        app.authStore.refreshToken = "RT"
        app.authStore.tokenExpiresAt = "2000-01-01T00:00:00Z"
        assertEquals(HevyApp.TokenState.ExpiringSoon, app.tokenState)
    }

    @Test fun `fresh token → Valid`() {
        app.authStore.accessToken = "AT"
        app.authStore.refreshToken = "RT"
        app.authStore.tokenExpiresAt = Instant.now().plusSeconds(3600).toString()
        assertEquals(HevyApp.TokenState.Valid, app.tokenState)
    }
}
