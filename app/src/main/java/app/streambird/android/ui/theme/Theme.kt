package app.streambird.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens copied from the website (web-app/src/index.css `:root` and its prefers-color-scheme: dark block),
 * mapped onto a Material 3 colour scheme so every screen inherits the website look.
 */
object SbTokens {
    // light
    val text = Color(0xFF4B5563)
    val textH = Color(0xFF111827)
    val bg = Color(0xFFF9FAFB)
    val cardBg = Color(0xFFFFFFFF)
    val border = Color(0xFFE5E7EB)
    val accent = Color(0xFF7C3AED)
    val accentHover = Color(0xFF6D28D9)
    val error = Color(0xFFDC2626)
    val warning = Color(0xFFD97706)
    // dark
    val textDark = Color(0xFF9CA3AF)
    val textHDark = Color(0xFFF3F4F6)
    val bgDark = Color(0xFF111318)
    val cardBgDark = Color(0xFF1A1C23)
    val borderDark = Color(0xFF2E303A)
    val accentDark = Color(0xFFA78BFA)
    val accentHoverDark = Color(0xFFC4B5FD)
    val errorDark = Color(0xFFF87171)
    val warningDark = Color(0xFFFBBF24)
    // web .success / status-live
    val success = Color(0xFF16A34A)
}

/** Amber warning and green OK, kept as plain constants because screens use them outside composable scopes. */
val Amber = SbTokens.warning
val Ok = SbTokens.success

/** Platform badge colours (web PlatformBadge.tsx). */
object PlatformColors {
    val youtube = Color(0xFFFF0000)
    val facebook = Color(0xFF1877F2)
    val twitch = Color(0xFF9146FF)
    val linkedin = Color(0xFF0A66C2)
    fun forPlatform(p: String) = when (p) {
        "youtube" -> youtube
        "facebook" -> facebook
        "twitch" -> twitch
        "linkedin" -> linkedin
        else -> SbTokens.accent
    }
}

fun sbLightColors(): ColorScheme = lightColorScheme(
    primary = SbTokens.accent, onPrimary = Color.White,
    primaryContainer = SbTokens.accent.copy(alpha = 0.12f).compositeOver(SbTokens.cardBg), onPrimaryContainer = SbTokens.accentHover,
    secondary = SbTokens.accent, onSecondary = Color.White,
    secondaryContainer = SbTokens.accent.copy(alpha = 0.12f).compositeOver(SbTokens.cardBg), onSecondaryContainer = SbTokens.textH,
    tertiary = SbTokens.warning, onTertiary = Color.White,
    background = SbTokens.bg, onBackground = SbTokens.textH,
    surface = SbTokens.cardBg, onSurface = SbTokens.textH,
    surfaceVariant = Color(0xFFF3F4F6), onSurfaceVariant = SbTokens.text,
    surfaceTint = SbTokens.accent,
    surfaceContainerLowest = SbTokens.cardBg, surfaceContainerLow = SbTokens.cardBg, surfaceContainer = SbTokens.cardBg,
    surfaceContainerHigh = SbTokens.cardBg, surfaceContainerHighest = SbTokens.cardBg,
    outline = SbTokens.border, outlineVariant = SbTokens.border,
    error = SbTokens.error, onError = Color.White,
)

fun sbDarkColors(): ColorScheme = darkColorScheme(
    primary = SbTokens.accentDark, onPrimary = SbTokens.bgDark,
    primaryContainer = SbTokens.accentDark.copy(alpha = 0.16f).compositeOver(SbTokens.cardBgDark), onPrimaryContainer = SbTokens.accentHoverDark,
    secondary = SbTokens.accentDark, onSecondary = SbTokens.bgDark,
    secondaryContainer = SbTokens.accentDark.copy(alpha = 0.16f).compositeOver(SbTokens.cardBgDark), onSecondaryContainer = SbTokens.textHDark,
    tertiary = SbTokens.warningDark, onTertiary = SbTokens.bgDark,
    background = SbTokens.bgDark, onBackground = SbTokens.textHDark,
    surface = SbTokens.cardBgDark, onSurface = SbTokens.textHDark,
    surfaceVariant = Color(0xFF23262E), onSurfaceVariant = SbTokens.textDark,
    surfaceTint = SbTokens.accentDark,
    surfaceContainerLowest = SbTokens.cardBgDark, surfaceContainerLow = SbTokens.cardBgDark, surfaceContainer = SbTokens.cardBgDark,
    surfaceContainerHigh = SbTokens.cardBgDark, surfaceContainerHighest = SbTokens.cardBgDark,
    outline = SbTokens.borderDark, outlineVariant = SbTokens.borderDark,
    error = SbTokens.errorDark, onError = SbTokens.bgDark,
)

/** Web: inputs/buttons 6 px, panels 10 px, pills 999 px. */
val SbShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

private val Sans = FontFamily.Default

/** Website scale: h1 28 px, body 16 px / 145 %, labels 14 px semibold. */
val SbTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Sans, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = Sans, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = Sans, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = Sans, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontFamily = Sans, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun StreamBirdTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) sbDarkColors() else sbLightColors(), shapes = SbShapes, typography = SbTypography, content = content)
}
