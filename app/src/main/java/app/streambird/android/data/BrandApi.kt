package app.streambird.android.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What the brand-kit editor can change. Everything else on the kit is carried over from [BrandKitPayload.patch]'s `original`.
 * Two sets of materials: landscape (16:9: [logoAssetId], [wallpaperAssetId], [ads]) and portrait (9:16: the `...Portrait` ones);
 * position, size, dim, style and [adsShrinkPicture] are shared.
 */
data class KitDraft(
    val name: String,
    val logoAssetId: String?,
    val logoPosition: String,
    val logoSize: Int,
    val lowerThirdStyle: String,
    val isDefault: Boolean,
    /** null = the user did not touch the ads: `ads` is OMITTED from the request (replace-all semantics: omitted = unchanged). [] clears them. */
    val ads: List<KitAd>? = null,
    /** Wallpaper of the landscape set; null = none (sent as an explicit null on PATCH, like the logo). */
    val wallpaperAssetId: String? = null,
    val logoAssetIdPortrait: String? = null,
    val wallpaperAssetIdPortrait: String? = null,
    /** Same semantics as [ads], for the 9:16 set. */
    val adsPortrait: List<KitAd>? = null,
    val adsShrinkPicture: Boolean = true,
    /** The kit's stream thumbnail (shared by both sets); null = none. Sent on PATCH only when it changed (explicit null clears), on POST only when set. */
    val thumbnailAssetId: String? = null,
    /** Default stream title of the kit (max 100) and description (max 5000): PATCH sends each only when it changed (blank = clear, explicit null), POST only when set. */
    val title: String? = null,
    val description: String? = null,
)

object BrandKitPayload {
    const val MIN_LOGO = 16
    const val MAX_LOGO = 600
    val STYLES = listOf("bar", "accent", "glass")

    fun clampSize(v: Int) = v.coerceIn(MIN_LOGO, MAX_LOGO)

    /** Draft of an existing kit with nothing changed (what the editor starts from). */
    fun draftOf(k: BrandKit) = KitDraft(
        k.name, k.logoAssetId, k.logoPosition, k.logoSize, k.lowerThirdStyle, k.isDefault,
        wallpaperAssetId = k.wallpaperAssetId, logoAssetIdPortrait = k.logoAssetIdPortrait,
        wallpaperAssetIdPortrait = k.wallpaperAssetIdPortrait, adsShrinkPicture = k.adsShrinkPicture, thumbnailAssetId = k.thumbnailAssetId, title = k.title, description = k.description,
    )

