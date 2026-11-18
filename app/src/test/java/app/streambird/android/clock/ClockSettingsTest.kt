package app.streambird.android.clock

import app.streambird.android.brand.KitCompat
import app.streambird.android.data.ApiJson
import app.streambird.android.data.BrandKit
import app.streambird.android.data.BrandKitPayload
import app.streambird.android.data.KitDraft
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the clock off a kit (lenient), what PATCH / POST send, the old-server sentence, the zone list and the description line. */
class ClockSettingsTest {
    private fun kit(json: String) = ApiJson.decodeFromString<BrandKit>(json)
    private val base = """"id":"k1","name":"Main","logoPosition":"left","logoSize":90,"lowerThirdStyle":"accent","isDefault":true,"connectionIds":[]"""

    // ---- parsing ----

    @Test fun `an older server's kit has the clock off with the defaults`() {
        val c = ClockSettings.fromKit(kit("{$base}"))
        assertEquals(ClockSettings(), c)
        assertFalse(c.enabled); assertEquals(ClockPosition.TOP_RIGHT, c.position); assertEquals(ClockHours.H12, c.hours)
        assertTrue(c.showDate); assertFalse(c.showSeconds); assertNull(c.timezone); assertEquals(ClockSize.MEDIUM, c.size)
    }

    @Test fun `a full clock parses`() {
        val c = ClockSettings.fromKit(kit("""{$base,"clockEnabled":true,"clockPosition":"bottom_left","clockFormat":"24h","clockShowDate":false,"clockShowSeconds":true,"clockTimezone":"Asia/Kolkata","clockSize":"large"}"""))
        assertEquals(ClockSettings(true, ClockPosition.BOTTOM_LEFT, ClockHours.H24, false, true, "Asia/Kolkata", ClockSize.LARGE), c)
    }

    @Test fun `unknown values and nulls fall back to the defaults`() {
        val c = ClockSettings.fromKit(kit("""{$base,"clockEnabled":true,"clockPosition":"middle","clockFormat":"36h","clockSize":"huge","clockTimezone":null,"clockShowDate":null}"""))
        assertTrue(c.enabled)
        assertEquals(ClockPosition.TOP_RIGHT, c.position); assertEquals(ClockHours.H12, c.hours); assertEquals(ClockSize.MEDIUM, c.size)
        assertNull(c.timezone); assertTrue(c.showDate)
    }

    @Test fun `a blank or oversized zone is cleaned`() {
        assertNull(ClockSettings.fromKit(kit("""{$base,"clockTimezone":"  "}""")).timezone)
        assertEquals(64, Clock.cleanZone("x".repeat(100))!!.length)
        assertEquals("Asia/Kolkata", Clock.cleanZone(" Asia/Kolkata "))
        assertNull(ClockSettings.fromKit(null).timezone)
        assertEquals(ClockSettings(), ClockSettings.fromKit(null))
    }

    // ---- request bodies ----

    private fun draftOf(k: BrandKit) = BrandKitPayload.draftOf(k)
    private fun JsonObject.clockKeys() = keys.filter { it.startsWith("clock") }.toSet()

    @Test fun `PATCH without a clock change sends no clock field`() {
        val k = kit("{$base}")
        assertEquals(emptySet<String>(), BrandKitPayload.patch(k, draftOf(k)).clockKeys())
        val k2 = kit("""{$base,"clockEnabled":true,"clockTimezone":"UTC"}""")
        assertEquals(emptySet<String>(), BrandKitPayload.patch(k2, draftOf(k2)).clockKeys())
    }

    @Test fun `PATCH sends only the changed clock fields`() {
        val k = kit("""{$base,"clockEnabled":false,"clockPosition":"top_left"}""")
        val d = draftOf(k).copy(clock = ClockSettings.fromKit(k).copy(enabled = true, showSeconds = true, size = ClockSize.LARGE))
        val b = BrandKitPayload.patch(k, d)
        assertEquals(setOf("clockEnabled", "clockShowSeconds", "clockSize"), b.clockKeys())
        assertTrue(b["clockEnabled"]!!.jsonPrimitive.boolean); assertTrue(b["clockShowSeconds"]!!.jsonPrimitive.boolean)
        assertEquals("large", b["clockSize"]!!.jsonPrimitive.content)
    }

    @Test fun `PATCH sends every wire name`() {
        val k = kit("{$base}")
        val d = draftOf(k).copy(clock = ClockSettings(true, ClockPosition.BOTTOM_RIGHT, ClockHours.H24, false, true, "Europe/Paris", ClockSize.SMALL))
        val b = BrandKitPayload.patch(k, d)
        assertEquals(setOf("clockEnabled", "clockPosition", "clockFormat", "clockShowDate", "clockShowSeconds", "clockTimezone", "clockSize"), b.clockKeys())
        assertEquals("bottom_right", b["clockPosition"]!!.jsonPrimitive.content)
        assertEquals("24h", b["clockFormat"]!!.jsonPrimitive.content)
        assertFalse(b["clockShowDate"]!!.jsonPrimitive.boolean)
        assertEquals("Europe/Paris", b["clockTimezone"]!!.jsonPrimitive.content)
    }

