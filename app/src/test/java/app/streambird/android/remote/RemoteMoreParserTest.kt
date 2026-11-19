package app.streambird.android.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.15: the strict parser for kit.apply, logo.set and lowerThird.set (valid, invalid, ranges, text caps, control and bidi characters). */
class RemoteMoreParserTest {
    private val at = "2026-11-19T10:00:00.000Z"
    private val uuid = "0b6f6f0e-3c43-4a76-9d3b-6a3f3d6a9c11"
    private fun cmd(type: String, payload: String) = """{"seq":1,"id":"11111111-2222-3333-4444-555555555555","type":"$type","payload":$payload,"at":"$at"}"""
    private fun parse(json: String) = RemoteParser.parse(kotlinx.serialization.json.Json.parseToJsonElement(json))
    private fun ok(json: String): RemoteAction = (parse(json) as? ParsedCommand.Ok)?.command?.action ?: error("expected accepted: " + parse(json))
    private fun reason(json: String): String = (parse(json) as? ParsedCommand.Rejected)?.reason ?: error("expected rejected: $json")

    @Test fun `the three commands are supported now and only layout and wallpaper stay unsupported`() {
        assertTrue(RemoteParser.SUPPORTED_TYPES.containsAll(listOf("kit.apply", "logo.set", "lowerThird.set")))
        assertEquals(setOf("layout.set", "wallpaper.dim"), RemoteParser.UNSUPPORTED_TYPES)
        assertEquals(RemoteAction.Unsupported(), ok(cmd("layout.set", """{"mode":"x"}""")))
        assertEquals(RemoteAction.Unsupported(), ok(cmd("wallpaper.dim", """{"n":10}""")))
    }

    // ---- kit.apply ----

    @Test fun `kit apply takes a uuid and nothing else`() {
        assertEquals(RemoteAction.KitApply(uuid), ok(cmd("kit.apply", """{"kitId":"$uuid"}""")))
        assertEquals(RemoteAction.KitApply(uuid.uppercase()), ok(cmd("kit.apply", """{"kitId":"${uuid.uppercase()}","extra":1}""")))   // unknown fields are ignored
        assertEquals("kitId must be a string", reason(cmd("kit.apply", """{}""")))
        assertEquals("kitId must be a string", reason(cmd("kit.apply", """{"kitId":5}""")))
        assertEquals("kitId must be a string", reason(cmd("kit.apply", """{"kitId":null}""")))
        assertEquals("kitId must be an id", reason(cmd("kit.apply", """{"kitId":"k1"}""")))
        assertEquals("kitId must be an id", reason(cmd("kit.apply", """{"kitId":"https://evil.example/x.png"}""")))            // never a URL
        assertEquals("kitId must be an id", reason(cmd("kit.apply", """{"kitId":"$uuid/../x"}""")))
        assertEquals("kitId must be an id", reason(cmd("kit.apply", """{"kitId":" $uuid"}""")))
        assertEquals("payload missing", reason("""{"seq":1,"id":"a","type":"kit.apply","at":"$at"}"""))
    }

    // ---- logo.set ----

    @Test fun `logo set accepts position and or size in range`() {
        assertEquals(RemoteAction.LogoSet(true, null), ok(cmd("logo.set", """{"position":"right"}""")))
        assertEquals(RemoteAction.LogoSet(false, null), ok(cmd("logo.set", """{"position":"left"}""")))
        assertEquals(RemoteAction.LogoSet(null, 16), ok(cmd("logo.set", """{"size":16}""")))
        assertEquals(RemoteAction.LogoSet(null, 600), ok(cmd("logo.set", """{"size":600}""")))
        assertEquals(RemoteAction.LogoSet(true, 120), ok(cmd("logo.set", """{"size":120.0,"position":"right"}""")))   // 120.0 is a whole number
    }

