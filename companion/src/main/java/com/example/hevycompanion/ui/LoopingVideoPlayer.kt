package com.example.hevycompanion.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * R8 — single source for the silent-looping mp4 player used by both the
 * long-press exercise preview and the detail screen. Uses the framework
 * [VideoView] (MediaPlayer under the hood) rather than pulling in ExoPlayer:
 * the catalog clips are short H.264 loops and don't justify the extra
 * dependency.
 *
 * Wrapped in `key(url)` because some callers re-target the player in place:
 * same composable slot, different URL. `AndroidView.factory` only runs once
 * per slot, so without re-keying the old `VideoView` would stay on screen
 * until the slot was disposed (e.g. by scrolling away and back). Re-keying
 * forces a fresh slot — old VideoView torn down via [DisposableEffect], new
 * one constructed with the new URI.
 */
@Composable
fun LoopingVideoPlayer(url: String, modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    key(url) {
        val videoView = remember {
            VideoView(context).apply {
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    // These clips have no audio track but be explicit — we
                    // never want sound in a long-press preview.
                    runCatching { mp.setVolume(0f, 0f) }
                    start()
                }
            }
        }
        DisposableEffect(videoView) { onDispose { videoView.stopPlayback() } }
        AndroidView(factory = { videoView }, modifier = modifier)
    }
}
