package com.example.hevywatch.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.hevywatch.R

/**
 * Cold-start splash: full-screen black with the valkyria launcher logo
 * filling the viewport. Shown for [SPLASH_DURATION_MS] before the NavHost
 * takes over. The orange disc sits inside a square 432-px raster, so on the
 * round Wear viewport the disc fills the inscribed circle edge-to-edge.
 */
@Composable
fun SplashLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

const val SPLASH_DURATION_MS: Long = 1200L
