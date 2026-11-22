package app.streambird.android.ads

import app.streambird.android.brand.BrandOrientation
import app.streambird.android.brand.kitSequence
import app.streambird.android.brand.kitSequences
import app.streambird.android.brand.kitSetFor
import app.streambird.android.brand.splitKitAds
import app.streambird.android.data.ApiJson
import app.streambird.android.data.BrandKit
import app.streambird.android.data.KitAd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ad sequences (1.0.18): the kit model and its flat sequences, the editor's list logic, the per-stream merge and edits, the words. */
class AdSequencePlanTest {
    private fun cont(p: String, id: String, sec: Int? = null, enabled: Boolean = true) = KitAd(p, id, enabled, "continuous", sec, null)
    private fun interval(p: String, id: String, show: Int, every: Int = 180, enabled: Boolean = true) = KitAd(p, id, enabled, "interval", show, every)

    // ---- the kit as the server sends it ----

    private val withMore = """{"id":"k","name":"Main","logoPosition":"left","logoSize":90,"lowerThirdStyle":"accent","isDefault":true,"connectionIds":[],
        "ads":[{"placement":"lower_third","assetId":"b1","enabled":true,"mode":"continuous","showSec":null,"everySec":null},
               {"placement":"l_shape","assetId":"a1","enabled":true,"mode":"continuous","showSec":20,"everySec":null}],
        "adsMore":[{"placement":"lower_third","assetId":"b2","enabled":false,"mode":"continuous","showSec":30,"everySec":null},
                   {"placement":"l_shape","assetId":"a2","enabled":true,"mode":"continuous","showSec":25,"everySec":null},
                   {"placement":"l_shape","assetId":"a3","enabled":true,"mode":"continuous","showSec":40,"everySec":null}],
        "adsPortrait":[{"placement":"l_shape","assetId":"p1","enabled":true,"mode":"continuous","showSec":null,"everySec":null}],
        "adsMorePortrait":[{"placement":"l_shape","assetId":"p2","enabled":true,"mode":"continuous","showSec":null,"everySec":null}]}"""
    private val oldServer = """{"id":"k2","name":"Old","logoPosition":"left","logoSize":90,"lowerThirdStyle":"accent","isDefault":false,"connectionIds":[],
        "ads":[{"placement":"lower_third","assetId":"b1","enabled":true,"mode":"continuous","showSec":null,"everySec":null}]}"""

    @Test fun `a kit with adsMore parses, an older server without it reads as no further ads`() {
        val k = ApiJson.decodeFromString<BrandKit>(withMore)
        assertEquals(3, k.adsMore.size); assertEquals(1, k.adsMorePortrait.size)
        val old = ApiJson.decodeFromString<BrandKit>(oldServer)
        assertEquals(emptyList<KitAd>(), old.adsMore); assertEquals(emptyList<KitAd>(), old.adsMorePortrait)
        assertEquals(listOf("b1"), kitSetFor(old, BrandOrientation.LANDSCAPE).ads.map { it.assetId })
    }

    @Test fun `a stream plays each placement's first ad then its further ads, per orientation, no fallback to the other set`() {
        val k = ApiJson.decodeFromString<BrandKit>(withMore)
        // placement by placement in the server's order (lower_third, side_third, full_screen, l_shape), each first ad then its further ads in play order
        assertEquals(listOf("b1", "b2", "a1", "a2", "a3"), kitSetFor(k, BrandOrientation.LANDSCAPE).ads.map { it.assetId })
        assertEquals(listOf("p1", "p2"), kitSetFor(k, BrandOrientation.PORTRAIT).ads.map { it.assetId })
        assertEquals(listOf("a1", "a2", "a3"), kitSequence(k, BrandOrientation.LANDSCAPE, AdPlacement.L_SHAPE).map { it.assetId })
        assertTrue(kitSequence(k, BrandOrientation.PORTRAIT, AdPlacement.LOWER_THIRD).isEmpty())
    }

