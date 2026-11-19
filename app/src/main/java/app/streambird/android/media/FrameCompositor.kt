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
import app.streambird.android.clock.ClockLayout
import app.streambird.android.clock.ClockRect
import app.streambird.android.clock.ClockSettings
import app.streambird.android.clock.ClockSize
import app.streambird.android.clock.ClockStyle
import app.streambird.android.clock.clockBucket
import app.streambird.android.clock.clockDateAlpha
import app.streambird.android.clock.clockTimeAlpha
import app.streambird.android.clock.formatClock
import java.time.Instant
import java.util.Locale
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
class LogoOverlay(val bitmap: Bitmap, onRight: Boolean, heightPx: Int) {
    /** Side and height in canvas px: changeable while live (remote `logo.set`, 1.0.15); the compositor reads them on every frame, so no frame is dropped. */
    @Volatile var onRight: Boolean = onRight
    @Volatile var heightPx: Int = heightPx

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
    logo: LogoOverlay?,
    /** Camera drivers/libwebrtc mirror front-camera frames for a selfie preview; the broadcast should not be mirrored. */
    unmirror: Boolean,
) : CapturerObserver {

    private class Slot(val texture: Int, val fbo: Int) { val busy = AtomicBoolean(false) }

    private var drawer: GlRectDrawer? = null
    private var frameDrawer: org.webrtc.VideoFrameDrawer? = null
    private var yuv: YuvConverter? = null
    private var slots: List<Slot> = emptyList()
    /** The logo being drawn (null = none). Replaced only by [swapKit] on the render thread, between two frames. */
    @Volatile private var logo: LogoOverlay? = logo
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

    /** Date and time overlay (null or not enabled = none). Set from any thread; the pill is re-rendered on the GL thread only when the shown text changes. */
    @Volatile var clock: ClockSettings? = null
    private var clockTex = 0
    private var clockTexW = 0
    private var clockTexH = 0
    private var clockShown: ClockSettings? = null
    private var clockBucketShown = Long.MIN_VALUE
    private var clockBitmap: ClockBitmap? = null
    private var clockBitmapSize: ClockSize? = null
    private val clockMeasure = IntArray(2)
    private var clockRectPx: ClockRect? = null
    private var clockBarShown = -1
    private var clockFailedFor: ClockSettings? = null
    // compact TEXT style: two textures (A = the time, or date and time on one line; B = the date while alternating), each re-uploaded only when its string changes
    private class ClockSlot { var tex = 0; var w = 0; var h = 0; var key: String? = null; var rect: ClockRect? = null }
    private val slotA = ClockSlot()
    private val slotB = ClockSlot()
    private var clockQuad: ClockQuadGl? = null
    private var clockPoweredShown = -1
    private var clockLogoShown = 0
    /** Where the logo is drawn now (the clock keeps clear of it); follows a live logo change. Allocates: used on re-placement only. */
    private fun clockLogoRect(): ClockRect? =
        logo?.let { ClockLayout.logoRect(outWidth, outHeight, it.bitmap.width, it.bitmap.height, it.heightPx, it.onRight) }
    /** A cheap per-frame key of the logo's picture, side and height: the clock re-places itself when it changes. */
    private fun logoKey(): Int { val l = logo ?: return 0; return 31 * (31 * System.identityHashCode(l.bitmap) + l.heightPx) + if (l.onRight) 1 else 2 }

    /** Name banner to draw (null = none). Set from any thread; the bitmap is rebuilt on the render thread only when [BannerSpec.version] changes. */
    @Volatile var banner: BannerSpec? = null
    private var bannerTex = 0
    private var bannerW = 0
    private var bannerH = 0
    private var bannerVersion = -1L
    private var bannerAlpha = 0f
    private var bannerLastMs = 0L
    private var bannerMaker: NameBannerBitmap? = null
    private var bannerRect: ClockRect? = null
    private var bannerKeyBar = -1
    private var bannerKeyClock = 0
    private var bannerKeyPowered = -1
    private var bannerKeyLogo = 0
    private var bannerFailedFor = -1L
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
            drawClock()   // above scene, logo and ticker; below the ads (full-screen ad, Free "Powered by" mark), which are drawn next
            drawBanner()  // the name banner: above scene, logo, ticker and clock; below the ads
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

    /**
     * Date and time pill. Per frame this is one wall-clock read, two comparisons and one textured quad. The pill bitmap is rebuilt (and re-uploaded to the
     * same texture) only when the displayed text can have changed: the epoch second with seconds on, else the epoch minute ([clockBucket]), or when the
     * settings change. A failure here switches the clock off for those settings; it never drops the frame.
     */
    private fun drawClock() {
        val spec = clock
        if (spec == null || !spec.enabled) { if (clockTex != 0) freeClock(); return }
        if (clockFailedFor == spec) return
        try {
            val nowMs = System.currentTimeMillis()
            val bucket = clockBucket(nowMs, spec.showSeconds)
            val bar = if (ticker != null) tickerBarPx else 0
            if (clockShown != null && clockShown?.style != spec.style) freeClock() // the other style's textures are not needed any more
            if (spec.style == ClockStyle.TEXT) { drawClockText(spec, nowMs, bucket, bar); return }
            if (clockTex == 0 || spec != clockShown || bucket != clockBucketShown) buildClock(spec, nowMs, bucket, bar)
            else if (bar != clockBarShown || logoKey() != clockLogoShown) placeClock(spec, bar)
            val r = clockRectPx ?: return
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            drawer!!.drawRgb(clockTex, FLIP_Y, clockTexW, clockTexH, r.x, outHeight - r.y - r.h, r.w, r.h) // layout is top-left origin, GL bottom-left
            GLES20.glDisable(GLES20.GL_BLEND)
        } catch (t: Throwable) {
            clockFailedFor = spec; lastError = "clock overlay: $t"
            runCatching { freeClock() }
        }
    }

    private fun buildClock(spec: ClockSettings, nowMs: Long, bucket: Long, bar: Int) {
        val maker = clockMaker(spec)
        // Locale.getDefault(): weekday / month names follow the phone's language; the patterns are fixed (see formatClock).
        val text = formatClock(Instant.ofEpochMilli(nowMs), spec, Locale.getDefault())
        maker.measure(text, clockMeasure)
        val (pw, ph) = ClockLayout.pillSize(clockMeasure[0], clockMeasure[1], ClockLayout.fontPx(outHeight, spec.size))
        val bmp = maker.render(text, pw, ph)
        if (clockTex == 0) clockTex = uploadLogo(bmp)
        else { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, clockTex); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0) }
        bmp.recycle()
        clockTexW = pw; clockTexH = ph; clockShown = spec; clockBucketShown = bucket
        placeClock(spec, bar)
    }

    private fun placeClock(spec: ClockSettings, bar: Int) {
        clockRectPx = ClockLayout.clockRect(outWidth, outHeight, spec.position, spec.size, clockLogoRect(), bar, clockMeasure[0], clockMeasure[1])
        clockBarShown = bar; clockLogoShown = logoKey()
    }

    private fun clockMaker(spec: ClockSettings): ClockBitmap =
        clockBitmap?.takeIf { clockBitmapSize == spec.size } ?: ClockBitmap(outWidth, outHeight, spec.size).also { clockBitmap = it; clockBitmapSize = spec.size }

    /**
     * Compact TEXT style: no box. Slot A holds the time (or, with alternation off, the date and time on one line), slot B the date while alternating.
     * Strings are re-formatted once per [clockBucket] step; a slot's texture is re-uploaded only when its string actually changed, so a phase switch
     * costs nothing: per frame it is one wall-clock read, two alpha computations and up to two quads (both only during the 200 ms cross-fade).
     */
    private fun drawClockText(spec: ClockSettings, nowMs: Long, bucket: Long, bar: Int) {
        val pk = ads?.poweredKey ?: 0
        if (spec != clockShown || bucket != clockBucketShown) {
            if (spec != clockShown) { slotA.key = null; slotB.key = null }
            buildClockText(spec, nowMs)
            clockShown = spec; clockBucketShown = bucket
            placeClockText(spec, bar, pk)
        } else if (bar != clockBarShown || pk != clockPoweredShown || logoKey() != clockLogoShown) placeClockText(spec, bar, pk)
        val q = clockQuad ?: ClockQuadGl(outWidth, outHeight).also { clockQuad = it }
        drawSlot(q, slotA, clockTimeAlpha(nowMs, spec.alternate, spec.showDate))
        drawSlot(q, slotB, clockDateAlpha(nowMs, spec.alternate, spec.showDate))
    }

    private fun drawSlot(q: ClockQuadGl, s: ClockSlot, alpha: Float) {
        val r = s.rect ?: return
        if (alpha > 0.002f && s.tex != 0) q.draw(s.tex, r.x, outHeight - r.y - r.h, r.w, r.h, alpha) // layout is top-left origin, GL bottom-left
    }

    private fun buildClockText(spec: ClockSettings, nowMs: Long) {
        val maker = clockMaker(spec)
        val text = formatClock(Instant.ofEpochMilli(nowMs), spec, Locale.getDefault())
        val alternating = spec.alternate && spec.showDate
        val lead = if (!alternating && text.date != null) text.date + " · " else null
        fillSlot(maker, slotA, (lead ?: "") + "|" + text.compactTime, lead, text.compactTime)
        if (alternating) fillSlot(maker, slotB, text.shortDate ?: "", text.shortDate, null)
        else if (slotB.tex != 0) { GLES20.glDeleteTextures(1, intArrayOf(slotB.tex), 0); slotB.tex = 0; slotB.key = null; slotB.rect = null }
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw IllegalStateException("GL error while uploading the clock text")
    }

    private fun fillSlot(maker: ClockBitmap, s: ClockSlot, key: String, lead: String?, time: String?) {
        if (s.tex != 0 && key == s.key) return
        maker.measureText(lead, time, clockMeasure)
        val w = clockMeasure[0]; val h = clockMeasure[1]
        val bmp = maker.renderText(lead, time, w, h)
        if (s.tex == 0) s.tex = uploadLogo(bmp)
        else { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, s.tex); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0) }
        bmp.recycle()
        s.w = w; s.h = h; s.key = key
    }

    private fun placeClockText(spec: ClockSettings, bar: Int, poweredKey: Int) {
        val powered = if (poweredKey != 0) ads?.poweredRect(bar) else null
        val logoRect = clockLogoRect()
        for (s in arrayOf(slotA, slotB)) {
            s.rect = if (s.tex != 0 && s.key != null) ClockLayout.clockRect(outWidth, outHeight, spec.position, spec.size, logoRect, bar, s.w, s.h, ClockStyle.TEXT, powered) else null
        }
        clockBarShown = bar; clockPoweredShown = poweredKey; clockLogoShown = logoKey()
    }

    private fun freeClock() {
        if (clockTex != 0) GLES20.glDeleteTextures(1, intArrayOf(clockTex), 0)
        for (s in arrayOf(slotA, slotB)) { if (s.tex != 0) GLES20.glDeleteTextures(1, intArrayOf(s.tex), 0); s.tex = 0; s.key = null; s.rect = null }
        clockTex = 0; clockShown = null; clockBucketShown = Long.MIN_VALUE; clockRectPx = null; clockBarShown = -1; clockPoweredShown = -1; clockLogoShown = 0
    }

    // ---- name banner (1.0.15) ----

    /**
     * The name banner: a prebuilt bitmap (name + title on a dark rounded bar with a coloured edge) drawn as one quad with a fading opacity. The bitmap is
     * rebuilt only when the text changes ([BannerSpec.version]) and only when it is about to be drawn; per frame this is one clock read, one fade step and
     * one quad. Placement (pure [NameBannerLayout]) is recomputed only when the ticker bar, the clock's rectangle or the "Powered by" mark changed.
     * A failure here switches the banner off for that text; it never drops the frame.
     */
    private fun drawBanner() {
        val spec = banner
        val now = SystemClock.elapsedRealtime()
        val dt = if (bannerLastMs == 0L) 0L else (now - bannerLastMs).coerceIn(0L, 100L)
        bannerLastMs = now
        val target = if (spec != null && spec.visible) 1f else 0f
        if (bannerTex == 0 && target == 0f) { bannerAlpha = 0f; return }
        if (spec != null && spec.version == bannerFailedFor) return
        try {
            bannerAlpha = NameBanner.fadeStep(bannerAlpha, target, dt)
            if (bannerAlpha <= NameBannerLayout.MIN_ALPHA && target == 0f) { bannerAlpha = 0f; if (spec == null) freeBanner(); return }
            if (spec != null && target == 1f && (bannerTex == 0 || spec.version != bannerVersion)) buildBanner(spec)
            if (bannerTex == 0) return
            val bar = if (ticker != null) tickerBarPx else 0
            val pk = ads?.poweredKey ?: 0
            val ck = clockAvoidKey()
            if (bannerRect == null || bar != bannerKeyBar || pk != bannerKeyPowered || ck != bannerKeyClock) {
                val powered = if (pk != 0) ads?.poweredRect(bar) else null
                bannerRect = NameBannerLayout.drawPlan(outWidth, outHeight, bar, bannerW, bannerH, 1f, clockAvoidRect(), powered)?.rect
                bannerKeyBar = bar; bannerKeyPowered = pk; bannerKeyClock = ck
            }
            val r = bannerRect ?: return
            val q = clockQuad ?: ClockQuadGl(outWidth, outHeight).also { clockQuad = it }
            q.draw(bannerTex, r.x, outHeight - r.y - r.h, r.w, r.h, bannerAlpha)   // layout is top-left origin, GL bottom-left
        } catch (t: Throwable) {
            bannerFailedFor = spec?.version ?: -1L; lastError = "name banner: $t"
            runCatching { freeBanner() }
        }
    }

    private fun buildBanner(spec: BannerSpec) {
        val maker = bannerMaker ?: NameBannerBitmap(outWidth, outHeight).also { bannerMaker = it }
        val pk = ads?.poweredKey ?: 0
        val bar = if (ticker != null) tickerBarPx else 0
        val powered = if (pk != 0) ads?.poweredRect(bar) else null
        val bmp = maker.render(spec.name, spec.title, NameBannerLayout.maxBarWidth(outWidth, powered))
        if (bannerTex == 0) bannerTex = uploadLogo(bmp)
        else { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bannerTex); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0) }
        bannerW = bmp.width; bannerH = bmp.height
        bmp.recycle()
        bannerVersion = spec.version; bannerRect = null
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw IllegalStateException("GL error while uploading the name banner")
    }

    private fun freeBanner() {
        if (bannerTex != 0) GLES20.glDeleteTextures(1, intArrayOf(bannerTex), 0)
        bannerTex = 0; bannerVersion = -1; bannerRect = null; bannerAlpha = 0f; bannerKeyBar = -1; bannerKeyPowered = -1; bannerKeyClock = 0
    }

    /** Changes whenever the clock's rectangle was re-placed (new instances), so the banner re-places itself; 0 = no clock rectangle. */
    private fun clockAvoidKey(): Int {
        val spec = clock
        if (spec == null || !spec.enabled || clockShown == null) return 0
        return if (spec.style == ClockStyle.TEXT) 31 * System.identityHashCode(slotA.rect) + System.identityHashCode(slotB.rect) else System.identityHashCode(clockRectPx)
    }

    /** The date and time overlay's current rectangle (both text slots joined), for the banner to stay clear of it. Allocates: re-placement only. */
    private fun clockAvoidRect(): ClockRect? {
        val spec = clock
        if (spec == null || !spec.enabled || clockShown == null) return null
        if (spec.style != ClockStyle.TEXT) return clockRectPx
        val a = slotA.rect; val b = slotB.rect
        if (a == null) return b
        if (b == null) return a
        val x = minOf(a.x, b.x); val y = minOf(a.y, b.y)
        return ClockRect(x, y, maxOf(a.right, b.right) - x, maxOf(a.bottom, b.bottom) - y)
    }

    // ---- live kit swap (1.0.15) ----

    /** Runs a task on the render thread (the camera's GL thread), BETWEEN two frames: that thread handles frames and posted tasks one after the other. */
    val renderExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { task ->
        if (released || !helper.handler.post(task)) throw java.util.concurrent.RejectedExecutionException("render thread is gone")
    }

    /**
     * RENDER THREAD ONLY (post it with [renderExecutor]). Replaces logo, ad layer and date and time in one step: the only GL work is one texture upload
     * for the new logo (done BEFORE anything is replaced, so a failure leaves the old state completely untouched) and the deletion of the old textures.
     * Everything slow (download, decoding, the ad layer's bitmaps) was done by the caller earlier. Returns false when nothing was changed.
     */
    fun swapKit(newLogo: LogoOverlay?, newAds: AdLayerGl?, newClock: ClockSettings?): Boolean {
        if (released) return false
        var newTex = 0
        try {
            if (newLogo != null && drawer != null) {
                newTex = uploadLogo(newLogo.bitmap)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw IllegalStateException("GL error while uploading the logo")
            }
        } catch (t: Throwable) {
            if (newTex != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(newTex), 0) }
            lastError = "kit swap: $t"
            return false
        }
        // From here on nothing can fail: commit.
        val oldTex = logoTexture
        logo = newLogo
        logoTexture = newTex                 // 0 while GL is not initialised yet: ensureGl() uploads the new logo with the rest
        if (animTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(animTexture), 0)
        animTexture = 0; animSource = null; animShown = -1
        if (oldTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oldTex), 0)
        val oldAds = ads
        ads = newAds
        runCatching { oldAds?.release() }
        clock = newClock
        bannerRect = null                    // placement depends on the ads' mark and the clock
        return true
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
                runCatching { freeClock() }
                runCatching { freeBanner() }
                runCatching { clockQuad?.release() }
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
