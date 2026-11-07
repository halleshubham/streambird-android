package app.streambird.android.brand

import app.streambird.android.brand.KitText.Change
import app.streambird.android.brand.KitText.TitleState
import app.streambird.android.data.ApiJson
import app.streambird.android.data.BrandKit
import app.streambird.android.data.BrandKitPayload
import app.streambird.android.data.KitDraft
import app.streambird.android.data.StreamDto
import app.streambird.android.data.fromKitFlag
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brand kit default title / description (1.0.9): limits, prefill rules, request bodies, tolerance of older servers. */
class KitTextTest {
    private fun kit(title: String? = null, description: String? = null, name: String = "Acme") = BrandKit(id = "k-$name", name = name, title = title, description = description)

    // ---- limits (server: kit title <= 200, description = stream description limit 5000, stream title 200) ----

    @Test fun `limits mirror the server`() {
        assertEquals(200, KitText.TITLE_MAX); assertEquals(5000, KitText.DESCRIPTION_MAX); assertEquals(200, KitText.STREAM_TITLE_MAX)
    }

    @Test fun `texts are trimmed, blank is null, and capped`() {
        assertNull(KitText.cleanTitle(null)); assertNull(KitText.cleanTitle("   ")); assertEquals("Hi", KitText.cleanTitle("  Hi \n"))
        assertEquals(200, KitText.cleanTitle("x".repeat(250))!!.length)
        assertEquals(5000, KitText.cleanDescription("y".repeat(6000))!!.length)
        assertNull(KitText.cleanDescription("\n \t"))
    }

    // ---- prefill: untouched vs typed, kit switched, kit without default, blank ----

    @Test fun `untouched title takes the kit default and says where from`() {
        val s = KitText.reconcile(TitleState(), kit("Friday Live"))
        assertEquals(TitleState("Friday Live", edited = false, fromKit = "Acme"), s)
        assertEquals("From brand kit Acme. Edit it to change it for this stream only.", KitText.titleNote(s, kit("Friday Live")))
    }

    @Test fun `switching the kit while untouched follows the new kit`() {
        var s = KitText.reconcile(TitleState(), kit("A title", name = "One"))
        s = KitText.reconcile(s, kit("Other title", name = "Two"))
        assertEquals(TitleState("Other title", false, "Two"), s)
        // to a kit without a default: the field empties and the note goes
        s = KitText.reconcile(s, kit(null, name = "Three"))
        assertEquals(TitleState("", false, null), s); assertNull(KitText.titleNote(s, kit(null)))
        // to no kit at all (kit not loaded, brand not in use)
        assertEquals(TitleState("", false, null), KitText.reconcile(TitleState("x", false, "Acme"), null))
    }

    @Test fun `a typed title always wins and is never overwritten`() {
        var s = KitText.reconcile(TitleState(), kit("Kit title"))
        s = KitText.typed(s, "My own title")
        assertEquals(TitleState("My own title", edited = true, fromKit = null), s)
        assertEquals(s, KitText.reconcile(s, kit("Another kit title", name = "Two")))
        assertEquals(s, KitText.reconcile(s, null))
        assertNull(KitText.titleNote(s, kit("Kit title")))
    }

    @Test fun `opening the title dialog and pressing Done without a change is not typing`() {
        val s = KitText.reconcile(TitleState(), kit("Kit title"))
        assertEquals(s, KitText.typed(s, "Kit title"))
        assertFalse(KitText.typed(s, "Kit title").edited)
    }

    @Test fun `emptying the field counts as typing, blank then falls back to the kit title`() {
        var s = KitText.reconcile(TitleState(), kit("Kit title"))
        s = KitText.typed(s, "")
        assertEquals(TitleState("", edited = true, fromKit = null), s)
        assertEquals(s, KitText.reconcile(s, kit("Kit title"))) // not re-filled while the host is editing
        assertEquals("Left blank: the title of brand kit Acme is used.", KitText.titleNote(s, kit("Kit title")))
        assertEquals("Kit title", KitText.finalTitle(s.text, kit("Kit title")))
    }

