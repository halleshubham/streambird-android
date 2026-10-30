package app.streambird.android.media

/** One entry of an RTCStatsReport, decoupled from org.webrtc so the parsing is JVM-testable. */
data class RawStat(val type: String, val id: String, val timestampUs: Double, val members: Map<String, Any?>)

data class EncoderStats(
    val width: Int,
    val height: Int,
    val fps: Double,
    val bytesSent: Long,
    val timestampMs: Double,
    val limitationReason: String?,
    val encoderImpl: String?,
    val powerEfficientEncoder: Boolean?,
    val framesEncoded: Long,
    val keyFramesEncoded: Long,
    val nackCount: Long,
    val pliCount: Long,
    val retransmittedPackets: Long,
    val rttMs: Double?,
    val lossPct: Double?,
    val availableOutgoingBps: Double?,
    /** Average encode time per frame in ms (totalEncodeTime / framesEncoded). */
    val avgEncodeMs: Double?,
)

object StatsParser {
    private fun Map<String, Any?>.num(k: String): Double? = (this[k] as? Number)?.toDouble()
    private fun Map<String, Any?>.long(k: String): Long = (this[k] as? Number)?.toLong() ?: 0L

    /** Null until an outbound video RTP stream with a real frame size exists. */
    fun parse(stats: Collection<RawStat>): EncoderStats? {
        val out = stats.firstOrNull { it.type == "outbound-rtp" && ((it.members["kind"] ?: it.members["mediaType"]) == "video") } ?: return null
        val m = out.members
        val w = m.num("frameWidth")?.toInt() ?: 0
        val h = m.num("frameHeight")?.toInt() ?: 0
        if (w == 0 || h == 0) return null
        val reason = (m["qualityLimitationReason"] as? String)?.takeIf { it != "none" }
        val remote = stats.firstOrNull { it.type == "remote-inbound-rtp" && ((it.members["kind"] ?: it.members["mediaType"]) == "video") }
        val pair = stats.firstOrNull { it.type == "candidate-pair" && (it.members["nominated"] == true || it.members["state"] == "succeeded") && it.members.num("currentRoundTripTime") != null }
        val rttS = remote?.members?.num("roundTripTime") ?: pair?.members?.num("currentRoundTripTime")
        val frames = m.long("framesEncoded")
        val encodeTime = m.num("totalEncodeTime")
        return EncoderStats(
            width = w, height = h,
            fps = m.num("framesPerSecond") ?: 0.0,
            bytesSent = m.long("bytesSent"),
            timestampMs = out.timestampUs / 1000.0,
            limitationReason = reason,
            encoderImpl = m["encoderImplementation"] as? String,
            powerEfficientEncoder = m["powerEfficientEncoder"] as? Boolean,
            framesEncoded = frames,
            keyFramesEncoded = m.long("keyFramesEncoded"),
            nackCount = m.long("nackCount"),
            pliCount = m.long("pliCount"),
            retransmittedPackets = m.long("retransmittedPacketsSent"),
            rttMs = rttS?.times(1000.0),
            lossPct = remote?.members?.num("fractionLost")?.times(100.0),
            availableOutgoingBps = pair?.members?.num("availableOutgoingBitrate"),
            avgEncodeMs = if (encodeTime != null && frames > 0) encodeTime / frames * 1000.0 else null,
        )
    }

    /** bits/s between two readings; null when it cannot be worked out (same rule as web bitrateBetween). */
    fun bitrateBetween(prev: EncoderStats?, cur: EncoderStats): Double? {
        if (prev == null || cur.timestampMs <= prev.timestampMs || cur.bytesSent < prev.bytesSent) return null
        return (cur.bytesSent - prev.bytesSent) * 8.0 * 1000.0 / (cur.timestampMs - prev.timestampMs)
    }

    /**
     * True when the encoder name says hardware. libwebrtc reports "MediaCodec" (Java HardwareVideoEncoder, hardware),
     * "OpenH264"/"libvpx"/"SimulcastEncoderAdapter (...)" for software ones. [powerEfficient] wins when present.
     */
    fun isHardwareEncoder(impl: String?, powerEfficient: Boolean? = null): Boolean? {
        if (powerEfficient != null) return powerEfficient
        if (impl == null) return null
        val s = impl.lowercase()
        return when {
            "mediacodec" in s || "hwencoder" in s || "hardware" in s || "c2.qti" in s || "omx.qcom" in s || "exynos" in s || "c2.exynos" in s || "mtk" in s -> true
            "openh264" in s || "libvpx" in s || "ffmpeg" in s || "software" in s || "c2.android" in s || "omx.google" in s -> false
            else -> null
        }
    }
}
