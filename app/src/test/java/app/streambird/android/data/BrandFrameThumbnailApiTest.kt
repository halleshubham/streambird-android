package app.streambird.android.data

import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.ads.slotsFromKit
import app.streambird.android.ads.kitAdsFromSlots
import app.streambird.android.ads.adsEdited
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The L-shaped frame placement (`l_shape`) and the kit stream thumbnail (`thumbnailAssetId`): wire shapes, request bodies, and tolerance of older servers. */
class BrandFrameThumbnailApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor(base)).build(), base)
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun body(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private val newKit = """{"id":"k1","name":"Main","logoAssetId":"as1","logoPosition":"left","logoSize":90,"wallpaperAssetId":null,"wallpaperDim":0,"lowerThirdStyle":"accent","isDefault":true,"connectionIds":["c1"],"thumbnailAssetId":"th1",
        "ads":[{"placement":"lower_third","assetId":"ad1","enabled":true,"mode":"continuous","showSec":null,"everySec":null},
               {"placement":"full_screen","assetId":"ad3","enabled":false,"mode":"interval","showSec":15,"everySec":180},
               {"placement":"l_shape","assetId":"fr1","enabled":true,"mode":"interval","showSec":30,"everySec":120}],
        "adsPortrait":[{"placement":"l_shape","assetId":"fr2","enabled":true,"mode":"continuous","showSec":null,"everySec":null}]}"""
    private val oldKit = """{"id":"k2","name":"Old","logoPosition":"left","logoSize":90,"lowerThirdStyle":"accent","isDefault":false,"connectionIds":[]}"""
    private val knownKitKeys = setOf(
        "name", "logoAssetId", "logoPosition", "logoSize", "lowerThirdStyle", "wallpaperAssetId", "wallpaperDim", "logoAssetIdPortrait", "wallpaperAssetIdPortrait",
        "adsShrinkPicture", "thumbnailAssetId", "isDefault", "ads", "adsPortrait",
    )
    private val original get() = ApiJson.decodeFromString<BrandKit>(newKit)
    private fun draft(o: BrandKit = original) = BrandKitPayload.draftOf(o)

    // ---- responses ----

    @Test fun `a kit with l_shape ads and a thumbnail parses, placements in the server's order`() = runBlocking {
        server.enqueue(json("[$newKit]"))
        val k = api.brandKits().single()
        assertEquals("th1", k.thumbnailAssetId)
        assertEquals(listOf("lower_third", "full_screen", "l_shape"), k.ads.map { it.placement })
        assertEquals(app.streambird.android.data.KitAd("l_shape", "fr1", true, "interval", 30, 120), k.ads[2])
        assertEquals(listOf("l_shape"), k.adsPortrait.map { it.placement })
        assertEquals(listOf("lower_third", "side_third", "full_screen", "l_shape"), AdPlacement.entries.map { it.wire })
    }

    @Test fun `an older server's kit has no thumbnailAssetId and no l_shape - parsed without error`() = runBlocking {
        server.enqueue(json("[$oldKit]"))
        val k = api.brandKits().single()
        assertNull(k.thumbnailAssetId); assertEquals(emptyList<KitAd>(), k.ads); assertEquals(emptyList<KitAd>(), k.adsPortrait)
        assertEquals(null, BrandKitPayload.draftOf(k).thumbnailAssetId)
    }

    @Test fun `an explicit null thumbnail, an unknown placement and extra fields are tolerated`() {
        val k = ApiJson.decodeFromString<BrandKit>("""{"id":"k","name":"n","thumbnailAssetId":null,"future":{"a":1},"ads":[{"placement":"corner_bug","assetId":"x"},{"placement":"l_shape","assetId":"y"}]}""")
        assertNull(k.thumbnailAssetId)
        // the unknown placement is carried as a string and ignored by the editor (AdPlacement.fromWire = null); the frame defaults to enabled / continuous
        assertEquals(listOf(null, AdPlacement.L_SHAPE), k.ads.map { AdPlacement.fromWire(it.placement) })
        assertTrue(k.ads[1].enabled); assertEquals("continuous", k.ads[1].mode)
    }

    // ---- PATCH ----

    @Test fun `PATCH leaves the thumbnail out when it did not change, and only sends known fields`() = runBlocking {
        server.enqueue(json(newKit))
        api.updateBrandKit(original, draft())
        val b = body()
        assertFalse(b.containsKey("thumbnailAssetId")); assertFalse(b.containsKey("ads")); assertFalse(b.containsKey("adsPortrait"))
        assertTrue("unknown keys: ${b.keys - knownKitKeys}", knownKitKeys.containsAll(b.keys))
    }

    @Test fun `PATCH sends a changed thumbnail id, and an explicit null to clear it`() = runBlocking {
        server.enqueue(json(newKit))
        api.updateBrandKit(original, draft().copy(thumbnailAssetId = "th2"))
        assertEquals("th2", body()["thumbnailAssetId"]!!.jsonPrimitive.content)
        server.enqueue(json(newKit))
        api.updateBrandKit(original, draft().copy(thumbnailAssetId = null))
        assertEquals(JsonNull, body()["thumbnailAssetId"])
        // a kit that never had one and still has none: nothing sent (older servers never see the field)
        val none = ApiJson.decodeFromString<BrandKit>(oldKit)
        server.enqueue(json(oldKit))
        api.updateBrandKit(none, draft(none))
        assertFalse(body().containsKey("thumbnailAssetId"))
    }

    @Test fun `editing one ad still sends all four placements - the frame is not dropped by replace-all`() = runBlocking {
        val slots = slotsFromKit(original.ads)
        assertEquals(AdPlacement.entries.toList(), slots.map { it.placement })
        // the user adds a left-third ad
        val edited = slots.map { if (it.placement == AdPlacement.SIDE_THIRD) it.copy(assetId = "ad2", schedule = AdSchedule.Continuous) else it }
        assertTrue(adsEdited(original.ads, edited))
        server.enqueue(json(newKit))
        api.updateBrandKit(original, draft().copy(ads = kitAdsFromSlots(edited)))
        val ads = body()["ads"]!!.jsonArray
        assertEquals(listOf("lower_third", "side_third", "full_screen", "l_shape"), ads.map { it.jsonObject["placement"]!!.jsonPrimitive.content })
        val frame = ads[3].jsonObject
        assertEquals(setOf("placement", "assetId", "enabled", "mode", "showSec", "everySec"), frame.keys)
        assertEquals("fr1", frame["assetId"]!!.jsonPrimitive.content); assertEquals("interval", frame["mode"]!!.jsonPrimitive.content)
        assertEquals("30", frame["showSec"]!!.jsonPrimitive.content); assertEquals("120", frame["everySec"]!!.jsonPrimitive.content)
    }

    @Test fun `an untouched ads list is not resent, and removing the frame sends the other placements without it`() = runBlocking {
        val slots = slotsFromKit(original.ads)
        assertFalse(adsEdited(original.ads, slots))
        val removed = slots.map { if (it.placement == AdPlacement.L_SHAPE) it.copy(assetId = null) else it }
        assertTrue(adsEdited(original.ads, removed))
        server.enqueue(json(newKit))
        api.updateBrandKit(original, draft().copy(ads = kitAdsFromSlots(removed)))
        assertEquals(listOf("lower_third", "full_screen"), body()["ads"]!!.jsonArray.map { it.jsonObject["placement"]!!.jsonPrimitive.content })
    }

    @Test fun `the portrait frame set is separate`() = runBlocking {
        val slots = slotsFromKit(original.adsPortrait)
        val edited = slots.map { if (it.placement == AdPlacement.LOWER_THIRD) it.copy(assetId = "adp") else it }
        server.enqueue(json(newKit))
        api.updateBrandKit(original, draft().copy(adsPortrait = kitAdsFromSlots(edited)))
        val b = body()
        assertFalse(b.containsKey("ads"))
        assertEquals(listOf("lower_third", "l_shape"), b["adsPortrait"]!!.jsonArray.map { it.jsonObject["placement"]!!.jsonPrimitive.content })
    }

    // ---- POST ----

    @Test fun `POST sends the thumbnail only when one is set`() = runBlocking {
        val d = KitDraft("New", null, "left", 90, "accent", false)
        server.enqueue(json(oldKit)); api.createBrandKit(d)
        assertFalse(body().containsKey("thumbnailAssetId"))
        server.enqueue(json(newKit)); api.createBrandKit(d.copy(thumbnailAssetId = "th1", ads = kitAdsFromSlots(slotsFromKit(original.ads))))
        val b = body()
        assertEquals("th1", b["thumbnailAssetId"]!!.jsonPrimitive.content)
        assertEquals(3, b["ads"]!!.jsonArray.size)
        assertTrue(knownKitKeys.containsAll(b.keys))
    }

    // ---- uploads / library ----

    @Test fun `a thumbnail picture is uploaded as asset kind thumbnail`() = runBlocking {
        server.enqueue(json("""{"id":"th9","kind":"thumbnail","name":"cover","contentType":"image/jpeg","sizeBytes":100,"width":1280,"height":720}"""))
        val up = api.uploadAsset(byteArrayOf(-1, -40, -1, -32), "image/jpeg", "cover.jpg", kind = "thumbnail", name = "cover")
        assertEquals("thumbnail", up.kind)
        val raw = server.takeRequest().body.readByteString().utf8()
        assertTrue(raw.contains("name=\"kind\"") && raw.contains("\r\n\r\nthumbnail\r\n")); assertTrue(raw.contains("Content-Type: image/jpeg"))
    }

    @Test fun `library - thumbnail kind, and usedBy includes the kit that uses the file`() = runBlocking {
        server.enqueue(json("""{"enabled":true,"quotaBytes":104857600,"usedBytes":10,"items":[
            {"id":"th1","kind":"thumbnail","name":"cover","contentType":"image/jpeg","sizeBytes":500000,"width":1280,"height":720,"usedBy":["Main"]},
            {"id":"lg","kind":"logo","name":"logo","contentType":"image/png","sizeBytes":900000,"width":1000,"height":500,"usedBy":[]},
            {"id":"gif","kind":"logo","name":"anim","contentType":"image/gif","sizeBytes":1000,"width":1000,"height":500,"usedBy":[]},
            {"id":"small","kind":"logo","name":"small","contentType":"image/png","sizeBytes":1000,"width":300,"height":300,"usedBy":[]},
            {"id":"big","kind":"other","name":"big","contentType":"image/jpeg","sizeBytes":3000000,"width":3000,"height":2000,"usedBy":[]}]}"""))
        val lib = api.assetLibrary()
        assertEquals(listOf("Main"), lib.items[0].usedBy)
        // thumbnail files, plus any JPG/PNG that already meets the platform limits (the server only checks the file); never a GIF, a small or a >2 MB one
        assertEquals(listOf("th1", "lg"), lib.thumbnails.map { it.id })
    }

    @Test fun `a file used by a kit cannot be deleted - the 409 text is shown as the server says it`() = runBlocking {
        server.enqueue(json("""{"statusCode":409,"message":"This file is used by the brand kit \"Main\". Change or delete it first.","error":"Conflict"}""", 409))
        try { api.deleteAsset("th1"); org.junit.Assert.fail() } catch (e: ApiException) { assertEquals(409, e.status); assertTrue(e.message.contains("used by the brand kit")) }
    }

    // ---- creating the stream ----

    @Test fun `createStream omits thumbnailBrandKitId unless it is set`() = runBlocking {
        val stream = """{"id":"s1","title":"t","status":"live","orientation":"landscape","destinations":[]}"""
        server.enqueue(json(stream)); api.createStream(CreateStreamRequest("Live", "public", "landscape", listOf("c1")))
        val a = body()
        assertEquals(setOf("title", "visibility", "orientation", "destinationConnectionIds"), a.keys)
        server.enqueue(json(stream)); api.createStream(CreateStreamRequest("Live", "public", "landscape", listOf("c1"), thumbnailBrandKitId = "k1"))
        val b = body()
        assertEquals(setOf("title", "visibility", "orientation", "destinationConnectionIds", "thumbnailBrandKitId"), b.keys)
        assertEquals("k1", b["thumbnailBrandKitId"]!!.jsonPrimitive.content)
    }

    @Test fun `Free plan and old server answers - the server's message comes through`() = runBlocking {
        server.enqueue(json("""{"statusCode":403,"message":"Your own ads are on the paid plans. Free streams carry a StreamBird ad instead -- upgrade to run yours.","error":"Forbidden"}""", 403))
        try { api.updateBrandKit(original, draft().copy(ads = kitAdsFromSlots(slotsFromKit(original.ads)))); org.junit.Assert.fail() } catch (e: ApiException) { assertEquals(403, e.status); assertTrue(e.message.isNotBlank()) }
        server.enqueue(json("""{"statusCode":400,"message":["ads.2.placement must be one of the following values: lower_third, side_third, full_screen"],"error":"Bad Request"}""", 400))
        try { api.updateBrandKit(original, draft().copy(ads = kitAdsFromSlots(slotsFromKit(original.ads)))); org.junit.Assert.fail() } catch (e: ApiException) { assertEquals(400, e.status); assertTrue(e.message.contains("placement")) }
    }
}
