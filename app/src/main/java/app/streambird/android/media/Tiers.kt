package app.streambird.android.media

/** Plan resolution names as returned by GET /plans/me (`maxResolution`). */
private val PLAN_RANK = mapOf("sd" to 0, "hd" to 1, "fhd" to 2, "qhd" to 3)

/**
 * Portrait (9:16) tiers. Bitrates are the web studio's RESOLUTIONS (web-app/src/studio/useHostStudio.ts): 1 / 3 / 6 Mbps.
 * The web "sd" tier is 360p; the app's smallest tier is 540p, so a plan limited to "sd" still gets 540p (see docs/BACKEND-NEEDS.md).
 */
enum class StreamTier(val label: String, val width: Int, val height: Int, val maxBitrateBps: Int, val minPlan: String, val captureWidth: Int, val captureHeight: Int) {
    P540("540p", 540, 960, 1_000_000, "sd", 1280, 720),
    P720("720p", 720, 1280, 3_000_000, "hd", 1280, 720),
    P1080("1080p", 1080, 1920, 6_000_000, "fhd", 1920, 1080);

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

/** Which brand kit applies: the one assigned to the single chosen channel, else the account default, else the first. */
fun <T> pickBrandKit(kits: List<T>, selectedConnectionIds: Set<String>, isDefault: (T) -> Boolean, connectionIds: (T) -> List<String>): T? {
    if (kits.isEmpty()) return null
    if (selectedConnectionIds.size == 1) {
        kits.firstOrNull { selectedConnectionIds.first() in connectionIds(it) }?.let { return it }
    }
    return kits.firstOrNull(isDefault) ?: kits.first()
}
