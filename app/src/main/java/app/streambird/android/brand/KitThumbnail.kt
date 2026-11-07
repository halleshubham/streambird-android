package app.streambird.android.brand

import app.streambird.android.data.BrandKit

/**
 * The kit's stream thumbnail, app side (pure Kotlin, JVM-tested). The SERVER applies it: a stream created without a thumbnail of its own gets the
 * thumbnail of its destination's kit (else the account default kit) on each destination. The app only tells the user, and in one case names the kit.
 */
object KitThumbnail {
    /** "Thumbnail: from brand kit Main", shown on the Setup/Customize screen; null when there is nothing to say. */
    fun note(kit: BrandKit?, ownPicked: Boolean): String? {
        if (kit == null || kit.thumbnailAssetId == null || ownPicked) return null
        return "Thumbnail: from brand kit ${kit.name}"
    }

    /**
     * The `thumbnailBrandKitId` to send on POST /streams: ONLY the kit the user explicitly picked, and only when it has a thumbnail (a kit from an older
     * server never has the field, so older servers never receive the parameter). Otherwise null = omitted = the server's automatic rule. A thumbnail
     * picked on the phone is uploaded after the stream exists and replaces whatever the kit put there, so it needs no parameter either.
     */
    fun requestKitId(selectedKitId: String?, kits: List<BrandKit>, usingKit: Boolean, ownPicked: Boolean): String? {
        if (!usingKit || ownPicked || selectedKitId == null) return null
        return kits.firstOrNull { it.id == selectedKitId }?.takeIf { it.thumbnailAssetId != null }?.id
    }
}
