package app.streambird.android.brand

import app.streambird.android.data.BrandKit
import app.streambird.android.data.KitAd

/**
 * Suggested picture sizes and limits for every brand-kit slot. Port of the web's web-app/src/studio/brandSizes.ts + brandKitSet.ts
 * (same numbers, same wording). Pure Kotlin, JVM-tested.
 */
enum class BrandOrientation(val wire: String, val label: String) {
    LANDSCAPE("landscape", "16:9"),
    PORTRAIT("portrait", "9:16");

    companion object {
        fun fromWire(s: String?): BrandOrientation = if (s == "portrait") PORTRAIT else LANDSCAPE
    }
}

enum class BrandSlot(val label: String, val gifAllowed: Boolean) {
    LOGO("Logo", true),
    WALLPAPER("Wallpaper", false),
    LOWER_THIRD("Lower third", true),
    SIDE_THIRD("Left third", true),
    FULL_SCREEN("Full screen", true),
    L_SHAPE("L-shaped frame", true),
    /** The kit's stream thumbnail: one picture shared by both orientations; JPG or PNG only. */
    THUMBNAIL("Stream thumbnail", false),
}

/** The stream-thumbnail limits the platforms (and the server, thumbnail.util.ts) set. */
object ThumbnailLimits {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MIN_WIDTH = 640
}

/** True for a library file that can be a kit's thumbnail (JPG/PNG, within the limits). */
fun isThumbnailCandidate(contentType: String, sizeBytes: Long, width: Int): Boolean =
    (contentType == "image/jpeg" || contentType == "image/png") && sizeBytes in 1..ThumbnailLimits.MAX_BYTES.toLong() && width >= ThumbnailLimits.MIN_WIDTH

/** File limits the server enforces for every library file. */
object BrandFileLimits {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_SIDE = 8192
}

/** A picture whose shape is off by more than this factor from the suggestion gets a (non-blocking) warning. */
const val ASPECT_TOLERANCE = 1.5

data class SlotSpec(val label: String, val w: Int, val h: Int, val ratioMin: Double, val ratioMax: Double, val hint: String) {
    val sizeText get() = "$w x $h"
}

private fun around(w: Int, h: Int, label: String, hint: String) =
    SlotSpec(label, w, h, w.toDouble() / h / ASPECT_TOLERANCE, w.toDouble() / h * ASPECT_TOLERANCE, hint)

private val LOGO_SPEC = SlotSpec("Logo", 512, 512, 0.4, 5.0, "Square or wide PNG with a transparent background, about 512 x 512 (up to 1024 px).")

/** The thumbnail is 16:9 for both orientations (vertical streams use a 16:9 thumbnail too). */
const val THUMBNAIL_HELP = "Suggested 1280x720 JPG or PNG, under 2 MB. Vertical streams use this 16:9 image too."
private val THUMBNAIL_SPEC = around(1280, 720, "Stream thumbnail", "16:9, 1280 x 720. Vertical (9:16) streams use a 16:9 thumbnail too.")

fun slotSpec(o: BrandOrientation, slot: BrandSlot): SlotSpec = when (o) {
    BrandOrientation.LANDSCAPE -> when (slot) {
        BrandSlot.LOGO -> LOGO_SPEC
        BrandSlot.WALLPAPER -> around(1920, 1080, "Wallpaper", "16:9, 1920 x 1080. It fills the frame behind everyone.")
        BrandSlot.LOWER_THIRD -> around(1280, 240, "Lower third", "Wide banner, about 16:3 (1280 x 240). It sits in the bottom third of the picture.")
        BrandSlot.SIDE_THIRD -> around(640, 1080, "Left third", "Tall slice, 640 x 1080. It fills the left third, full height.")
        BrandSlot.FULL_SCREEN -> around(1920, 1080, "Full screen", "16:9, 1920 x 1080. It covers the whole picture while it shows.")
        BrandSlot.L_SHAPE -> around(1920, 1080, "L-shaped frame", "Full frame, 16:9, 1920 x 1080: a coloured column and/or band around a plain white (or transparent) window, 16:9, where the live picture goes.")
        BrandSlot.THUMBNAIL -> THUMBNAIL_SPEC
    }
    BrandOrientation.PORTRAIT -> when (slot) {
        BrandSlot.LOGO -> LOGO_SPEC
        BrandSlot.WALLPAPER -> around(1080, 1920, "Wallpaper", "9:16, 1080 x 1920. It fills the frame behind everyone.")
        BrandSlot.LOWER_THIRD -> around(1080, 360, "Lower third", "Banner about 3:1 (1080 x 360, at most 1080 x 640). It sits at the bottom of the tall picture.")
        BrandSlot.SIDE_THIRD -> around(360, 1920, "Left third", "Narrow tall strip, 360 x 1920. It fills the left third, full height.")
        BrandSlot.FULL_SCREEN -> around(1080, 1920, "Full screen", "9:16, 1080 x 1920. It covers the whole picture while it shows.")
        BrandSlot.L_SHAPE -> around(1080, 1920, "L-shaped frame", "Full frame, 9:16, 1080 x 1920: a coloured band around a plain white (or transparent) window where the live picture goes.")
        BrandSlot.THUMBNAIL -> THUMBNAIL_SPEC
    }
}

/** True when a picture's shape is far from what the slot suggests (a warning, never an error). */
fun aspectMismatch(width: Int, height: Int, spec: SlotSpec): Boolean {
    if (width <= 0 || height <= 0) return false
    val r = width.toDouble() / height
    return r < spec.ratioMin || r > spec.ratioMax
}

/** The helper line for a slot: shape advice plus the file limits. */
fun slotHelp(o: BrandOrientation, slot: BrandSlot): String {
    if (slot == BrandSlot.THUMBNAIL) return THUMBNAIL_HELP
    val gif = if (slot.gifAllowed) "PNG, JPG, WebP or GIF" else "PNG, JPG or WebP"
    return "${slotSpec(o, slot).hint} $gif, up to ${BrandFileLimits.MAX_BYTES / 1024 / 1024} MB."
}

/** The non-blocking warning for a chosen picture, or null when its shape is fine (or its size is unknown). */
fun slotAspectWarning(o: BrandOrientation, slot: BrandSlot, width: Int, height: Int): String? {
    val spec = slotSpec(o, slot)
    if (!aspectMismatch(width, height, spec)) return null
    return "This picture is $width x $height, a different shape than suggested (${spec.sizeText}). It still works, but it may look small or be cropped."
}

/**
 * Which materials of a kit a stream uses, by the stream's orientation (rule of web brandKitSet.ts):
 * landscape -> logoAssetId, wallpaperAssetId, ads. Portrait -> logoAssetIdPortrait (falls back to the LANDSCAPE logo),
 * wallpaperAssetIdPortrait and adsPortrait (NO fallback). Position, size, dim, lower-third style and the shrink switch are shared.
 */
data class KitSet(val logoAssetId: String?, val wallpaperAssetId: String?, val ads: List<KitAd>)

fun kitSetFor(kit: BrandKit, o: BrandOrientation): KitSet = when (o) {
    BrandOrientation.PORTRAIT -> KitSet(kit.logoAssetIdPortrait ?: kit.logoAssetId, kit.wallpaperAssetIdPortrait, kit.adsPortrait)
    BrandOrientation.LANDSCAPE -> KitSet(kit.logoAssetId, kit.wallpaperAssetId, kit.ads)
}
