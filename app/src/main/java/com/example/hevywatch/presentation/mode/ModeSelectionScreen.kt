package com.example.hevywatch.presentation.mode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.foundation.layout.Row
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.example.hevywatch.ui.components.WorkoutAwareTimeText
import com.example.hevywatch.HevyApp
import com.example.hevywatch.MainActivity
import com.example.hevywatch.data.api.model.WorkoutPostRequest
import com.example.hevywatch.data.api.model.WorkoutPutRequest
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.ui.components.RotatingLogo
import com.example.hevywatch.wear.PhoneLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Auto-discard threshold for crash-recovered workouts. Anything started more
 * than 24h ago is treated as abandoned (battery died mid-set, user never
 * resumed) — without this cap the resume prompt would re-appear every
 * launch indefinitely, and a "yes" tap would save a workout dated days ago.
 */
private const val STRANDED_WORKOUT_MAX_AGE_MS = 24L * 60L * 60L * 1000L

@Composable
fun ModeSelectionScreen(navController: NavController) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as HevyApp }
    val scope = rememberCoroutineScope()

    var error by remember { mutableStateOf<String?>(null) }
    var showRecoveryPrompt by remember { mutableStateOf(false) }
    var recoveredWorkoutName by remember { mutableStateOf("") }
    var showPendingRetryPrompt by remember { mutableStateOf(false) }
    var isSendingPending by remember { mutableStateOf(false) }
    var pendingSendError by remember { mutableStateOf<String?>(null) }
    /** Polled in parallel with the main startup effect so the user sees
     *  "Turn on Bluetooth" / "Connect your phone" while [HevyApp.activatePrivateMode]
     *  is suspended on the same gate. */
    var linkState by remember { mutableStateOf<PhoneLink.State>(PhoneLink.State.Ready) }

    LaunchedEffect(Unit) {
        while (true) {
            linkState = PhoneLink.currentState(context)
            if (linkState is PhoneLink.State.Ready) return@LaunchedEffect
            delay(1000L)
        }
    }

    val activity = context as? MainActivity
    val tileRoute = activity?.tileNavigateTo

    // Auto-activate private mode on first composition
    LaunchedEffect(Unit) {
        // Active workout in memory → go straight to it
        if (app.activeWorkout != null) {
            val dest = tileRoute ?: Screen.LOG_WORKOUT
            activity?.tileNavigateTo = null
            navController.navigate(Screen.LOG_WORKOUT) {
                popUpTo(Screen.MODE_SELECTION) { inclusive = true }
            }
            if (dest == Screen.LOG_SET) {
                navController.navigate(Screen.LOG_SET)
            }
            return@LaunchedEffect
        }

        // Check for crash-recovered workout on disk. Auto-discard anything
        // older than 24h — those are abandoned (battery died mid-set, user
        // never came back) and resuming them would either save a stale
        // workout dated days ago or pile up confirmation prompts forever.
        val recovered = app.activeWorkoutStore.load()
        if (recovered != null) {
            val ageMs = System.currentTimeMillis() - recovered.startTimeMs
            if (ageMs > STRANDED_WORKOUT_MAX_AGE_MS) {
                app.activeWorkoutStore.clear()
            } else {
                recoveredWorkoutName = recovered.name
                showRecoveryPrompt = true
                return@LaunchedEffect
            }
        }

        // Check for unsent workout request (failed send, app killed before retry)
        if (app.pendingRequestStore.hasPending()) {
            showPendingRetryPrompt = true
            return@LaunchedEffect
        }

        // Normal startup
        try {
            app.activatePrivateMode()
            val dest = tileRoute ?: Screen.ROUTINE_FOLDERS
            activity?.tileNavigateTo = null
            navController.navigate(dest) {
                popUpTo(Screen.MODE_SELECTION) { inclusive = true }
            }
        } catch (e: Exception) {
            error = e.message ?: "Connection failed"
        }
    }

    Scaffold(timeText = { WorkoutAwareTimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (showPendingRetryPrompt) {
                    // Unsent workout request found on disk
                    Text(
                        text = "Retry unsent workout?",
                        style = MaterialTheme.typography.title3,
                        color = MaterialTheme.colors.onPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    val method = app.pendingRequestStore.getPendingMethod() ?: "?"
                    Text(
                        text = "A $method request failed to send",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center
                    )
                    if (pendingSendError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = pendingSendError!!,
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (isSendingPending) {
                        // Explicit size — RotatingLogo's default is fullscreen,
                        // which would overlay the "Retry unsent workout?" title
                        // and the pending-request caption above it.
                        RotatingLogo(size = 40.dp)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    app.pendingRequestStore.clear()
                                    showPendingRetryPrompt = false
                                    scope.launch {
                                        try {
                                            app.activatePrivateMode()
                                            navController.navigate(Screen.ROUTINE_FOLDERS) {
                                                popUpTo(Screen.MODE_SELECTION) { inclusive = true }
                                            }
                                        } catch (e: Exception) {
                                            error = e.message ?: "Connection failed"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.secondaryButtonColors()
                            ) {
                                Text("N")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        isSendingPending = true
                                        pendingSendError = null
                                        try {
                                            retrySendPending(app)
                                            showPendingRetryPrompt = false
                                            app.activatePrivateMode()
                                            navController.navigate(Screen.ROUTINE_FOLDERS) {
                                                popUpTo(Screen.MODE_SELECTION) { inclusive = true }
                                            }
                                        } catch (e: Exception) {
                                            pendingSendError = e.message ?: "Send failed"
                                        } finally {
                                            isSendingPending = false
                                        }
                                    }
                                }
                            ) {
                                Text("Y")
                            }
                        }
                    }
                } else if (showRecoveryPrompt) {
                    // Crash recovery prompt
                    Text(
                        text = "Resume workout?",
                        style = MaterialTheme.typography.title3,
                        color = MaterialTheme.colors.onPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = recoveredWorkoutName,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                app.activeWorkoutStore.clear()
                                showRecoveryPrompt = false
                                scope.launch {
                                    try {
                                        app.activatePrivateMode()
                                        navController.navigate(Screen.ROUTINE_FOLDERS) {
                                            popUpTo(Screen.MODE_SELECTION) { inclusive = true }
                                        }
                                    } catch (e: Exception) {
                                        error = e.message ?: "Connection failed"
                                    }
                                }
                            },
                            colors = ButtonDefaults.secondaryButtonColors()
                        ) {
                            Text("N")
                        }
                        Button(
                            onClick = {
                                val recovered = app.activeWorkoutStore.load()
                                if (recovered != null) {
                                    app.updateActiveWorkout(recovered)
                                    showRecoveryPrompt = false
                                    navController.navigate(Screen.LOG_WORKOUT) {
                                        popUpTo(Screen.MODE_SELECTION) { inclusive = true }
                                    }
                                }
                            }
                        ) {
                            Text("Y")
                        }
                    }
                } else {
                    // Normal loading / error state
                    Text(
                        text = "valkyria",
                        style = MaterialTheme.typography.title3,
                        color = MaterialTheme.colors.onPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    if (linkState !is PhoneLink.State.Ready) {
                        // BT off or no phone — block first API call until both
                        // come back. activatePrivateMode() is also gated on
                        // PhoneLink.awaitReady so it will simply resume once
                        // the user re-enables Bluetooth or brings the phone
                        // back in range.
                        Text(
                            text = PhoneLink.userMessage(linkState),
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        RotatingLogo(size = 40.dp)
                    } else if (error != null) {
                        Text(
                            text = error!!,
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                error = null
                                scope.launch {
                                    try {
                                        app.activatePrivateMode()
                                        navController.navigate(Screen.ROUTINE_FOLDERS) {
                                            popUpTo(Screen.MODE_SELECTION) { inclusive = true }
                                        }
                                    } catch (e: Exception) {
                                        error = e.message ?: "Connection failed"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Text("Retry")
                        }
                    } else {
                        // Explicit size — fullscreen default would overlay
                        // the "valkyria" title shown above this branch.
                        RotatingLogo(size = 40.dp)
                    }
                }
            }
        }
    }
}

