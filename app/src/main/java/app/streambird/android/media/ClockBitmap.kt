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
class ClockBitmap(private val canvasH: Int, private val size: ClockSize) {
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
}
