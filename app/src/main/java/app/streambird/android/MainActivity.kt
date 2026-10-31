package app.streambird.android

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.streambird.android.stream.StreamController
import app.streambird.android.ui.AppRoot
import app.streambird.android.ui.theme.StreamBirdTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var controller: StreamController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StreamBirdTheme { AppRoot() } }
        // A landscape stream locks the screen to sensor-landscape (either way up) while it runs; everything else is portrait.
        // The activity handles orientation itself (configChanges in the manifest), so rotating never recreates it
        // and the stream/engine live in the application-scoped controller anyway.
        lifecycleScope.launch {
            controller.ui.collect { ui ->
                val landscape = ui.active && ui.request?.orientation?.isLandscape == true
                val want = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                if (requestedOrientation != want) requestedOrientation = want
            }
        }
    }
}