    @Test fun `the title that goes on air`() {
        assertEquals("Typed", KitText.finalTitle("  Typed ", kit("Kit")))
        assertEquals("Kit", KitText.finalTitle("", kit("Kit")))
        assertEquals("Kit", KitText.finalTitle("   ", kit("  Kit  ")))
        assertEquals("", KitText.finalTitle("", kit(null)))
        assertEquals("", KitText.finalTitle("", null))
        assertEquals("Typed", KitText.finalTitle("Typed", null))
    }

    @Test fun `a typed title is capped at the stream limit`() {
        assertEquals(200, KitText.typed(TitleState(), "z".repeat(300)).text.length)
    }

    @Test fun `a kit title longer than the server limit would never show more than 200`() {
        assertEquals(200, KitText.reconcile(TitleState(), kit("t".repeat(250))).text.length)
    }

    @Test fun `blank kit default means none`() {
        assertEquals(TitleState(), KitText.reconcile(TitleState(), kit("   ")))
    }

    @Test fun `description note only when the kit has a default description`() {
        assertEquals("Description: from brand kit Acme", KitText.descriptionNote(kit(description = "About us")))
        assertNull(KitText.descriptionNote(kit(description = "  "))); assertNull(KitText.descriptionNote(kit())); assertNull(KitText.descriptionNote(null))
    }

    // ---- change detection for PATCH ----

    @Test fun `unchanged, set and clear`() {
        assertEquals(Change.Unchanged, KitText.titleChange(null, null))
        assertEquals(Change.Unchanged, KitText.titleChange(null, "   "))
        assertEquals(Change.Unchanged, KitText.titleChange("Same", " Same "))
        assertEquals(Change.Set("New"), KitText.titleChange(null, " New "))
        assertEquals(Change.Set("New"), KitText.titleChange("Old", "New"))
        assertEquals(Change.Clear, KitText.titleChange("Old", ""))
        assertEquals(Change.Clear, KitText.titleChange("Old", null))
        assertEquals(Change.Clear, KitText.descriptionChange("Old", "   "))
        assertEquals(Change.Set("d".repeat(5000)), KitText.descriptionChange(null, "d".repeat(5200)))
    }

    // ---- request bodies ----

    private fun draftOf(k: BrandKit, title: String? = k.title, description: String? = k.description) = BrandKitPayload.draftOf(k).copy(title = title, description = description)

    @Test fun `PATCH never mentions title or description when they did not change (older servers are safe)`() {
        val k = kit("Same", "Desc")
        val body = BrandKitPayload.patch(k, BrandKitPayload.draftOf(k))
        assertFalse("title" in body.keys); assertFalse("description" in body.keys)
        val noText = BrandKitPayload.patch(kit(), BrandKitPayload.draftOf(kit()))
        assertFalse("title" in noText.keys); assertFalse("description" in noText.keys)
        // a kit from an older server (fields absent) edited with empty fields sends nothing about them either
        assertFalse("title" in BrandKitPayload.patch(kit(), draftOf(kit(), title = "", description = "")).keys)
    }

    @Test fun `PATCH sends a changed value trimmed, and null to clear`() {
        val k = kit("Old", "Old desc")
        val set = BrandKitPayload.patch(k, draftOf(k, title = "  New title ", description = "New desc"))
        assertEquals("New title", set["title"]!!.jsonPrimitive.content); assertEquals("New desc", set["description"]!!.jsonPrimitive.content)
        val clear = BrandKitPayload.patch(k, draftOf(k, title = "", description = "   "))
        assertEquals(JsonNull, clear["title"]); assertEquals(JsonNull, clear["description"])
        val onlyTitle = BrandKitPayload.patch(k, draftOf(k, title = "Other"))
        assertTrue("title" in onlyTitle.keys); assertFalse("description" in onlyTitle.keys)
    }

    @Test fun `PATCH and POST cap the texts at the server limits`() {
        val k = kit()
        val p = BrandKitPayload.patch(k, draftOf(k, title = "t".repeat(300), description = "d".repeat(9000)))
        assertEquals(200, p["title"]!!.jsonPrimitive.content.length); assertEquals(5000, p["description"]!!.jsonPrimitive.content.length)
        val c = BrandKitPayload.create(KitDraft("New", null, "left", 90, "accent", false, title = "t".repeat(300), description = "d".repeat(9000)))
        assertEquals(200, c["title"]!!.jsonPrimitive.content.length); assertEquals(5000, c["description"]!!.jsonPrimitive.content.length)
    }

