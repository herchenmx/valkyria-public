package com.example.hevywatch.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.example.hevywatch.R

/**
 * Loading spinner rendered as the valkyria launcher logo rotating clockwise.
 *
 * Defaults to **fullscreen** (`Modifier.fillMaxSize()` + `ContentScale.Fit`):
 * the orange disc fills the inscribed circle of the watch viewport. Pass an
 * explicit [size] to render an inline-compact spinner — e.g. next to a
 * "Loading history…" caption, where a fullscreen logo would clobber the
 * surrounding text.
 */
@Composable
fun RotatingLogo(
    modifier: Modifier = Modifier,
    size: Dp? = null,
    periodMillis: Int = 1200,
) {
    val transition = rememberInfiniteTransition(label = "rotating-logo")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotating-logo-angle",
    )
    val sizeModifier = if (size != null) Modifier.size(size) else Modifier.fillMaxSize()
    Image(
        painter = painterResource(id = R.drawable.ic_launcher_foreground),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .then(sizeModifier)
            .rotate(angle),
    )
}
