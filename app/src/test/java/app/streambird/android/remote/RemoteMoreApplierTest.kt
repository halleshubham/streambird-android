package app.streambird.android.remote

import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.clock.ClockSettings
import app.streambird.android.media.NameBannerState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.15: logo.set, the name banner and kit.apply mapped onto a fake RemoteControls; the conflict window for the new keys; the snapshot with the new optional fields. */
class RemoteMoreApplierTest {
    private var now = 1_000_000L
    private val controls = RemoteApplierTest.FakeControls()
    private val host = HostChangeTracker { now }
    private val applier = RemoteApplier(controls, host)
    private fun c(a: RemoteAction, seq: Long = 1) = RemoteCommand(seq, "id$seq", "t", 0, a)
    private val kit = "0b6f6f0e-3c43-4a76-9d3b-6a3f3d6a9c11"

    // ---- logo.set ----

    @Test fun `logo set changes side and size through the controls`() {
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.LogoSet(true, null))))
        assertEquals(LogoInfo(true, true, 90), controls.logoNow)
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.LogoSet(null, 200))))
        assertEquals(LogoInfo(true, true, 200), controls.logoNow)
        assertEquals(listOf("logo true null", "logo null 200"), controls.calls)
    }

    @Test fun `logo set clamps the size and refuses when the stream has no logo`() {
        assertTrue(applier.apply(c(RemoteAction.LogoSet(null, 5000))).ok)
        assertEquals(600, controls.logoNow!!.size)
        assertTrue(applier.apply(c(RemoteAction.LogoSet(null, 1))).ok)
        assertEquals(16, controls.logoNow!!.size)
        controls.logoNow = null; controls.calls.clear()
        val r = applier.apply(c(RemoteAction.LogoSet(false, 100)))
        assertFalse(r.ok); assertEquals("no logo on this stream", r.error)
        assertTrue(controls.calls.isEmpty())
    }

    // ---- name banner ----

    @Test fun `banner set shows hides and edits without showing by itself`() {
        assertTrue(applier.apply(c(RemoteAction.LowerThirdSet("Jane Doe", true, "Host", null))).ok)
        assertEquals(NameBannerState("Jane Doe", "Host", false), controls.bannerNow)
        assertTrue(applier.apply(c(RemoteAction.LowerThirdSet(null, false, null, true))).ok)
        assertTrue(controls.bannerNow.visible)
        assertTrue(applier.apply(c(RemoteAction.LowerThirdSet("John", false, null, null))).ok)          // text only: stays shown, new text
        assertEquals(NameBannerState("John", "Host", true), controls.bannerNow)
        assertTrue(applier.apply(c(RemoteAction.LowerThirdSet(null, true, null, null))).ok)             // title removed
        assertNull(controls.bannerNow.title)
        assertTrue(applier.apply(c(RemoteAction.LowerThirdSet(null, false, null, false))).ok)           // hide keeps the texts
        assertEquals(NameBannerState("John", null, false), controls.bannerNow)
    }

    @Test fun `banner show without any name is refused and changes nothing`() {
        val r = applier.apply(c(RemoteAction.LowerThirdSet(null, false, null, true)))
        assertFalse(r.ok); assertEquals("no name for the banner", r.error)
        assertEquals(NameBannerState(), controls.bannerNow)
        assertTrue(controls.calls.isEmpty())
        val r2 = applier.apply(c(RemoteAction.LowerThirdSet("", false, null, true)))                    // an empty name counts as none
        assertEquals("no name for the banner", r2.error)
        assertTrue(controls.calls.isEmpty())
    }

    @Test fun `clearing the name of a banner that is on is allowed and hides it`() {
        controls.bannerNow = NameBannerState("Jane", null, true)
        assertTrue(applier.apply(c(RemoteAction.LowerThirdSet("", false, null, null))).ok)
        assertFalse(controls.bannerNow.visible)
    }

    // ---- kit.apply ----

    @Test fun `kit apply starts the work and reports pending, the late result comes through the callback`() {
        val r = applier.apply(c(RemoteAction.KitApply(kit))) { }
        assertTrue(r.pending); assertFalse(r.ok)
        assertEquals(listOf("kit $kit"), controls.calls)
        var got: ApplyResult? = null
        val r2 = applier.apply(c(RemoteAction.KitApply(kit), 2)) { got = it }
        controls.kitLate!!.invoke(ApplyResult.OK)
        assertEquals(ApplyResult.OK, got); assertTrue(r2.pending)
    }

    @Test fun `kit apply answers busy, free plan and not live at once`() {
        controls.kitResult = ApplyResult.err("busy")
        assertEquals("busy", applier.apply(c(RemoteAction.KitApply(kit))).error)
        controls.freePlan = true; controls.calls.clear()
        assertEquals("plan does not include brand kits", applier.apply(c(RemoteAction.KitApply(kit))).error)
        assertTrue(controls.calls.isEmpty())
        controls.freePlan = false; controls.isLive = false
        assertEquals("stream not live", applier.apply(c(RemoteAction.KitApply(kit))).error)
        assertTrue(controls.calls.isEmpty())
    }

    // ---- conflict window for the new keys ----

    @Test fun `host changes to logo and banner block the remote command for 3 seconds`() {
        host.mark(RemoteKeys.LOGO)
        now += 2_999
        assertEquals("host changed it just now", applier.apply(c(RemoteAction.LogoSet(true, null))).error)
        assertTrue(applier.apply(c(RemoteAction.LowerThirdSet("A", false, null, null))).ok)             // another setting
        host.mark(RemoteKeys.LOWER_THIRD)
        assertEquals("host changed it just now", applier.apply(c(RemoteAction.LowerThirdSet("B", false, null, null))).error)
        now += 3_000
        assertTrue(applier.apply(c(RemoteAction.LowerThirdSet("B", false, null, null))).ok)
        assertTrue(applier.apply(c(RemoteAction.LogoSet(true, null))).ok)
    }

    @Test fun `kit apply is refused while the host touched anything it replaces`() {
        for (key in listOf(RemoteKeys.KIT, RemoteKeys.LOGO, RemoteKeys.CLOCK, RemoteKeys.ADS_SHRINK, RemoteKeys.adManual(AdPlacement.L_SHAPE), RemoteKeys.adEnabled(AdPlacement.FULL_SCREEN), RemoteKeys.adSchedule(AdPlacement.SIDE_THIRD))) {
            host.clear(); controls.calls.clear()
            host.mark(key)
            now += 100
            assertEquals(key, "host changed it just now", applier.apply(c(RemoteAction.KitApply(kit))).error)
            assertTrue(key, controls.calls.isEmpty())
            now += 3_000
            assertTrue(key, applier.apply(c(RemoteAction.KitApply(kit))).pending)
        }
        host.clear(); controls.calls.clear(); host.mark(RemoteKeys.TICKER)                                // the ticker is not part of a kit
        assertTrue(applier.apply(c(RemoteAction.KitApply(kit))).pending)
    }

    @Test fun `changedSince tells a host change during a long apply from an old one`() {
        host.mark(RemoteKeys.LOGO)                       // at 1_000_000
        now = 1_005_000
        assertFalse(host.changedSince(RemoteKeys.kitCovers(), 1_000_001))
        assertTrue(host.changedSince(RemoteKeys.kitCovers(), 1_000_000))
        host.mark(RemoteKeys.adManual(AdPlacement.LOWER_THIRD))
        assertTrue(host.changedSince(RemoteKeys.kitCovers(), 1_005_000))
        assertFalse(host.changedSince(listOf(RemoteKeys.TICKER), 0))
        assertTrue(RemoteKeys.kitCovers().containsAll(listOf(RemoteKeys.KIT, RemoteKeys.LOGO, RemoteKeys.CLOCK, RemoteKeys.ADS_SHRINK)))
        assertEquals(4 + 3 * AdPlacement.entries.size, RemoteKeys.kitCovers().size)
    }

    // ---- snapshot ----

    private fun snapshot(i: RemoteStateInput): JsonObject = Json.parseToJsonElement(RemoteState.build(i).toString()).jsonObject
    private fun input(freePlan: Boolean = false, kits: List<KitRef> = emptyList(), kitId: String? = null, logo: LogoInfo = LogoInfo(false, false, 90), banner: NameBannerState = NameBannerState()) =
        RemoteStateInput(emptyList(), true, "", ClockSettings(), "portrait", "1.0.15", freePlan, kits, kitId, logo, banner)

    @Test fun `snapshot omits kits when unknown and keeps the always present new fields`() {
        val o = snapshot(input())
        assertFalse(o.containsKey("kits"))
        assertEquals("null", o["kit"].toString())
        assertEquals("""{"present":false,"position":"left","size":90}""", o["logo"].toString())
        assertEquals("""{"available":true,"name":null,"title":null,"show":false}""", o["lowerThird"].toString())
        assertEquals(1, o["v"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `snapshot lists kits with the applied one, logo and banner`() {
        val id = "0b6f6f0e-3c43-4a76-9d3b-6a3f3d6a9c11"
        val o = snapshot(input(kits = listOf(KitRef(id, "Acme"), KitRef("22222222-2222-2222-2222-222222222222", "  Two ‮ lines\n ")), kitId = id, logo = LogoInfo(true, true, 140), banner = NameBannerState("Jane", "Host", true)))
        assertEquals("""[{"id":"$id","name":"Acme"},{"id":"22222222-2222-2222-2222-222222222222","name":"Two lines"}]""", o["kits"]!!.jsonArray.toString())
        assertEquals("""{"id":"$id"}""", o["kit"].toString())
        assertEquals("""{"present":true,"position":"right","size":140}""", o["logo"].toString())
        assertEquals("""{"available":true,"name":"Jane","title":"Host","show":true}""", o["lowerThird"].toString())
    }

    @Test fun `snapshot caps kits at 50 and names at 80 and drops the list on the Free plan`() {
        val many = (1..80).map { KitRef("00000000-0000-0000-0000-%012d".format(it), "k".repeat(200)) }
        val o = snapshot(input(kits = many))
        val arr = o["kits"]!!.jsonArray
        assertEquals(50, arr.size)
        assertEquals(80, arr[0].jsonObject["name"]!!.jsonPrimitive.content.length)
        assertEquals("00000000-0000-0000-0000-000000000001", arr[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse(snapshot(input(freePlan = true, kits = many)).containsKey("kits"))
        assertEquals("Brand kit", snapshot(input(kits = listOf(KitRef("a", "‮")))) ["kits"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)   // a name that cleans to nothing
        assertFalse(snapshot(input(kits = listOf(KitRef("", "x")))).containsKey("kits"))                         // no id: not listed
        assertFalse(snapshot(input(kits = listOf(KitRef("i".repeat(65), "x")))).containsKey("kits"))             // over-long id: not listed
        assertEquals("null", snapshot(input(kitId = "i".repeat(65)))["kit"].toString())
    }

    @Test fun `snapshot clamps the logo size and holds no secret looking text`() {
        assertEquals(600, snapshot(input(logo = LogoInfo(true, false, 9999)))["logo"]!!.jsonObject["size"]!!.jsonPrimitive.content.toInt())
        assertEquals(16, snapshot(input(logo = LogoInfo(true, false, 1)))["logo"]!!.jsonObject["size"]!!.jsonPrimitive.content.toInt())
        val s = RemoteState.build(input(kits = listOf(KitRef("0b6f6f0e-3c43-4a76-9d3b-6a3f3d6a9c11", "Acme")), kitId = "0b6f6f0e-3c43-4a76-9d3b-6a3f3d6a9c11", logo = LogoInfo(true, false, 100))).toString()
        for (bad in listOf("token", "cookie", "secret", "password", "http", "authorization", "assetid")) assertFalse(bad, s.lowercase().contains(bad))
        assertNotNull(s)
    }

    @Test fun `old snapshots keep every earlier field`() {
        val o = snapshot(RemoteStateInput(emptyList(), true, "Hello", ClockSettings(), "landscape", "1.0.15", false))
        for (k in listOf("v", "platform", "appVersion", "orientation", "freePlan", "ads", "adsShrink", "ticker", "clock")) assertTrue(k, o.containsKey(k))
        assertEquals("""{"text":"Hello","on":true}""", o["ticker"].toString())
    }

    @Test fun `ad manual key helpers are unchanged`() {
        assertEquals("ads.manual:l_shape", RemoteKeys.adManual(AdPlacement.L_SHAPE))
        assertEquals(AdManual.SHOWN, AdManual.SHOWN)
    }
}
