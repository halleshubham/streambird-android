package app.streambird.android.data

import kotlinx.serialization.Serializable

/** One file of the account's asset library (GET /assets `items[]`). */
@Serializable
data class AssetItem(
    val id: String,
    val kind: String = "other",
    val name: String = "",
    val contentType: String = "",
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val createdAt: String? = null,
    /** Names of the brand kits that use this file. */
    val usedBy: List<String> = emptyList(),
) {
    val isGif get() = contentType == "image/gif"
}

/** GET /assets. `enabled=false`: object storage is not configured on this server. `quotaBytes=0`: plan without a library (Free). */
@Serializable
data class AssetLibrary(
    val enabled: Boolean = true,
    val quotaBytes: Long = 0,
    val usedBytes: Long = 0,
    val items: List<AssetItem> = emptyList(),
) {
    val logos get() = items.filter { it.kind == "logo" }
}

/** Response of POST /assets (the stored row; only what the app needs). */
@Serializable
data class UploadedAsset(val id: String, val name: String = "", val kind: String = "logo", val contentType: String = "", val sizeBytes: Long = 0, val width: Int = 0, val height: Int = 0)

/** Response of PUT /streams/:id/thumbnail. */
@Serializable
data class ThumbnailResult(
    val hasThumbnail: Boolean = false,
    val thumbnailUpdatedAt: String? = null,
    val platformWarnings: List<String> = emptyList(),
)
