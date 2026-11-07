package app.streambird.android.media

import android.graphics.Bitmap

/**
 * The decoded frames of an animated GIF logo and the wall-clock schedule they play on. Built once before/just after going live
 * ([LogoAnimationLoader]), read by the compositor on the GL thread (it uploads the current frame to a texture only when the frame changes).
 * UNVERIFIED on a device: the pure parts (parse, frame selection, delay merging, LZW decode) are JVM-tested; the bitmaps and GL upload are compiled only.
 */
class LogoAnimation(val frames: List<Bitmap>, delaysMs: IntArray) {
    val width = frames.first().width
    val height = frames.first().height
    private val ends = cumulativeEnds(delaysMs)
    val durationMs get() = ends.last()

    /** Set (elapsedRealtime) when the animation is handed to the compositor: frame 0 shows then, the loop follows the wall clock. */
    @Volatile var startedAtMs = 0L

    fun indexAt(nowMs: Long): Int = frameIndexAt(ends, nowMs - startedAtMs)

    fun recycle() { frames.forEach { runCatching { it.recycle() } } }
}

sealed interface LogoAnimationResult {
    class Ready(val animation: LogoAnimation, val plan: GifLogoPlan.Animate) : LogoAnimationResult
    /** [reason] is shown to the host ("This GIF cannot animate ..."); null = a one-frame GIF, nothing to say. */
    class Still(val reason: String?) : LogoAnimationResult
}

object LogoAnimationLoader {
    /** Blocking, CPU heavy (run it off the main thread). Never throws: any failure (damaged GIF, out of memory) is a [LogoAnimationResult.Still]. */
    fun load(bytes: ByteArray, logoSizePx: Int): LogoAnimationResult {
        val timing = parseGifTiming(bytes)
        val plan = planGifLogo(timing, logoSizePx)
        if (plan !is GifLogoPlan.Animate || timing == null) return LogoAnimationResult.Still((plan as? GifLogoPlan.Still)?.reason)
        val frames = ArrayList<Bitmap>(plan.frames.size)
        return try {
            var got = 0
            val sw = timing.width; val sh = timing.height
            decodeGif(bytes, plan.frames.sourceIndices) { _, argb ->
                val full = Bitmap.createBitmap(argb, sw, sh, Bitmap.Config.ARGB_8888)   // copies the pixels
                if (sw == plan.targetW && sh == plan.targetH) frames.add(full)
                else { frames.add(Bitmap.createScaledBitmap(full, plan.targetW, plan.targetH, true)); full.recycle() }
                got++
            }
            if (got != plan.frames.size) { frames.forEach { it.recycle() }; return LogoAnimationResult.Still("the GIF could not be fully read") }
            LogoAnimationResult.Ready(LogoAnimation(frames, plan.frames.delaysMs), plan)
        } catch (t: Throwable) {
            frames.forEach { runCatching { it.recycle() } }
            LogoAnimationResult.Still(if (t is OutOfMemoryError) "the phone ran out of memory decoding it" else "the GIF could not be fully read")
        }
    }
}
