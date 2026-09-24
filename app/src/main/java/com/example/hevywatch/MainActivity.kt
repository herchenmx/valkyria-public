package com.example.hevywatch

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.example.hevywatch.presentation.mode.ModeSelectionScreen
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.presentation.routine.RoutineDetailScreen
import com.example.hevywatch.presentation.routine.RoutineFolderListScreen
import com.example.hevywatch.presentation.routine.RoutineListScreen
import com.example.hevywatch.presentation.settings.SettingsScreen
import com.example.hevywatch.presentation.workout.CongratsScreen
import com.example.hevywatch.presentation.workout.LogSetScreen
import com.example.hevywatch.presentation.workout.LogWorkoutScreen
import com.example.hevywatch.presentation.workout.RestTimerScreen
import com.example.hevywatch.presentation.workout.SwapExerciseScreen
import com.example.hevywatch.presentation.workout.WorkoutControlScreen
import com.example.hevywatch.presentation.workout.WorkoutDetailScreen
import com.example.hevywatch.ui.components.BrightnessCoordinator
import com.example.hevywatch.ui.components.SPLASH_DURATION_MS
import com.example.hevywatch.ui.components.SplashLogo
import com.example.hevywatch.ui.components.WorkoutDisplayController
import com.example.hevywatch.ui.theme.HevyWatchTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : FragmentActivity(), AmbientModeSupport.AmbientCallbackProvider {

    /** Keeps the activity in the foreground when the display goes ambient (dim),
     *  so the user returns to the app rather than the watch face on wake. */
    override fun getAmbientCallback() = object : AmbientModeSupport.AmbientCallback() {}

    private val _stemButton2Flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stemButton2Flow = _stemButton2Flow.asSharedFlow()

    /** Route requested by a Tile tap (via intent extra). */
    var tileNavigateTo: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmbientModeSupport.attach(this)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        tileNavigateTo = Screen.sanitizeTileRoute(intent?.getStringExtra("navigate_to"))
        val hevyApp = applicationContext as HevyApp
        setContent {
            // Reading the store's Compose state here recomposes the whole tree
            // (and re-derives the palette) the instant the user flips the theme
            // toggle on the Settings screen.
            HevyWatchTheme(themeMode = hevyApp.themeSettingsStore.themeMode) {
                WearApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tileNavigateTo = Screen.sanitizeTileRoute(intent.getStringExtra("navigate_to"))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_STEM_2 -> {
                _stemButton2Flow.tryEmit(Unit)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onResume() {
        super.onResume()
        // P9 — every time the user opens the app, refresh the tile so a
        // glance immediately after closing the app shows the latest workout /
        // routine state. The Tiles framework coalesces requests, so this is
        // cheap even if the underlying state didn't change.
        (applicationContext as? HevyApp)?.requestTileUpdate()
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val hevyApp = remember(context) { context.applicationContext as HevyApp }
    val startDestination = if (hevyApp.activeWorkout != null) Screen.LOG_WORKOUT
                           else Screen.MODE_SELECTION

    val navController = rememberSwipeDismissableNavController()

    // Cold-start splash: rememberSaveable so it survives configuration changes
    // (e.g. ambient transitions) — once dismissed it stays dismissed until the
    // process is killed and re-launched.
    var splashDismissed by rememberSaveable { mutableStateOf(false) }
    if (!splashDismissed) {
        LaunchedEffect(Unit) {
            delay(SPLASH_DURATION_MS)
            splashDismissed = true
        }
        SplashLogo()
        return
    }

    // Stem button 2 → workout-control menu. Collected ONCE here rather than
    // per-screen: LogWorkoutScreen, LogSetScreen and RestTimerScreen each used
    // to collect this flow, and when two of them are on the back stack
    // together (LOG_SET over LOG_WORKOUT) both collectors were live, so a
    // single press pushed WORKOUT_CONTROL twice. Guarding on the current
    // destination also stops a press while already on the control screen from
    // stacking another copy.
    val activityForStem = LocalContext.current as? MainActivity
    LaunchedEffect(activityForStem) {
        activityForStem?.stemButton2Flow?.collect {
            if (navController.currentDestination?.route != Screen.WORKOUT_CONTROL) {
                navController.navigate(Screen.WORKOUT_CONTROL)
            }
        }
    }

    WorkoutDisplayController {
    BrightnessCoordinator(navController = navController) {
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.MODE_SELECTION) {
            ModeSelectionScreen(navController)
        }

        composable(Screen.ROUTINE_FOLDERS) {
            RoutineFolderListScreen(navController)
        }

        composable(
            route = Screen.ROUTINE_LIST,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType })
        ) { entry ->
            val folderId = entry.arguments?.getString("folderId") ?: return@composable
            RoutineListScreen(folderId = folderId, navController = navController)
        }

        composable(
            route = Screen.ROUTINE_DETAIL,
            arguments = listOf(navArgument("routineId") { type = NavType.StringType })
        ) { entry ->
            val routineId = entry.arguments?.getString("routineId") ?: return@composable
            RoutineDetailScreen(routineId, navController)
        }

        composable(Screen.LOG_WORKOUT) {
            LogWorkoutScreen(navController)
        }

        composable(Screen.LOG_SET) {
            LogSetScreen(navController)
        }

        composable(Screen.SWAP_EXERCISE) {
            SwapExerciseScreen(navController)
        }

        composable(Screen.WORKOUT_CONTROL) {
            WorkoutControlScreen(navController)
        }

        composable(
            route = Screen.REST_TIMER,
            arguments = listOf(navArgument("seconds") { type = NavType.IntType })
        ) { entry ->
            val seconds = entry.arguments?.getInt("seconds") ?: 90
            RestTimerScreen(seconds, navController)
        }

        composable(
            route = Screen.WORKOUT_DETAIL,
            arguments = listOf(navArgument("workoutId") { type = NavType.StringType })
        ) { entry ->
            val workoutId = entry.arguments?.getString("workoutId") ?: return@composable
            WorkoutDetailScreen(workoutId, navController)
        }

        composable(Screen.CONGRATS) {
            CongratsScreen(navController = navController)
        }

        composable(Screen.SETTINGS) {
            SettingsScreen(navController)
        }
    }
    }
    }
}
