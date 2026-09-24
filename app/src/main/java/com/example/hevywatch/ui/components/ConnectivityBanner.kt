package com.example.hevywatch.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.hevywatch.HevyApp
import com.example.hevywatch.ui.theme.hevyExtendedColors

/**
 * A slim strip shown above workout screens while the watch has no Internet-capable
 * network. The companion bridges over Bluetooth when no WiFi is around, so
 * "no internet" usually means "phone is out of range or offline" — this banner
 * is the signal that a Finish tap would fail the connectivity precheck.
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isOnline by rememberConnectivityState(context)
    if (isOnline) return
    val ext = hevyExtendedColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(ext.bannerOfflineBg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Offline — Finish will queue",
            style = MaterialTheme.typography.caption3,
            color = ext.bannerOfflineFg,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Sibling banner to [OfflineBanner] for when token refresh has failed. The
 * watch can be online (BT to phone) but still unable to mint a fresh access
 * token (e.g. refresh-token rotation hasn't reached the watch yet). Without
 * this signal the user only finds out at the next Finish tap.
 */
@Composable
fun RefreshErrorBanner(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as? HevyApp ?: return
    val msg = app.tokenRefreshError ?: return
    val ext = hevyExtendedColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(ext.bannerWarnBg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = msg,
            style = MaterialTheme.typography.caption3,
            color = ext.bannerWarnFg,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Surfaces [HevyApp.companionSyncError]: the watch refreshed tokens
 * successfully but couldn't push the new triple back to the companion phone.
 * The watch is fully functional in this state — the only thing that's stale
 * is the home-screen widget on the phone. Quieter color than
 * [RefreshErrorBanner] (muted blue) because no user action is strictly
 * required: opening the companion app, or any subsequent successful push,
 * clears it.
 */
@Composable
fun CompanionSyncBanner(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as? HevyApp ?: return
    val msg = app.companionSyncError ?: return
    val ext = hevyExtendedColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(ext.bannerInfoBg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = msg,
            style = MaterialTheme.typography.caption3,
            color = ext.bannerInfoFg,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun rememberConnectivityState(context: Context): State<Boolean> =
    produceState(initialValue = currentlyOnline(context), context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) { value = true; return@produceState }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { value = true }
            override fun onLost(network: Network) { value = currentlyOnline(context) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        // Seed with a fresh read to avoid races with the first callback.
        value = currentlyOnline(context)
        cm.registerDefaultNetworkCallback(callback)
        awaitDispose { cm.unregisterNetworkCallback(callback) }
    }

private fun currentlyOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
