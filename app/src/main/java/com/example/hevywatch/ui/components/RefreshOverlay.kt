package com.example.hevywatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.wear.compose.material.MaterialTheme

/**
 * Full-screen black overlay with a centered [RotatingLogo],
 * shown during user-tapped Refresh on every list screen.
 *
 * The covered list (folders / routines / recent / exercises / progress)
 * stays *mounted* underneath — only this overlay hides it. That's load-
 * bearing for two reasons:
 *
 * 1. The underlying `ScalingLazyListState` stays attached to a real laid-out
 *    column, so the post-refresh `animateScrollToItem(0)` call on the
 *    isRefreshing → false transition reliably scrolls to the top. (Early-
 *    returning past the column unmounts it; the listState then has nothing
 *    to scroll until the next composition lands a new layout, by which
 *    point our scroll call has already no-op'd.)
 * 2. State declared inside the same composable — `currentPage`, expanded-
 *    chip ids, etc. — survives a refresh. With early-return, slots after
 *    the return point lose their values when execution skips them.
 *
 * Pointer events are absorbed so taps on the (hidden) chips below don't
 * trigger anything mid-refresh.
 */
@Composable
fun RefreshOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .pointerInput(Unit) {
                // Swallow taps so the user can't fire a chip while the
                // refresh is still in flight.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        RotatingLogo()
    }
}