    @Test fun `a further ad without a first ad of its placement is not played, and a long list is cut at 5`() {
        assertEquals(emptyList<KitAd>(), kitSequences(emptyList(), listOf(cont("l_shape", "z"))))
        val six = kitSequences(listOf(cont("l_shape", "a1")), (2..9).map { cont("l_shape", "a$it") })
        assertEquals(5, six.size)
    }

    @Test fun `splitKitAds is the wire split the server expects, the inverse of the flat sequences`() {
        val k = ApiJson.decodeFromString<BrandKit>(withMore)
        val (first, more) = splitKitAds(kitSetFor(k, BrandOrientation.LANDSCAPE).ads)
        assertEquals(k.ads.map { it.assetId }.sorted(), first.map { it.assetId }.sorted())
        assertEquals(k.adsMore.map { it.assetId }, more.map { it.assetId })
        assertEquals(listOf("lower_third", "l_shape"), first.map { it.placement })     // one per placement, in placement order
    }

    // ---- the editor's rows ----

    private fun slot(p: AdPlacement, vararg ids: String, sch: AdSchedule = AdSchedule.Continuous): AdEditSlot {
        var s = AdEditSlot(p, true, null, sch)
        ids.forEach { s = s.addAd(it) }
        return s
    }

    @Test fun `rows are built from first ads plus further ads and give the same lists back`() {
        val k = ApiJson.decodeFromString<BrandKit>(withMore)
        val slots = slotsFromKit(k.ads, k.adsMore)
        val frame = slots.first { it.placement == AdPlacement.L_SHAPE }
        assertEquals(listOf("a1", "a2", "a3"), frame.ads.map { it.assetId })
        assertEquals(listOf(20, 25, 40), frame.ads.map { it.showSec })
        assertEquals(k.ads, kitAdsFromSlots(slots).let { first -> k.ads.map { a -> first.first { it.placement == a.placement } } })
        assertEquals(k.adsMore.map { it.assetId }, kitAdsMoreFromSlots(slots).map { it.assetId })
        assertEquals(k.adsMore, kitAdsMoreFromSlots(slots))
        assertFalse(slots.first { it.placement == AdPlacement.LOWER_THIRD }.more.first().enabled)    // ad 2 saved off stays off in the editor
    }

    @Test fun `add, remove and reorder keep the order and stop at five`() {
        var s = slot(AdPlacement.LOWER_THIRD, "a", "b", "c")
        assertEquals(listOf("a", "b", "c"), s.ads.map { it.assetId })
        s = s.moveAd(2, -1); assertEquals(listOf("a", "c", "b"), s.ads.map { it.assetId })
        s = s.moveAd(1, -1); assertEquals(listOf("c", "a", "b"), s.ads.map { it.assetId }); assertEquals("c", s.assetId)   // the first ad changes with the order
        assertEquals(s, s.moveAd(0, -1)); assertEquals(s, s.moveAd(2, 1))                                                  // the ends do not move
        s = s.removeAd(0); assertEquals(listOf("a", "b"), s.ads.map { it.assetId }); assertEquals("a", s.assetId)
        s = s.addAd("d").addAd("e").addAd("f")
        assertEquals(5, s.count); assertFalse(s.canAddAnother)
        assertEquals(s, s.addAd("g"))                                                                                         // the limit is 5
        assertEquals(5, s.removeAd(9).count)
        assertEquals(listOf("a", "b", "d", "e"), s.removeAd(4).ads.map { it.assetId })
        val none = s.ads.indices.fold(s) { acc, _ -> acc.removeAd(0) }
        assertNull(none.assetId); assertEquals(0, none.count)
    }

    @Test fun `each ad has its own On switch and seconds`() {
        var s = slot(AdPlacement.SIDE_THIRD, "a", "b", "c")
        s = s.setAdEnabled(1, false).setAdSeconds(2, 45).setAdSeconds(0, 10)
        assertEquals(listOf(true, false, true), s.ads.map { it.enabled })
        assertEquals(listOf(10, null, 45), s.ads.map { it.showSec })
        assertEquals(10, s.dwellSec)                                                                  // the first ad's seconds live on the row in continuous mode
        val s2 = s.replaceAd(1, "z")
        assertEquals(listOf("a", "z", "c"), s2.ads.map { it.assetId }); assertFalse(s2.ads[1].enabled)    // replacing keeps its switch
    }

