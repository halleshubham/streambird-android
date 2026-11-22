package app.streambird.android.brand

import app.streambird.android.ads.AdPlacement

/**
 * What the screens show while ONE file is being uploaded (the app uploads one at a time): which slot it belongs to ([slotKey], see [UploadKeys]),
 * the picked file's name once known, and the bytes sent so far. Pure data, JVM-tested; the ViewModel holds it so it survives rotation and navigation.
 * [total] <= 0 means the size is unknown (indeterminate loader).
 */
data class UploadState(
    val slotKey: String,
    val kind: String,
    val fileName: String? = null,
    val sent: Long = 0L,
    val total: Long = 0L,
) {
    /** 0..1 when the size is known, null (indeterminate) otherwise. */
    val fraction: Float? get() = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else null
    val percent: Int? get() = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }

    /** The text TalkBack announces (politely, once): "Uploading logo.png"; the percentage is deliberately NOT in it (it would be read on every change). */
    val announcement: String get() = if (fileName.isNullOrBlank()) "Uploading" else "Uploading $fileName"

    /** "Uploading logo.png 42%" for the screen. */
    val visibleText: String get() = announcement + (percent?.let { " $it%" } ?: "...")

    fun withName(name: String?) = copy(fileName = name?.takeIf { it.isNotBlank() })
    fun withProgress(sent: Long, total: Long) = copy(sent = sent.coerceAtLeast(0L), total = total.coerceAtLeast(0L))
}

/** Stable keys tying an upload to the slot / button that started it. */
object UploadKeys {
    fun logo(o: BrandOrientation) = "logo:${o.wire}"
    fun wallpaper(o: BrandOrientation) = "wallpaper:${o.wire}"
    const val THUMBNAIL = "thumbnail"
    /** [index] = the ad row being replaced; null = "+ Add another". */
    fun ad(o: BrandOrientation, p: AdPlacement, index: Int?) = "ad:${o.wire}:${p.wire}:${index ?: "add"}"
    const val LIBRARY_LOGO = "library:logo"
    const val LIBRARY_AD = "library:ad"
    /** The "+ Add another" upload of a placement, whichever row asked. */
    fun isAdd(key: String) = key.endsWith(":add")
}

/**
 * Whether a progress callback should reach the UI: the first and last step always, otherwise only when the whole percentage moved
 * (an 8 MB upload in 64 KB steps is 128 callbacks; the UI needs at most 100, and Compose should not recompose per chunk).
 * [lastPercent] is -1 before anything was reported.
 */
fun shouldReportProgress(lastPercent: Int, sent: Long, total: Long): Boolean {
    if (total <= 0) return lastPercent < 0 // unknown size: tell once
    val p = (sent * 100 / total).toInt().coerceIn(0, 100)
    return lastPercent < 0 || p != lastPercent || sent >= total
}
