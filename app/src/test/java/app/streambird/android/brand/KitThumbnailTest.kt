package app.streambird.android.brand

import app.streambird.android.data.BrandKit
import app.streambird.android.data.KitDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kit thumbnail rules on the app side, the shared constants of the new slots, and the plain-words messages for an older server. */
class KitThumbnailTest {
    private fun kit(id: String, name: String, thumb: String?) = BrandKit(id = id, name = name, thumbnailAssetId = thumb)

    // ---- the Setup note and the request parameter ----

    @Test fun `note names the kit when it has a thumbnail and the user picked none`() {
        assertEquals("Thumbnail: from brand kit Main", KitThumbnail.note(kit("k1", "Main", "th1"), ownPicked = false))
        assertNull(KitThumbnail.note(kit("k1", "Main", null), ownPicked = false))        // the kit has none
        assertNull(KitThumbnail.note(kit("k1", "Main", "th1"), ownPicked = true))        // the user's own picture wins: nothing changes
        assertNull(KitThumbnail.note(null, ownPicked = false))                           // no kit in use
    }

    @Test fun `thumbnailBrandKitId is only sent for an explicitly picked kit that has a thumbnail`() {
        val kits = listOf(kit("k1", "A", "th1"), kit("k2", "B", null))
        assertEquals("k1", KitThumbnail.requestKitId("k1", kits, usingKit = true, ownPicked = false))
        assertNull(KitThumbnail.requestKitId(null, kits, usingKit = true, ownPicked = false))       // automatic = omitted
        assertNull(KitThumbnail.requestKitId("k2", kits, usingKit = true, ownPicked = false))       // no thumbnail on that kit (or an older server)
        assertNull(KitThumbnail.requestKitId("k1", kits, usingKit = true, ownPicked = true))        // own thumbnail picked: as before
        assertNull(KitThumbnail.requestKitId("k1", kits, usingKit = false, ownPicked = false))      // no kit look for this stream
        assertNull(KitThumbnail.requestKitId("gone", kits, usingKit = true, ownPicked = false))     // a kit that no longer exists
    }

    // ---- shared constants ----

    @Test fun `new slots - suggested sizes, GIF rule and wording`() {
        val l = BrandOrientation.LANDSCAPE; val p = BrandOrientation.PORTRAIT
        assertEquals("1920 x 1080", slotSpec(l, BrandSlot.L_SHAPE).sizeText); assertEquals("1080 x 1920", slotSpec(p, BrandSlot.L_SHAPE).sizeText)
        assertEquals("1280 x 720", slotSpec(l, BrandSlot.THUMBNAIL).sizeText); assertEquals("1280 x 720", slotSpec(p, BrandSlot.THUMBNAIL).sizeText)   // shared by both shapes
        assertEquals("L-shaped frame", BrandSlot.L_SHAPE.label); assertEquals("Stream thumbnail", BrandSlot.THUMBNAIL.label)
        assertTrue(BrandSlot.L_SHAPE.gifAllowed); assertFalse(BrandSlot.THUMBNAIL.gifAllowed)
        assertTrue(slotHelp(l, BrandSlot.L_SHAPE).endsWith("PNG, JPG, WebP or GIF, up to 8 MB."))
        assertTrue(slotHelp(l, BrandSlot.L_SHAPE).startsWith("Full frame, 16:9, 1920 x 1080:")); assertTrue(slotHelp(p, BrandSlot.L_SHAPE).startsWith("Full frame, 9:16, 1080 x 1920:"))
        assertEquals("Suggested 1280x720 JPG or PNG, under 2 MB. Vertical streams use this 16:9 image too.", slotHelp(l, BrandSlot.THUMBNAIL))
        assertEquals(slotHelp(l, BrandSlot.THUMBNAIL), slotHelp(p, BrandSlot.THUMBNAIL))
        assertEquals(2 * 1024 * 1024, ThumbnailLimits.MAX_BYTES); assertEquals(640, ThumbnailLimits.MIN_WIDTH)
        // the frame is a whole-frame picture of the stream's shape: a portrait picture in the landscape slot is flagged, the right shape is not
        assertNull(slotAspectWarning(l, BrandSlot.L_SHAPE, 1920, 1080)); assertTrue(slotAspectWarning(l, BrandSlot.L_SHAPE, 1080, 1920) != null)
        assertNull(slotAspectWarning(l, BrandSlot.THUMBNAIL, 1280, 720)); assertTrue(slotAspectWarning(p, BrandSlot.THUMBNAIL, 720, 1280) != null)
    }

