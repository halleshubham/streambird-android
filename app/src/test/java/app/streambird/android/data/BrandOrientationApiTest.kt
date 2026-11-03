package app.streambird.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** (De)serialisation of the 16:9 / 9:16 kit fields: replace-all per set, omit = unchanged, and nothing new is sent to an older server unless it changed. */
class BrandOrientationApiTest {
    private val oldKit = """{"id":"k1","name":"Main","logoAssetId":"as1","logoPosition":"right","logoSize":120,"wallpaperAssetId":"as9","wallpaperDim":35,"lowerThirdStyle":"glass","isDefault":true,"connectionIds":["c1"],"ads":[]}"""
    private val newKit = """{"id":"k1","name":"Main","logoAssetId":"as1","logoAssetIdPortrait":"asP","logoPosition":"right","logoSize":120,"wallpaperAssetId":"as9","wallpaperAssetIdPortrait":"wP","wallpaperDim":35,"lowerThirdStyle":"glass","isDefault":true,"adsShrinkPicture":false,"connectionIds":[],"ads":[],"adsPortrait":[]}"""
    private fun kit(json: String) = ApiJson.decodeFromString<BrandKit>(json)
    private val ad = KitAd("lower_third", "ad1", true, "continuous", null, null)

    @Test fun `untouched edit of an old-server kit sends none of the new fields`() {
        val o = kit(oldKit)
        val p = BrandKitPayload.patch(o, BrandKitPayload.draftOf(o))
        for (k in listOf("logoAssetIdPortrait", "wallpaperAssetIdPortrait", "adsShrinkPicture", "adsPortrait", "ads", "isDefault")) assertFalse("$k must be omitted", p.containsKey(k))
        assertEquals("as9", p["wallpaperAssetId"]!!.jsonPrimitive.content); assertEquals(35, p["wallpaperDim"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `untouched edit of a new-server kit also sends none of the new fields`() {
        val o = kit(newKit)
        val p = BrandKitPayload.patch(o, BrandKitPayload.draftOf(o))
        for (k in listOf("logoAssetIdPortrait", "wallpaperAssetIdPortrait", "adsShrinkPicture", "adsPortrait", "ads")) assertFalse("$k must be omitted", p.containsKey(k))
    }

    @Test fun `changed portrait fields are sent, clearing is an explicit null`() {
        val o = kit(newKit)
        val d = BrandKitPayload.draftOf(o).copy(logoAssetIdPortrait = "asNew", wallpaperAssetIdPortrait = null, adsShrinkPicture = true)
        val p = BrandKitPayload.patch(o, d)
        assertEquals("asNew", p["logoAssetIdPortrait"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, p["wallpaperAssetIdPortrait"])
        assertTrue(p["adsShrinkPicture"]!!.jsonPrimitive.boolean)
    }

    @Test fun `ads are replace-all per set, omitted when untouched, empty list clears`() {
        val o = kit(newKit)
        // landscape only
        val a = BrandKitPayload.patch(o, BrandKitPayload.draftOf(o).copy(ads = listOf(ad)))
        assertTrue(a.containsKey("ads")); assertFalse(a.containsKey("adsPortrait"))
        assertEquals("lower_third", a["ads"]!!.jsonArray[0].jsonObject["placement"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, a["ads"]!!.jsonArray[0].jsonObject["showSec"])
        // portrait only
        val b = BrandKitPayload.patch(o, BrandKitPayload.draftOf(o).copy(adsPortrait = listOf(ad.copy(placement = "full_screen", mode = "interval", showSec = 15, everySec = 180))))
        assertFalse(b.containsKey("ads")); assertTrue(b.containsKey("adsPortrait"))
        val sent = b["adsPortrait"]!!.jsonArray[0].jsonObject
        assertEquals("full_screen", sent["placement"]!!.jsonPrimitive.content); assertEquals(15, sent["showSec"]!!.jsonPrimitive.content.toInt()); assertEquals(180, sent["everySec"]!!.jsonPrimitive.content.toInt())
        // [] clears one set without touching the other
        val c = BrandKitPayload.patch(o, BrandKitPayload.draftOf(o).copy(adsPortrait = emptyList()))
        assertEquals(JsonArray(emptyList()), c["adsPortrait"]); assertFalse(c.containsKey("ads"))
    }

    @Test fun `create sends the new fields only when set`() {
        val plain = BrandKitPayload.create(KitDraft("New", null, "left", 90, "accent", false))
        for (k in listOf("logoAssetId", "wallpaperAssetId", "logoAssetIdPortrait", "wallpaperAssetIdPortrait", "adsShrinkPicture", "ads", "adsPortrait", "isDefault")) assertFalse("$k must be omitted", plain.containsKey(k))
        val full = BrandKitPayload.create(
            KitDraft("New", "l", "left", 90, "accent", true, listOf(ad), wallpaperAssetId = "w", logoAssetIdPortrait = "lp", wallpaperAssetIdPortrait = "wp", adsPortrait = emptyList(), adsShrinkPicture = false),
        )
        assertEquals("lp", full["logoAssetIdPortrait"]!!.jsonPrimitive.content); assertEquals("wp", full["wallpaperAssetIdPortrait"]!!.jsonPrimitive.content)
        assertFalse(full["adsShrinkPicture"]!!.jsonPrimitive.boolean)
        assertEquals(1, full["ads"]!!.jsonArray.size); assertEquals(0, full["adsPortrait"]!!.jsonArray.size)
    }

    @Test fun `response round trip keeps the portrait set`() {
        val k = kit(newKit)
        assertEquals("asP", k.logoAssetIdPortrait); assertEquals("wP", k.wallpaperAssetIdPortrait); assertFalse(k.adsShrinkPicture)
        val back = ApiJson.decodeFromString<BrandKit>(ApiJson.encodeToString(BrandKit.serializer(), k))
        assertEquals(k, back)
    }
}
