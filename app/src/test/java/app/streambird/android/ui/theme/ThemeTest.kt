package app.streambird.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** The website's tokens (web-app/src/index.css) land on the right Material roles. */
class ThemeTest {
    @Test fun `light scheme maps the website tokens`() {
        val c = sbLightColors()
        assertEquals(Color(0xFF7C3AED), c.primary)
        assertEquals(Color(0xFFF9FAFB), c.background)
        assertEquals(Color(0xFFFFFFFF), c.surface)
        assertEquals(Color(0xFF111827), c.onSurface)
        assertEquals(Color(0xFF4B5563), c.onSurfaceVariant)
        assertEquals(Color(0xFFE5E7EB), c.outline)
        assertEquals(Color(0xFFDC2626), c.error)
        assertEquals(Color(0xFFD97706), Amber)
    }

    @Test fun `dark scheme maps the website dark tokens`() {
        val c = sbDarkColors()
        assertEquals(Color(0xFFA78BFA), c.primary)
        assertEquals(Color(0xFF111318), c.background)
        assertEquals(Color(0xFF1A1C23), c.surface)
        assertEquals(Color(0xFFF3F4F6), c.onSurface)
        assertEquals(Color(0xFF9CA3AF), c.onSurfaceVariant)
        assertEquals(Color(0xFF2E303A), c.outline)
        assertEquals(Color(0xFFF87171), c.error)
    }

    @Test fun `platform badge colours match PlatformBadge tsx`() {
        assertEquals(Color(0xFFFF0000), PlatformColors.forPlatform("youtube"))
        assertEquals(Color(0xFF1877F2), PlatformColors.forPlatform("facebook"))
        assertEquals(Color(0xFF9146FF), PlatformColors.forPlatform("twitch"))
        assertEquals(SbTokens.accent, PlatformColors.forPlatform("other"))
    }
}
