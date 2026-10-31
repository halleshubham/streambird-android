package app.streambird.android.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the brand-kit editor can change. Everything else on the kit is carried over from [BrandKitPayload.patch]'s `original`. */
data class KitDraft(
    val name: String,
    val logoAssetId: String?,
    val logoPosition: String,
    val logoSize: Int,
    val lowerThirdStyle: String,
    val isDefault: Boolean,
    /** null = the user did not touch the ads: `ads` is OMITTED from the request (replace-all semantics: omitted = unchanged). [] clears them. */
    val ads: List<KitAd>? = null,
)

object BrandKitPayload {
    const val MIN_LOGO = 16
    const val MAX_LOGO = 600
    val STYLES = listOf("bar", "accent", "glass")

    fun clampSize(v: Int) = v.coerceIn(MIN_LOGO, MAX_LOGO)

    /**
     * PATCH /brand-kits/:id body. The app does not expose wallpaper, so the kit's own wallpaperAssetId / wallpaperDim are sent
     * back unchanged (a missing field would also be left alone by the backend, but sending them is explicit and survives a
     * future "replace" semantic). A cleared logo is an explicit JSON null; `isDefault` is only sent when turning it on
     * (the backend ignores false: another kit has to be made the default instead).
     */
    fun patch(original: BrandKit, d: KitDraft): JsonObject = buildJsonObject {
        put("name", d.name.trim())
        put("logoAssetId", d.logoAssetId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("logoPosition", d.logoPosition)
        put("logoSize", clampSize(d.logoSize))
        put("lowerThirdStyle", d.lowerThirdStyle)
        put("wallpaperAssetId", original.wallpaperAssetId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("wallpaperDim", original.wallpaperDim)
        if (d.isDefault && !original.isDefault) put("isDefault", true)
        d.ads?.let { put("ads", adsJson(it)) }
    }

    /** POST /brand-kits body (no wallpaper: the backend defaults it). */
    fun create(d: KitDraft): JsonObject = buildJsonObject {
        put("name", d.name.trim())
        d.logoAssetId?.let { put("logoAssetId", it) }
        put("logoPosition", d.logoPosition)
        put("logoSize", clampSize(d.logoSize))
        put("lowerThirdStyle", d.lowerThirdStyle)
        if (d.isDefault) put("isDefault", true)
        d.ads?.let { put("ads", adsJson(it)) }
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

/** POST /assets multipart: fields `kind`, optional `name`, and the file as `file`. */
fun assetUploadBody(bytes: ByteArray, mime: String, fileName: String, kind: String, name: String?): MultipartBody =
    MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("kind", kind)
        .apply { if (!name.isNullOrBlank()) addFormDataPart("name", name) }
        .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
        .build()

suspend fun ApiClient.uploadAsset(bytes: ByteArray, mime: String, fileName: String, kind: String = "logo", name: String? = null): UploadedAsset =
    call(Request.Builder().url(url("/assets")).post(assetUploadBody(bytes, mime, fileName, kind, name)).build()) { ApiJson.decodeFromString(it) }

/** URL Coil loads (authenticated through the app's OkHttpClient: cookie + Origin). */
fun ApiClient.assetFileUrl(id: String): String = url("/assets/$id/file").toString()

// ---- stream thumbnail ----
fun thumbnailUploadBody(jpeg: ByteArray): MultipartBody =
    MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "thumbnail.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())).build()

suspend fun ApiClient.uploadStreamThumbnail(streamId: String, jpeg: ByteArray): ThumbnailResult =
    call(Request.Builder().url(url("/streams/$streamId/thumbnail")).put(thumbnailUploadBody(jpeg)).build()) { ApiJson.decodeFromString(it) }

