package app.streambird.android.remote

import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.clock.ClockHours
import app.streambird.android.clock.ClockPosition
import app.streambird.android.clock.ClockSettings
import app.streambird.android.clock.ClockSize
import app.streambird.android.clock.ClockStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strict parser: accept / reject matrix per command. */
class RemoteParserTest {
    private val at = "2026-11-19T10:00:00.000Z"
    private fun cmd(type: String, payload: String, seq: Int = 1, id: String = "11111111-2222-3333-4444-555555555555", extra: String = "") =
        """{"seq":$seq,"id":"$id","type":"$type","payload":$payload,"at":"$at"$extra}"""

    private fun parse(json: String) = RemoteParser.parse(kotlinx.serialization.json.Json.parseToJsonElement(json))
    private fun ok(json: String): RemoteCommand = (parse(json) as? ParsedCommand.Ok)?.command ?: error("expected accepted: " + parse(json))
    private fun reason(json: String): String = (parse(json) as? ParsedCommand.Rejected)?.reason ?: error("expected rejected: $json")

    @Test fun `ads manual accepts shown hidden and null and every placement`() {
        for (p in AdPlacement.entries) {
            assertEquals(RemoteAction.AdsManual(p, AdManual.SHOWN), ok(cmd("ads.manual", """{"placement":"${p.wire}","mode":"shown"}""")).action)
        }
        assertEquals(RemoteAction.AdsManual(AdPlacement.L_SHAPE, AdManual.HIDDEN), ok(cmd("ads.manual", """{"placement":"l_shape","mode":"hidden"}""")).action)
        assertEquals(RemoteAction.AdsManual(AdPlacement.LOWER_THIRD, null), ok(cmd("ads.manual", """{"placement":"lower_third","mode":null}""")).action)
    }

    @Test fun `ads manual rejects bad placement mode and a missing mode`() {
        assertEquals("unknown placement", reason(cmd("ads.manual", """{"placement":"banner","mode":"shown"}""")))
        assertEquals("placement must be a string", reason(cmd("ads.manual", """{"placement":3,"mode":"shown"}""")))
        assertEquals("mode must be shown, hidden or null", reason(cmd("ads.manual", """{"placement":"l_shape","mode":"on"}""")))
        assertEquals("mode missing", reason(cmd("ads.manual", """{"placement":"l_shape"}""")))
    }

    @Test fun `ads enabled and shrink need a real boolean`() {
        assertEquals(RemoteAction.AdsEnabled(AdPlacement.FULL_SCREEN, true), ok(cmd("ads.enabled", """{"placement":"full_screen","on":true}""")).action)
        assertEquals(RemoteAction.AdsShrink(false), ok(cmd("ads.shrink", """{"on":false}""")).action)
        assertEquals("on must be true or false", reason(cmd("ads.shrink", """{"on":"true"}""")))
        assertEquals("on must be true or false", reason(cmd("ads.shrink", """{"on":1}""")))
        assertEquals("on must be true or false", reason(cmd("ads.enabled", """{"placement":"side_third"}""")))
    }

    @Test fun `ads schedule accepts continuous and interval within the ranges`() {
        assertEquals(RemoteAction.AdsSchedule(AdPlacement.SIDE_THIRD, AdSchedule.Continuous), ok(cmd("ads.schedule", """{"placement":"side_third","mode":"continuous"}""")).action)
        assertEquals(RemoteAction.AdsSchedule(AdPlacement.SIDE_THIRD, AdSchedule.Interval(15, 180)), ok(cmd("ads.schedule", """{"placement":"side_third","mode":"interval","showSec":15,"everySec":180}""")).action)
        assertEquals(AdSchedule.Interval(1, 1), (ok(cmd("ads.schedule", """{"placement":"side_third","mode":"interval","showSec":1,"everySec":1}""")).action as RemoteAction.AdsSchedule).schedule)
        assertEquals(AdSchedule.Interval(3600, 86400), (ok(cmd("ads.schedule", """{"placement":"side_third","mode":"interval","showSec":3600.0,"everySec":86400}""")).action as RemoteAction.AdsSchedule).schedule)
    }

