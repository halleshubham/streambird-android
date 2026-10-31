package app.streambird.android.media

/** Plan resolution names as returned by GET /plans/me (`maxResolution`). */
private val PLAN_RANK = mapOf("sd" to 0, "hd" to 1, "fhd" to 2, "qhd" to 3)

/** Stream shape; immutable per stream (the backend fixes `orientation` at creation). `wire` is the API value. */
enum class StreamOrientation(val wire: String, val label: String) {
    PORTRAIT("portrait", "Portrait (9:16)"),
    LANDSCAPE("landscape", "Landscape (16:9)");

    val isLandscape get() = this == LANDSCAPE
}

/**
 * Tiers. Landscape sizes are the web's RESOLUTIONS (web-app/src/studio/useHostStudio.ts: hd 1280x720, fhd 1920x1080);
 * portrait swaps width and height exactly like `canvasSizeFor` (compose.ts). Bitrates are the web's: 1 / 3 / 6 Mbps.
 * The web "sd" tier is 360p; the app's smallest tier is 540p (960x540), so a plan limited to "sd" still gets 540p (see docs/BACKEND-NEEDS.md).
 * [longSide] x [shortSide] is the landscape size.
 */
enum class StreamTier(val label: String, val longSide: Int, val shortSide: Int, val maxBitrateBps: Int, val minPlan: String, val captureWidth: Int, val captureHeight: Int) {
    P540("540p", 960, 540, 1_000_000, "sd", 1280, 720),
    P720("720p", 1280, 720, 3_000_000, "hd", 1280, 720),
    P1080("1080p", 1920, 1080, 6_000_000, "fhd", 1920, 1080);

    fun width(o: StreamOrientation) = if (o.isLandscape) longSide else shortSide
    fun height(o: StreamOrientation) = if (o.isLandscape) shortSide else longSide
    fun sizeLabel(o: StreamOrientation) = "${width(o)}x${height(o)}"

    fun allowedBy(planMaxResolution: String): Boolean =
        (PLAN_RANK[planMaxResolution] ?: 0) >= (PLAN_RANK[minPlan] ?: 0)

    /** Start-up bandwidth hint so the estimator does not spend a minute ramping from ~300 kbps. */
    val startBitrateBps: Int get() = (maxBitrateBps * 0.7).toInt()

    companion object {
        fun allowed(planMaxResolution: String): List<StreamTier> = entries.filter { it.allowedBy(planMaxResolution) }
        fun best(planMaxResolution: String): StreamTier = allowed(planMaxResolution).lastOrNull() ?: P540
    }
}

/** Like the web studio's "Picture priority" (degradationPreference). */
enum class PicturePriority(val label: String, val hint: String) {
    MAINTAIN_RESOLUTION("Sharp picture", "Keep the picture size; the frame rate drops first when the network is tight."),
    BALANCED("Balanced", "Let the encoder shrink the picture and the frame rate as it likes.");
}

/** How many destinations may be ticked (GET /plans/me maxDestinations). */
fun canSelectMore(selectedCount: Int, maxDestinations: Int): Boolean = selectedCount < maxDestinations

/**
 * Which brand kit applies (web studio: the kit of the stream's destination channel, else the account default).
 * Destinations are walked in the order they were ticked; the first one that has a kit assigned wins. Else the default, else the first kit.
 */
fun <T> pickBrandKit(kits: List<T>, selectedConnectionIds: Collection<String>, isDefault: (T) -> Boolean, connectionIds: (T) -> List<String>): T? {
    if (kits.isEmpty()) return null
    for (id in selectedConnectionIds) kits.firstOrNull { id in connectionIds(it) }?.let { return it }
    return kits.firstOrNull(isDefault) ?: kits.first()
}
