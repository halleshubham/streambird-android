package app.streambird.android.ads

import app.streambird.android.data.EffectiveLimits
import app.streambird.android.data.KitAd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPlanTest {
    private fun limits(watermark: Boolean = false, storage: Int = 100) = EffectiveLimits(planKey = "p", maxDestinations = 1, assetStorageMb = storage, watermark = watermark)

    @Test fun `plan gating states`() {
        assertEquals(AdsAccess.LOADING, adsAccess(null))                               // never editable before the plan limits loaded
        assertEquals(AdsAccess.FREE_PLAN, adsAccess(limits(watermark = true)))
        assertEquals(AdsAccess.FREE_PLAN, adsAccess(limits(watermark = true, storage = 0)))
        assertEquals(AdsAccess.NO_STORAGE, adsAccess(limits(storage = 0)))
        assertEquals(AdsAccess.EDITABLE, adsAccess(limits()))                          // paid and day pass (watermark false)
    }

    private val kitAds = listOf(
        KitAd("lower_third", "a1", true, "continuous", null, null),
        KitAd("full_screen", "a3", false, "interval", 15, 180),
    )

    @Test fun `kit ads round trip through the editor slots, ordered by placement`() {
        val slots = slotsFromKit(kitAds)
        assertEquals(listOf(AdPlacement.LOWER_THIRD, AdPlacement.SIDE_THIRD, AdPlacement.FULL_SCREEN), slots.map { it.placement })
        assertNull(slots[1].assetId)                                                  // no side_third ad
        assertEquals(AdSchedule.Interval(15, 180), slots[2].schedule); assertFalse(slots[2].enabled)
        assertEquals(kitAds, kitAdsFromSlots(slots))                                   // slots without a picture are not sent
        assertEquals(emptyList<KitAd>(), kitAdsFromSlots(slotsFromKit(emptyList())))
    }

    @Test fun `ads are only sent when the user changed them`() {
        val slots = slotsFromKit(kitAds)
        assertFalse(adsEdited(kitAds, slots))
        assertTrue(adsEdited(kitAds, slots.map { if (it.placement == AdPlacement.LOWER_THIRD) it.copy(enabled = false) else it }))
        assertTrue(adsEdited(kitAds, slots.map { if (it.placement == AdPlacement.SIDE_THIRD) it.copy(assetId = "a2") else it }))
        assertTrue(adsEdited(kitAds, slots.map { if (it.placement == AdPlacement.FULL_SCREEN) it.copy(schedule = AdSchedule.Continuous) else it }))
        assertTrue(adsEdited(kitAds, slots.map { it.copy(assetId = null) }))           // removing all = edited ([] clears)
        assertFalse(adsEdited(emptyList(), slotsFromKit(emptyList())))
    }

    @Test fun `validation names the placement and uses the web's messages`() {
        val ok = slotsFromKit(kitAds)
        assertNull(validateSlots(ok))
        val bad = ok.map { if (it.placement == AdPlacement.FULL_SCREEN) it.copy(schedule = AdSchedule.Interval(0, 10)) else it }
        assertTrue(validateSlots(bad)!!.startsWith("Full screen: Show time must be between 1 second and 60 minutes"))
        // a slot without a picture is not validated (it is not sent)
        assertNull(validateSlots(bad.map { it.copy(assetId = null) }))
    }

    private val phone = PhoneAd(byteArrayOf(1), "p.png", false)

    @Test fun `per-stream merge, kit ads apply automatically, overrides change them, a phone picture replaces the file`() {
        assertEquals(listOf(AdPlacement.LOWER_THIRD, AdPlacement.FULL_SCREEN), mergeStreamAds(kitAds, emptyMap(), false).map { it.placement })
        val a = mergeStreamAds(kitAds, emptyMap(), false)
        assertEquals(AdSource.KIT, a[0].source); assertEquals("a1", a[0].assetId); assertTrue(a[0].enabled); assertFalse(a[1].enabled)
        // switch off / schedule override
        val o = mergeStreamAds(kitAds, mapOf(AdPlacement.LOWER_THIRD to AdOverride(enabled = false), AdPlacement.FULL_SCREEN to AdOverride(enabled = true, schedule = AdSchedule.Continuous)), false)
        assertFalse(o[0].enabled); assertTrue(o[1].enabled); assertEquals(AdSchedule.Continuous, o[1].schedule)
        // phone picture replaces the kit file but keeps the kit's schedule unless overridden; new placement from phone only
        val p = mergeStreamAds(kitAds, mapOf(AdPlacement.FULL_SCREEN to AdOverride(phone = phone), AdPlacement.SIDE_THIRD to AdOverride(phone = phone, schedule = AdSchedule.Interval(5, 20))), false)
        assertEquals(listOf(AdPlacement.LOWER_THIRD, AdPlacement.SIDE_THIRD, AdPlacement.FULL_SCREEN), p.map { it.placement })
        assertEquals(AdSource.PHONE, p[2].source); assertNull(p[2].assetId); assertEquals(AdSchedule.Interval(15, 180), p[2].schedule); assertTrue(p[2].enabled)
        assertEquals(AdSchedule.Interval(5, 20), p[1].schedule)
    }

    @Test fun `no kit clears kit ads but keeps phone pictures, and Free has no custom ads at all`() {
        val m = mergeStreamAds(null, mapOf(AdPlacement.SIDE_THIRD to AdOverride(phone = phone)), false)
        assertEquals(listOf(AdPlacement.SIDE_THIRD), m.map { it.placement })
        assertEquals(emptyList<ResolvedAd>(), mergeStreamAds(kitAds, mapOf(AdPlacement.SIDE_THIRD to AdOverride(phone = phone)), true))
        assertEquals(emptyList<ResolvedAd>(), mergeStreamAds(null, emptyMap(), false))
        // an override with only 'enabled' for a placement that has no ad creates nothing
        assertEquals(emptyList<ResolvedAd>(), mergeStreamAds(null, mapOf(AdPlacement.LOWER_THIRD to AdOverride(enabled = true)), false))
    }

    @Test fun `independent load failures are named`() {
        assertNull(describeAdFailures(emptyList()))
        val one = describeAdFailures(listOf(AdLoadFailure(AdPlacement.SIDE_THIRD, "File not found.")))!!
        assertTrue(one.contains("this ad") && one.contains("Left third") && one.contains("File not found.") && one.contains("other ads and the logo are not affected"))
        val two = describeAdFailures(listOf(AdLoadFailure(AdPlacement.LOWER_THIRD, "timeout"), AdLoadFailure(AdPlacement.FULL_SCREEN, "404")))!!
        assertTrue(two.contains("these ads") && two.contains("Lower third") && two.contains("Full screen"))
    }

    @Test fun `phone picture caps match the web`() {
        assertNull(phoneAdError(1_000_000, 4000, 3000)); assertNull(phoneAdError(MAX_PHONE_AD_BYTES, 8192, 8192))
        assertTrue(phoneAdError(MAX_PHONE_AD_BYTES + 1, 10, 10)!!.contains("12 MB"))
        assertTrue(phoneAdError(10, 8193, 10)!!.contains("8192px")); assertTrue(phoneAdError(10, 10, 9000)!!.contains("8192px"))
    }

    // ---- GIF ----

    /** A minimal valid GIF with [frames] 1x1 frames: header, logical screen (no global table), then image blocks. */
    private fun gif(frames: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("GIF89a".toByteArray()); out.write(byteArrayOf(1, 0, 1, 0, 0, 0, 0))
        repeat(frames) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))                // graphic control extension
            out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0))                       // image descriptor, no local table
            out.write(byteArrayOf(2, 2, 0x44, 0x01, 0))                                    // LZW min size + one data sub-block + terminator
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    @Test fun `counts GIF frames without decoding`() {
        assertEquals(1, countGifFrames(gif(1))); assertEquals(7, countGifFrames(gif(7))); assertEquals(0, countGifFrames(gif(0)))
        assertEquals(-1, countGifFrames("PNG....not a gif at all".toByteArray()))
    }

    @Test fun `GIF decode planning, size by area, caps, API level, reasons`() {
        val g = gif(10)
        val ok = planGif(g, 1280, 240, 1280, 240, sdk = 35)
        assertTrue(ok.animate); assertEquals(1280, ok.targetW); assertNull(ok.reason)
        val big = planGif(g, 4000, 3000, 720, 427, sdk = 35)                                // fitted into the area, never enlarged
        assertTrue(big.animate); assertEquals(569, big.targetW); assertEquals(427, big.targetH)
        assertEquals(100, planGif(g, 100, 50, 720, 427, sdk = 35).targetW)
        val old = planGif(g, 100, 100, 720, 427, sdk = 27)
        assertFalse(old.animate); assertTrue(old.reason!!.contains("Android 9"))
        val many = planGif(gif(151), 10, 10, 720, 427, sdk = 35)
        assertFalse(many.animate); assertTrue(many.reason!!.contains("151 frames"))
        assertTrue(planGif(gif(150), 10, 10, 720, 427, sdk = 35).animate)
        // memory cap: 96 MB / (w*h*4) frames
        val heavy = planGif(gif(150), 1080, 1920, 1080, 1920, sdk = 35)                       // 1080*1920*4*150 = 1.2 GB
        assertFalse(heavy.animate); assertTrue(heavy.reason!!.contains("memory"))
        assertFalse(planGif(gif(1), 100, 100, 720, 427, sdk = 35).animate); assertNull(planGif(gif(1), 100, 100, 720, 427, sdk = 35).reason)  // a one-frame GIF is just a still
        assertFalse(planGif("junk".toByteArray(), 100, 100, 720, 427, sdk = 35).animate)
        assertFalse(planGif(g, 0, 100, 720, 427, sdk = 35).animate)
    }

    @Test fun `still decoding is bounded for huge pictures`() {
        assertEquals(1, stillSampleSize(800, 400, 720, 427))
        assertEquals(4, stillSampleSize(8192, 8192, 1080, 1920))                             // 2048 x 2048 decoded instead of 8192 x 8192
        assertEquals(1, stillSampleSize(8192, 1000, 8192, 1000))
    }

    @Test fun `kit summary for the setup screen`() {
        assertEquals(emptyList<Pair<AdPlacement, String>>(), kitAdSummary(null))
        val kit = app.streambird.android.data.BrandKit("k", "Kit", ads = kitAds)
        assertEquals(listOf(AdPlacement.LOWER_THIRD to "Always on", AdPlacement.FULL_SCREEN to "Off"), kitAdSummary(kit))
    }
}
