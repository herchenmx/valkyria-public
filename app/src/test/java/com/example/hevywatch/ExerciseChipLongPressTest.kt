package com.example.hevywatch

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material.MaterialTheme
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.presentation.workout.ExerciseChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Guards the contract that a long-press on an ExerciseChip emits both the
 * in-app behaviour (`onLongClick` fires, `onClick` does not) AND the
 * `TYPE_VIEW_LONG_CLICKED` accessibility event that external AccessibilityServices
 * (e.g. WearControl's power-profile telemetry) depend on.
 *
 * The chip's `pointerInput` consumes the up event on long-press so the Chip's
 * internal `Modifier.clickable` is suppressed — that suppression also drops
 * the framework's accessibility-event dispatch, which is why
 * `ExerciseChip` calls `view.sendAccessibilityEvent(TYPE_VIEW_LONG_CLICKED)`
 * explicitly. This test pins both halves of that contract.
 */
@RunWith(RobolectricTestRunner::class)
class ExerciseChipLongPressTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var accessibilityManager: AccessibilityManager

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        // View.sendAccessibilityEvent silently no-ops when the manager reports
        // accessibility disabled; enable it for the test so the call lands.
        shadowOf(accessibilityManager).setEnabled(true)
    }

    @Test
    fun longPress_dispatchesViewLongClickedAndRunsOnLongClickWithoutOnClick() {
        var clickCount = 0
        var longClickCount = 0

        composeRule.setContent {
            MaterialTheme {
                ExerciseChip(
                    exercise = ActiveExercise(
                        exerciseTemplateId = "tpl-1",
                        title = "Bench Press",
                    ),
                    targetWeightKg = null,
                    poWillIncrease = false,
                    lastSessionEntries = emptyList(),
                    isExpanded = false,
                    onClick = { clickCount++ },
                    onLongClick = { longClickCount++ },
                )
            }
        }

        composeRule.onNodeWithText("Bench Press")
            .performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals("onClick must not fire on long-press", 0, clickCount)
        assertEquals("onLongClick must fire exactly once", 1, longClickCount)

        val sent = shadowOf(accessibilityManager).sentAccessibilityEvents
        assertTrue(
            "Expected at least one TYPE_VIEW_LONG_CLICKED event, got: " +
                sent.map { it.eventType },
            sent.any { it.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED },
        )
    }
}
