package com.example.hevywatch.wear

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Hard gate for the watch's first API call. Wear OS tethers internet through
 * the paired phone over Bluetooth; if the radio is off or the phone isn't
 * reachable, the auth/refresh request just times out with a misleading
 * "socket closed" error from deep inside OkHttp. Front-loading the check lets
 * the ModeSelectionScreen show "Enable Bluetooth" / "Connect your phone"
 * instead.
 *
 * "Connected" means: BluetoothAdapter.isEnabled AND at least one Wear node
 * with [com.google.android.gms.wearable.Node.isNearby] = true (i.e. directly
 * reachable, not via cloud sync — cloud-only would still leave the watch
 * effectively phoneless for our purposes).
 */
object PhoneLink {

    sealed interface State {
        object Ready : State
        object BluetoothOff : State
        object PhoneNotConnected : State
    }

    /** Short — we don't want the splash to hang. If the NodeClient call times
     *  out we treat that as PhoneNotConnected and let the poll loop retry. */
    private const val NODE_TIMEOUT_SECONDS = 3L

    /** One-shot snapshot. Suspends on [Dispatchers.IO] for the NodeClient call. */
    suspend fun currentState(context: Context): State = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return@withContext State.BluetoothOff
        val nodes = try {
            Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                NODE_TIMEOUT_SECONDS, TimeUnit.SECONDS,
            )
        } catch (_: Throwable) {
            return@withContext State.PhoneNotConnected
        }
        if (nodes.any { it.isNearby }) State.Ready else State.PhoneNotConnected
    }

    /** Polls [currentState] until it returns [State.Ready]. [onState] fires
     *  after every poll so callers can drive UI feedback. */
    suspend fun awaitReady(
        context: Context,
        pollMs: Long = 1000L,
        onState: ((State) -> Unit)? = null,
    ) {
        while (true) {
            val s = currentState(context)
            onState?.invoke(s)
            if (s is State.Ready) return
            delay(pollMs)
        }
    }

    /** UI string for a non-Ready state. Pure — exposed for testing. */
    fun userMessage(state: State): String = when (state) {
        State.BluetoothOff -> "Turn on Bluetooth"
        State.PhoneNotConnected -> "Connect your phone"
        State.Ready -> ""
    }
}
