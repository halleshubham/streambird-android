package app.streambird.android.brand

import app.streambird.android.data.KitDraft

/**
 * A server from before the frame (`l_shape`) / kit-thumbnail release answers a 400 for the new fields. The kit editor shows this plain sentence instead
 * of the validator's wording; any other error is shown as the server says it. Pure Kotlin, JVM-tested.
 */
object KitCompat {
    const val NO_FRAME_ADS = "This server does not support L-shaped frame ads yet. Remove that ad or try again later."
    const val NO_KIT_THUMBNAIL = "This server does not support kit thumbnails yet. Remove it or try again later."
    const val NO_KIT_TEXT = "This server does not support a default title and description for brand kits yet. Clear them or try again later."

    /** The replacement message for a failed save, or null to keep the server's own. Only a 400 that names the new field is rewritten. */
    fun saveError(status: Int, message: String, draft: KitDraft): String? {
        if (status != 400) return null
        val m = message.lowercase()
        val sentFrame = draft.ads.orEmpty().any { it.placement == "l_shape" } || draft.adsPortrait.orEmpty().any { it.placement == "l_shape" }
        if (sentFrame && ("placement" in m || "l_shape" in m)) return NO_FRAME_ADS
        if (draft.thumbnailAssetId != null && "thumbnailassetid" in m) return NO_KIT_THUMBNAIL
        val sentText = KitText.cleanTitle(draft.title) != null || KitText.cleanDescription(draft.description) != null
        if (sentText && "should not exist" in m && ("title" in m || "description" in m)) return NO_KIT_TEXT
        return null
    }
}
