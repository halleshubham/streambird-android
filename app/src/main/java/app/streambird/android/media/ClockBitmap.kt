package app.streambird.android.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.streambird.android.clock.ClockLayout
import app.streambird.android.clock.ClockSize
import app.streambird.android.clock.ClockText
import kotlin.math.ceil

/**
 * Draws the date-and-time pill (dark semi-transparent rounded rectangle, white text: the date in a regular weight, the time in bold) into a Bitmap.
 * Two Paint objects are kept for the life of the compositor; a bitmap is built only when the shown text changes (see FrameCompositor.drawClock), so
 * there is no per-frame work. Android code, not unit-tested; every number comes from the pure [ClockLayout].
 */
class ClockBitmap(private val canvasW: Int, private val canvasH: Int, private val size: ClockSize) {
    private val fontPx = ClockLayout.fontPx(canvasH, size)
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 255, 255, 255); textSize = fontPx; typeface = Typeface.SANS_SERIF }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = fontPx; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ClockLayout.PILL_ARGB }
    private val box = RectF()
    private val gap = fontPx * 0.6f

    /** Text size in px (width, height) of [t], for [ClockLayout.clockRect]. */
    fun measure(t: ClockText, out: IntArray) {
        var w = ceil(timePaint.measureText(t.time).toDouble()).toInt()
        if (t.date != null) w += ceil(datePaint.measureText(t.date).toDouble()).toInt() + ceil(gap.toDouble()).toInt()
        val fm = timePaint.fontMetrics
        out[0] = w; out[1] = ceil((fm.descent - fm.ascent).toDouble()).toInt()
    }

    /** The pill for [t], [w] x [h] px (the size [ClockLayout.pillSize] gave for [measure]). */
    fun render(t: ClockText, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        c.drawRoundRect(box, h / 2f, h / 2f, pillPaint)
        val fm = timePaint.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        var x = ClockLayout.padX(fontPx).toFloat()
        if (t.date != null) { c.drawText(t.date, x, baseline, datePaint); x += datePaint.measureText(t.date) + gap }
        c.drawText(t.time, x, baseline, timePaint)
        return bmp
    }

    // ---- compact TEXT style: no box, white semi-bold text, thin dark outline + soft shadow, fixed-width time ----

    private val tFont = ClockLayout.textFontPx(canvasW, canvasH, size)
    private val tFace = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val tFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = tFont; typeface = tFace; fontFeatureSettings = "tnum" }
    private val tEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0); textSize = tFont; typeface = tFace; fontFeatureSettings = "tnum"
        style = Paint.Style.STROKE; strokeWidth = tFont * 0.12f; strokeJoin = Paint.Join.ROUND
        setShadowLayer(tFont * 0.16f, 0f, tFont * 0.05f, Color.argb(190, 0, 0, 0))
    }
    private val pad = ceil(tFont * 0.4).toInt()
    private val digitW = ('0'..'9').maxOf { tFill.measureText(it.toString()) }
    private val meridiemW = maxOf(tFill.measureText("AM"), tFill.measureText("PM"))

    /** Width of a time such as "02:56 PM": every digit takes the widest digit's cell and AM / PM share one cell, so the width never changes from second to second. */
    private fun timeWidth(time: String): Float {
        val sp = time.lastIndexOf(' ')
        val body = if (sp >= 0) time.substring(0, sp) else time
        var w = 0f
        for (ch in body) w += if (ch in '0'..'9') digitW else tFill.measureText(ch.toString())
        if (sp >= 0) w += tFill.measureText(" ") + meridiemW
        return w
    }

    /** Bitmap size (width, height) for [lead] (natural width, may be null) followed by [time] (fixed-width cells, may be null), shadow room included. */
    fun measureText(lead: String?, time: String?, out: IntArray) {
        var w = 0f
        if (lead != null) w += tFill.measureText(lead)
        if (time != null) w += timeWidth(time)
        val fm = tFill.fontMetrics
        out[0] = ceil(w.toDouble()).toInt() + 2 * pad
        out[1] = ceil((fm.descent - fm.ascent).toDouble()).toInt() + 2 * pad
    }

    /** The text bitmap, [w] x [h] as [measureText] said: outline and shadow pass first, then the white fill, on the same positions. */
    fun renderText(lead: String?, time: String?, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fm = tFill.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        var x = pad.toFloat()
        if (lead != null) { drawBoth(c, lead, x, baseline); x += tFill.measureText(lead) }
        if (time != null) {
            val sp = time.lastIndexOf(' ')
            val body = if (sp >= 0) time.substring(0, sp) else time
            for (ch in body) {
                val s = ch.toString()
                if (ch in '0'..'9') { drawBoth(c, s, x + (digitW - tFill.measureText(s)) / 2f, baseline); x += digitW }
                else { drawBoth(c, s, x, baseline); x += tFill.measureText(s) }
            }
            if (sp >= 0) { x += tFill.measureText(" "); drawBoth(c, time.substring(sp + 1), x, baseline) }
        }
        return bmp
    }

    private fun drawBoth(c: Canvas, s: String, x: Float, y: Float) { c.drawText(s, x, y, tEdge); c.drawText(s, x, y, tFill) }
}
