package com.example.hevywatch

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.hevywatch.ui.components.observableClick
import com.example.hevywatch.ui.components.observableClickable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Locks in the [observableClick] / [observableClickable] contract: a Compose
 * click handler runs as usual AND the host View dispatches
 * `TYPE_VIEW_CLICKED` so external AccessibilityServices can observe the tap.
 * Compose's own clickable doesn't do this for touch input — see KDoc on
 * [com.example.hevywatch.ui.components.observableClick].
 */
@RunWith(RobolectricTestRunner::class)
class ObservableClickTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var accessibilityManager: AccessibilityManager

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(accessibilityManager).setEnabled(true)
    }

    @Test
    fun observableClickable_dispatchesViewClickedAndRunsOnClick() {
        var clicks = 0

        composeRule.setContent {
            MaterialTheme {
                Text(
                    "tap-me",
                    modifier = Modifier
                        .size(80.dp)
                        .observableClickable { clicks++ },
                )
            }
        }

        composeRule.onNodeWithText("tap-me").performClick()
        composeRule.waitForIdle()

        assertEquals(1, clicks)
        val sent = shadowOf(accessibilityManager).sentAccessibilityEvents
        assertTrue(
            "Expected TYPE_VIEW_CLICKED, got: " + sent.map { it.eventType },
            sent.any { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED },
        )
    }

    @Test
    fun observableClick_wrapsButtonOnClickAndDispatchesViewClicked() {
        var clicks = 0

        composeRule.setContent {
            MaterialTheme {
                Button(onClick = observableClick { clicks++ }) {
                    Text("press")
                }
            }
        }

        composeRule.onNodeWithText("press").performClick()
        composeRule.waitForIdle()

        assertEquals(1, clicks)
        val sent = shadowOf(accessibilityManager).sentAccessibilityEvents
        assertTrue(
            "Expected TYPE_VIEW_CLICKED, got: " + sent.map { it.eventType },
            sent.any { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED },
        )
    }
}