    @Test fun `logo set rejects empty out of range and wrongly typed fields`() {
        assertEquals("no logo fields", reason(cmd("logo.set", """{}""")))
        assertEquals("no logo fields", reason(cmd("logo.set", """{"color":"red"}""")))
        assertEquals("unknown position", reason(cmd("logo.set", """{"position":"center"}""")))
        assertEquals("position must be a string", reason(cmd("logo.set", """{"position":1}""")))
        assertEquals("size out of range", reason(cmd("logo.set", """{"size":15}""")))
        assertEquals("size out of range", reason(cmd("logo.set", """{"size":601}""")))
        assertEquals("size out of range", reason(cmd("logo.set", """{"size":-5}""")))
        assertEquals("size must be a whole number", reason(cmd("logo.set", """{"size":90.5}""")))
        assertEquals("size must be a whole number", reason(cmd("logo.set", """{"size":"90"}""")))
        assertEquals("size must be a whole number", reason(cmd("logo.set", """{"size":null}""")))
        assertEquals("size must be a whole number", reason(cmd("logo.set", """{"size":true}""")))
    }

    // ---- lowerThird.set ----

    @Test fun `lower third accepts any one key and carries cleaned text`() {
        assertEquals(RemoteAction.LowerThirdSet("Jane Doe", false, null, null), ok(cmd("lowerThird.set", """{"name":"Jane Doe"}""")))
        assertEquals(RemoteAction.LowerThirdSet(null, true, "Host", null), ok(cmd("lowerThird.set", """{"title":"Host"}""")))
        assertEquals(RemoteAction.LowerThirdSet(null, true, null, null), ok(cmd("lowerThird.set", """{"title":null}""")))        // null = remove the title
        assertEquals(RemoteAction.LowerThirdSet(null, false, null, true), ok(cmd("lowerThird.set", """{"show":true}""")))
        assertEquals(RemoteAction.LowerThirdSet("A", true, "B", false), ok(cmd("lowerThird.set", """{"name":"A","title":"B","show":false,"x":1}""")))
        assertEquals(RemoteAction.LowerThirdSet("", false, null, null), ok(cmd("lowerThird.set", """{"name":""}""")))           // empty name clears it
    }

    @Test fun `lower third text caps are 80 and 120 characters`() {
        assertEquals("a".repeat(80), (ok(cmd("lowerThird.set", """{"name":"${"a".repeat(80)}"}""")) as RemoteAction.LowerThirdSet).name)
        assertEquals("name longer than 80", reason(cmd("lowerThird.set", """{"name":"${"a".repeat(81)}"}""")))
        assertEquals("a".repeat(120), (ok(cmd("lowerThird.set", """{"title":"${"a".repeat(120)}"}""")) as RemoteAction.LowerThirdSet).title)
        assertEquals("title longer than 120", reason(cmd("lowerThird.set", """{"title":"${"a".repeat(121)}"}""")))
    }

    @Test fun `lower third strips control and bidi characters and line breaks`() {
        val a = ok(cmd("lowerThird.set", """{"name":"‮Jane\u0000 \nDoe‏","title":"⁦Host⁩\t of  the show"}""")) as RemoteAction.LowerThirdSet
        assertEquals("Jane Doe", a.name)
        assertEquals("Host of the show", a.title)
        val onlyBidi = ok(cmd("lowerThird.set", """{"name":"‮‬","title":"​"}""")) as RemoteAction.LowerThirdSet
        assertEquals("", onlyBidi.name); assertEquals(null, onlyBidi.title)
    }

    @Test fun `lower third rejects empty and wrongly typed fields`() {
        assertEquals("no banner fields", reason(cmd("lowerThird.set", """{}""")))
        assertEquals("no banner fields", reason(cmd("lowerThird.set", """{"other":"x"}""")))
        assertEquals("name must be a string", reason(cmd("lowerThird.set", """{"name":null}""")))
        assertEquals("name must be a string", reason(cmd("lowerThird.set", """{"name":5}""")))
        assertEquals("title must be a string or null", reason(cmd("lowerThird.set", """{"title":5}""")))
        assertEquals("show must be true or false", reason(cmd("lowerThird.set", """{"show":"yes"}""")))
        assertEquals("show must be true or false", reason(cmd("lowerThird.set", """{"show":1}""")))
        assertEquals("show must be true or false", reason(cmd("lowerThird.set", """{"show":null}""")))
    }

    @Test fun `an oversized payload is still refused before parsing the fields`() {
        assertEquals("payload too large", reason(cmd("lowerThird.set", """{"name":"x","junk":"${"j".repeat(5000)}"}""")))
    }
}