    @Test fun `the first ad's seconds follow it when the order changes (interval and continuous)`() {
        var s = slot(AdPlacement.LOWER_THIRD, "a", "b", sch = AdSchedule.Interval(15, 180)).setAdSeconds(1, 40)
        assertEquals(listOf(15, 40), s.ads.map { it.showSec })
        s = s.moveAd(1, -1)
        assertEquals(listOf("b", "a"), s.ads.map { it.assetId }); assertEquals(listOf(40, 15), s.ads.map { it.showSec })
        assertEquals(AdSchedule.Interval(40, 180), s.schedule)
        var c = slot(AdPlacement.LOWER_THIRD, "a", "b").setAdSeconds(0, 30).setAdSeconds(1, 50).moveAd(0, 1)
        assertEquals(listOf(50, 30), c.ads.map { it.showSec }); assertEquals(50, c.dwellSec)
    }

    @Test fun `changing the mode clears the other ads' seconds, changing only the numbers does not`() {
        val s = slot(AdPlacement.LOWER_THIRD, "a", "b", sch = AdSchedule.Interval(15, 180)).setAdSeconds(1, 40)
        val same = s.withSchedule(AdSchedule.Interval(20, 300))
        assertEquals(40, same.more[0].showSec)
        val flipped = s.withSchedule(AdSchedule.Continuous)
        assertNull(flipped.more[0].showSec); assertNull(flipped.dwellSec)
        assertEquals(AdSchedule.Continuous, flipped.schedule)
    }

    @Test fun `validation names the ad and uses the server ranges`() {
        val ok = listOf(slot(AdPlacement.LOWER_THIRD, "a", "b").setAdSeconds(1, 5))
        assertNull(validateSlots(ok))
        assertEquals("Lower third ad 2: Seconds before the next ad must be between 5 and 300.", validateSlots(listOf(slot(AdPlacement.LOWER_THIRD, "a", "b").setAdSeconds(1, 301))))
        assertEquals("Left third ad 1: Seconds before the next ad must be between 5 and 300.", validateSlots(listOf(slot(AdPlacement.SIDE_THIRD, "a", "b").setAdSeconds(0, 4))))
        assertEquals("Full screen ad 2: Show time must be between 1 second and 60 minutes.", validateSlots(listOf(slot(AdPlacement.FULL_SCREEN, "a", "b", sch = AdSchedule.Interval(15, 60)).setAdSeconds(1, 4000))))
        assertNull(validateSlots(listOf(slot(AdPlacement.LOWER_THIRD, "a").setAdSeconds(0, 9999))))     // a single ad: as before, its seconds are not looked at in continuous mode
        assertNull(adSecondsError(AdSchedule.Continuous, null))
    }

    // ---- what is sent ----

    @Test fun `one ad per placement saves exactly like before - adsMore is not sent`() {
        val original = listOf(cont("lower_third", "a"), interval("l_shape", "f", 30, 120))
        assertNull(kitAdsSave(original, emptyList(), slotsFromKit(original)))                       // untouched = nothing sent
        val edited = slotsFromKit(original).map { if (it.placement == AdPlacement.SIDE_THIRD) it.addAd("s") else it }
        val save = kitAdsSave(original, emptyList(), edited)!!
        assertEquals(listOf("lower_third", "side_third", "l_shape"), save.ads.map { it.placement })
        assertNull(save.adsMore)
    }