    @Test fun `thumbnail candidates - JPG or PNG, at most 2 MB, at least 640 px wide`() {
        assertTrue(isThumbnailCandidate("image/jpeg", 500_000, 1280)); assertTrue(isThumbnailCandidate("image/png", 2L * 1024 * 1024, 640))
        assertFalse(isThumbnailCandidate("image/png", 2L * 1024 * 1024 + 1, 1280)); assertFalse(isThumbnailCandidate("image/jpeg", 1000, 639))
        assertFalse(isThumbnailCandidate("image/gif", 1000, 1280)); assertFalse(isThumbnailCandidate("image/webp", 1000, 1280)); assertFalse(isThumbnailCandidate("image/jpeg", 0, 1280))
    }

    // ---- upload check for the thumbnail slot ----

    private fun png(w: Int, h: Int, pad: Int = 0): ByteArray {
        val b = ByteArray(24 + pad)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10).copyInto(b)
        "IHDR".toByteArray().copyInto(b, 12)
        fun be(i: Int, v: Int) { b[i] = (v shr 24).toByte(); b[i + 1] = (v shr 16).toByte(); b[i + 2] = (v shr 8).toByte(); b[i + 3] = v.toByte() }
        be(16, w); be(20, h)
        return b
    }

    @Test fun `thumbnail upload check - same limits and words as the server`() {
        assertTrue(checkThumbnailUpload(png(1280, 720)) is UploadCheck.Ok)
        assertEquals("The thumbnail is too small -- it must be at least 640px wide (1280x720 is recommended).", (checkThumbnailUpload(png(639, 360)) as UploadCheck.Refused).message)
        assertEquals("The thumbnail is too large -- the limit is 2 MB.", (checkThumbnailUpload(png(1280, 720, pad = 2 * 1024 * 1024)) as UploadCheck.Refused).message)
        assertEquals("The thumbnail must be a JPG or PNG image.", (checkThumbnailUpload("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".toByteArray(Charsets.ISO_8859_1)) as UploadCheck.Refused).message)
        assertEquals("The thumbnail must be a JPG or PNG image.", (checkThumbnailUpload("not an image at all, just text".toByteArray()) as UploadCheck.Refused).message)
        assertEquals("Choose an image file to upload.", (checkThumbnailUpload(ByteArray(0)) as UploadCheck.Refused).message)
        // the ordinary library check still allows a GIF logo and refuses nothing about width
        assertTrue(checkAssetUpload(png(100, 100)) is UploadCheck.Ok)
    }

    // ---- older servers ----

    private fun draft(ads: List<app.streambird.android.data.KitAd>? = null, thumb: String? = null) = KitDraft("n", null, "left", 90, "accent", false, ads = ads, thumbnailAssetId = thumb)
    private val frame = listOf(app.streambird.android.data.KitAd("l_shape", "fr1", true, "continuous", null, null))

    @Test fun `an older server's 400 for the new fields is said in plain words, other errors stay as the server says them`() {
        val validator = "ads.0.placement must be one of the following values: lower_third, side_third, full_screen"
        assertEquals(KitCompat.NO_FRAME_ADS, KitCompat.saveError(400, validator, draft(frame)))
        assertEquals(KitCompat.NO_FRAME_ADS, KitCompat.saveError(400, validator, draft().copy(adsPortrait = frame)))
        assertNull(KitCompat.saveError(400, validator, draft(emptyList())))                                  // we did not send a frame: the server's text is the real story
        assertEquals(KitCompat.NO_KIT_THUMBNAIL, KitCompat.saveError(400, "property thumbnailAssetId should not exist", draft(thumb = "th1")))
        assertNull(KitCompat.saveError(400, "property thumbnailAssetId should not exist", draft()))
        assertNull(KitCompat.saveError(403, "Your own ads are on the paid plans.", draft(frame)))              // Free plan: the server's own message
        assertNull(KitCompat.saveError(409, "conflict", draft(frame)))
        assertNull(KitCompat.saveError(400, "The thumbnail is too small -- it must be at least 640px wide (1280x720 is recommended).", draft(thumb = "th1")))
    }
}
