package app.streambird.android.remote

import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.ads.AdSource
import app.streambird.android.clock.ClockSettings
import app.streambird.android.media.AdInput
import app.streambird.android.stream.LiveAd
import app.streambird.android.stream.liveAdsOf
import app.streambird.android.stream.rescheduled
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remote-control snapshot of ad sequences (1.0.18): one state per placement, plus `sequence` and `current`; and the commands acting on the whole placement. */
class RemoteSequenceStateTest {
    private fun state(ads: List<LiveAd>, elapsed: Double = 0.0): JsonObject = RemoteState.build(
        RemoteStateInput(ads, true, "", ClockSettings(), "landscape", "1.0.18", false, adElapsedSec = elapsed),
    )
    private fun ad(state: JsonObject, p: AdPlacement) = state["ads"]!!.jsonObject[p.wire]!!.jsonObject
    private fun live(i: Int, enabled: Boolean = true, sec: Int? = null, schedule: AdSchedule = AdSchedule.Continuous, problem: String? = null, asset: String? = "as$i", manual: AdManual? = null, source: AdSource = AdSource.KIT) =
        LiveAd(AdPlacement.LOWER_THIRD, manual, source, enabled, schedule, problem, index = i, dwellSec = sec, assetId = asset)

    @Test fun `one ad per placement is the snapshot of the release before sequences, no sequence, no current`() {
        val a = ad(state(listOf(live(0))), AdPlacement.LOWER_THIRD)
        assertEquals("""{"present":true,"enabled":true,"manual":null,"mode":"continuous","source":"kit"}""", a.toString())
        assertEquals("""{"present":false}""", ad(state(listOf(live(0))), AdPlacement.L_SHAPE).toString())
    }

    @Test fun `a sequence lists every ad in play order and the one that is current`() {
        val ads = listOf(live(0, sec = 20), live(1, sec = 30), live(2, sec = 10))
        val a = ad(state(ads, elapsed = 25.0), AdPlacement.LOWER_THIRD)
        assertEquals("true", a["present"].toString()); assertEquals("true", a["enabled"].toString()); assertEquals("\"continuous\"", a["mode"].toString())
        val seq = a["sequence"] as JsonArray
        assertEquals(listOf("""{"assetId":"as0","enabled":true,"showSec":20}""", """{"assetId":"as1","enabled":true,"showSec":30}""", """{"assetId":"as2","enabled":true,"showSec":10}"""), seq.map { it.toString() })
        assertEquals("1", a["current"].toString())                                                        // 25 s: the second ad (20..50)
        assertEquals("0", ad(state(ads, elapsed = 5.0), AdPlacement.LOWER_THIRD)["current"].toString())
        assertEquals("2", ad(state(ads, elapsed = 55.0), AdPlacement.LOWER_THIRD)["current"].toString())
        assertEquals("0", ad(state(ads, elapsed = 60.0), AdPlacement.LOWER_THIRD)["current"].toString())    // the round repeats
    }

    @Test fun `interval - the placement schedule is the first ad's, the sequence carries each ad's own show window`() {
        val i = AdSchedule.Interval(15, 180); val j = AdSchedule.Interval(40, 180)
        val ads = listOf(live(0, schedule = i), live(1, schedule = j))
        val a = ad(state(ads, elapsed = 0.0), AdPlacement.LOWER_THIRD)
        assertEquals("""{"present":true,"enabled":true,"manual":null,"mode":"interval","showSec":15,"everySec":180,"source":"kit","sequence":[{"assetId":"as0","enabled":true,"showSec":15},{"assetId":"as1","enabled":true,"showSec":40}],"current":0}""", a.toString())
        assertEquals("1", ad(state(ads, elapsed = 200.0), AdPlacement.LOWER_THIRD)["current"].toString())    // ad 2's cycle began at 195
    }

    @Test fun `a switched-off ad and an ad that failed to load are enabled false, the placement stays on while one ad is`() {
        val ads = listOf(live(0, enabled = false), live(1, problem = "not found"), live(2))
        val a = ad(state(ads), AdPlacement.LOWER_THIRD)
        assertEquals("true", a["enabled"].toString()); assertEquals("2", a["current"].toString())
        assertEquals(listOf("false", "false", "true"), (a["sequence"] as JsonArray).map { it.jsonObject["enabled"].toString() })
        val allOff = ad(state(listOf(live(0, enabled = false), live(1, enabled = false))), AdPlacement.LOWER_THIRD)
        assertEquals("false", allOff["enabled"].toString()); assertFalse(allOff.containsKey("current")); assertTrue(allOff.containsKey("sequence"))
    }