    @Test fun `POST sends the texts only when filled`() {
        val none = BrandKitPayload.create(KitDraft("New", null, "left", 90, "accent", false, title = "  ", description = null))
        assertFalse("title" in none.keys); assertFalse("description" in none.keys)
        val some = BrandKitPayload.create(KitDraft("New", null, "left", 90, "accent", false, title = "Show", description = "About"))
        assertEquals("Show", some["title"]!!.jsonPrimitive.content); assertEquals("About", some["description"]!!.jsonPrimitive.content)
    }

    @Test fun `only known fields are sent`() {
        val k = kit("A", "B")
        val keys = BrandKitPayload.patch(k, draftOf(k, title = "C", description = "D")).keys
        val allowed = setOf("name", "logoAssetId", "logoPosition", "logoSize", "lowerThirdStyle", "wallpaperAssetId", "wallpaperDim", "logoAssetIdPortrait", "wallpaperAssetIdPortrait",
            "adsShrinkPicture", "thumbnailAssetId", "title", "description", "isDefault", "ads", "adsPortrait")
        assertTrue(keys.toString(), allowed.containsAll(keys))
    }

    @Test fun `editing the draft of an existing kit keeps its texts`() {
        val k = kit("Keep", "Keep too")
        val d = BrandKitPayload.draftOf(k)
        assertEquals("Keep", d.title); assertEquals("Keep too", d.description)
    }

    // ---- JSON tolerance ----

    @Test fun `a kit from an older server without the fields parses with nulls`() {
        val k = ApiJson.decodeFromString<BrandKit>("""{"id":"1","name":"Old kit","logoPosition":"left","logoSize":90}""")
        assertNull(k.title); assertNull(k.description)
    }

    @Test fun `a kit from the new server carries the fields, null is null`() {
        val k = ApiJson.decodeFromString<BrandKit>("""{"id":"1","name":"K","title":"Friday Live","description":"Line 1\nLine 2","extra":1}""")
        assertEquals("Friday Live", k.title); assertEquals("Line 1\nLine 2", k.description)
        val n = ApiJson.decodeFromString<BrandKit>("""{"id":"1","name":"K","title":null,"description":null}""")
        assertNull(n.title); assertNull(n.description)
    }

    @Test fun `stream answer flags are read leniently and ignored when absent`() {
        val none = ApiJson.decodeFromString<StreamDto>("""{"id":"s","title":"T","status":"live"}""")
        assertNull(fromKitFlag(none.titleFromKit)); assertNull(fromKitFlag(none.descriptionFromKit))
        val yes = ApiJson.decodeFromString<StreamDto>("""{"id":"s","title":"T","status":"live","titleFromKit":true,"descriptionFromKit":false}""")
        assertEquals(true, fromKitFlag(yes.titleFromKit)); assertEquals(false, fromKitFlag(yes.descriptionFromKit))
        val weird = ApiJson.decodeFromString<StreamDto>("""{"id":"s","title":"T","status":"live","titleFromKit":"nope","descriptionFromKit":{"a":1}}""")
        assertNull(fromKitFlag(weird.titleFromKit)); assertNull(fromKitFlag(weird.descriptionFromKit))
        val nul = ApiJson.decodeFromString<StreamDto>("""{"id":"s","title":"T","status":"live","titleFromKit":null}""")
        assertNull(fromKitFlag(nul.titleFromKit))
        assertEquals(true, fromKitFlag(ApiJson.decodeFromString<StreamDto>("""{"id":"s","title":"T","status":"live","titleFromKit":"TRUE"}""").titleFromKit))
    }

    @Test fun `an older server rejecting the new fields gets a plain sentence`() {
        val draft = KitDraft("K", null, "left", 90, "accent", false, title = "Show")
        assertEquals(KitCompat.NO_KIT_TEXT, KitCompat.saveError(400, "property title should not exist", draft))
        assertEquals(KitCompat.NO_KIT_TEXT, KitCompat.saveError(400, "property description should not exist", draft.copy(title = null, description = "d")))
        assertNull(KitCompat.saveError(400, "property title should not exist", draft.copy(title = null)))
        assertNull(KitCompat.saveError(400, "name must be shorter than or equal to 80 characters", draft))
        assertNull(KitCompat.saveError(500, "property title should not exist", draft))
    }
}
