package app.streambird.android.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import org.webrtc.CapturerObserver
import org.webrtc.GlRectDrawer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame
import org.webrtc.YuvConverter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A logo to burn into every frame. Geometry follows web-app useHostStudio.ts drawLogo: height = [heightPx] canvas px, 16 px margin. */
class LogoOverlay(val bitmap: Bitmap, val onRight: Boolean, val heightPx: Int) {
    /**
     * Frames of an animated GIF logo, set (from any thread) once they are decoded; until then, and whenever it is null, [bitmap] (the first frame) is drawn.
     * The compositor picks the frame from the wall clock and uploads it to a texture only when it changes.
     */
    @Volatile var animation: LogoAnimation? = null
}

/**
 * GPU compositing step between the camera and the WebRTC VideoSource.
 *
 *   Camera2 -> SurfaceTextureHelper (OES texture, GL thread) -> [FrameCompositor] -> VideoSource -> HW H.264 encoder
 *
 * For each camera frame it (on the camera's GL thread, whose EGL context is shared with the encoder):
 *  1. picks a free RGBA texture from a small ring and renders into it through an FBO,
 *  2. draws the camera frame (rotation + sensor transform handled by VideoFrameDrawer), aspect-FILL cropped to the output size,
 *  3. draws the logo (alpha blended),
 *  4. wraps the texture in a TextureBufferImpl (RGB, identity matrix) and hands it on. The buffer's release callback frees the slot.
 * If every slot is still held by the encoder/preview, the frame is dropped (counted in [droppedFrames]) rather than blocking.
 *
 * UNVERIFIED ON A DEVICE: written against the libwebrtc API and its documented conventions, compiled but never run here.
 */
