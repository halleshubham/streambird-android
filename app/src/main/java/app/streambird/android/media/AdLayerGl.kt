package app.streambird.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.SystemClock
import app.streambird.android.R
import app.streambird.android.ads.AD_DRAW_ORDER
import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.ads.AdSlot
import app.streambird.android.ads.FREE_PLAN_AD_SCHEDULE
import app.streambird.android.ads.POWERED_BY_TEXT
import app.streambird.android.ads.POWERED_PILL_ARGB
import app.streambird.android.ads.adRect
import app.streambird.android.ads.adTargetAlpha
import app.streambird.android.ads.fitAd
import app.streambird.android.ads.planGif
import app.streambird.android.ads.poweredByLayout
import app.streambird.android.ads.resolveAdAlphas
import app.streambird.android.ads.secondsUntilVisible
import app.streambird.android.ads.stepAlpha
import app.streambird.android.ads.stillSampleSize
import app.streambird.android.brand.sniffImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/** One custom ad as handed to the engine (raw picture bytes, from the kit library or the phone). */
class AdInput(val placement: AdPlacement, val bytes: ByteArray, val enabled: Boolean, val schedule: AdSchedule)

/**
 * Draws the ads over the finished frame, on the camera's GL thread, after the camera, logo and ticker and before nothing else
 * except the Free "Powered by" mark (drawn last). Rules: web-app/src/studio/ads.ts / adLayer.ts, see the pure port in ads/Ads.kt.
 *
 * Cost model: pictures are decoded once, off the GL thread ([prepare]) and uploaded once to textures when first needed; per frame
 * there is no bitmap work and no allocation, only a few alpha-blended quads. A GIF is decoded lazily (ImageDecoder, API 28+) on a helper
 * thread shortly before it can be seen, and its frames are rendered into one reusable bitmap at most 15 times a second while it is visible;
 * on API 26/27 or any failure the first frame is shown as a still (the host is told). With no ads and no Free plan [draw] returns at once.
 * UNVERIFIED on a device (written against the Android/GL docs, compiled only).
 */