/**
 * Single-shot variant of [PhoneLink.awaitReady] for the pending-retry path. The
 * surrounding `Y` button shows the message in `pendingSendError` rather than
 * the always-on splash banner used by the normal startup flow, so blocking
 * with a poll loop would freeze the screen instead of giving the user a clear
 * "fix the radio and tap again" cue.
 */
private suspend fun requirePhoneLinked(app: HevyApp) {
    val state = PhoneLink.currentState(app)
    if (state !is PhoneLink.State.Ready) {
        throw IllegalStateException(PhoneLink.userMessage(state))
    }
}

/** Re-send the pending request from PendingRequestStore, then clear it on success.
 *  B6: a corrupted prefs blob (truncated JSON, schema drift) used to surface as an
 *  unhandled JsonSyntaxException that crashed Compose mid-Retry. We now catch the
 *  parse failure, clear the un-deserializable pending entry, and surface a clean
 *  error so the user can pick "discard and continue" without an app restart. */
internal suspend fun retrySendPending(app: HevyApp) {
    val store = app.pendingRequestStore
    val method = store.getPendingMethod() ?: error("No pending method")
    val url = store.getPendingUrl() ?: error("No pending URL")
    val bodyJson = store.getPendingBody() ?: error("No pending body")
    val gson = com.example.hevywatch.util.GsonHolder.gson
    val service = app.requireApiService()

    try {
        when (method) {
            "PUT" -> {
                val workoutId = url.removePrefix("v1/workouts/")
                val request = gson.fromJson(bodyJson, WorkoutPutRequest::class.java)
                    ?: error("Pending PUT body is empty")
                requirePhoneLinked(app)
                service.putWorkout(workoutId, request)
            }
            "POST" -> {
                if (url == "v1/workouts") {
                    val request = gson.fromJson(bodyJson, WorkoutPostRequest::class.java)
                        ?: error("Pending POST body is empty")
                    requirePhoneLinked(app)
                    service.postWorkout(request)
                } else {
                    // v2/workout (private API) — need token refresh
                    val request = gson.fromJson(bodyJson,
                        com.example.hevywatch.data.api.model.WorkoutPostRequestV2::class.java)
                        ?: error("Pending POST V2 body is empty")
                    requirePhoneLinked(app)
                    app.refreshTokenIfNeeded()
                    app.requirePrivateApiService().postWorkoutPrivate(request)
                }
            }
            else -> error("Unknown method: $method")
        }
    } catch (e: com.google.gson.JsonSyntaxException) {
        // Pending body can't be parsed back into a request — drop it so the
        // user isn't stuck on this screen forever. The user has already
        // confirmed they want to recover (Retry tap); discarding is the only
        // forward path when the persisted payload is corrupt.
        store.clear()
        throw IllegalStateException(
            "Pending request was corrupted and could not be parsed. " +
                "It has been discarded — please re-log the workout.",
            e
        )
    }

    store.clear()
}
