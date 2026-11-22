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
import app.streambird.android.ads.AD_DWELL_DEFAULT
import app.streambird.android.ads.AdBlendIn
import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdRect
import app.streambird.android.ads.AdSchedule
import app.streambird.android.ads.AdShrinkInput
import app.streambird.android.ads.AdSlot
import app.streambird.android.ads.AdSource
import app.streambird.android.ads.FREE_PLAN_AD_SCHEDULE
import app.streambird.android.ads.MAX_ADS_PER_PLACEMENT
import app.streambird.android.ads.NormRect
import app.streambird.android.ads.POWERED_BY_TEXT
import app.streambird.android.ads.POWERED_PILL_ARGB
import app.streambird.android.ads.SeqAd
import app.streambird.android.ads.SeqSchedule
import app.streambird.android.ads.SeqState
import app.streambird.android.ads.adPriorityFactors
import app.streambird.android.ads.adRect
import app.streambird.android.ads.adSequenceState
import app.streambird.android.ads.adSequenceTargets
import app.streambird.android.ads.adTargetAlpha
import app.streambird.android.ads.combinePlacement
import app.streambird.android.ads.fitAd
import app.streambird.android.ads.placementShrinks
import app.streambird.android.ads.planGif
import app.streambird.android.ads.poweredByLayout
import app.streambird.android.ads.sceneRectFor
import app.streambird.android.ads.seqScheduleOf
import app.streambird.android.ads.stepAlpha
import app.streambird.android.ads.stillSampleSize
import app.streambird.android.ads.wantedAds
import app.streambird.android.ads.windowOfBitmap
import app.streambird.android.ads.windowOnCanvas
import app.streambird.android.brand.sniffImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * One custom ad as handed to the engine (raw picture bytes, from the kit library or the phone). Several inputs may share a placement: they are its
 * SEQUENCE in play order (at most 5). [schedule] = the placement's mode and pause with THIS ad's show window (interval); [dwellSec] = this ad's seconds in
 * continuous mode (null = 20). [index] = its place in the stream's list for that placement (a gap = an ad that could not be loaded).
 */
class AdInput(
    val placement: AdPlacement, val bytes: ByteArray, val enabled: Boolean, val schedule: AdSchedule,
    val dwellSec: Int? = null, val source: AdSource = AdSource.KIT, val index: Int = 0, val assetId: String? = null, val name: String? = null,
) {
    /** The seconds the scheduler uses for this ad (interval: its show window; continuous: its dwell). */
    val showSec: Int get() = (schedule as? AdSchedule.Interval)?.showSec ?: dwellSec ?: AD_DWELL_DEFAULT
}

/**
 * Draws the ads over the finished frame, on the camera's GL thread, after the camera, logo and ticker and before nothing else
 * except the Free "Powered by" mark (drawn last). Rules: web-app/src/studio/ads.ts / adLayer.ts, see the pure port in ads/Ads.kt and ads/AdSequence.kt.
 *
 * Sequences (1.0.18): a placement holds up to 5 ads. Per frame the pure scheduler ([adSequenceTargets]) says which of them show and how opaque each one is
 * (round-robin for "now and then", cross-fade for always-on); every ad is drawn with its own opacity and the priority between placements multiplies all ads of
 * a placement by the same factor.
 *
 * Cost model: pictures are decoded once at [prepare] (size, L-frame window), but only the CURRENT, the fading-out and the NEXT ad of a placement keep a
 * bitmap / texture / GIF animation; the others are released as soon as they have faded out and are decoded again from their file bytes (helper thread) when
 * they come up next, so GPU memory stays bounded by 3 pictures per placement however long the sequence is. Per frame there is no bitmap work and no
 * allocation beyond a few small objects, only a few alpha-blended quads. A GIF is decoded lazily (ImageDecoder, API 28+) on a helper thread when it becomes
 * current or next, and its frames are rendered into one reusable bitmap at most 15 times a second while it is visible; on API 26/27 or any failure the first
 * frame is shown as a still (the host is told). With no ads and no Free plan [draw] returns at once.
 * UNVERIFIED on a device (written against the Android/GL docs, compiled only).
 */
