package app.streambird.android.media

/** Port of web-app/src/studio/sendQuality.ts: what the encoder is really sending, classified. Pure Kotlin. */

data class SendSample(
    val width: Int,
    val height: Int,
    val fps: Double,
    /** null until two samples exist to take a delta from. */
    val bitrateBps: Double?,
    /** null when WebRTC reports "none". */
    val limitationReason: String?,
)

data class SendQuality(val limited: Boolean, val summary: String, val message: String?)

/** A sent picture smaller than this share of the chosen size counts as scaled down (web: SCALED_DOWN_RATIO). */
const val SCALED_DOWN_RATIO = 0.75

fun formatBitrate(bps: Double?): String = when {
    bps == null || !bps.isFinite() || bps < 0 -> "-- Mbps"
    bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000)
    else -> "${Math.round(bps / 1000)} kbps"
}

fun classifySendQuality(sample: SendSample, expectedWidth: Int, expectedHeight: Int, warmingUp: Boolean = false): SendQuality {
    val summary = "Sending ${sample.width}x${sample.height} - ${Math.round(sample.fps)} fps - ${formatBitrate(sample.bitrateBps)}"
    when (sample.limitationReason) {
        "bandwidth" -> return SendQuality(true, summary, "Your connection is limiting quality. Try 720p, picture priority 'Sharp', or a better network.")
        "cpu" -> return SendQuality(true, summary, "The phone is struggling (CPU/thermal). Try a lower quality or let it cool.")
        "other" -> return SendQuality(true, summary, "The encoder is limiting quality for another reason.")
    }
    // Compare the short side so a vertical (9:16) stream is judged like a landscape one (same rule as the web).
    val sentShort = minOf(sample.width, sample.height)
    val wantShort = minOf(expectedWidth, expectedHeight)
    if (!warmingUp && sentShort > 0 && wantShort > 0 && sentShort < wantShort * SCALED_DOWN_RATIO) {
        return SendQuality(true, summary, "The picture is being sent smaller than the ${wantShort}p you chose.")
    }
    return SendQuality(false, summary, null)
}
