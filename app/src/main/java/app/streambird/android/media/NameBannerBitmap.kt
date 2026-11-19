package app.streambird.android.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * Renders the name banner into a Bitmap: a dark semi-transparent rounded bar with a coloured edge on the left, the name in white bold and the title
 * (when there is one) below it, lighter and smaller. Built only when the text changes (see FrameCompositor.drawBanner); text wider than the room is
 * squeezed horizontally, never cut. Android code, not unit-tested; every number comes from the pure [NameBannerLayout].
 */
class NameBannerBitmap(private val canvasW: Int, private val canvasH: Int) {
    private val nameFont = NameBannerLayout.nameFontPx(canvasW, canvasH)
    private val titleFont = NameBannerLayout.titleFontPx(canvasW, canvasH)
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = nameFont; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 255, 255); textSize = titleFont; typeface = Typeface.SANS_SERIF }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NameBannerLayout.BAR_ARGB }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NameBannerLayout.ACCENT_ARGB }

    /** The banner for [name] / [title], at most [maxW] px wide (the width limit comes from [NameBannerLayout.maxBarWidth]). */
    fun render(name: String, title: String?, maxW: Int): Bitmap {
        namePaint.textScaleX = 1f; titlePaint.textScaleX = 1f
        val accent = NameBannerLayout.accentPx(canvasW, canvasH)
        val padX = NameBannerLayout.padX(canvasW, canvasH)
        val textRoom = (maxW - accent - 2 * padX).coerceAtLeast(1)
        val nameNatural = ceil(namePaint.measureText(name).toDouble()).toInt()
        val titleNatural = title?.let { ceil(titlePaint.measureText(it).toDouble()).toInt() }
        // Squeeze (never cut) a line that does not fit.
        if (nameNatural > textRoom) namePaint.textScaleX = textRoom.toFloat() / nameNatural
        if (titleNatural != null && titleNatural > textRoom) titlePaint.textScaleX = textRoom.toFloat() / titleNatural
        val nameW = minOf(nameNatural, textRoom)
        val titleW = titleNatural?.let { minOf(it, textRoom) }
        val nfm = namePaint.fontMetrics
        val tfm = titlePaint.fontMetrics
        val nameH = ceil((nfm.descent - nfm.ascent).toDouble()).toInt()
        val titleH = titleW?.let { ceil((tfm.descent - tfm.ascent).toDouble()).toInt() }
        val (w, h) = NameBannerLayout.barSize(canvasW, canvasH, nameW, nameH, titleW, titleH, maxW)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = NameBannerLayout.cornerPx(canvasW, canvasH).toFloat()
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, barPaint)
        // The coloured edge: a strip along the left side, clipped to the rounded corners of the bar.
        c.save()
        c.clipRect(0f, 0f, accent.toFloat(), h.toFloat())
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, accentPaint)
        c.restore()
        val padY = NameBannerLayout.padY(canvasW, canvasH)
        val x = (accent + padX).toFloat()
        c.drawText(name, x, padY - nfm.ascent, namePaint)
        if (title != null && titleH != null) c.drawText(title, x, padY + nameH + NameBannerLayout.lineGap(canvasW, canvasH) - tfm.ascent, titlePaint)
        return bmp
    }
}
