package app.streambird.android.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

data class H264EncoderInfo(val name: String, val hardware: Boolean)

/** What H.264 encoders this phone exposes (independent of libwebrtc). Shown on the setup screen. */
object CodecProbe {
    fun h264Encoders(): List<H264EncoderInfo> = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", ignoreCase = true) } }
            .map { H264EncoderInfo(it.name, isHardware(it)) }
    }.getOrDefault(emptyList())

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))

    fun summary(): String {
        val all = h264Encoders()
        if (all.isEmpty()) return "No H.264 encoder found on this phone"
        val hw = all.filter { it.hardware }
        return if (hw.isEmpty()) "H.264: software only (${all.joinToString { it.name }})"
        else "H.264 hardware: ${hw.joinToString { it.name }}"
    }
}