    @Test fun `adding a second ad sends ads and adsMore together, removing it sends an empty adsMore`() {
        val original = listOf(cont("lower_third", "a"))
        val two = slotsFromKit(original).map { if (it.placement == AdPlacement.LOWER_THIRD) it.addAd("b") else it }
        val save = kitAdsSave(original, emptyList(), two)!!
        assertEquals(listOf("a"), save.ads.map { it.assetId }); assertEquals(listOf("b"), save.adsMore!!.map { it.assetId })
        // a lone continuous ad has no seconds; with two the second carries its own (null = default)
        assertNull(save.ads[0].showSec)
        val savedBack = listOf(cont("lower_third", "a")) to listOf(cont("lower_third", "b"))
        val one = slotsFromKit(savedBack.first, savedBack.second).map { it.removeAd(1) }
        val clear = kitAdsSave(savedBack.first, savedBack.second, one)!!
        assertEquals(emptyList<KitAd>(), clear.adsMore)                                             // [] = no further ads, sent explicitly
    }

    @Test fun `only reordering sends both fields, only a changed second ad sends them too`() {
        val a = listOf(cont("l_shape", "a1", 20)); val m = listOf(cont("l_shape", "a2", 25), cont("l_shape", "a3", 40))
        val rows = slotsFromKit(a, m)
        assertNull(kitAdsSave(a, m, rows))
        val swapped = rows.map { if (it.placement == AdPlacement.L_SHAPE) it.moveAd(2, -1) else it }
        val s = kitAdsSave(a, m, swapped)!!
        assertEquals(listOf("a1"), s.ads.map { it.assetId }); assertEquals(listOf("a3", "a2"), s.adsMore!!.map { it.assetId })
        val moved = rows.map { if (it.placement == AdPlacement.L_SHAPE) it.moveAd(0, 1) else it }
        val s2 = kitAdsSave(a, m, moved)!!
        assertEquals(listOf("a2"), s2.ads.map { it.assetId }); assertEquals(listOf("a1", "a3"), s2.adsMore!!.map { it.assetId })
        assertEquals(25, s2.ads[0].showSec); assertEquals(20, s2.adsMore!![0].showSec)
    }

    @Test fun `interval ads send their own show window and the placement's pause on every ad`() {
        val rows = listOf(slot(AdPlacement.FULL_SCREEN, "a", "b", "c", sch = AdSchedule.Interval(15, 90)).setAdSeconds(1, 40))
        val first = kitAdsFromSlots(rows); val more = kitAdsMoreFromSlots(rows)
        assertEquals(KitAd("full_screen", "a", true, "interval", 15, 90), first[0])
        assertEquals(KitAd("full_screen", "b", true, "interval", 40, 90), more[0])
        assertEquals(KitAd("full_screen", "c", true, "interval", 15, 90), more[1])         // an unset show window takes the first ad's (the server needs one per ad)
    }

    // ---- per stream: merge ----

    private val kitAds = listOf(cont("lower_third", "b1"), cont("lower_third", "b2", 30), cont("lower_third", "b3", 10), interval("l_shape", "f1", 30, 120), interval("l_shape", "f2", 10, 120))
    private val phone1 = PhoneAd(byteArrayOf(1), "one.png", false)

    @Test fun `a new stream plays every kit ad of a placement in order, all switched on, with the kit's seconds`() {
        val m = mergeStreamAds(listOf(cont("lower_third", "b1", enabled = false), cont("lower_third", "b2", 30, enabled = false)), emptyMap(), false)
        assertEquals(listOf("b1", "b2"), m.map { it.assetId }); assertTrue(m.all { it.enabled })      // saved "off" starts on
        assertEquals(listOf(0, 1), m.map { it.index }); assertEquals(30, m[1].dwellSec); assertEquals(30, m[1].showSec)
        assertNull(m[0].dwellSec); assertEquals(AD_DWELL_DEFAULT, m[0].showSec)
        val all = mergeStreamAds(kitAds, emptyMap(), false)
        assertEquals(listOf("b1", "b2", "b3", "f1", "f2"), all.map { it.assetId })
        assertEquals(AdSchedule.Interval(30, 120), all[3].schedule); assertEquals(AdSchedule.Interval(10, 120), all[4].schedule)   // each its own show window, one pause
    }