    /**
     * PATCH /brand-kits/:id body. Landscape logo / wallpaper are always sent (a cleared one is an explicit JSON null), `wallpaperDim` is sent back
     * unchanged. The fields that only exist on servers with the orientation release (`logoAssetIdPortrait`, `wallpaperAssetIdPortrait`,
     * `adsShrinkPicture`, `adsPortrait`, `thumbnailAssetId`) are sent ONLY when they changed, so editing a kit never trips an older server that does
     * not know them. `thumbnailAssetId`: omitted = unchanged, explicit null = clear.
     * Ads: replace-all per set, omitted = unchanged, [] = none. Each set carries ALL its placements (lower_third, side_third, full_screen, l_shape):
     * an app that sent only three would drop the frame. `isDefault` is only sent when turning it on.
     */
    fun patch(original: BrandKit, d: KitDraft): JsonObject = buildJsonObject {
        put("name", d.name.trim())
        put("logoAssetId", d.logoAssetId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("logoPosition", d.logoPosition)
        put("logoSize", clampSize(d.logoSize))
        put("lowerThirdStyle", d.lowerThirdStyle)
        put("wallpaperAssetId", d.wallpaperAssetId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("wallpaperDim", original.wallpaperDim)
        if (d.logoAssetIdPortrait != original.logoAssetIdPortrait) put("logoAssetIdPortrait", d.logoAssetIdPortrait?.let { JsonPrimitive(it) } ?: JsonNull)
        if (d.wallpaperAssetIdPortrait != original.wallpaperAssetIdPortrait) put("wallpaperAssetIdPortrait", d.wallpaperAssetIdPortrait?.let { JsonPrimitive(it) } ?: JsonNull)
        if (d.adsShrinkPicture != original.adsShrinkPicture) put("adsShrinkPicture", d.adsShrinkPicture)
        if (d.thumbnailAssetId != original.thumbnailAssetId) put("thumbnailAssetId", d.thumbnailAssetId?.let { JsonPrimitive(it) } ?: JsonNull)
        textChange(this, "title", app.streambird.android.brand.KitText.titleChange(original.title, d.title))
        textChange(this, "description", app.streambird.android.brand.KitText.descriptionChange(original.description, d.description))
        if (d.isDefault && !original.isDefault) put("isDefault", true)
        d.ads?.let { put("ads", adsJson(it)) }
        d.adsPortrait?.let { put("adsPortrait", adsJson(it)) }
    }

    /** POST /brand-kits body. New-release fields are only sent when set / non-default. */
    fun create(d: KitDraft): JsonObject = buildJsonObject {
        put("name", d.name.trim())
        d.logoAssetId?.let { put("logoAssetId", it) }
        d.wallpaperAssetId?.let { put("wallpaperAssetId", it) }
        d.logoAssetIdPortrait?.let { put("logoAssetIdPortrait", it) }
        d.wallpaperAssetIdPortrait?.let { put("wallpaperAssetIdPortrait", it) }
        if (!d.adsShrinkPicture) put("adsShrinkPicture", false)
        d.thumbnailAssetId?.let { put("thumbnailAssetId", it) }
        app.streambird.android.brand.KitText.cleanTitle(d.title)?.let { put("title", it) }
        app.streambird.android.brand.KitText.cleanDescription(d.description)?.let { put("description", it) }
        put("logoPosition", d.logoPosition)
        put("logoSize", clampSize(d.logoSize))
        put("lowerThirdStyle", d.lowerThirdStyle)
        if (d.isDefault) put("isDefault", true)
        d.ads?.let { put("ads", adsJson(it)) }
        d.adsPortrait?.let { put("adsPortrait", adsJson(it)) }
    }

    private fun textChange(b: kotlinx.serialization.json.JsonObjectBuilder, key: String, c: app.streambird.android.brand.KitText.Change) {
        when (c) {
            is app.streambird.android.brand.KitText.Change.Set -> b.put(key, c.value)
            app.streambird.android.brand.KitText.Change.Clear -> b.put(key, JsonNull)
            app.streambird.android.brand.KitText.Change.Unchanged -> Unit
        }
    }

    /** The exact shape the backend expects: placement, assetId, enabled, mode, showSec, everySec (null for continuous). */
    fun adsJson(ads: List<KitAd>) = kotlinx.serialization.json.buildJsonArray {
        ads.forEach { a ->
            add(buildJsonObject {
                put("placement", a.placement); put("assetId", a.assetId); put("enabled", a.enabled); put("mode", a.mode)
                put("showSec", a.showSec?.let { JsonPrimitive(it) } ?: JsonNull)
                put("everySec", a.everySec?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }
    }
}

private fun JsonObject.body() = toString().toRequestBody("application/json".toMediaType())

// ---- brand kits ----
suspend fun ApiClient.createBrandKit(d: KitDraft): BrandKit =
    call(Request.Builder().url(url("/brand-kits")).post(BrandKitPayload.create(d).body()).build()) { ApiJson.decodeFromString(it) }

suspend fun ApiClient.updateBrandKit(original: BrandKit, d: KitDraft): BrandKit =
    call(Request.Builder().url(url("/brand-kits/${original.id}")).patch(BrandKitPayload.patch(original, d).body()).build()) { ApiJson.decodeFromString(it) }

suspend fun ApiClient.deleteBrandKit(id: String) = call(Request.Builder().url(url("/brand-kits/$id")).delete().build()) { }

/** PUT /brand-kits/connections/:connectionId; kitId null sends the connection back to the account default. */
suspend fun ApiClient.assignBrandKit(connectionId: String, kitId: String?) {
    val body = buildJsonObject { put("brandKitId", kitId?.let { JsonPrimitive(it) } ?: JsonNull) }.body()
    call(Request.Builder().url(url("/brand-kits/connections/$connectionId")).put(body).build()) { }
}

// ---- assets ----
suspend fun ApiClient.assetLibrary(): AssetLibrary = call(get("/assets")) { ApiJson.decodeFromString(it) }

suspend fun ApiClient.deleteAsset(id: String) = call(Request.Builder().url(url("/assets/$id")).delete().build()) { }

/** POST /assets multipart: fields `kind`, optional `name`, and the file as `file`. The file is any [RequestBody] (a streaming one for the phone's gallery). */
fun assetUploadBody(file: RequestBody, fileName: String, kind: String, name: String?): MultipartBody =
    MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("kind", kind)
        .apply { if (!name.isNullOrBlank()) addFormDataPart("name", name) }
        .addFormDataPart("file", fileName, file)
        .build()

fun assetUploadBody(bytes: ByteArray, mime: String, fileName: String, kind: String, name: String?): MultipartBody =
    assetUploadBody(bytes.toRequestBody(mime.toMediaType()), fileName, kind, name)

suspend fun ApiClient.uploadAsset(bytes: ByteArray, mime: String, fileName: String, kind: String = "logo", name: String? = null): UploadedAsset =
    call(Request.Builder().url(url("/assets")).post(assetUploadBody(bytes, mime, fileName, kind, name)).build(), uploadHttp) { ApiJson.decodeFromString(it) }

/** Streams [file] (never held in memory) to POST /assets over the upload client (stall-based timeouts for slow uplinks). */
suspend fun ApiClient.uploadAssetStreaming(file: RequestBody, fileName: String, kind: String = "logo", name: String? = null): UploadedAsset =
    call(Request.Builder().url(url("/assets")).post(assetUploadBody(file, fileName, kind, name)).build(), uploadHttp) { ApiJson.decodeFromString(it) }

/** URL Coil loads (authenticated through the app's OkHttpClient: cookie + Origin). */
fun ApiClient.assetFileUrl(id: String): String = url("/assets/$id/file").toString()

// ---- stream thumbnail ----
fun thumbnailUploadBody(jpeg: ByteArray): MultipartBody =
    MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "thumbnail.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())).build()

suspend fun ApiClient.uploadStreamThumbnail(streamId: String, jpeg: ByteArray): ThumbnailResult =
    call(Request.Builder().url(url("/streams/$streamId/thumbnail")).put(thumbnailUploadBody(jpeg)).build(), uploadHttp) { ApiJson.decodeFromString(it) }

