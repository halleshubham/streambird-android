package app.streambird.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.streambird.android.data.Stage
import app.streambird.android.stream.LivePhase
import app.streambird.android.ui.screens.ChannelsScreen
import app.streambird.android.ui.screens.LiveScreen
import app.streambird.android.ui.screens.LoginScreen
import app.streambird.android.ui.screens.NeedsPhoneScreen
import app.streambird.android.ui.screens.PendingScreen
import app.streambird.android.ui.screens.SetupScreen

@Composable
fun AppRoot() {
    val auth: AuthViewModel = hiltViewModel()
    val a by auth.ui.collectAsStateWithLifecycle()
    when (a.stage) {
        Stage.LOADING -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        Stage.LOGGED_OUT -> LoginScreen(a, auth)
        Stage.PENDING -> PendingScreen(a, auth)
        Stage.NEEDS_PHONE -> NeedsPhoneScreen(a, auth)
        Stage.READY -> MainNav()
    }
}

@Composable
private fun MainNav() {
    val studio: StudioViewModel = hiltViewModel()
    val live by studio.controller.ui.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    LaunchedEffect(Unit) { studio.refresh() }
    // A running (or just-finished) stream always owns the screen; tapping the notification lands here too.
    LaunchedEffect(live.phase) {
        when (live.phase) {
            LivePhase.STARTING, LivePhase.LIVE, LivePhase.ENDING, LivePhase.ENDED, LivePhase.FAILED ->
                if (nav.currentDestination?.route != "live") nav.navigate("live") { launchSingleTop = true }
            LivePhase.IDLE -> if (nav.currentDestination?.route == "live") nav.popBackStack("setup", inclusive = false)
        }
    }
    NavHost(nav, startDestination = if (live.active) "live" else "channels") {
        composable("channels") { ChannelsScreen(studio, onNext = { nav.navigate("setup") }) }
        composable("setup") { SetupScreen(studio, onBack = { nav.popBackStack() }) }
        composable("live") { LiveScreen(studio.controller) }
    }
}
