package app.streambird.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class BrandAdsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor(base)).build(), base)
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val kitWithAds = """{"id":"k1","accountId":"a1","name":"Main","logoAssetId":"as1","logoPosition":"right","logoSize":120,"wallpaperAssetId":"as9","wallpaperDim":35,"lowerThirdStyle":"glass","isDefault":true,"connectionIds":["c1"],"ads":[{"placement":"lower_third","assetId":"ad1","enabled":true,"mode":"continuous","showSec":null,"everySec":null},{"placement":"full_screen","assetId":"ad3","enabled":false,"mode":"interval","showSec":15,"everySec":180}]}"""
    private val kitNoAds = """{"id":"k2","name":"Old","logoPosition":"left","logoSize":90,"lowerThirdStyle":"accent","isDefault":false,"connectionIds":[]}"""
    private val original get() = ApiJson.decodeFromString<BrandKit>(kitWithAds)
    private fun draft(ads: List<KitAd>? = null) = KitDraft("Main", "as1", "right", 120, "glass", true, ads)

    @Test fun `kits parse with and without ads`() = runBlocking {
        server.enqueue(json("[$kitWithAds,$kitNoAds]"))
        val kits = api.brandKits()
        assertEquals(2, kits[0].ads.size)
        assertEquals(KitAd("lower_third", "ad1", true, "continuous", null, null), kits[0].ads[0])
        assertEquals(KitAd("full_screen", "ad3", false, "interval", 15, 180), kits[0].ads[1])
        assertEquals(emptyList<KitAd>(), kits[1].ads)                               // an older server / kit without the field
    }

    @Test fun `PATCH omits ads when the user did not touch them, other fields unchanged`() = runBlocking {
        server.enqueue(json(kitWithAds))
        api.updateBrandKit(original, draft(ads = null))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(body.containsKey("ads"))
        assertEquals("as9", body["wallpaperAssetId"]!!.jsonPrimitive.content); assertEquals("glass", body["lowerThirdStyle"]!!.jsonPrimitive.content)
    }

    @Test fun `PATCH carries ads in the exact shape when edited, continuous with null seconds`() = runBlocking {
        server.enqueue(json(kitWithAds))
        api.updateBrandKit(original, draft(ads = listOf(KitAd("lower_third", "ad1", true, "continuous", null, null), KitAd("full_screen", "ad3", false, "interval", 15, 180))))
        val r = server.takeRequest()
        assertEquals("PATCH", r.method); assertEquals("/api/brand-kits/k1", r.path)
        val ads = Json.parseToJsonElement(r.body.readUtf8()).jsonObject["ads"]!!.jsonArray
        assertEquals(2, ads.size)
        val a0 = ads[0].jsonObject
        assertEquals(setOf("placement", "assetId", "enabled", "mode", "showSec", "everySec"), a0.keys)
        assertEquals("lower_third", a0["placement"]!!.jsonPrimitive.content); assertEquals("ad1", a0["assetId"]!!.jsonPrimitive.content)
        assertEquals("true", a0["enabled"]!!.jsonPrimitive.content); assertEquals("continuous", a0["mode"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, a0["showSec"]); assertEquals(JsonNull, a0["everySec"])
        val a1 = ads[1].jsonObject
        assertEquals("15", a1["showSec"]!!.jsonPrimitive.content); assertEquals("180", a1["everySec"]!!.jsonPrimitive.content)
        assertEquals("false", a1["enabled"]!!.jsonPrimitive.content)
    }

    @Test fun `ads empty array clears, and POST sends ads only when present`() = runBlocking {
        server.enqueue(json(kitNoAds))
        api.updateBrandKit(original, draft(ads = emptyList()))
        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["ads"])

        server.enqueue(json(kitWithAds))
        api.createBrandKit(draft(ads = null))
        assertFalse(Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.containsKey("ads"))
        server.enqueue(json(kitWithAds))
        api.createBrandKit(draft(ads = listOf(KitAd("side_third", "ad2", true, "continuous", null, null))))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("side_third", Json.parseToJsonElement(r.body.readUtf8()).jsonObject["ads"]!!.jsonArray[0].jsonObject["placement"]!!.jsonPrimitive.content)
    }

    @Test fun `ad picture upload is multipart kind ad`() = runBlocking {
        server.enqueue(json("""{"id":"ad9","kind":"ad","name":"promo","contentType":"image/gif","sizeBytes":6,"width":10,"height":10}"""))
        val up = api.uploadAsset(byteArrayOf(71, 73, 70, 56, 57, 97), "image/gif", "promo.gif", kind = "ad", name = "promo")
        assertEquals("ad9", up.id); assertEquals("ad", up.kind)
        val body = server.takeRequest().body.readByteString().utf8()
        assertTrue(body.contains("name=\"kind\"") && body.contains("\r\n\r\nad\r\n")); assertTrue(body.contains("filename=\"promo.gif\"")); assertTrue(body.contains("Content-Type: image/gif"))
    }

    @Test fun `library lists ad files with their usage`() = runBlocking {
        server.enqueue(json("""{"enabled":true,"quotaBytes":104857600,"usedBytes":1000,"items":[{"id":"ad1","kind":"ad","name":"promo.gif","contentType":"image/gif","sizeBytes":500,"width":300,"height":100,"usedBy":["Main"]},{"id":"l1","kind":"logo","name":"logo","contentType":"image/png","sizeBytes":500,"width":10,"height":10,"usedBy":[]}]}"""))
        val lib = api.assetLibrary()
        assertEquals(listOf("ad1"), lib.adFiles.map { it.id }); assertEquals(listOf("l1"), lib.logos.map { it.id }); assertEquals(listOf("Main"), lib.adFiles[0].usedBy)
    }

    private suspend fun expect(code: Int, message: String, block: suspend () -> Unit) {
        server.enqueue(json("""{"statusCode":$code,"message":"${message.replace("\"", "\\\"")}","error":"x"}""", code))
        try { block(); fail("expected $code") } catch (e: ApiException) { assertEquals(code, e.status); assertEquals(message, e.message) }
    }

    @Test fun `backend refusals are shown with the backend's own message`() = runBlocking {
        val ads = listOf(KitAd("lower_third", "ad1", true, "continuous", null, null))
        expect(403, "Your own ads are on the paid plans. Upgrade to run them.") { api.updateBrandKit(original, draft(ads)) }
        expect(400, "showSec must be an integer number not greater than 3600") { api.updateBrandKit(original, draft(listOf(KitAd("lower_third", "ad1", true, "interval", 5000, 10)))) }
        expect(409, "This file is used by the brand kit \"Main\". Change or delete it first.") { api.deleteAsset("ad1") }
        expect(400, "A logo or wallpaper cannot use an ad picture.") { api.updateBrandKit(original, draft()) }
        // clearing is always allowed, even on a downgraded plan
        server.enqueue(json(kitNoAds)); assertEquals(emptyList<KitAd>(), api.updateBrandKit(original, draft(emptyList())).ads)
        assertNull(null)
    }
}