class AdLayerGl(
    private val outW: Int,
    private val outH: Int,
    private val onEvent: (String) -> Unit,
    private val onNote: (String) -> Unit,
) {
    private class Rt(val placement: AdPlacement) {
        @Volatile var bitmap: Bitmap? = null          // prepared still / first frame, consumed by the GL upload
        var tex = 0; var texW = 0; var texH = 0
        @Volatile var enabled = false
        @Volatile var schedule: AdSchedule = AdSchedule.Continuous
        @Volatile var manual: AdManual? = null
        var alpha = 0.0; var snap = true
        var wasVisible = false
        var rect = app.streambird.android.ads.AdRect(0.0, 0.0, 0.0, 0.0)
        var keyBar = -1
        // animated GIF
        @Volatile var gifBytes: ByteArray? = null
        var gifW = 0; var gifH = 0
        var gifRequested = false
        @Volatile var anim: Any? = null               // android.graphics.drawable.AnimatedImageDrawable (API 28+)
        var animBmp: Bitmap? = null; var animCanvas: Canvas? = null; var lastUploadMs = 0L; var animTexReady = false
    }

    private val rts = AD_DRAW_ORDER.map { Rt(it) }
    private val slots = rts.map { AdSlot(it.placement, false, AdSchedule.Continuous) }
    private val targets = DoubleArray(3)
    private val decodeExec by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "sb-ad-decode").apply { isDaemon = true } } }

    // Free plan
    @Volatile private var free = false
    @Volatile private var freeBanner: Bitmap? = null
    private var freeTex = 0; private var freeW = 0; private var freeH = 0
    private val freeRt = Rt(AdPlacement.LOWER_THIRD)
    private val freeSlot = AdSlot(AdPlacement.LOWER_THIRD, true, FREE_PLAN_AD_SCHEDULE)
    @Volatile private var poweredBmp: Bitmap? = null
    private var poweredTex = 0; private var poweredW = 0; private var poweredH = 0

    /** Real go-live time (SystemClock.elapsedRealtime). 0 = not started: the clock reads 0. Survives a quiet republish. */
    @Volatile var clockStartMs = 0L

    @Volatile private var released = false
    private var lastNowMs = -1L
    private var program = 0; private var aPos = 0; private var aTex = 0; private var uTex = 0; private var uAlpha = 0
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v; bitmaps are uploaded top row first, so v runs 1 -> 0 from bottom to top
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
    }

    val hasAnything: Boolean get() = free || rts.any { it.enabled || it.alpha > 0 }

    fun setManual(p: AdPlacement, m: AdManual?) { rts[p.ordinal].manual = m }

    // ---- preparation (any thread, before frames flow) ----

    /** Decodes the pictures (bounded by their area) and, on the Free plan, loads the StreamBird banner and renders the mark. */
    fun prepare(context: android.content.Context, inputs: List<AdInput>, freePlan: Boolean) {
        free = freePlan
        if (freePlan) {
            // Custom ads are never decoded or drawn on Free.
            freeBanner = runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.streambird_ad_lower_third) }.getOrNull()
            poweredBmp = runCatching { renderPoweredMark(context) }.getOrNull()
            return
        }
        for (a in inputs) {
            val rt = rts[a.placement.ordinal]
            val area = adRect(a.placement, outW, outH, 0)
            val info = sniffImage(a.bytes)
            if (info == null) { onNote("${a.placement.label} ad: the picture could not be read."); continue }
            val aw = area.w.toInt(); val ah = area.h.toInt()
            val opts = BitmapFactory.Options().apply { inSampleSize = stillSampleSize(info.width, info.height, aw, ah) }
            val bmp = runCatching { BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size, opts) }.getOrNull()
            if (bmp == null) { onNote("${a.placement.label} ad: the picture could not be decoded."); continue }
            rt.schedule = a.schedule; rt.enabled = a.enabled
            if (info.format == app.streambird.android.brand.ImageFormat.GIF) {
                val plan = planGif(a.bytes, info.width, info.height, aw, ah, Build.VERSION.SDK_INT)
                if (plan.animate) { rt.gifBytes = a.bytes; rt.gifW = plan.targetW; rt.gifH = plan.targetH }
                else plan.reason?.let { onNote("${a.placement.label} ad: shown as a still picture, $it.") }
            }
            rt.bitmap = bmp
        }
    }

    private fun renderPoweredMark(context: android.content.Context): Bitmap {
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.logo_bird)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = 0xFFFFFFFF.toInt() }
        val unit = (if (outH > outW) outW else outH) / 720.0
        paint.textSize = Math.round(18 * unit).toFloat()
        val lay = poweredByLayout(outW, outH, paint.measureText(POWERED_BY_TEXT).toDouble(), logo?.let { it.width.toDouble() / it.height }, 0)
        val bmp = Bitmap.createBitmap(lay.pillW, lay.pillH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRoundRect(0f, 0f, lay.pillW.toFloat(), lay.pillH.toFloat(), lay.pillH / 2f, lay.pillH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POWERED_PILL_ARGB })
        var cx = lay.padX.toFloat()
        if (logo != null && lay.iconW > 0) {
            c.drawBitmap(logo, null, android.graphics.RectF(cx, (lay.pillH - lay.iconH) / 2f, cx + lay.iconW.toFloat(), (lay.pillH + lay.iconH) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
            cx += lay.iconW.toFloat() + lay.gap
        }
        val fm = paint.fontMetrics
        c.drawText(POWERED_BY_TEXT, cx, lay.pillH / 2f - (fm.ascent + fm.descent) / 2f, paint)
        logo?.recycle()
        return bmp
    }

    // ---- drawing (GL thread, inside the frame's FBO) ----

    /** Draws the ads (or the Free banner). [tickerBar] = ticker bar height in px while the ticker shows, else 0. Returns whether anything may have changed GL state. */
    fun draw(tickerBar: Int) {
        if (released || !hasAnything) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastNowMs < 0) 0.0 else ((now - lastNowMs) / 1000.0).coerceIn(0.0, 0.25)
        lastNowMs = now
        val elapsed = if (clockStartMs == 0L) 0.0 else (now - clockStartMs) / 1000.0
        ensureProgram()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTex, 0)
        if (free) {
            drawFree(elapsed, dt, tickerBar)
        } else {
            for (i in rts.indices) {
                val r = rts[i]; val s = slots[i]
                s.enabled = r.enabled && (r.bitmap != null || r.tex != 0); s.schedule = r.schedule; s.manual = r.manual
                if (s.enabled && r.gifBytes != null && !r.gifRequested && secondsUntilVisible(elapsed, r.schedule) < 3.0) requestAnim(r)
            }
            resolveAdAlphas(elapsed, slots, targets)
            for (p in AD_DRAW_ORDER) {
                val r = rts[p.ordinal]
                if (r.bitmap != null || r.tex != 0) {
                    r.alpha = if (r.snap) targets[p.ordinal] else stepAlpha(r.alpha, targets[p.ordinal], dt)
                    r.snap = false
                } else r.alpha = 0.0
                val vis = r.alpha > 0.002
                if (vis != r.wasVisible) { r.wasVisible = vis; onEvent("ad ${p.wire} ${if (vis) "shown" else "hidden"}") }
                if (vis) { uploadIfNeeded(r); updateAnim(r, now); drawTex(r.tex, r.texW, r.texH, r, p, tickerBar, r.alpha) }
            }
        }
        // The Free "Powered by StreamBird" mark is ALWAYS on top.
        if (free) drawPowered(tickerBar)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glUseProgram(0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glViewport(0, 0, outW, outH)
    }

    private fun drawFree(elapsed: Double, dt: Double, tickerBar: Int) {
        val b = freeBanner
        if (b != null && freeTex == 0) { freeTex = upload(b); freeW = b.width; freeH = b.height; b.recycle(); freeBanner = null }
        if (freeTex == 0) return
        val target = adTargetAlpha(elapsed, freeSlot)
        freeRt.alpha = if (freeRt.snap) target else stepAlpha(freeRt.alpha, target, dt)
        freeRt.snap = false
        val vis = freeRt.alpha > 0.002
        if (vis != freeRt.wasVisible) { freeRt.wasVisible = vis; onEvent("ad lower_third (StreamBird) ${if (vis) "shown" else "hidden"}") }
        if (vis) drawTex(freeTex, freeW, freeH, freeRt, AdPlacement.LOWER_THIRD, tickerBar, freeRt.alpha)
    }

    private fun drawPowered(tickerBar: Int) {
        val b = poweredBmp
        if (b != null && poweredTex == 0) { poweredTex = upload(b); poweredW = b.width; poweredH = b.height; b.recycle(); poweredBmp = null }
        if (poweredTex == 0) return
        val lay = poweredByLayout(outW, outH, poweredW.toDouble(), null, tickerBar)
        // the pill bitmap already contains its icon and text: only its position depends on the ticker
        val x = outW - (20 * lay.unit).toInt() - poweredW
        val bottomGap = if (outH > outW) Math.round(outH * 0.06).toInt() else Math.round(20 * lay.unit).toInt()
        val yTop = outH - tickerBar - bottomGap - poweredH
        quadAt(poweredTex, x, outH - yTop - poweredH, poweredW, poweredH, 1.0)
    }

    private fun drawTex(tex: Int, tw: Int, th: Int, r: Rt, p: AdPlacement, tickerBar: Int, alpha: Double) {
        if (tex == 0) return
        if (r.keyBar != tickerBar || r.rect.w == 0.0) {
            r.rect = fitAd(p, tw, th, adRect(p, outW, outH, tickerBar)); r.keyBar = tickerBar
        }
        val f = r.rect
        if (f.w <= 0.0) return
        // origin top-left (spec) -> GL origin bottom-left
        quadAt(tex, f.x.toInt(), (outH - (f.y + f.h)).toInt(), f.w.toInt().coerceAtLeast(1), f.h.toInt().coerceAtLeast(1), alpha)
    }

    private fun quadAt(tex: Int, x: Int, y: Int, w: Int, h: Int, alpha: Double) {
        GLES20.glViewport(x, y, w, h)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1f(uAlpha, alpha.toFloat())
        quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---- textures, animation ----

    private fun upload(b: Bitmap): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        return t[0]
    }

    private fun uploadIfNeeded(r: Rt) {
        val b = r.bitmap ?: return
        r.tex = upload(b); r.texW = b.width; r.texH = b.height; r.keyBar = -1
        r.bitmap = null; b.recycle()
    }

    /** Decodes the GIF on a helper thread; the GL thread picks the result up in [updateAnim]. */
    private fun requestAnim(r: Rt) {
        r.gifRequested = true
        val bytes = r.gifBytes ?: return
        decodeExec.execute {
            try {
                if (Build.VERSION.SDK_INT >= 28 && !released) {
                    val src = android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                    val d = android.graphics.ImageDecoder.decodeDrawable(src) { dec, _, _ -> dec.setTargetSize(r.gifW, r.gifH) }
                    if (d is android.graphics.drawable.AnimatedImageDrawable) {
                        d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                        d.setBounds(0, 0, r.gifW, r.gifH)
                        d.start()
                        r.anim = d
                    } else onNote("${r.placement.label} ad: shown as a still picture, the GIF could not be animated.")
                }
            } catch (t: Throwable) {
                onNote("${r.placement.label} ad: shown as a still picture, the GIF could not be animated (${t.javaClass.simpleName}).")
            }
        }
    }

    private fun updateAnim(r: Rt, now: Long) {
        if (Build.VERSION.SDK_INT < 28) return
        val d = r.anim as? android.graphics.drawable.AnimatedImageDrawable ?: return
        if (now - r.lastUploadMs < 66) return                       // at most ~15 renders/s, and only while the ad is visible
        r.lastUploadMs = now
        try {
            var bmp = r.animBmp
            if (bmp == null) { bmp = Bitmap.createBitmap(r.gifW, r.gifH, Bitmap.Config.ARGB_8888); r.animBmp = bmp; r.animCanvas = Canvas(bmp) }
            bmp.eraseColor(0)
            d.draw(r.animCanvas!!)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, r.tex.takeIf { r.animTexReady } ?: run {
                if (r.tex != 0) GLES20.glDeleteTextures(1, intArrayOf(r.tex), 0)
                r.tex = upload(bmp); r.texW = r.gifW; r.texH = r.gifH; r.keyBar = -1; r.animTexReady = true; r.tex
            })
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        } catch (t: Throwable) {
            r.anim = null; r.gifBytes = null
            onNote("${r.placement.label} ad: shown as a still picture, the GIF stopped animating.")
        }
    }

    private fun ensureProgram() {
        if (program != 0) return
        fun sh(type: Int, src: String): Int = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        val vs = sh(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex; void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }")
        val fs = sh(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 vTex; uniform sampler2D uTex; uniform float uAlpha; void main(){ gl_FragColor = texture2D(uTex, vTex) * uAlpha; }")
        program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs); GLES20.glLinkProgram(it) }
        aPos = GLES20.glGetAttribLocation(program, "aPos"); aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex"); uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
    }

    /** GL thread: frees textures and drawables (the compositor calls this from its own release). */
    fun release() {
        released = true
        runCatching { decodeExec.shutdownNow() }
        for (r in rts) {
            if (r.tex != 0) GLES20.glDeleteTextures(1, intArrayOf(r.tex), 0)
            r.tex = 0; r.bitmap?.recycle(); r.bitmap = null; r.animBmp?.recycle(); r.animBmp = null
            r.anim = null
        }
        if (freeTex != 0) GLES20.glDeleteTextures(1, intArrayOf(freeTex), 0)
        if (poweredTex != 0) GLES20.glDeleteTextures(1, intArrayOf(poweredTex), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        freeTex = 0; poweredTex = 0; program = 0
    }
}