    @Test fun `at most 5 entries, a phone ad has no assetId, manual is the placement's`() {
        val ads = (0..4).map { live(it, source = AdSource.PHONE, manual = AdManual.SHOWN) }
        val a = ad(state(ads), AdPlacement.LOWER_THIRD)
        assertEquals(5, (a["sequence"] as JsonArray).size)
        assertTrue((a["sequence"] as JsonArray).all { !it.jsonObject.containsKey("assetId") })
        assertEquals("\"shown\"", a["manual"].toString()); assertEquals("\"phone\"", a["source"].toString())
    }

    @Test fun `the snapshot matches what the server reads - sequence of enabled and showSec, current inside the list`() {
        val a = ad(state(listOf(live(0, sec = 20), live(1, sec = 5)), elapsed = 21.0), AdPlacement.LOWER_THIRD)
        val seq = a["sequence"] as JsonArray
        // support-state / phone-mapping: a sequence entry needs a boolean `enabled`, an optional integer showSec 1..3600; `current` an integer 0..length-1
        assertTrue(seq.all { e -> e.jsonObject["enabled"].toString().let { it == "true" || it == "false" } })
        assertTrue(seq.all { e -> e.jsonObject["showSec"].toString().toInt() in 1..3600 })
        assertTrue(a["current"].toString().toInt() in 0 until seq.size)
    }

    // ---- the commands act on the whole placement ----

    private fun inputs() = listOf(
        AdInput(AdPlacement.LOWER_THIRD, byteArrayOf(1), true, AdSchedule.Interval(15, 180), index = 0),
        AdInput(AdPlacement.LOWER_THIRD, byteArrayOf(2), true, AdSchedule.Interval(40, 180), index = 1),
    )

    @Test fun `the Live list has one entry per ad, failed ones included, in play order`() {
        val l = liveAdsOf(inputs(), listOf(app.streambird.android.ads.AdLoadFailure(AdPlacement.LOWER_THIRD, "gone", "K", 2), app.streambird.android.ads.AdLoadFailure(AdPlacement.SIDE_THIRD, "gone", "K", 0)))
        assertEquals(listOf(AdPlacement.LOWER_THIRD, AdPlacement.LOWER_THIRD, AdPlacement.LOWER_THIRD, AdPlacement.SIDE_THIRD), l.map { it.placement })
        assertEquals(listOf(0, 1, 2, 0), l.map { it.index }); assertEquals(listOf(null, null, "gone", "gone"), l.map { it.problem })
    }

    @Test fun `a remote schedule changes the mode and pause of every ad but only the first ad's show window`() {
        val l = liveAdsOf(inputs(), emptyList())
        val r = l.rescheduled(AdPlacement.LOWER_THIRD, AdSchedule.Interval(5, 60))
        assertEquals(listOf(AdSchedule.Interval(5, 60), AdSchedule.Interval(40, 60)), r.map { it.schedule })
        val c = r.rescheduled(AdPlacement.LOWER_THIRD, AdSchedule.Continuous)
        assertEquals(listOf(AdSchedule.Continuous, AdSchedule.Continuous), c.map { it.schedule })
        assertEquals(listOf(5, 40), c.map { it.showSec })                                                  // the seconds survive the flip, like the web studio
        assertEquals(l.filter { it.placement != AdPlacement.LOWER_THIRD }, r.filter { it.placement != AdPlacement.LOWER_THIRD })
        assertNull(r[0].manual)
    }

    @Test fun `a kit apply replaces the sequences - the list follows the new inputs only`() {
        val before = liveAdsOf(inputs(), emptyList())
        val after = liveAdsOf(listOf(AdInput(AdPlacement.FULL_SCREEN, byteArrayOf(3), true, AdSchedule.Continuous)), emptyList())
        assertEquals(2, before.size); assertEquals(listOf(AdPlacement.FULL_SCREEN), after.map { it.placement })
    }
}
