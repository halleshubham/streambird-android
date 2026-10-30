package app.streambird.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFFFF5A36)
val Amber = Color(0xFFFFB020)
val Ok = Color(0xFF2EBD6B)

private val Dark = darkColorScheme(primary = Brand, onPrimary = Color.White, background = Color(0xFF101114), surface = Color(0xFF181A20), surfaceVariant = Color(0xFF23262E))
private val Light = lightColorScheme(primary = Color(0xFFD9411F), onPrimary = Color.White)

@Composable
fun StreamBirdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
