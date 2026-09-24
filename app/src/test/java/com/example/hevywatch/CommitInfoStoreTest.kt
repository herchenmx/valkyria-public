package com.example.hevywatch

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.store.CommitInfoStore
import com.example.hevywatch.util.CommitInfoReceiver
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the runtime-mutable commit-hash contract:
 *  - the store round-trips the hash across instances
 *  - the BroadcastReceiver writes the intent extra into the store
 *  - whitespace is trimmed; blank/clear works
 *  - wrong action is ignored
 */
@RunWith(RobolectricTestRunner::class)
class CommitInfoStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("commit_info", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `store starts empty`() {
        assertEquals("", CommitInfoStore(context).commitHash)
    }

    @Test
    fun `store round-trips a hash across instances`() {
        CommitInfoStore(context).update("abc1234")
        assertEquals("abc1234", CommitInfoStore(context).commitHash)
    }

    @Test
    fun `update trims whitespace`() {
        val store = CommitInfoStore(context)
        store.update("  deadbeef  ")
        assertEquals("deadbeef", store.commitHash)
    }

    @Test
    fun `receiver writes the intent extra into the store`() {
        val intent = Intent(CommitInfoReceiver.ACTION)
            .putExtra(CommitInfoReceiver.EXTRA_HASH, "7e2c2b0")
        CommitInfoReceiver().onReceive(context, intent)
        val app = context as Application as HevyApp
        assertEquals("7e2c2b0", app.commitInfoStore.commitHash)
    }

    @Test
    fun `receiver clears the store when extra is blank`() {
        val app = context as Application as HevyApp
        app.commitInfoStore.update("seedhash")
        val intent = Intent(CommitInfoReceiver.ACTION)
            .putExtra(CommitInfoReceiver.EXTRA_HASH, "")
        CommitInfoReceiver().onReceive(context, intent)
        assertEquals("", app.commitInfoStore.commitHash)
    }

    @Test
    fun `receiver ignores wrong action`() {
        val app = context as Application as HevyApp
        app.commitInfoStore.update("seed")
        val intent = Intent("com.example.hevywatch.SOME_OTHER_ACTION")
            .putExtra(CommitInfoReceiver.EXTRA_HASH, "ignored")
        CommitInfoReceiver().onReceive(context, intent)
        assertEquals("seed", app.commitInfoStore.commitHash)
    }
}
