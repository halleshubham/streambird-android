package app.streambird.android.data

import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.addAd
import app.streambird.android.ads.kitAdsSave
import app.streambird.android.ads.removeAd
import app.streambird.android.ads.slotsFromKit
import app.streambird.android.brand.KitCompat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

/** Ad sequences on the wire (1.0.18): `adsMore` / `adsMorePortrait` in kits, what a save sends, and the old-server cases. */
class BrandAdsMoreApiTest {
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

    private val kitJson = """{"id":"k1","name":"Main","logoPosition":"left","logoSize":90,"wallpaperDim":0,"lowerThirdStyle":"accent","isDefault":true,"connectionIds":[],
        "ads":[{"placement":"lower_third","assetId":"a1","enabled":true,"mode":"interval","showSec":15,"everySec":180}],
        "adsMore":[{"placement":"lower_third","assetId":"a2","enabled":true,"mode":"interval","showSec":25,"everySec":180}],
        "adsPortrait":[],"adsMorePortrait":[]}"""
    private val original get() = ApiJson.decodeFromString<BrandKit>(kitJson)
    private fun draft(o: BrandKit = original) = BrandKitPayload.draftOf(o)

    @Test fun `the kit list reads adsMore, adsMorePortrait and an old server's missing ones`() = runBlocking {
        server.enqueue(json("[$kitJson,{\"id\":\"k2\",\"name\":\"Old\",\"logoPosition\":\"left\",\"logoSize\":90,\"lowerThirdStyle\":\"accent\",\"isDefault\":false,\"connectionIds\":[]}]"))
        val kits = api.brandKits()
        assertEquals(listOf("a2"), kits[0].adsMore.map { it.assetId }); assertEquals(25, kits[0].adsMore[0].showSec)
        assertEquals(emptyList<KitAd>(), kits[1].adsMore); assertEquals(emptyList<KitAd>(), kits[1].adsMorePortrait)
    }

    @Test fun `an untouched kit sends neither ads nor adsMore (other edits never touch the ads)`() = runBlocking {
        server.enqueue(json(kitJson))
        api.updateBrandKit(original, draft())
        val b = body()
        assertFalse(b.containsKey("ads")); assertFalse(b.containsKey("adsMore")); assertFalse(b.containsKey("adsMorePortrait"))
    }

    @Test fun `a set is saved exactly - ads and adsMore together, adsMore empty when the further ads are removed`() = runBlocking {
        val rows = slotsFromKit(original.ads, original.adsMore).map { if (it.placement == AdPlacement.LOWER_THIRD) it.removeAd(1) else it }
        val save = kitAdsSave(original.ads, original.adsMore, rows)!!
        server.enqueue(json(kitJson))
        api.updateBrandKit(original, draft().copy(ads = save.ads, adsMore = save.adsMore))
        val b = body()
        assertEquals(1, b["ads"]!!.jsonArray.size)
        assertEquals(JsonArray(emptyList()), b["adsMore"])                                       // [] = no further ads, sent explicitly
        assertFalse(b.containsKey("adsMorePortrait"))
    }

    @Test fun `adding a second ad sends the full wire shape of both fields`() = runBlocking {
        val noMore = original.copy(adsMore = emptyList())
        val rows = slotsFromKit(noMore.ads).map { if (it.placement == AdPlacement.FULL_SCREEN) it.addAd("f1") else if (it.placement == AdPlacement.LOWER_THIRD) it.addAd("a3") else it }
        val save = kitAdsSave(noMore.ads, noMore.adsMore, rows)!!
        server.enqueue(json(kitJson))
        api.updateBrandKit(noMore, draft(noMore).copy(ads = save.ads, adsMore = save.adsMore))
        val b = body()
        assertEquals(listOf("lower_third", "full_screen"), b["ads"]!!.jsonArray.map { it.jsonObject["placement"]!!.jsonPrimitive.content })
        val more = b["adsMore"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a3"), more.map { it["assetId"]!!.jsonPrimitive.content })
        assertEquals(setOf("placement", "assetId", "enabled", "mode", "showSec", "everySec"), more[0].keys)
        assertEquals("interval", more[0]["mode"]!!.jsonPrimitive.content); assertEquals("180", more[0]["everySec"]!!.jsonPrimitive.content)   // the placement's pause on every ad
    }

    @Test fun `a lone ad per placement saves exactly like before - no adsMore on the wire`() = runBlocking {
        val noMore = original.copy(adsMore = emptyList())
        val rows = slotsFromKit(noMore.ads).map { if (it.placement == AdPlacement.SIDE_THIRD) it.addAd("s1") else it }
        val save = kitAdsSave(noMore.ads, noMore.adsMore, rows)!!
        assertNull(save.adsMore)
        server.enqueue(json(kitJson))
        api.updateBrandKit(noMore, draft(noMore).copy(ads = save.ads, adsMore = save.adsMore))
        assertFalse(body().containsKey("adsMore"))
    }

    @Test fun `the portrait set has its own adsMorePortrait`() = runBlocking {
        server.enqueue(json(kitJson))
        api.updateBrandKit(original, draft().copy(adsPortrait = emptyList(), adsMorePortrait = emptyList()))
        val b = body()
        assertTrue(b.containsKey("adsPortrait")); assertTrue(b.containsKey("adsMorePortrait")); assertFalse(b.containsKey("adsMore"))
    }

    @Test fun `creating a kit sends adsMore only when set`() = runBlocking {
        server.enqueue(json(kitJson)); api.createBrandKit(draft().copy(name = "New"))
        assertFalse(body().containsKey("adsMore"))
        server.enqueue(json(kitJson)); api.createBrandKit(draft().copy(name = "New", ads = original.ads, adsMore = original.adsMore))
        assertEquals(1, body()["adsMore"]!!.jsonArray.size)
    }

    @Test fun `a server without sequences answers the new field with a plain sentence`() {
        val d = draft().copy(ads = original.ads, adsMore = original.adsMore)
        assertEquals(KitCompat.NO_AD_SEQUENCES, KitCompat.saveError(400, "property adsMore should not exist", d))
        assertEquals(KitCompat.NO_AD_SEQUENCES, KitCompat.saveError(400, "adsMore should not exist", d))
        assertNull(KitCompat.saveError(400, "adsMore should not exist", draft()))                     // not sent = not ours to explain
        assertNull(KitCompat.saveError(403, "property adsMore should not exist", d))
        assertNull(KitCompat.saveError(400, "ads.0.mode must be one of the following values", d))
    }
}
