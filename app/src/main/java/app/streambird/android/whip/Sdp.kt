package app.streambird.android.whip

/** Small, pure SDP helpers (no Android/WebRTC types, so they are unit-tested on the JVM). */
object Sdp {
    private val rtpmap = Regex("""^a=rtpmap:(\d+)\s+([A-Za-z0-9\-]+)/""")
    private val fmtpApt = Regex("""^a=fmtp:(\d+)\s+.*\bapt=(\d+)""")

    fun hasH264(sdp: String): Boolean = videoPayloads(sdp).any { it.codec.equals("H264", ignoreCase = true) }

    data class Payload(val pt: Int, val codec: String)

    fun videoPayloads(sdp: String): List<Payload> {
        val out = mutableListOf<Payload>()
        var inVideo = false
        for (line in sdp.lines().map { it.trimEnd('\r') }) {
            if (line.startsWith("m=")) inVideo = line.startsWith("m=video")
            else if (inVideo) rtpmap.find(line)?.let { out += Payload(it.groupValues[1].toInt(), it.groupValues[2]) }
        }
        return out
    }

    /**
     * Reorders the payload types on the `m=video` line so H.264 (and its RTX partners) come first. The downstream path is
     * RTMP/FLV via `ffmpeg -c:v copy`, which only carries H.264. The answerer picks the first mutually supported codec,
     * so this is what makes MediaMTX answer with H.264. No H.264 in the offer -> returned unchanged.
     */
    fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n", "\n")
        val crlf = sdp.contains("\r\n")
        val mIdx = lines.indexOfFirst { it.startsWith("m=video") }
        if (mIdx < 0) return sdp
        // Section end
        var end = lines.size
        for (i in mIdx + 1 until lines.size) if (lines[i].startsWith("m=")) { end = i; break }
        val section = lines.subList(mIdx, end)
        val h264 = section.mapNotNull { rtpmap.find(it) }.filter { it.groupValues[2].equals("H264", true) }.map { it.groupValues[1] }.toSet()
        if (h264.isEmpty()) return sdp
        val rtx = section.mapNotNull { fmtpApt.find(it) }.filter { it.groupValues[2] in h264 }.map { it.groupValues[1] }.toSet()
        val parts = lines[mIdx].split(" ")
        if (parts.size < 4) return sdp
        val head = parts.subList(0, 3)
        val pts = parts.subList(3, parts.size)
        val ordered = pts.filter { it in h264 } + pts.filter { it in rtx } + pts.filter { it !in h264 && it !in rtx }
        val newLines = lines.toMutableList()
        newLines[mIdx] = (head + ordered).joinToString(" ")
        return newLines.joinToString(if (crlf) "\r\n" else "\n")
    }
}
