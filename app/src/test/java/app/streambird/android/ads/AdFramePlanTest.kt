package app.streambird.android.ads

import app.streambird.android.data.BrandKit
import app.streambird.android.data.KitAd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fourth placement in the kit <-> editor conversions and the per-stream merge. */
class AdFramePlanTest {
    private val frame = KitAd("l_shape", "fr1", true, "interval", 30, 120)
    private val lower = KitAd("lower_third", "ad1", true, "continuous", null, null)

    @Test fun `the editor always has four rows, in the server's order`() {
        val slots = slotsFromKit(listOf(frame, lower))
        assertEquals(listOf(AdPlacement.LOWER_THIRD, AdPlacement.SIDE_THIRD, AdPlacement.FULL_SCREEN, AdPlacement.L_SHAPE), slots.map { it.placement })
        assertEquals("fr1", slots[3].assetId); assertEquals(AdSchedule.Interval(30, 120), slots[3].schedule); assertNull(slots[1].assetId)
        assertEquals(4, MAX_KIT_ADS)
    }

    @Test fun `what is sent contains every placement that has a picture, so the frame survives a replace-all`() {
        val slots = slotsFromKit(listOf(lower, frame))
        assertEquals(listOf(lower, frame), kitAdsFromSlots(slots))
        // editing the lower third's schedule keeps the frame in the list
        val edited = slots.map { if (it.placement == AdPlacement.LOWER_THIRD) it.copy(schedule = AdSchedule.Interval(10, 60)) else it }
        assertEquals(listOf("lower_third", "l_shape"), kitAdsFromSlots(edited).map { it.placement })
        assertTrue(adsEdited(listOf(lower, frame), edited)); assertFalse(adsEdited(listOf(lower, frame), slots))
    }

    @Test fun `all four ads together are valid, and a bad frame schedule is named`() {
        val four = AdPlacement.entries.map { AdEditSlot(it, true, "a-${it.wire}", AdSchedule.Continuous) }
        assertNull(validateSlots(four)); assertEquals(4, kitAdsFromSlots(four).size)
        val bad = four.map { if (it.placement == AdPlacement.L_SHAPE) it.copy(schedule = AdSchedule.Interval(0, 10)) else it }
        assertEquals("L-shaped frame: Show time must be between 1 second and 60 minutes.", validateSlots(bad))
    }

    @Test fun `merging for a stream - kit frame, switched off, schedule override, phone picture, Free plan`() {
        val kitAds = listOf(lower, frame)
        val merged = mergeStreamAds(kitAds, emptyMap(), freePlan = false)
        assertEquals(listOf(AdPlacement.LOWER_THIRD, AdPlacement.L_SHAPE), merged.map { it.placement })
        assertEquals("fr1", merged[1].assetId); assertEquals(AdSource.KIT, merged[1].source)
        val off = mergeStreamAds(kitAds, mapOf(AdPlacement.L_SHAPE to AdOverride(enabled = false)), false)
        assertFalse(off[1].enabled)
        val phone = mergeStreamAds(null, mapOf(AdPlacement.L_SHAPE to AdOverride(phone = PhoneAd(ByteArray(4), "frame.png", false))), false)
        assertEquals(AdSource.PHONE, phone.single().source); assertEquals(AdPlacement.L_SHAPE, phone.single().placement)
        assertTrue(mergeStreamAds(kitAds, emptyMap(), freePlan = true).isEmpty())
    }

    @Test fun `the kit summary lists the frame, and an unknown placement from a future server is skipped`() {
        val kit = BrandKit(id = "k", name = "n", ads = listOf(frame, KitAd("corner_bug", "x"), lower.copy(enabled = false)))
        assertEquals(listOf(AdPlacement.L_SHAPE to "Shown for 30 sec, then hidden for 2 min", AdPlacement.LOWER_THIRD to "Off"), kitAdSummary(kit))
    }

    @Test fun `a frame is a full-frame picture and its GIF is planned against the whole frame`() {
        val area = adRect(AdPlacement.L_SHAPE, 1280, 720)
        // 3000 x 1688 GIF into the 1280 x 720 frame: scaled to fit, never above its own size
        val p = planGif(ByteArray(0), 3000, 1688, area.w.toInt(), area.h.toInt(), sdk = 35)
        assertFalse(p.animate); assertEquals(1280, p.targetW)        // (empty bytes are not a GIF: a still, with the size it would have had)
        assertEquals(2, stillSampleSize(3000, 1688, area.w.toInt(), area.h.toInt()))
        assertEquals(2, stillSampleSize(4000, 2250, area.w.toInt(), area.h.toInt()))
    }
}