    @Test fun `old overrides still work on a sequence - a switch acts on every ad, the schedule is the placement's`() {
        val off = mergeStreamAds(kitAds, mapOf(AdPlacement.LOWER_THIRD to AdOverride(enabled = false)), false)
        assertEquals(listOf(false, false, false, true, true), off.map { it.enabled })
        val sch = mergeStreamAds(kitAds, mapOf(AdPlacement.L_SHAPE to AdOverride(schedule = AdSchedule.Interval(5, 60))), false).filter { it.placement == AdPlacement.L_SHAPE }
        assertEquals(listOf(AdSchedule.Interval(5, 60), AdSchedule.Interval(10, 60)), sch.map { it.schedule })     // first ad's window from the override, the other keeps its own
        val phone = mergeStreamAds(kitAds, mapOf(AdPlacement.LOWER_THIRD to AdOverride(phone = phone1)), false).filter { it.placement == AdPlacement.LOWER_THIRD }
        assertEquals(listOf(AdSource.PHONE, AdSource.KIT, AdSource.KIT), phone.map { it.source }); assertEquals(listOf(null, "b2", "b3"), phone.map { it.assetId })
    }

    @Test fun `a list edited on Setup wins, a removed kit ad stays removed and an ad of another kit is appended`() {
        var o = overrideRemoveAd(kitAds, emptyMap(), AdPlacement.LOWER_THIRD, 1)
        assertEquals(listOf("b1", "b3"), mergeStreamAds(kitAds, o, false).filter { it.placement == AdPlacement.LOWER_THIRD }.map { it.assetId })
        // the host switches to another kit with different ads: the removed one is not among them; new ads are appended, vanished ones dropped
        val other = listOf(cont("lower_third", "b1"), cont("lower_third", "n1"))
        assertEquals(listOf("b1", "n1"), mergeStreamAds(other, o, false).map { it.assetId })
        o = overrideReset(o)
        assertEquals(3, mergeStreamAds(kitAds, o, false).count { it.placement == AdPlacement.LOWER_THIRD })
    }

    private fun overrideReset(o: Map<AdPlacement, AdOverride>) = resetPlacement(o, AdPlacement.LOWER_THIRD)

    @Test fun `reorder, switch one ad, seconds, add a phone picture and the limit of five`() {
        var o = overrideMoveAd(kitAds, emptyMap(), AdPlacement.LOWER_THIRD, 2, -2)
        fun lower() = currentAds(kitAds, o, AdPlacement.LOWER_THIRD)
        assertEquals(listOf("b3", "b1", "b2"), lower().map { it.assetId })
        assertEquals(listOf(10, null, 30), lower().map { it.dwellSec })                  // the kit's seconds travel with their ad
        o = overrideSetOn(kitAds, o, AdPlacement.LOWER_THIRD, 1, false)
        assertEquals(listOf(true, false, true), lower().map { it.enabled })
        o = overrideSetSeconds(kitAds, o, AdPlacement.LOWER_THIRD, 0, 60)
        assertEquals(60, lower()[0].showSec)
        o = overrideAddPhone(kitAds, o, AdPlacement.LOWER_THIRD, phone1)
        assertEquals(listOf(AdSource.KIT, AdSource.KIT, AdSource.KIT, AdSource.PHONE), lower().map { it.source })
        o = overrideAddPhone(kitAds, o, AdPlacement.LOWER_THIRD, phone1)
        assertEquals(5, lower().size)
        val full = overrideAddPhone(kitAds, o, AdPlacement.LOWER_THIRD, phone1)
        assertEquals(5, currentAds(kitAds, full, AdPlacement.LOWER_THIRD).size)           // the sixth is refused
        assertTrue(placementEdited(o, AdPlacement.LOWER_THIRD)); assertFalse(placementEdited(o, AdPlacement.L_SHAPE))
        // the other placements are untouched
        assertEquals(listOf("f1", "f2"), currentAds(kitAds, o, AdPlacement.L_SHAPE).map { it.assetId })
    }