class FrameCompositor(
    private val target: CapturerObserver,
    private val helper: SurfaceTextureHelper,
    private val outWidth: Int,
    private val outHeight: Int,
    private val logo: LogoOverlay?,
    /** Camera drivers/libwebrtc mirror front-camera frames for a selfie preview; the broadcast should not be mirrored. */
    unmirror: Boolean,
) : CapturerObserver {

    private class Slot(val texture: Int, val fbo: Int) { val busy = AtomicBoolean(false) }

    private var drawer: GlRectDrawer? = null
    private var frameDrawer: org.webrtc.VideoFrameDrawer? = null
    private var yuv: YuvConverter? = null
    private var slots: List<Slot> = emptyList()
    private var logoTexture = 0
    // animated GIF logo: one texture, re-filled only when the wall-clock frame changes
    private var animTexture = 0
    private var animSource: LogoAnimation? = null
    private var animShown = -1
    private var released = false

    /** Front-camera frames arrive mirrored (selfie style); the published picture must not be. Changed by a live camera switch. */
    @Volatile var unmirror: Boolean = unmirror

    /** News ticker to draw (null = none). Set from any thread; the bitmap is only re-rendered on the GL thread when its [TickerSpec.version] changes. */
    @Volatile var ticker: TickerSpec? = null
    /** Advertisements (null = none: the frame is then exactly what it was before ads existed). */
    @Volatile var ads: AdLayerGl? = null
    private var tickerTex = 0
    private var tickerBarTex = 0
    private var tickerVersion = -1L
    private var tickerTexW = 0
    private var tickerBarPx = 0

    /** Frames skipped because all texture slots were still held downstream (encoder start-up, slow preview). */
    @Volatile var droppedNoSlot = 0L; private set
    /** Frames lost to an exception in the GL code (should stay 0). */
    @Volatile var droppedError = 0L; private set
    @Volatile var composedFrames = 0L; private set
    /** composedFrames at the time of the most recent drop: a value near 0 means the drops were start-up only. */
    @Volatile var lastDropAtFrame = -1L; private set
    @Volatile var lastError: String? = null; private set
    val droppedFrames: Long get() = droppedNoSlot + droppedError

    override fun onCapturerStarted(success: Boolean) = target.onCapturerStarted(success)
    override fun onCapturerStopped() = target.onCapturerStopped()

    /** Called on the SurfaceTextureHelper thread with its EGL context current. */
    override fun onFrameCaptured(frame: VideoFrame) {
        if (released) return
        try {
            ensureGl()
            val slot = slots.firstOrNull { it.busy.compareAndSet(false, true) }
            if (slot == null) { droppedNoSlot++; lastDropAtFrame = composedFrames; return }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.fbo)
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Ads first advance their clock/fades (no GL) so the scene can make room for a visible lower/left third ad (picture shrink).
            val layer = ads
            layer?.update(if (ticker != null) tickerBarPx else 0)
            layer?.drawBase()   // an L-shaped frame is the base layer: drawn under the scene, which then goes into its window
            val sc = layer?.sceneRect()
            val full = sc == null || (sc.w >= outWidth && sc.h >= outHeight)
            val sx = if (full) 0 else Math.round(sc!!.x).toInt()
            val sy = if (full) 0 else outHeight - Math.round(sc!!.y + sc.h).toInt() // GL origin is bottom-left
            val sw = if (full) outWidth else Math.round(sc!!.w).toInt().coerceAtLeast(1)
            val sh = if (full) outHeight else Math.round(sc!!.h).toInt().coerceAtLeast(1)
            frameDrawer!!.drawFrame(frame, drawer!!, cropMatrix(frame), sx, sy, sw, sh)
            drawLogo(sx, sy, sw.toFloat() / outWidth)
            drawTicker()
            layer?.draw(if (ticker != null) tickerBarPx else 0)   // after camera, logo and ticker; the Free "Powered by" mark is drawn last inside
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            // The texture is consumed from other GL contexts (encoder, preview): make sure the writes are visible.
            GLES20.glFinish()

            val buffer = TextureBufferImpl(
                outWidth, outHeight, VideoFrame.TextureBuffer.Type.RGB, slot.texture, Matrix(),
                helper.handler, yuv, Runnable { slot.busy.set(false) },
            )
            val out = VideoFrame(buffer, 0, frame.timestampNs)
            target.onFrameCaptured(out)
            out.release()
            composedFrames++
        } catch (t: Throwable) {
            // Never throw into the camera thread; the Live screen shows the drop counter.
            droppedError++; lastDropAtFrame = composedFrames; lastError = t.toString()
        }
    }

    /** Aspect-fill crop in OUTPUT (post-rotation) texture space, optionally cancelling the front-camera mirror. */
    private fun cropMatrix(frame: VideoFrame): Matrix {
        val sx = cropScale(frame.rotatedWidth, frame.rotatedHeight, outWidth, outHeight)
        return Matrix().apply { setScale(if (unmirror) -sx.first else sx.first, sx.second, 0.5f, 0.5f) }
    }

    private var glFailures = 0

    /** Builds all GL objects into locals and publishes them only when everything worked, so a failure is retried on the next frame (and counted) instead of leaving a half-built state. */
    private fun ensureGl() {
        if (drawer != null) return
        val d = GlRectDrawer()
        val fd = org.webrtc.VideoFrameDrawer()
        val y = YuvConverter()
        try {
            val ring = List(RING) { newSlot() }
            logo?.let { logoTexture = uploadLogo(it.bitmap) }
            slots = ring; frameDrawer = fd; yuv = y; drawer = d
        } catch (t: Throwable) {
            glFailures++
            runCatching { d.release(); fd.release(); y.release() }
            lastError = "GL init failed ($glFailures): $t"
            throw t
        }
    }

    private fun newSlot(): Slot {
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outWidth, outHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        setupTex()
        val fbo = IntArray(1); GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return Slot(tex[0], fbo[0])
    }

    private fun uploadLogo(bmp: Bitmap): Int {
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        setupTex()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return tex[0]
    }

    /**
     * Bar + scrolling text at the bottom, like the web's drawNewsline. The text is a prebuilt bitmap texture (rebuilt only when the
     * text/size changes); per frame it is two textured quads positioned from the wall clock, no bitmap work.
     */
    private fun drawTicker() {
        val spec = ticker
        if (spec == null) { if (tickerTex != 0) freeTicker(); return }
        if (spec.version != tickerVersion || tickerTex == 0) buildTicker(spec)
        val now = SystemClock.elapsedRealtime()
        val slide = Ticker.slideOffsetPx(spec.entranceAtMs?.let { now - it }, tickerBarPx)
        val y = -slide.toInt() // GL origin is bottom-left: the bar rests at y = 0 and slides up from below the frame
        val x = Ticker.scrollX(now - spec.startedAtMs, outWidth, tickerTexW.toFloat()).toInt()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawer!!.drawRgb(tickerBarTex, FLIP_Y, 1, 1, 0, y, outWidth, tickerBarPx)
        if (x < outWidth && x + tickerTexW > 0) drawer!!.drawRgb(tickerTex, FLIP_Y, tickerTexW, tickerBarPx, x, y, tickerTexW, tickerBarPx)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun buildTicker(spec: TickerSpec) {
        freeTicker()
        val m = Ticker.metrics(spec.size, outWidth, outHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = m.fontPx; typeface = Typeface.SANS_SERIF }
        val natural = paint.measureText(spec.text)
        val w = Math.ceil(natural.toDouble()).toInt().coerceIn(1, Ticker.MAX_TEXTURE_WIDTH)
        if (natural > w) paint.textScaleX = w / natural
        val bmp = Bitmap.createBitmap(w, m.barPx, Bitmap.Config.ARGB_8888)
        val fm = paint.fontMetrics
        Canvas(bmp).drawText(spec.text, 0f, m.barPx / 2f - (fm.ascent + fm.descent) / 2f, paint)
        tickerTex = uploadLogo(bmp)
        bmp.recycle()
        val bar = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(Ticker.BAR_ALPHA, 0, 0, 0)) }
        tickerBarTex = uploadLogo(bar)
        bar.recycle()
        tickerTexW = w; tickerBarPx = m.barPx; tickerVersion = spec.version
    }

    private fun freeTicker() {
        if (tickerTex != 0) GLES20.glDeleteTextures(1, intArrayOf(tickerTex), 0)
        if (tickerBarTex != 0) GLES20.glDeleteTextures(1, intArrayOf(tickerBarTex), 0)
        tickerTex = 0; tickerBarTex = 0; tickerVersion = -1; tickerBarPx = 0
    }

    private fun setupTex() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** The logo is part of the scene: when the picture shrinks for an ad it moves and scales with it ([offX]/[offY] = scene origin in px, GL bottom-left; [scale] 1 = full frame). */
    private fun drawLogo(offX: Int = 0, offY: Int = 0, scale: Float = 1f) {
        val l = logo ?: return
        if (logoTexture == 0) return
        val h = l.heightPx.coerceIn(8, outHeight)
        val w = (l.bitmap.width.toFloat() / l.bitmap.height * h).toInt().coerceAtMost(outWidth - 2 * MARGIN)
        val x = if (l.onRight) outWidth - MARGIN - w else MARGIN
        val y = outHeight - MARGIN - h // GL origin is bottom-left; the web draws the logo 16 px from the TOP.
        val tex = currentLogoTexture(l)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // Android bitmaps are premultiplied
        if (scale == 1f && offX == 0 && offY == 0) drawer!!.drawRgb(tex, FLIP_Y, l.bitmap.width, l.bitmap.height, x, y, w, h)
        else drawer!!.drawRgb(tex, FLIP_Y, l.bitmap.width, l.bitmap.height, offX + (x * scale).toInt(), offY + (y * scale).toInt(), (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * The texture to draw the logo with: the static one, or, for an animated GIF logo, the frame the wall clock selects (frame loop in
     * [LogoAnimation]; uploaded only when the frame index changes). Any problem falls back to the static first frame.
     */
    private fun currentLogoTexture(l: LogoOverlay): Int {
        val anim = l.animation ?: return logoTexture
        if (anim.startedAtMs == 0L) return logoTexture
        return try {
            val idx = anim.indexAt(SystemClock.elapsedRealtime())
            val bmp = anim.frames[idx]
            if (bmp.isRecycled) return logoTexture
            if (animSource !== anim || animTexture == 0) {
                if (animTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(animTexture), 0)
                animTexture = uploadLogo(bmp); animSource = anim; animShown = idx
            } else if (idx != animShown) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, animTexture)
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
                animShown = idx
            }
            animTexture
        } catch (t: Throwable) {
            l.animation = null // never break the frame for a logo effect: back to the still first frame
            logoTexture
        }
    }

    /** Must be called before the helper is disposed; runs the GL clean-up on the camera thread. */
    fun release() {
        released = true // no new frames from now on
        // The encoder / preview may still hold texture buffers: wait (bounded) until every ring slot was handed back before deleting textures.
        val deadline = System.currentTimeMillis() + 1500
        while (slots.any { it.busy.get() } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val done = CountDownLatch(1)
        helper.handler.post {
            released = true
            runCatching {
                slots.forEach {
                    GLES20.glDeleteFramebuffers(1, intArrayOf(it.fbo), 0)
                    GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0)
                }
                if (logoTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(logoTexture), 0)
                if (animTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(animTexture), 0)
                animTexture = 0; animSource = null
                freeTicker()
                runCatching { ads?.release() }
                drawer?.release(); frameDrawer?.release(); yuv?.release()
            }
            slots = emptyList(); drawer = null
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
    }

    companion object {
        private const val RING = 6
        private const val MARGIN = 16

        /** Bitmaps are uploaded top-row-first, GL textures are bottom-row-first: flip t. Column-major 4x4. */
        private val FLIP_Y = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

        /** Fractions (sx, sy) of the source that stay visible when aspect-filling srcW x srcH into outW x outH. Pure, unit-tested. */
        fun cropScale(srcW: Int, srcH: Int, outW: Int, outH: Int): Pair<Float, Float> {
            val src = srcW.toFloat() / srcH
            val out = outW.toFloat() / outH
            return if (src > out) Pair(out / src, 1f) else Pair(1f, src / out)
        }
    }
}