    @Test fun `ads schedule rejects out of range fractional and missing numbers`() {
        fun s(body: String) = reason(cmd("ads.schedule", """{"placement":"l_shape",$body}"""))
        assertEquals("showSec out of range", s(""""mode":"interval","showSec":0,"everySec":10"""))
        assertEquals("showSec out of range", s(""""mode":"interval","showSec":3601,"everySec":10"""))
        assertEquals("everySec out of range", s(""""mode":"interval","showSec":5,"everySec":0"""))
        assertEquals("everySec out of range", s(""""mode":"interval","showSec":5,"everySec":86401"""))
        assertEquals("showSec must be a whole number", s(""""mode":"interval","showSec":5.5,"everySec":10"""))
        assertEquals("showSec must be a whole number", s(""""mode":"interval","showSec":"5","everySec":10"""))
        assertEquals("everySec must be a whole number", s(""""mode":"interval","showSec":5"""))
        assertEquals("mode must be continuous or interval", s(""""mode":"sometimes""""))
    }

    @Test fun `ticker accepts text null and the 200 character cap`() {
        assertEquals(RemoteAction.TickerSet("Hello", true), ok(cmd("ticker.set", """{"text":"Hello","on":true}""")).action)
        assertEquals(RemoteAction.TickerSet(null, false), ok(cmd("ticker.set", """{"text":null,"on":false}""")).action)
        val t200 = "a".repeat(200)
        assertEquals(RemoteAction.TickerSet(t200, true), ok(cmd("ticker.set", """{"text":"$t200","on":true}""")).action)
    }

    @Test fun `ticker rejects 201 characters wrong types and missing fields`() {
        assertEquals("text longer than 200", reason(cmd("ticker.set", """{"text":"${"a".repeat(201)}","on":true}""")))
        assertEquals("text must be a string or null", reason(cmd("ticker.set", """{"text":5,"on":true}""")))
        assertEquals("text missing", reason(cmd("ticker.set", """{"on":true}""")))
        assertEquals("on must be true or false", reason(cmd("ticker.set", """{"text":"x"}""")))
    }

    @Test fun `clock accepts a full command with the kit contract names and values`() {
        val a = ok(cmd("clock.set", """{"enabled":true,"position":"bottom_left","format":"24h","showDate":false,"showSeconds":true,"timezone":"Asia/Kolkata","size":"large","style":"pill","alternate":false}""")).action as RemoteAction.ClockSet
        val c = a.patch.applyTo(ClockSettings())
        assertEquals(ClockSettings(true, ClockPosition.BOTTOM_LEFT, ClockHours.H24, false, true, "Asia/Kolkata", ClockSize.LARGE, ClockStyle.PILL, false), c)
    }

    @Test fun `clock partial command keeps everything else and null timezone means the phone zone`() {
        val base = ClockSettings(true, ClockPosition.TOP_LEFT, ClockHours.H24, true, false, "UTC", ClockSize.MEDIUM, ClockStyle.TEXT, true)
        val p1 = (ok(cmd("clock.set", """{"position":"top_right"}""")).action as RemoteAction.ClockSet).patch
        assertEquals(base.copy(position = ClockPosition.TOP_RIGHT), p1.applyTo(base))
        val p2 = (ok(cmd("clock.set", """{"timezone":null}""")).action as RemoteAction.ClockSet).patch
        assertNull(p2.applyTo(base).timezone)
        val p3 = (ok(cmd("clock.set", """{"timezone":"  "}""")).action as RemoteAction.ClockSet).patch
        assertNull(p3.applyTo(base).timezone)
    }

    @Test fun `clock rejects bad enums zones types and an empty command`() {
        assertEquals("unknown position", reason(cmd("clock.set", """{"position":"middle"}""")))
        assertEquals("unknown format", reason(cmd("clock.set", """{"format":"13h"}""")))
        assertEquals("unknown size", reason(cmd("clock.set", """{"size":"huge"}""")))
        assertEquals("unknown style", reason(cmd("clock.set", """{"style":"neon"}""")))
        assertEquals("unknown timezone", reason(cmd("clock.set", """{"timezone":"Mars/Olympus"}""")))
        assertEquals("timezone too long", reason(cmd("clock.set", """{"timezone":"${"A".repeat(65)}"}""")))
        assertEquals("timezone must be a string or null", reason(cmd("clock.set", """{"timezone":4}""")))
        assertEquals("enabled must be true or false", reason(cmd("clock.set", """{"enabled":"yes"}""")))
        assertEquals("position must be a string", reason(cmd("clock.set", """{"position":null}""")))
        assertEquals("no clock fields", reason(cmd("clock.set", """{"nothing":1}""")))
    }

    @Test fun `unsupported types are accepted as shape and carry the not supported action`() {
        for (t in listOf("layout.set", "wallpaper.dim")) {
            assertEquals(t, RemoteAction.Unsupported(), ok(cmd(t, """{"anything":1}""")).action)
        }
        assertEquals("not supported on the phone app", NOT_SUPPORTED)
    }

    @Test fun `types outside the whitelist are never accepted`() {
        for (t in listOf("stream.stop", "stream.end", "stream.start", "stop", "end", "start", "camera.set", "mic.mute", "resolution.set", "bitrate.set", "recording.start", "destinations.set", "ADS.MANUAL", "ads.manual ", "", "eval")) {
            assertEquals(t, "command not allowed", reason(cmd(t, """{"placement":"l_shape","mode":"shown"}""")))
        }
    }

    @Test fun `envelope problems are rejected with seq and id kept when readable`() {
        val good = """{"placement":"l_shape","mode":"shown"}"""
        assertTrue(parse("""[1]""") is ParsedCommand.Rejected)
        assertEquals("bad seq", reason("""{"seq":"1","id":"a","type":"ads.manual","payload":$good,"at":"$at"}"""))
        assertEquals("bad seq", reason("""{"seq":0,"id":"a","type":"ads.manual","payload":$good,"at":"$at"}"""))
        assertEquals("bad seq", reason("""{"seq":1.5,"id":"a","type":"ads.manual","payload":$good,"at":"$at"}"""))
        assertEquals("bad id", reason("""{"seq":1,"id":"a b","type":"ads.manual","payload":$good,"at":"$at"}"""))
        assertEquals("bad id", reason("""{"seq":1,"id":"${"x".repeat(65)}","type":"ads.manual","payload":$good,"at":"$at"}"""))
        assertEquals("bad at", reason("""{"seq":1,"id":"a","type":"ads.manual","payload":$good,"at":"yesterday"}"""))
        assertEquals("bad at", reason("""{"seq":1,"id":"a","type":"ads.manual","payload":$good}"""))
        assertEquals("payload missing", reason("""{"seq":1,"id":"a","type":"ads.manual","at":"$at"}"""))
        assertEquals("payload missing", reason("""{"seq":1,"id":"a","type":"ads.manual","payload":[],"at":"$at"}"""))
        val r = parse("""{"seq":7,"id":"abc","type":"ads.manual","payload":{"placement":"nope","mode":"shown"},"at":"$at"}""") as ParsedCommand.Rejected
        assertEquals(7L, r.seq); assertEquals("abc", r.id)
    }

    @Test fun `payload larger than 4096 characters is refused`() {
        assertEquals("payload too large", reason(cmd("ticker.set", """{"text":"x","on":true,"junk":"${"j".repeat(5000)}"}""")))
    }

    @Test fun `unknown fields are ignored at every level and timestamps with offsets parse`() {
        val c = ok("""{"seq":3,"id":"abc","type":"ads.shrink","payload":{"on":true,"future":{"a":1}},"at":"2026-11-19T15:30:00+05:30","extra":[1,2],"v":9}""")
        assertEquals(RemoteAction.AdsShrink(true), c.action)
        assertEquals(RemoteParser.parseInstantMs("2026-11-19T10:00:00Z"), c.atMs)
    }

    @Test fun `batch parsing is tolerant a bad command does not cost the others and garbage is empty`() {
        val body = """{"commands":[${cmd("ads.shrink", """{"on":true}""", seq = 1, id = "a")},"junk",${cmd("nope", "{}", seq = 3, id = "c")},${cmd("ads.shrink", """{"on":false}""", seq = 4, id = "d")}],"now":"2026-11-19T10:00:05Z","unknown":1}"""
        val b = RemoteParser.parseBatch(body)
        assertEquals(4, b.commands.size)
        assertEquals(listOf(true, false, false, true), b.commands.map { it is ParsedCommand.Ok })
        assertEquals(RemoteParser.parseInstantMs("2026-11-19T10:00:05Z"), b.serverNowMs)
        assertTrue(RemoteParser.parseBatch("<html>captive portal</html>").commands.isEmpty())
        assertTrue(RemoteParser.parseBatch("").commands.isEmpty())
        assertTrue(RemoteParser.parseBatch("""{"commands":"x"}""").commands.isEmpty())
        assertNull(RemoteParser.parseBatch("""{"commands":[]}""").serverNowMs)
    }

    @Test fun `no more than 100 commands are read from one answer`() {
        val many = (1..150).joinToString(",") { cmd("ads.shrink", """{"on":true}""", seq = it, id = "id$it") }
        assertEquals(100, RemoteParser.parseBatch("""{"commands":[$many]}""").commands.size)
    }
}