    @Test fun `replacing one ad with a phone picture keeps its switch and seconds`() {
        var o = overrideSetOn(kitAds, emptyMap(), AdPlacement.LOWER_THIRD, 1, false)
        o = overrideReplaceWithPhone(kitAds, o, AdPlacement.LOWER_THIRD, 1, phone1)
        val r = currentAds(kitAds, o, AdPlacement.LOWER_THIRD)[1]
        assertEquals(AdSource.PHONE, r.source); assertFalse(r.enabled); assertEquals(30, r.dwellSec)
    }

    @Test fun `the placement schedule on a sequence - the first ad's window, the mode flip clears seconds, one ad is the old override`() {
        val o = overrideSetSchedule(kitAds, emptyMap(), AdPlacement.L_SHAPE, AdSchedule.Interval(7, 45))
        assertEquals(listOf(AdSchedule.Interval(7, 45), AdSchedule.Interval(10, 45)), currentAds(kitAds, o, AdPlacement.L_SHAPE).map { it.schedule })
        val flip = overrideSetSchedule(kitAds, emptyMap(), AdPlacement.L_SHAPE, AdSchedule.Continuous)
        val ads = currentAds(kitAds, flip, AdPlacement.L_SHAPE)
        assertEquals(listOf(AdSchedule.Continuous, AdSchedule.Continuous), ads.map { it.schedule }); assertEquals(listOf(AD_DWELL_DEFAULT, AD_DWELL_DEFAULT), ads.map { it.showSec })
        val single = overrideSetSchedule(listOf(cont("full_screen", "x")), emptyMap(), AdPlacement.FULL_SCREEN, AdSchedule.Interval(5, 20))
        assertEquals(AdOverride(schedule = AdSchedule.Interval(5, 20)), single[AdPlacement.FULL_SCREEN])    // exactly the single-ad override of 1.0.17
    }

    @Test fun `no kit - phone pictures alone make a sequence`() {
        var o = mapOf(AdPlacement.SIDE_THIRD to AdOverride(phone = phone1, enabled = true))
        o = overrideAddPhone(null, o, AdPlacement.SIDE_THIRD, PhoneAd(byteArrayOf(2), "two.png", false))
        val m = mergeStreamAds(null, o, false)
        assertEquals(2, m.size); assertTrue(m.all { it.source == AdSource.PHONE })
        assertTrue(mergeStreamAds(kitAds, o, freePlan = true).isEmpty())
    }

    // ---- words ----

    @Test fun `switched-off wording names the ad number, a placement counts as off only when every ad is`() {
        assertEquals("L-shaped frame is switched off: tick On to use it", adSwitchedOffText(AdPlacement.L_SHAPE))
        assertEquals("L-shaped frame is switched off: tick On to use it", adSwitchedOffText(AdPlacement.L_SHAPE, adNumber = 1))
        assertEquals("L-shaped frame ad 2 is switched off: tick On to use it", adSwitchedOffText(AdPlacement.L_SHAPE, adNumber = 2))
        assertTrue(adSwitchedOffText(AdPlacement.LOWER_THIRD, hasOnSwitch = false, adNumber = 3).startsWith("Lower third ad 3 is switched off: it cannot be switched on during the stream"))
        assertEquals(listOf(AdPlacement.L_SHAPE), switchedOffPlacements(listOf(AdPlacement.L_SHAPE to false)))
        assertEquals(emptyList<AdPlacement>(), switchedOffPlacements(listOf(AdPlacement.L_SHAPE to false, AdPlacement.L_SHAPE to true)))
        assertEquals(listOf(AdPlacement.L_SHAPE), switchedOffPlacements(listOf(AdPlacement.L_SHAPE to false, AdPlacement.L_SHAPE to false, AdPlacement.LOWER_THIRD to true)))
        assertEquals(emptyList<AdPlacement>(), slotsSwitchedOff(listOf(slot(AdPlacement.L_SHAPE, "a", "b").setAdEnabled(0, false))))
        assertEquals(listOf(AdPlacement.L_SHAPE), slotsSwitchedOff(listOf(slot(AdPlacement.L_SHAPE, "a", "b").setAdEnabled(0, false).setAdEnabled(1, false))))
        assertEquals("Lower third ad 2", adLabel(AdPlacement.LOWER_THIRD, 2)); assertEquals("Lower third", adLabel(AdPlacement.LOWER_THIRD, 1))
        assertEquals("Lower third ad 1", adTalkBackLabel(AdPlacement.LOWER_THIRD, 1))
    }

