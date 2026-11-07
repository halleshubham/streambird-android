package app.streambird.android.brand

import app.streambird.android.data.ApiJson
import app.streambird.android.data.BrandKit
import app.streambird.android.data.KitAd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same numbers as the web's brandSizes.ts / brandKitSet.ts. */
class BrandSizesTest {
    @Test fun `suggested sizes per orientation`() {
        val l = BrandOrientation.LANDSCAPE; val p = BrandOrientation.PORTRAIT
        assertEquals("512 x 512", slotSpec(l, BrandSlot.LOGO).sizeText); assertEquals("512 x 512", slotSpec(p, BrandSlot.LOGO).sizeText)
        assertEquals("1920 x 1080", slotSpec(l, BrandSlot.WALLPAPER).sizeText); assertEquals("1080 x 1920", slotSpec(p, BrandSlot.WALLPAPER).sizeText)
        assertEquals("1280 x 240", slotSpec(l, BrandSlot.LOWER_THIRD).sizeText); assertEquals("1080 x 360", slotSpec(p, BrandSlot.LOWER_THIRD).sizeText)
        assertEquals("640 x 1080", slotSpec(l, BrandSlot.SIDE_THIRD).sizeText); assertEquals("360 x 1920", slotSpec(p, BrandSlot.SIDE_THIRD).sizeText)
        assertEquals("1920 x 1080", slotSpec(l, BrandSlot.FULL_SCREEN).sizeText); assertEquals("1080 x 1920", slotSpec(p, BrandSlot.FULL_SCREEN).sizeText)
    }

    @Test fun `file limits and gif rule`() {
        assertEquals(8 * 1024 * 1024, BrandFileLimits.MAX_BYTES)
        assertEquals(8192, BrandFileLimits.MAX_SIDE)
        assertEquals(1.5, ASPECT_TOLERANCE, 0.0)
        assertTrue(BrandSlot.LOGO.gifAllowed); assertTrue(BrandSlot.LOWER_THIRD.gifAllowed); assertTrue(BrandSlot.SIDE_THIRD.gifAllowed); assertTrue(BrandSlot.FULL_SCREEN.gifAllowed)
        assertFalse(BrandSlot.WALLPAPER.gifAllowed)
        assertTrue(slotHelp(BrandOrientation.LANDSCAPE, BrandSlot.LOGO).endsWith("PNG, JPG, WebP or GIF, up to 8 MB."))
        assertTrue(slotHelp(BrandOrientation.PORTRAIT, BrandSlot.WALLPAPER).endsWith("PNG, JPG or WebP, up to 8 MB."))
        assertTrue(slotHelp(BrandOrientation.PORTRAIT, BrandSlot.WALLPAPER).startsWith("9:16, 1080 x 1920."))
    }

    @Test fun `aspect ratio ranges are the suggestion within 1_5x`() {
        val s = slotSpec(BrandOrientation.LANDSCAPE, BrandSlot.WALLPAPER)
        assertEquals(16.0 / 9 / 1.5, s.ratioMin, 1e-12); assertEquals(16.0 / 9 * 1.5, s.ratioMax, 1e-12)
        val logo = slotSpec(BrandOrientation.LANDSCAPE, BrandSlot.LOGO)
        assertEquals(0.4, logo.ratioMin, 0.0); assertEquals(5.0, logo.ratioMax, 0.0)
    }

    @Test fun `warning when the shape is off by more than 1_5x, never for the right shape or unknown size`() {
        val wall = slotSpec(BrandOrientation.LANDSCAPE, BrandSlot.WALLPAPER)
        assertFalse(aspectMismatch(1920, 1080, wall))
        assertFalse(aspectMismatch(1600, 900, wall))
        assertFalse(aspectMismatch(1280, 1024, wall))   // 1.25: inside 1.185..2.667
        assertTrue(aspectMismatch(1080, 1920, wall))    // portrait picture in a landscape slot
        assertTrue(aspectMismatch(1000, 1000, wall))    // 1.0 < 1.185
        assertFalse(aspectMismatch(0, 0, wall)); assertFalse(aspectMismatch(100, 0, wall))
        val lower = slotSpec(BrandOrientation.LANDSCAPE, BrandSlot.LOWER_THIRD) // 16:3 = 5.333
        assertFalse(aspectMismatch(1280, 240, lower)); assertTrue(aspectMismatch(1280, 720, lower)); assertTrue(aspectMismatch(300, 300, lower))
        val sidePortrait = slotSpec(BrandOrientation.PORTRAIT, BrandSlot.SIDE_THIRD) // 360:1920
        assertFalse(aspectMismatch(360, 1920, sidePortrait)); assertTrue(aspectMismatch(640, 1080, sidePortrait))
        val logo = slotSpec(BrandOrientation.PORTRAIT, BrandSlot.LOGO)
        assertFalse(aspectMismatch(512, 512, logo)); assertFalse(aspectMismatch(1000, 200, logo)); assertTrue(aspectMismatch(1000, 100, logo)); assertTrue(aspectMismatch(100, 1000, logo))
    }

    @Test fun `warning text names the size and the suggestion, and is null when fine`() {
        assertNull(slotAspectWarning(BrandOrientation.LANDSCAPE, BrandSlot.FULL_SCREEN, 1920, 1080))
        val w = slotAspectWarning(BrandOrientation.PORTRAIT, BrandSlot.FULL_SCREEN, 1920, 1080)
        assertNotNull(w); assertTrue(w!!.contains("1920 x 1080") && w.contains("1080 x 1920"))
        assertNull(slotAspectWarning(BrandOrientation.PORTRAIT, BrandSlot.FULL_SCREEN, 0, 0))
    }