class AdLayerGl(
    private val outW: Int,
    private val outH: Int,
    private val onEvent: (String) -> Unit,
    private val onNote: (String) -> Unit,
    /** (placement, what, the ad's index in the stream's list for that placement). */
    private val onProblem: (AdPlacement, app.streambird.android.ads.AdProblem, Int) -> Unit = { _, _, _ -> },
) {
    /** One ad. Fields written from other threads are volatile. */
    private class Rt(val placement: AdPlacement) {
        @Volatile var bitmap: Bitmap? = null          // decoded still / first frame, consumed by the GL upload
        var tex = 0; var texW = 0; var texH = 0
        @Volatile var enabled = false
        @Volatile var showSec = AD_DWELL_DEFAULT
        /** The encoded file, kept so a released picture can be decoded again (null on the Free banner). */
        var bytes: ByteArray? = null
        var sample = 1
        /** Size of the decoded picture (what the rectangle is fitted from, also while its texture is released). 0 = this ad never loaded. */
        var picW = 0; var picH = 0
        @Volatile var decoding = false
        var alpha = 0.0; var snap = true
        var wasVisible = false
        var rect = app.streambird.android.ads.AdRect(0.0, 0.0, 0.0, 0.0)
        var keyBar = -1
        /** l_shape only: the clear window of the graphic (0..1 of the picture); null = none found (the graphic is then a plain full-screen overlay). */
        @Volatile var window: NormRect? = null
        // animated GIF
        @Volatile var gifBytes: ByteArray? = null
        var gifW = 0; var gifH = 0
        var gifRequested = false
        @Volatile var anim: Any? = null               // android.graphics.drawable.AnimatedImageDrawable (API 28+)
        var animBmp: Bitmap? = null; var animCanvas: Canvas? = null; var lastUploadMs = 0L; var animTexReady = false
    }

    /** One placement: its ads in play order, the schedule they share and the scratch the per-frame scheduler works in. */
    private class Pl(val placement: AdPlacement, val rts: List<Rt>) {
        @Volatile var schedule: SeqSchedule = SeqSchedule.Continuous
        @Volatile var manual: AdManual? = null
        val seq: List<SeqAd> = List(rts.size) { SeqAd() }
        val out = DoubleArray(MAX_ADS_PER_PLACEMENT)
        val st = SeqState()
        val stNow = SeqState()
        val wanted = BooleanArray(MAX_ADS_PER_PLACEMENT)
        var alpha = 0.0
    }

    /** Rebuilt by [prepare] (before any frame): the placements with their ads. Index = ordinal. */
    @Volatile private var placements: List<Pl> = AdPlacement.entries.map { Pl(it, emptyList()) }
    private val placementAlpha = DoubleArray(AdPlacement.entries.size)
    private val factors = DoubleArray(AdPlacement.entries.size)
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

    val hasAnything: Boolean get() = free || placements.any { pl -> pl.rts.any { it.enabled || it.alpha > 0 } }

    /** "Shrink picture while ads show" (kit switch + per-stream override). Changeable live. */
    @Volatile var shrinkPicture = true
    private val fullFrame = AdRect(0.0, 0.0, outW.toDouble(), outH.toDouble())
    private val shrinkIn = HashMap<AdPlacement, AdShrinkInput>()
    private var steppedTickerBar = 0

    /** Show now / Hide for the WHOLE placement (every ad of it); null = follow the schedule. */
    fun setManual(p: AdPlacement, m: AdManual?) { placements[p.ordinal].manual = m }

    /** Live On / Off of a placement: every ad of it (volatile fields, read by the next frame): nothing is decoded or uploaded, so no frame is dropped. A placement without a loaded ad is ignored. */
    fun setEnabled(p: AdPlacement, on: Boolean) { for (r in placements[p.ordinal].rts) r.enabled = on }

    /**
     * Live schedule of a placement: its mode and pause, and (interval) the show window of its FIRST ad (the others keep their own), like the web's `setSchedule`.
     * Ignored for a placement without a loaded ad.
     */
    fun setSchedule(p: AdPlacement, s: AdSchedule) {
        val pl = placements[p.ordinal]
        if (pl.rts.isEmpty()) return
        pl.schedule = seqScheduleOf(s)
        if (s is AdSchedule.Interval) pl.rts[0].showSec = s.showSec
    }

    private companion object {
        /** The placements whose visible picture can take room from the scene (the lower and left third; the frame when it has a window). */
        val SHRINKING = listOf(AdPlacement.LOWER_THIRD, AdPlacement.SIDE_THIRD, AdPlacement.L_SHAPE)
        /** Pictures of one placement that keep a bitmap straight after [prepare] (the current and the next one). */
        const val KEEP_AFTER_PREPARE = 2
    }

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
        val built = AdPlacement.entries.map { p ->
            val list = ArrayList<Rt>()
            var first: AdInput? = null
            for (a in inputs.filter { it.placement == p }.take(MAX_ADS_PER_PLACEMENT)) {
                val rt = prepareOne(a, p, list.size) ?: continue
                if (first == null) first = a
                list.add(rt)
            }
            Pl(p, list).also { pl -> first?.let { pl.schedule = seqScheduleOf(it.schedule) } }
        }
        placements = built
    }

    /** Decodes one ad (size, GIF plan, L-frame window); null when its picture cannot be used. The first [KEEP_AFTER_PREPARE] of a placement keep their bitmap. */
    private fun prepareOne(a: AdInput, p: AdPlacement, position: Int): Rt? {
        val rt = Rt(p)
        val area = adRect(p, outW, outH, 0)
        val label = if (position > 0 || a.index > 0) "${p.label} ad ${a.index + 1}" else "${p.label} ad"
        val info = sniffImage(a.bytes)
        if (info == null) { onNote("$label: the picture could not be read."); onProblem(p, app.streambird.android.ads.AdProblem.UNREADABLE, a.index); return null }
        val aw = area.w.toInt(); val ah = area.h.toInt()
        val sample = stillSampleSize(info.width, info.height, aw, ah)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = runCatching { BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size, opts) }.getOrNull()
        if (bmp == null) { onNote("$label: the picture could not be decoded."); onProblem(p, app.streambird.android.ads.AdProblem.UNREADABLE, a.index); return null }
        rt.enabled = a.enabled; rt.showSec = a.showSec
        rt.bytes = a.bytes; rt.sample = sample; rt.picW = bmp.width; rt.picH = bmp.height
        if (info.format == app.streambird.android.brand.ImageFormat.GIF) {
            val plan = planGif(a.bytes, info.width, info.height, aw, ah, Build.VERSION.SDK_INT)
            if (plan.animate) { rt.gifBytes = a.bytes; rt.gifW = plan.targetW; rt.gifH = plan.targetH }
            else plan.reason?.let { onNote("$label: shown as a still picture, $it.") }
        }
        if (p == AdPlacement.L_SHAPE) {
            // Where the live picture goes: the clear window of the frame graphic (GIF: its first frame). None found -> plain full-screen overlay.
            rt.window = runCatching { windowOfBitmap(bmp) }.getOrNull()
            if (rt.window == null) {
                onNote("$label: no clear window found in this graphic. It is drawn as a full-screen overlay and the picture is not resized.")
                onProblem(p, app.streambird.android.ads.AdProblem.NO_WINDOW, a.index)
            }
        }
        if (position < KEEP_AFTER_PREPARE) rt.bitmap = bmp else bmp.recycle()
        return rt
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

    /**
     * Advances the ads one frame (clock, schedule, fades, Free banner) WITHOUT touching GL. The compositor calls it before drawing the scene
     * so [sceneRect] is current, then [draw]. [tickerBar] = ticker bar height in px while the ticker shows, else 0.
     */
    fun update(tickerBar: Int) {
        if (released || !hasAnything) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastNowMs < 0) 0.0 else ((now - lastNowMs) / 1000.0).coerceIn(0.0, 0.25)
        lastNowMs = now
        val elapsed = if (clockStartMs == 0L) 0.0 else (now - clockStartMs) / 1000.0
        steppedTickerBar = tickerBar
        if (free) {
            val target = if (freeBanner != null || freeTex != 0) adTargetAlpha(elapsed, freeSlot) else 0.0
            freeRt.alpha = if (freeRt.snap) target else stepAlpha(freeRt.alpha, target, dt)
            if (freeBanner != null || freeTex != 0) freeRt.snap = false
            val vis = freeRt.alpha > 0.002
            if (vis != freeRt.wasVisible) { freeRt.wasVisible = vis; onEvent("ad lower_third (StreamBird) ${if (vis) "shown" else "hidden"}") }
            freeRt.rect = fitAd(AdPlacement.LOWER_THIRD, freeW.takeIf { it > 0 } ?: freeBanner?.width ?: 0, freeH.takeIf { it > 0 } ?: freeBanner?.height ?: 0, adRect(AdPlacement.LOWER_THIRD, outW, outH, tickerBar))
            return
        }
        val all = placements
        // 1. Per placement: which of its ads show and how opaque each one is (the sequence), which pictures must be ready.
        for (pl in all) {
            val n = pl.rts.size
            if (n == 0) { pl.alpha = 0.0; placementAlpha[pl.placement.ordinal] = 0.0; continue }
            for (i in 0 until n) { val r = pl.rts[i]; pl.seq[i].enabled = r.enabled && r.picW > 0; pl.seq[i].showSec = r.showSec }
            adSequenceState(elapsed, pl.seq, pl.schedule, n, pl.stNow)
            wantedAds(pl.stNow, pl.seq, n, pl.schedule, pl.wanted)
            pl.alpha = adSequenceTargets(elapsed, pl.seq, n, pl.schedule, pl.manual, pl.out, pl.st)
            placementAlpha[pl.placement.ordinal] = pl.alpha
            for (i in 0 until n) {
                val r = pl.rts[i]
                if (pl.wanted[i] && r.tex == 0 && r.bitmap == null && !r.decoding) requestStill(r)
                if (pl.wanted[i] && r.gifBytes != null && !r.gifRequested) requestAnim(r)
            }
        }
        // 2. Priority between placements (full_screen > l_shape > the thirds) multiplies every ad of the placement by the same factor.
        adPriorityFactors(placementAlpha, factors)
        for (p in AD_DRAW_ORDER) {
            val pl = all[p.ordinal]
            val f = factors[p.ordinal]
            for (i in pl.rts.indices) {
                val r = pl.rts[i]
                val target = pl.out[i] * f
                r.alpha = if (r.snap) target else stepAlpha(r.alpha, target, dt)
                r.snap = false
                if (r.keyBar != tickerBar || r.rect.w == 0.0) { r.rect = fitAd(p, r.picW, r.picH, adRect(p, outW, outH, tickerBar)); r.keyBar = tickerBar }
                val vis = r.alpha > 0.002
                if (vis != r.wasVisible) { r.wasVisible = vis; onEvent("ad ${p.wire}${if (pl.rts.size > 1) " ${i + 1}" else ""} ${if (vis) "shown" else "hidden"}") }
            }
        }
    }

    /**
     * Where the scene (camera, logo) is drawn right now, as of the last [update]: the whole frame, or the rectangle the visible lower/left
     * third ad leaves free (rule and formulas: ads/AdShrink.kt = web ads.ts). Top-left origin, pixels. With several ads in a placement the rectangle
     * (and the frame's window) is the blend of the showing ads' by their alpha shares, so the scene eases between them (see [combinePlacement]).
     */
    fun sceneRect(): AdRect {
        if (released) return fullFrame
        if (free) {
            if (!shrinkPicture) return fullFrame
            val a = if (freeRt.alpha > 0.002) freeRt.alpha else 0.0
            if (a == 0.0) return fullFrame
            shrinkIn.clear(); shrinkIn[AdPlacement.LOWER_THIRD] = AdShrinkInput(a, freeRt.rect)
        } else {
            var any = false
            shrinkIn.clear()
            for (p in SHRINKING) {
                if (!placementShrinks(p, shrinkPicture)) continue // the switch governs only the thirds; the frame always fits the picture to its window
                val pl = placements[p.ordinal]
                if (pl.rts.isEmpty()) continue
                val ins = pl.rts.map { r -> AdBlendIn(if (r.alpha > 0.002) r.alpha else 0.0, r.rect, if (p == AdPlacement.L_SHAPE) r.window?.let { windowOnCanvas(r.rect, it) } else null) }
                val c = combinePlacement(ins, windowed = p == AdPlacement.L_SHAPE)
                // The frame only resizes the picture when its window is known; without one it is just an overlay.
                if (!(c.alpha > 0.0) || c.rect == null || (p == AdPlacement.L_SHAPE && c.win == null)) continue
                any = true
                shrinkIn[p] = AdShrinkInput(c.alpha, c.rect, if (p == AdPlacement.L_SHAPE) c.win else null)
            }
            if (!any) return fullFrame
        }
        return sceneRectFor(outW, outH, shrinkIn, steppedTickerBar)
    }

    /** True while an L-shaped frame with a window is on screen (whatever the shrink switch says): its graphic is the base layer under the scene. */
    private fun frameOnBase(): Boolean = !free && placements[AdPlacement.L_SHAPE.ordinal].rts.any { it.window != null && it.alpha > 0.002 }

    /**
     * Draws the L-shaped frame graphic(s) as the BASE layer. The compositor calls it right after clearing the frame and BEFORE the scene, so the
     * camera and logo are drawn on top, inside the window ([sceneRect]). Does nothing when no frame with a window is showing.
     */
    fun drawBase() {
        if (released || !frameOnBase()) return
        beginDraw()
        val now = SystemClock.elapsedRealtime()
        for (r in placements[AdPlacement.L_SHAPE.ordinal].rts) {
            if (r.window == null || r.alpha <= 0.002) continue
            uploadIfNeeded(r); updateAnim(r, now); drawTex(r.tex, r.picW, r.picH, r, AdPlacement.L_SHAPE, steppedTickerBar, r.alpha)
        }
        endDraw()
    }

    private fun beginDraw() {
        ensureProgram()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTex, 0)
    }

    private fun endDraw() {
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glUseProgram(0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glViewport(0, 0, outW, outH)
    }

    /** Draws the ads (or the Free banner) as stepped by [update]. */
    fun draw(tickerBar: Int) {
        if (released || !hasAnything) return
        val now = SystemClock.elapsedRealtime()
        beginDraw()
        if (free) {
            drawFree(tickerBar)
        } else {
            housekeeping()
            for (p in AD_DRAW_ORDER) {
                for (r in placements[p.ordinal].rts) {
                    if (p == AdPlacement.L_SHAPE && r.window != null) continue   // a frame with a window is drawn under the scene by drawBase()
                    if (r.alpha > 0.002) { uploadIfNeeded(r); updateAnim(r, now); drawTex(r.tex, r.picW, r.picH, r, p, tickerBar, r.alpha) }
                }
            }
        }
        // The Free "Powered by StreamBird" mark is ALWAYS on top.
        if (free) drawPowered(tickerBar)
        endDraw()
    }

    /**
     * Keeps the GPU memory bounded: the current, fading-out and next ad of a placement are uploaded ahead of their time (one picture per frame, so a
     * cross-fade never waits for an upload); an ad that is neither of them and has faded out gives its texture, bitmap and GIF animation back.
     */
    private fun housekeeping() {
        var uploads = 0
        for (pl in placements) {
            for (i in pl.rts.indices) {
                val r = pl.rts[i]
                if (pl.wanted[i]) {
                    if (r.tex == 0 && r.bitmap != null && uploads < 1) { uploadIfNeeded(r); uploads++ }
                } else if (r.alpha <= 0.002 && (r.tex != 0 || r.bitmap != null || r.anim != null || r.gifRequested)) dropPicture(r)
            }
        }
    }

    /** GL thread. Frees one ad's texture, decoded bitmap and GIF animation; its file bytes stay, so it can be decoded again when it is next. */
    private fun dropPicture(r: Rt) {
        if (r.tex != 0) GLES20.glDeleteTextures(1, intArrayOf(r.tex), 0)
        r.tex = 0; r.texW = 0; r.texH = 0; r.keyBar = -1
        r.bitmap?.recycle(); r.bitmap = null
        r.anim = null; r.animBmp?.recycle(); r.animBmp = null; r.animCanvas = null; r.animTexReady = false; r.gifRequested = false; r.lastUploadMs = 0L
    }

    private fun drawFree(tickerBar: Int) {
        val b = freeBanner
        if (b != null && freeTex == 0) { freeTex = upload(b); freeW = b.width; freeH = b.height; b.recycle(); freeBanner = null }
        if (freeTex == 0) return
        if (freeRt.alpha > 0.002) drawTex(freeTex, freeW, freeH, freeRt, AdPlacement.LOWER_THIRD, tickerBar, freeRt.alpha)
    }

    /** Width of the Free-plan "Powered by" mark once it is on the GPU (0 = not shown / not ready): a cheap per-frame key for "must the clock re-place itself". */
    val poweredKey: Int get() = if (free && poweredTex != 0) poweredW else 0

    /** Where [drawPowered] puts the mark (origin top-left), for the compact clock to keep clear of it; null when it is not shown. Allocates: call on re-placement only. */
    fun poweredRect(tickerBar: Int): app.streambird.android.clock.ClockRect? {
        if (!free || poweredTex == 0) return null
        val lay = poweredByLayout(outW, outH, poweredW.toDouble(), null, tickerBar)
        val x = outW - (20 * lay.unit).toInt() - poweredW
        val bottomGap = if (outH > outW) Math.round(outH * 0.06).toInt() else Math.round(20 * lay.unit).toInt()
        return app.streambird.android.clock.ClockRect(x, outH - tickerBar - bottomGap - poweredH, poweredW, poweredH)
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
        if (r.tex != 0) { GLES20.glDeleteTextures(1, intArrayOf(r.tex), 0); r.tex = 0 }
        r.tex = upload(b); r.texW = b.width; r.texH = b.height; r.keyBar = -1
        r.bitmap = null; b.recycle()
    }

    /** Decodes a released still again on the helper thread; the GL thread uploads it ([housekeeping] / draw). Never touches GL. */
    private fun requestStill(r: Rt) {
        val bytes = r.bytes ?: return
        r.decoding = true
        try {
            decodeExec.execute {
                try {
                    if (!released) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = r.sample })
                        if (bmp != null) { if (released) bmp.recycle() else r.bitmap = bmp }
                    }
                } catch (t: Throwable) {
                    onNote("${r.placement.label} ad: the picture could not be decoded again (${t.javaClass.simpleName}).")
                } finally { r.decoding = false }
            }
        } catch (t: Throwable) { r.decoding = false }
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
        for (pl in placements) for (r in pl.rts) {
            if (r.tex != 0) GLES20.glDeleteTextures(1, intArrayOf(r.tex), 0)
            r.tex = 0; r.bitmap?.recycle(); r.bitmap = null; r.animBmp?.recycle(); r.animBmp = null
            r.anim = null; r.bytes = null; r.gifBytes = null
        }
        if (freeTex != 0) GLES20.glDeleteTextures(1, intArrayOf(freeTex), 0)
        if (poweredTex != 0) GLES20.glDeleteTextures(1, intArrayOf(poweredTex), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        freeTex = 0; poweredTex = 0; program = 0
    }
}
