package com.example.hevywatch

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.store.WifiPriorityStore
import com.example.hevywatch.util.WifiPriorityReceiver
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the runtime-mutable Wi-Fi priority contract:
 *  - parsePriority normalises whitespace and skips blank entries
 *  - the store round-trips a CSV across instances
 *  - the BroadcastReceiver updates the store from an Intent extra
 */
@RunWith(RobolectricTestRunner::class)
class WifiPriorityStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("wifi_priority", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `parsePriority splits and trims`() {
        assertEquals(
            listOf("Home", "Phone", "Gym"),
            WifiPriorityStore.parsePriority("  Home , Phone,Gym "),
        )
    }

    @Test
    fun `parsePriority drops blank entries`() {
        assertEquals(
            listOf("Home", "Gym"),
            WifiPriorityStore.parsePriority("Home,,  ,Gym,"),
        )
    }

    @Test
    fun `parsePriority empty input gives empty list`() {
        assertEquals(emptyList<String>(), WifiPriorityStore.parsePriority(""))
        assertEquals(emptyList<String>(), WifiPriorityStore.parsePriority("   "))
        assertEquals(emptyList<String>(), WifiPriorityStore.parsePriority(",,,"))
    }

    @Test
    fun `store round-trips a CSV across instances`() {
        WifiPriorityStore(context).update("A,B,C")
        assertEquals(listOf("A", "B", "C"), WifiPriorityStore(context).priority)
    }

    @Test
    fun `receiver writes the intent extra into the store`() {
        val intent = Intent(WifiPriorityReceiver.ACTION)
            .putExtra(WifiPriorityReceiver.EXTRA_PRIORITY, "FRITZ!Box 7520 DU,WLAN-077848")
        WifiPriorityReceiver().onReceive(context, intent)
        val app = context as Application as com.example.hevywatch.HevyApp
        assertEquals(
            listOf("FRITZ!Box 7520 DU", "WLAN-077848"),
            app.wifiPriorityStore.priority,
        )
    }

    @Test
    fun `receiver clears the store when extra is blank`() {
        // Seed
        val app = context as Application as com.example.hevywatch.HevyApp
        app.wifiPriorityStore.update("A,B")
        // Clear via broadcast
        val intent = Intent(WifiPriorityReceiver.ACTION)
            .putExtra(WifiPriorityReceiver.EXTRA_PRIORITY, "")
        WifiPriorityReceiver().onReceive(context, intent)
        assertEquals(emptyList<String>(), app.wifiPriorityStore.priority)
    }

    @Test
    fun `receiver ignores wrong action`() {
        val app = context as Application as com.example.hevywatch.HevyApp
        app.wifiPriorityStore.update("seed")
        val intent = Intent("com.example.hevywatch.SOME_OTHER_ACTION")
            .putExtra(WifiPriorityReceiver.EXTRA_PRIORITY, "ignored")
        WifiPriorityReceiver().onReceive(context, intent)
        assertEquals(listOf("seed"), app.wifiPriorityStore.priority)
    }
}
