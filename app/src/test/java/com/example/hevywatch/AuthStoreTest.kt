package com.example.hevywatch

import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.store.AuthStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the isLoggedIn invariant the rest of the app depends on — both the API
 * key (public mode) and access token (private mode) must be present and
 * non-blank. The AND (not OR) and the blank-vs-null handling are the two bug
 * surfaces most likely to drift.
 */
@RunWith(RobolectricTestRunner::class)
class AuthStoreTest {

    private lateinit var store: AuthStore

    @Before
    fun setUp() {
        store = AuthStore(ApplicationProvider.getApplicationContext())
        store.clear()
    }

    @Test
    fun `fresh state is empty and not logged in`() {
        assertNull(store.apiKey)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertNull(store.tokenExpiresAt)
        assertFalse(store.isLoggedIn)
    }

    @Test
    fun `isLoggedIn requires both apiKey and accessToken`() {
        store.apiKey = "abc"
        assertFalse("apiKey alone is not logged in", store.isLoggedIn)

        store.apiKey = null
        store.accessToken = "xyz"
        assertFalse("accessToken alone is not logged in", store.isLoggedIn)

        store.apiKey = "abc"
        assertTrue("both present is logged in", store.isLoggedIn)
    }

    @Test
    fun `blank apiKey or accessToken is treated as not logged in`() {
        store.apiKey = ""
        store.accessToken = "xyz"
        assertFalse("blank apiKey must not count as logged in", store.isLoggedIn)

        store.apiKey = "abc"
        store.accessToken = ""
        assertFalse("blank accessToken must not count as logged in", store.isLoggedIn)
    }

    @Test
    fun `assigning null removes the preference`() {
        store.apiKey = "abc"
        assertEquals("abc", store.apiKey)
        store.apiKey = null
        assertNull(store.apiKey)
    }

    @Test
    fun `every field round-trips through SharedPreferences`() {
        store.apiKey = "k"
        store.accessToken = "at"
        store.refreshToken = "rt"
        store.tokenExpiresAt = "2030-01-01T00:00:00Z"

        assertEquals("k", store.apiKey)
        assertEquals("at", store.accessToken)
        assertEquals("rt", store.refreshToken)
        assertEquals("2030-01-01T00:00:00Z", store.tokenExpiresAt)
        assertTrue(store.isLoggedIn)
    }

    @Test
    fun `clear wipes every field`() {
        store.apiKey = "k"
        store.accessToken = "at"
        store.refreshToken = "rt"
        store.tokenExpiresAt = "exp"

        store.clear()

        assertNull(store.apiKey)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertNull(store.tokenExpiresAt)
        assertFalse(store.isLoggedIn)
    }
}