    @Test fun `the kit list chip counts every switched-off ad, further ads and the 9 by 16 set included`() {
        val k = ApiJson.decodeFromString<BrandKit>(withMore)
        val (l, p) = k.allAds()
        assertEquals(1, kitAdsSwitchedOffCount(l, p))                                  // b2 is saved off
        assertEquals("1 ad switched off", adsSwitchedOffChipText(kitAdsSwitchedOffCount(l, p)))
        val more = k.copy(adsMorePortrait = k.adsMorePortrait.map { it.copy(enabled = false) }, adsMore = k.adsMore.map { it.copy(enabled = false) })
        assertEquals("4 ads switched off", adsSwitchedOffChipText(kitAdsSwitchedOffCount(more.allAds().first, more.allAds().second)))
    }

    @Test fun `Live says which ad of the sequence is showing`() {
        val ads = listOf(SeqAd(true, 20), SeqAd(true, 20), SeqAd(true, 20))
        assertEquals("Now showing 2 of 3. Next ad in 0:15", sequenceStatusText(25.0, ads, null, SeqSchedule.Continuous))
        assertEquals("Now showing 1 of 3. Next ad in 0:10", sequenceStatusText(10.0, ads, null, SeqSchedule.Continuous))
        val iv = listOf(SeqAd(true, 10), SeqAd(true, 20))
        assertEquals("Up next: 1 of 2. Shows in 1:40 for 10 s", sequenceStatusText(0.0, iv, null, SeqSchedule.Interval(100)))
        assertEquals("Now showing 1 of 2 (ends in 5 s)", sequenceStatusText(105.0, iv, null, SeqSchedule.Interval(100)))
        assertEquals("Up next: 2 of 2. Shows in 1:35 for 20 s", sequenceStatusText(115.0, iv, null, SeqSchedule.Interval(100)))
        assertEquals("Switched off for this stream", sequenceStatusText(0.0, listOf(SeqAd(false, 20), SeqAd(false, 20)), null, SeqSchedule.Continuous))
        assertTrue(sequenceStatusText(0.0, ads, AdManual.HIDDEN, SeqSchedule.Continuous).startsWith("Hidden by you"))
        assertTrue(sequenceStatusText(0.0, ads, AdManual.SHOWN, SeqSchedule.Continuous).startsWith("Shown by you (Now showing 1 of 3)"))
        assertNull(sequencePositionText(1, 1, true))
        assertNotNull(sequencePositionText(2, 3, true))
    }

    @Test fun `the Setup card says how a sequence plays`() {
        val ads = mergeStreamAds(kitAds, emptyMap(), false)
        assertEquals("3 ads play in turn, always on: each stays for its own seconds, then the next one fades in.", sequenceSetupText(ads.filter { it.placement == AdPlacement.LOWER_THIRD }))
        assertTrue(sequenceSetupText(ads.filter { it.placement == AdPlacement.L_SHAPE }).startsWith("2 ads play in turn. Each shows for its own seconds, then hides for 2 min"))
        assertEquals("No ad", sequenceSetupText(emptyList()))
    }

    @Test fun `a failure of a later ad names its number`() {
        assertEquals("Could not load the Left third ad 2 of kit 'K' (not found).", describeAdFailure(AdLoadFailure(AdPlacement.SIDE_THIRD, "not found", "K", 1)))
        assertEquals("Could not load the Left third ad of kit 'K' (not found).", describeAdFailure(AdLoadFailure(AdPlacement.SIDE_THIRD, "not found", "K")))
    }
}