    @Test fun `going back to the phone's timezone is an explicit null`() {
        val k = kit("""{$base,"clockTimezone":"Asia/Kolkata"}""")
        val b = BrandKitPayload.patch(k, draftOf(k).copy(clock = ClockSettings.fromKit(k).copy(timezone = " ")))
        assertEquals(JsonNull, b["clockTimezone"])
        assertEquals(setOf("clockTimezone"), b.clockKeys())
    }

    @Test fun `an oversized zone is cut to the server limit`() {
        val k = kit("{$base}")
        val b = BrandKitPayload.patch(k, draftOf(k).copy(clock = ClockSettings(timezone = "z".repeat(90))))
        assertEquals(64, b["clockTimezone"]!!.jsonPrimitive.content.length)
    }

    @Test fun `POST sends nothing for the defaults and only what is set otherwise`() {
        val plain = BrandKitPayload.create(KitDraft("A", null, "left", 90, "accent", false))
        assertEquals(emptySet<String>(), plain.clockKeys())
        val set = BrandKitPayload.create(KitDraft("A", null, "left", 90, "accent", false, clock = ClockSettings(enabled = true, hours = ClockHours.H24)))
        assertEquals(setOf("clockEnabled", "clockFormat"), set.clockKeys())
        assertEquals(JsonPrimitive(true), set["clockEnabled"])
        assertNull(set["clockPosition"])
    }

    // ---- old server ----

    @Test fun `an old server's 400 about a clock field becomes a plain sentence`() {
        val d = KitDraft("A", null, "left", 90, "accent", false, clock = ClockSettings(enabled = true))
        assertEquals(KitCompat.NO_KIT_CLOCK, KitCompat.saveError(400, "property clockEnabled should not exist", d))
        assertEquals(KitCompat.NO_KIT_CLOCK, KitCompat.saveError(400, "property clockTimezone should not exist, property clockSize should not exist", d))
        assertNull(KitCompat.saveError(500, "property clockEnabled should not exist", d))
        assertNull(KitCompat.saveError(400, "name must be shorter", d))
    }

    // ---- zones and text ----

    @Test fun `zone search is forgiving`() {
        val all = listOf("America/New_York", "Asia/Kolkata", "Asia/Kolkata_x", "Europe/Paris")
        assertEquals(all, ClockZones.filter("  ", all))
        assertEquals(listOf("America/New_York"), ClockZones.filter("new york", all))
        assertEquals(listOf("America/New_York"), ClockZones.filter("NEW_YORK", all))
        assertEquals(listOf("Asia/Kolkata", "Asia/Kolkata_x"), ClockZones.filter("kolk", all))
        assertEquals(listOf("Asia/Kolkata", "Asia/Kolkata_x"), ClockZones.filter("asia/ko", all))
        assertTrue(ClockZones.filter("zzz", all).isEmpty())
    }

    @Test fun `the real zone list has India and every common zone is valid`() {
        val all = ClockZones.all()
        assertTrue("Asia/Kolkata" in all); assertTrue("UTC" in all)
        assertEquals(all.sorted(), all)
        ClockZones.COMMON.forEach { assertTrue("$it is a known zone", it in all) }
        assertEquals("Asia/Kolkata", ClockZones.COMMON.first())
        assertTrue(all.all { it.length <= Clock.TZ_MAX })
        assertEquals(ClockZones.AUTOMATIC_LABEL, ClockZones.label(null))
        assertEquals("Asia/Kolkata", ClockZones.label("Asia/Kolkata"))
    }

    @Test fun `description line`() {
        assertEquals("Off", Clock.describe(ClockSettings()))
        assertEquals("On: top right, 12-hour, with date, medium, this phone's timezone", Clock.describe(ClockSettings(enabled = true)))
        assertEquals(
            "On: bottom left, 24-hour, time only, with seconds, large, Asia/Kolkata",
            Clock.describe(ClockSettings(true, ClockPosition.BOTTOM_LEFT, ClockHours.H24, false, true, "Asia/Kolkata", ClockSize.LARGE)),
        )
    }

    @Test fun `wire round trip of the enums`() {
        ClockPosition.entries.forEach { assertEquals(it, ClockPosition.fromWire(it.wire)) }
        ClockHours.entries.forEach { assertEquals(it, ClockHours.fromWire(it.wire)) }
        ClockSize.entries.forEach { assertEquals(it, ClockSize.fromWire(it.wire)) }
        assertEquals(ClockPosition.TOP_RIGHT, ClockPosition.fromWire(null))
    }
}
