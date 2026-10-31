package app.streambird.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
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

/** Sample JSON follows the backend: assets.service.ts AssetLibrary, brand-kits.service.ts BrandKitView, platform-connections, stream-scheduling ThumbnailState. */
class BrandApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor(base)).build(), base)
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val kitJson = """{"id":"k1","accountId":"a1","name":"Main","logoAssetId":"as1","logoPosition":"right","logoSize":120,"wallpaperAssetId":"as9","wallpaperDim":35,"lowerThirdStyle":"glass","isDefault":true,"createdAt":"2026-10-01T00:00:00.000Z","updatedAt":"2026-10-02T00:00:00.000Z","connectionIds":["c1","c2"]}"""

    @Test fun `brand kits parse with wallpaper fields and connections`() = runBlocking {
        server.enqueue(json("[$kitJson]"))
        val k = api.brandKits().single()
        assertEquals("k1", k.id); assertEquals("right", k.logoPosition); assertEquals(120, k.logoSize)
        assertEquals("as9", k.wallpaperAssetId); assertEquals(35, k.wallpaperDim); assertEquals("glass", k.lowerThirdStyle)
        assertTrue(k.isDefault); assertEquals(listOf("c1", "c2"), k.connectionIds)
        assertEquals("/api/brand-kits", server.takeRequest().path)
    }

    @Test fun `asset library parses usage and the enabled flag`() = runBlocking {
        server.enqueue(json("""{"enabled":true,"quotaBytes":104857600,"usedBytes":2097152,"items":[
            {"id":"as1","kind":"logo","name":"logo.gif","contentType":"image/gif","sizeBytes":2048,"width":200,"height":100,"createdAt":"2026-10-01T00:00:00.000Z","usedBy":["Main"]},
            {"id":"as2","kind":"wallpaper","name":"bg.png","contentType":"image/png","sizeBytes":4096,"width":1920,"height":1080,"createdAt":"2026-10-01T00:00:00.000Z","usedBy":[]}]}"""))
        val lib = api.assetLibrary()
        assertTrue(lib.enabled); assertEquals(104857600L, lib.quotaBytes); assertEquals(2097152L, lib.usedBytes)
        assertEquals(listOf("as1"), lib.logos.map { it.id })
        assertTrue(lib.items[0].isGif); assertEquals(listOf("Main"), lib.items[0].usedBy)
        server.enqueue(json("""{"enabled":false,"quotaBytes":0,"usedBytes":0,"items":[]}"""))
        assertFalse(api.assetLibrary().enabled)
    }

    @Test fun `connections carry liveLinkUrl`() = runBlocking {
        server.enqueue(json("""[{"id":"c1","platform":"youtube","externalAccountId":"UC1","label":"My channel","isActive":true,"liveSlug":"my-channel","liveLinkUrl":"https://streambird.app/stream/my-channel"},
            {"id":"c2","platform":"twitch","externalAccountId":"t","label":"Tw","isActive":true,"liveSlug":null,"liveLinkUrl":null}]"""))
        val c = api.platformConnections()
        assertEquals("https://streambird.app/stream/my-channel", c[0].liveLinkUrl); assertNull(c[1].liveLinkUrl)
    }

    @Test fun `kit edit payload preserves wallpaper fields and clears the logo with an explicit null`() {
        val original = ApiJson.decodeFromString<BrandKit>(kitJson)
        val p = BrandKitPayload.patch(original, KitDraft(" Renamed ", null, "left", 9999, "bar", isDefault = true))
        assertEquals("Renamed", p["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, p["logoAssetId"])
        assertEquals("left", p["logoPosition"]!!.jsonPrimitive.content)
        assertEquals(600, p["logoSize"]!!.jsonPrimitive.int)          // clamped like the DTO's @Max(600)
        assertEquals("bar", p["lowerThirdStyle"]!!.jsonPrimitive.content)
        assertEquals("as9", p["wallpaperAssetId"]!!.jsonPrimitive.content)  // unchanged
        assertEquals(35, p["wallpaperDim"]!!.jsonPrimitive.int)             // unchanged
        assertFalse(p.containsKey("isDefault"))                              // already the default: not sent
        val q = BrandKitPayload.patch(original.copy(isDefault = false, wallpaperAssetId = null), KitDraft("x", "as1", "right", 5, "accent", true))
        assertEquals(JsonNull, q["wallpaperAssetId"]); assertTrue(q["isDefault"]!!.jsonPrimitive.boolean); assertEquals(16, q["logoSize"]!!.jsonPrimitive.int)
    }

    @Test fun `PATCH goes to the kit and returns the parsed kit`() = runBlocking {
        val original = ApiJson.decodeFromString<BrandKit>(kitJson)
        server.enqueue(json(kitJson))
        val saved = api.updateBrandKit(original, KitDraft("Main", "as1", "right", 120, "glass", true))
        val r = server.takeRequest()
        assertEquals("PATCH", r.method); assertEquals("/api/brand-kits/k1", r.path)
        assertTrue(r.getHeader("Origin")!!.startsWith("http://"))
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("as9", body["wallpaperAssetId"]!!.jsonPrimitive.content)
        assertEquals("k1", saved.id)
    }

    @Test fun `create, delete and assign`() = runBlocking {
        server.enqueue(json(kitJson))
        api.createBrandKit(KitDraft("New", "as1", "left", 90, "accent", false))
        val create = server.takeRequest()
        assertEquals("POST", create.method); assertEquals("/api/brand-kits", create.path)
        val cb = Json.parseToJsonElement(create.body.readUtf8()).jsonObject
        assertFalse(cb.containsKey("isDefault")); assertFalse(cb.containsKey("wallpaperAssetId"))

        server.enqueue(MockResponse().setResponseCode(204))
        api.deleteBrandKit("k1")
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(json("""{"connectionId":"c1","brandKitId":"k1"}""")); server.enqueue(json("""{"connectionId":"c2","brandKitId":null}"""))
        api.assignBrandKit("c1", "k1"); api.assignBrandKit("c2", null)
        val a1 = server.takeRequest(); val a2 = server.takeRequest()
        assertEquals("PUT", a1.method); assertEquals("/api/brand-kits/connections/c1", a1.path)
        assertEquals("k1", Json.parseToJsonElement(a1.body.readUtf8()).jsonObject["brandKitId"]!!.jsonPrimitive.content)
        assertEquals("""{"brandKitId":null}""", a2.body.readUtf8())
    }

    @Test fun `asset upload is multipart with kind, name and the file`() = runBlocking {
        server.enqueue(json("""{"id":"as5","accountId":"a1","kind":"logo","name":"mylogo","contentType":"image/png","sizeBytes":8,"width":10,"height":10,"storageKey":"x"}"""))
        val png = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val up = api.uploadAsset(png, "image/png", "mylogo.png", "logo", "mylogo")
        assertEquals("as5", up.id)
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertEquals("/api/assets", r.path)
        assertTrue(r.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = r.body.readByteString().utf8()
        assertTrue(body.contains("name=\"kind\"")); assertTrue(body.contains("\r\n\r\nlogo\r\n"))
        assertTrue(body.contains("name=\"name\"")); assertTrue(body.contains("mylogo\r\n"))
        assertTrue(body.contains("name=\"file\"; filename=\"mylogo.png\"")); assertTrue(body.contains("Content-Type: image/png"))
    }

    @Test fun `backend refusals keep their own message`() = runBlocking {
        server.enqueue(json("""{"statusCode":409,"message":"This file is used by the brand kit \"Main\". Change or delete it first.","error":"Conflict"}""", 409))
        try { api.deleteAsset("as1"); fail() } catch (e: ApiException) {
            assertEquals(409, e.status); assertEquals("This file is used by the brand kit \"Main\". Change or delete it first.", e.message)
        }
        server.enqueue(json("""{"statusCode":403,"message":"Brand storage is on the paid plans. Your logo and wallpaper stay saved in this browser on the Free plan.","error":"Forbidden"}""", 403))
        try { api.uploadAsset(byteArrayOf(1), "image/png", "a.png"); fail() } catch (e: ApiException) {
            assertTrue(e.message.startsWith("Brand storage is on the paid plans"))
        }
        server.enqueue(json("""{"statusCode":400,"message":"The image is too big -- its longest side must be at most 8192px.","error":"Bad Request"}""", 400))
        try { api.uploadAsset(byteArrayOf(1), "image/png", "a.png"); fail() } catch (e: ApiException) { assertTrue(e.message.contains("8192")) }
    }

    @Test fun `thumbnail upload is a PUT multipart with field file and parses platform warnings`() = runBlocking {
        server.enqueue(json("""{"hasThumbnail":true,"thumbnailUpdatedAt":"2026-10-31T10:00:00.000Z","platformWarnings":["YouTube: your channel must be verified to use custom thumbnails."]}"""))
        val res = api.uploadStreamThumbnail("s1", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2))
        assertTrue(res.hasThumbnail); assertEquals(1, res.platformWarnings.size)
        val r = server.takeRequest()
        assertEquals("PUT", r.method); assertEquals("/api/streams/s1/thumbnail", r.path)
        assertTrue(r.getHeader("Origin") != null)
        val body = r.body.readByteString().utf8()
        assertTrue(body.contains("name=\"file\"; filename=\"thumbnail.jpg\"")); assertTrue(body.contains("Content-Type: image/jpeg"))

        server.enqueue(json("""{"hasThumbnail":true,"thumbnailUpdatedAt":null}"""))
        assertTrue(api.uploadStreamThumbnail("s1", byteArrayOf(1)).platformWarnings.isEmpty())
    }

    @Test fun `asset file url points at the authenticated file route`() {
        assertTrue(api.assetFileUrl("as1").endsWith("/api/assets/as1/file"))
    }
}
