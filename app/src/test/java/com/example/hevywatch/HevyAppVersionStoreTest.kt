package com.example.hevywatch

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.store.HevyAppVersionStore
import com.example.hevywatch.util.SetApiVersionReceiver
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the runtime-mutable Hevy-App-Version / Hevy-App-Build contract:
 *  - the store starts at the BuildConfig defaults
 *  - update() round-trips both fields across instances
 *  - whitespace is trimmed; partial (blank-one-of-two) updates are rejected so
 *    a fumbled broadcast can't half-update the pair
 *  - the SetApiVersionReceiver writes the intent extras into the store
 *  - wrong action is ignored
 */
@RunWith(RobolectricTestRunner::class)
class HevyAppVersionStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("hevy_app_version", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `store starts at BuildConfig defaults`() {
        val store = HevyAppVersionStore(context)
        assertEquals(BuildConfig.DEFAULT_HEVY_APP_VERSION, store.versionName)
        assertEquals(BuildConfig.DEFAULT_HEVY_APP_BUILD, store.versionCode)
    }

    @Test
    fun `update round-trips across instances`() {
        HevyAppVersionStore(context).update("3.0.13", "2033100")
        val reloaded = HevyAppVersionStore(context)
        assertEquals("3.0.13", reloaded.versionName)
        assertEquals("2033100", reloaded.versionCode)
    }

    @Test
    fun `update trims whitespace`() {
        val store = HevyAppVersionStore(context)
        store.update("  3.0.14  ", "  2034500  ")
        assertEquals("3.0.14", store.versionName)
        assertEquals("2034500", store.versionCode)
    }

    @Test
    fun `update with blank name is rejected to keep the pair consistent`() {
        val store = HevyAppVersionStore(context)
        val originalName = store.versionName
        val originalCode = store.versionCode
        store.update("", "2034500")
        assertEquals(originalName, store.versionName)
        assertEquals(originalCode, store.versionCode)
    }

    @Test
    fun `update with blank code is rejected to keep the pair consistent`() {
        val store = HevyAppVersionStore(context)
        val originalName = store.versionName
        val originalCode = store.versionCode
        store.update("3.0.14", "  ")
        assertEquals(originalName, store.versionName)
        assertEquals(originalCode, store.versionCode)
    }

    @Test
    fun `asPair returns current values`() {
        val store = HevyAppVersionStore(context)
        store.update("3.0.15", "2035000")
        assertEquals("3.0.15" to "2035000", store.asPair())
    }

    @Test
    fun `receiver writes intent extras into the store`() {
        val intent = Intent(SetApiVersionReceiver.ACTION)
            .putExtra(SetApiVersionReceiver.EXTRA_NAME, "3.0.20")
            .putExtra(SetApiVersionReceiver.EXTRA_CODE, "2040000")
        SetApiVersionReceiver().onReceive(context, intent)
        val app = context as Application as HevyApp
        assertEquals("3.0.20", app.hevyAppVersionStore.versionName)
        assertEquals("2040000", app.hevyAppVersionStore.versionCode)
    }

    @Test
    fun `receiver ignores wrong action`() {
        val app = context as Application as HevyApp
        app.hevyAppVersionStore.update("3.0.13", "2033100")
        val intent = Intent("com.example.hevywatch.SOME_OTHER_ACTION")
            .putExtra(SetApiVersionReceiver.EXTRA_NAME, "9.9.9")
            .putExtra(SetApiVersionReceiver.EXTRA_CODE, "999")
        SetApiVersionReceiver().onReceive(context, intent)
        assertEquals("3.0.13", app.hevyAppVersionStore.versionName)
        assertEquals("2033100", app.hevyAppVersionStore.versionCode)
    }
}
