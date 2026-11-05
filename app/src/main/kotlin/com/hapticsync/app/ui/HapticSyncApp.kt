package com.hapticsync.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hapticsync.app.AppContainer
import com.hapticsync.app.appContainer
import com.hapticsync.app.ui.calibration.CalibrationScreen
import com.hapticsync.app.ui.calibration.CalibrationViewModel
import com.hapticsync.app.ui.components.NowPlayingBar
import com.hapticsync.app.ui.demo.DemoScreen
import com.hapticsync.app.ui.demo.DemoViewModel
import com.hapticsync.app.ui.home.HomeScreen
import com.hapticsync.app.ui.home.HomeViewModel
import com.hapticsync.app.ui.player.PlayerScreen
import com.hapticsync.app.ui.player.PlayerViewModel
import com.hapticsync.app.ui.presets.PresetsScreen
import com.hapticsync.app.ui.presets.PresetsViewModel
import com.hapticsync.app.ui.settings.SettingsScreen
import com.hapticsync.app.ui.settings.SettingsViewModel
import com.hapticsync.app.ui.studio.StudioScreen
import com.hapticsync.app.ui.studio.StudioViewModel

/** Navigation destinations. */
object Routes {
    const val HOME = "home"
    const val PLAYER = "player"
    const val STUDIO = "studio"
    const val CALIBRATION = "calibration"
    const val PRESETS = "presets"
    const val SETTINGS = "settings"
    const val DEMO = "demo"
}

/** Creates a screen's view model from the process-wide [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer, SavedStateHandle) -> VM): VM {
    val container = LocalContext.current.appContainer
    return viewModel(factory = viewModelFactory { initializer { create(container, createSavedStateHandle()) } })
}

@Composable
fun HapticSyncApp(container: AppContainer) {
    val navController = rememberNavController()
    val nowPlaying by container.nowPlaying.collectAsState()

    // Any screen (or an "Open with" intent) can queue media; bring the player to the front.
    LaunchedEffect(navController) {
        container.playbackQueue.navigation.collect { navController.showPlayer() }
    }

    val back: () -> Unit = { navController.popBackStack() }
    val nowPlayingBar: @Composable () -> Unit = {
        NowPlayingBar(nowPlaying, onOpen = { navController.showPlayer() })
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(240)) { it / 10 } },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = containerViewModel { c, _ -> HomeViewModel(c) },
                onOpenStudio = { navController.navigate(Routes.STUDIO) },
                onOpenPresets = { navController.navigate(Routes.PRESETS) },
                onOpenCalibration = { navController.navigate(Routes.CALIBRATION) },
                onOpenDemo = { navController.navigate(Routes.DEMO) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.PLAYER) {
            PlayerScreen(
                viewModel = containerViewModel { c, saved -> PlayerViewModel(c, saved) },
                onBack = back,
                onOpenStudio = { navController.navigate(Routes.STUDIO) { launchSingleTop = true } },
            )
        }
        composable(Routes.STUDIO) {
            StudioScreen(
                viewModel = containerViewModel { c, _ -> StudioViewModel(c) },
                onBack = back,
                onOpenPresets = { navController.navigate(Routes.PRESETS) { launchSingleTop = true } },
                bottomBar = nowPlayingBar,
            )
        }
        composable(Routes.CALIBRATION) {
            CalibrationScreen(
                viewModel = containerViewModel { c, _ -> CalibrationViewModel(c) },
                onBack = back,
                bottomBar = nowPlayingBar,
            )
        }
        composable(Routes.PRESETS) {
            PresetsScreen(
                viewModel = containerViewModel { c, _ -> PresetsViewModel(c) },
                onBack = back,
                onOpenStudio = { navController.navigate(Routes.STUDIO) { launchSingleTop = true } },
                bottomBar = nowPlayingBar,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = containerViewModel { c, _ -> SettingsViewModel(c) },
                onBack = back,
                onOpenCalibration = { navController.navigate(Routes.CALIBRATION) { launchSingleTop = true } },
            )
        }
        composable(Routes.DEMO) {
            DemoScreen(
                viewModel = containerViewModel { c, _ -> DemoViewModel(c) },
                onBack = back,
            )
        }
    }
}

/** Shows the player: returns to the open one if it is on the back stack, else opens it. */
private fun NavController.showPlayer() {
    if (currentDestination?.route == Routes.PLAYER) return
    if (!popBackStack(Routes.PLAYER, inclusive = false)) {
        navigate(Routes.PLAYER) { launchSingleTop = true }
    }
}