    @Test fun `orientation wire values`() {
        assertEquals("portrait", BrandOrientation.PORTRAIT.wire); assertEquals("9:16", BrandOrientation.PORTRAIT.label)
        assertEquals("landscape", BrandOrientation.LANDSCAPE.wire); assertEquals("16:9", BrandOrientation.LANDSCAPE.label)
        assertEquals(BrandOrientation.PORTRAIT, BrandOrientation.fromWire("portrait"))
        assertEquals(BrandOrientation.LANDSCAPE, BrandOrientation.fromWire("landscape"))
        assertEquals(BrandOrientation.LANDSCAPE, BrandOrientation.fromWire(null))
    }

    // ---- kit set selection ----

    private fun ad(p: String, id: String) = KitAd(p, id)
    private fun kit(logo: String? = "L", logoP: String? = null, wall: String? = "W", wallP: String? = null, ads: List<KitAd> = emptyList(), adsP: List<KitAd> = emptyList()) =
        BrandKit("k", "Kit", logoAssetId = logo, logoAssetIdPortrait = logoP, wallpaperAssetId = wall, wallpaperAssetIdPortrait = wallP, ads = ads, adsPortrait = adsP)

    @Test fun `landscape stream uses the landscape set only`() {
        val k = kit(logoP = "LP", wallP = "WP", ads = listOf(ad("lower_third", "A1")), adsP = listOf(ad("full_screen", "A2")))
        val s = kitSetFor(k, BrandOrientation.LANDSCAPE)
        assertEquals("L", s.logoAssetId); assertEquals("W", s.wallpaperAssetId); assertEquals(listOf(ad("lower_third", "A1")), s.ads)
    }

    @Test fun `portrait stream uses the portrait set`() {
        val k = kit(logoP = "LP", wallP = "WP", ads = listOf(ad("lower_third", "A1")), adsP = listOf(ad("full_screen", "A2")))
        val s = kitSetFor(k, BrandOrientation.PORTRAIT)
        assertEquals("LP", s.logoAssetId); assertEquals("WP", s.wallpaperAssetId); assertEquals(listOf(ad("full_screen", "A2")), s.ads)
    }

    @Test fun `portrait falls back to the landscape logo only`() {
        val k = kit(logoP = null, wallP = null, ads = listOf(ad("lower_third", "A1")))
        val s = kitSetFor(k, BrandOrientation.PORTRAIT)
        assertEquals("L", s.logoAssetId)       // logo falls back
        assertNull(s.wallpaperAssetId)          // wallpaper does not
        assertTrue(s.ads.isEmpty())             // ads do not
        assertNull(kitSetFor(kit(logo = null), BrandOrientation.PORTRAIT).logoAssetId)
    }

    @Test fun `an old server response without portrait fields still parses and selects`() {
        val old = ApiJson.decodeFromString<BrandKit>(
            """{"id":"k1","name":"Old","logoAssetId":"as1","logoPosition":"right","logoSize":120,"wallpaperAssetId":"as9","wallpaperDim":35,"lowerThirdStyle":"glass","isDefault":true,"connectionIds":["c1"],"ads":[{"placement":"lower_third","assetId":"ad1","enabled":true,"mode":"continuous","showSec":null,"everySec":null}]}""",
        )
        assertNull(old.logoAssetIdPortrait); assertNull(old.wallpaperAssetIdPortrait); assertTrue(old.adsPortrait.isEmpty())
        assertTrue(old.adsShrinkPicture)                       // default on
        assertEquals("as1", kitSetFor(old, BrandOrientation.PORTRAIT).logoAssetId)
        assertTrue(kitSetFor(old, BrandOrientation.PORTRAIT).ads.isEmpty())
        assertEquals(1, kitSetFor(old, BrandOrientation.LANDSCAPE).ads.size)
    }

    @Test fun `new server response parses every new field`() {
        val k = ApiJson.decodeFromString<BrandKit>(
            """{"id":"k1","name":"New","logoAssetId":"as1","logoAssetIdPortrait":"as2","logoPosition":"left","logoSize":90,"wallpaperAssetId":"w1","wallpaperAssetIdPortrait":"w2","wallpaperDim":0,"lowerThirdStyle":"bar","isDefault":false,"adsShrinkPicture":false,"connectionIds":[],"ads":[],"adsPortrait":[{"placement":"side_third","assetId":"ad9","enabled":false,"mode":"interval","showSec":15,"everySec":180}]}""",
        )
        assertEquals("as2", k.logoAssetIdPortrait); assertEquals("w2", k.wallpaperAssetIdPortrait); assertFalse(k.adsShrinkPicture)
        assertEquals(KitAd("side_third", "ad9", false, "interval", 15, 180), k.adsPortrait.single())
        // null-valued new fields (a kit that never set them) also parse
        val n = ApiJson.decodeFromString<BrandKit>("""{"id":"k","name":"n","logoAssetIdPortrait":null,"wallpaperAssetIdPortrait":null,"adsPortrait":[],"adsShrinkPicture":true}""")
        assertNull(n.logoAssetIdPortrait); assertTrue(n.adsShrinkPicture)
    }
}
