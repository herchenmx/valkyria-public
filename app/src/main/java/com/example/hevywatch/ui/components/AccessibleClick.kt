package com.example.hevywatch.ui.components

import android.view.accessibility.AccessibilityEvent
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

/**
 * Why this file exists: Jetpack Compose renders the entire composable tree into
 * a single host [android.view.View] ([androidx.compose.ui.platform.AndroidComposeView])
 * and exposes its node tree virtually via an [android.view.accessibility.AccessibilityNodeProvider].
 * `Modifier.clickable` (and every wrapper built on it — Wear `Chip`, `Button`,
 * `CompactButton`) hooks up the click semantically (so TalkBack's
 * `performAction(ACTION_CLICK)` works) but does NOT call
 * `view.sendAccessibilityEvent(TYPE_VIEW_CLICKED)` on touch input. The
 * framework only synthesizes that event when an assistive tool invokes the
 * action, not when a finger taps the screen.
 *
 * External `AccessibilityService`s that filter on `TYPE_VIEW_CLICKED` — e.g.
 * WearControl's SCENE_2 power-profile telemetry sitting on the same watch —
 * therefore see zero events while the user interacts with a pure-Compose UI.
 * The helpers here wrap a normal Compose click so it ALSO dispatches the event
 * through the host View, restoring the observable contract that the older
 * View-based UI used to provide for free.
 *
 * Usage:
 *  - For Wear `Chip` / `Button` / `CompactButton` (anything taking `onClick`):
 *      `Chip(onClick = observableClick { vm.openSet(idx) }, ...)`
 *  - For raw clickables on a `Modifier` chain:
 *      `.observableClickable { vm.dismissBanner() }`
 */

/**
 * Returns a stable `() -> Unit` that dispatches
 * [AccessibilityEvent.TYPE_VIEW_CLICKED] via [LocalView] before invoking
 * [onClick]. Designed for `onClick` parameters of Wear Compose components.
 */
@Composable
fun observableClick(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return {
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        onClick()
    }
}

/**
 * `Modifier.clickable` that ALSO dispatches
 * [AccessibilityEvent.TYPE_VIEW_CLICKED] via [LocalView]. Otherwise identical
 * to a plain `Modifier.clickable { onClick() }`.
 */
@Composable
fun Modifier.observableClickable(onClick: () -> Unit): Modifier {
    val view = LocalView.current
    return this.clickable {
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        onClick()
    }
}
