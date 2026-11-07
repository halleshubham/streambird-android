package app.streambird.android.ads

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Finding the window of an L-shaped (frame) ad: a 1:1 port of the web studio's web-app/src/studio/adWindow.ts. Pure Kotlin on decoded pixels
 * (no Android classes), so it is JVM-tested with synthetic pixel arrays.
 *
 * THE SPEC
 *   clear pixel   alpha < 16 (transparent), or opaque near-white: alpha >= 200 and min(r, g, b) >= 246.
 *   grid          the bitmap is looked at in square cells of about 4 px (bigger for huge bitmaps so at most about 640 cells span the long
 *                 side); a cell is clear when the pixel at its centre is clear.
 *   seed          the cell at the image centre; if that one is not clear (text or a logo sits there) the nearest clear cell within a quarter
 *                 of the shorter side, searched in growing rings.
 *   region        4-connected flood fill of clear cells from the seed (iterative, one pass over the grid at most).
 *   box           the bounding box of the region, then refined at pixel level: each edge moves inward while its row or column is less than
 *                 90 % clear, and outward (at most one cell) while the next one is 98 % clear.
 *   validation    the box covers at least 25 % of the picture's area and at least 20 % of each axis, is not (nearly) the whole picture
 *                 (that is a picture without a frame), and the region fills at least 70 % of its box (a clear area that is itself L-shaped
 *                 or ragged is not a window). Otherwise: null.
 *   result        [NormRect] as fractions 0..1 of the picture, or null when there is no usable window.
 */

/** Decoded pixels, ARGB ints (what Bitmap.getPixels returns: alpha in the top byte, NOT premultiplied), row by row. */
class PixelImage(val width: Int, val height: Int, val argb: IntArray)

object WindowRules {
    const val CELL_PX = 4
    const val MAX_CELLS_ON_LONG_SIDE = 640
    const val ALPHA_CLEAR = 16
    const val ALPHA_OPAQUE = 200
    const val WHITE_MIN = 246
    const val MIN_AREA_FRACTION = 0.25
    const val MIN_AXIS_FRACTION = 0.2
    const val MAX_AXIS_FRACTION = 0.98
    const val MIN_FILL = 0.7

    /** The bitmap handed to [detectWindow] is at most this many pixels on its long side (the web decodes to <= 1280 too). */
    const val MAX_DETECT_SIDE = 1280
}

/** True for a pixel that belongs to the window: transparent, or opaque near-white. */
fun isClearPixel(r: Int, g: Int, b: Int, a: Int): Boolean {
    if (a < WindowRules.ALPHA_CLEAR) return true
    return a >= WindowRules.ALPHA_OPAQUE && min(r, min(g, b)) >= WindowRules.WHITE_MIN
}

private fun clearAt(img: PixelImage, x: Int, y: Int): Boolean {
    val p = img.argb[y * img.width + x]
    return isClearPixel((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff, (p ushr 24) and 0xff)
}

private fun clearShareRow(img: PixelImage, y: Int, x0: Int, x1: Int): Double {
    var n = 0
    for (x in x0 until x1) if (clearAt(img, x, y)) n++
    return if (x1 > x0) n.toDouble() / (x1 - x0) else 0.0
}

private fun clearShareCol(img: PixelImage, x: Int, y0: Int, y1: Int): Double {
    var n = 0
    for (y in y0 until y1) if (clearAt(img, x, y)) n++
    return if (y1 > y0) n.toDouble() / (y1 - y0) else 0.0
}

/** The window of a frame graphic as a normalised rectangle, or null when the picture has no usable clear window. [cellPx] overrides the grid size (tests). */
fun detectWindow(img: PixelImage, cellPx: Int = WindowRules.CELL_PX): NormRect? {
    val w = img.width
    val h = img.height
    if (w < 8 || h < 8 || img.argb.size < w * h) return null
    val cell = max(cellPx, ceil(max(w, h).toDouble() / WindowRules.MAX_CELLS_ON_LONG_SIDE).toInt())
    val cols = ceil(w.toDouble() / cell).toInt()
    val rows = ceil(h.toDouble() / cell).toInt()
    val clear = BooleanArray(cols * rows)
    for (cy in 0 until rows) {
        val py = min(h - 1, cy * cell + (cell shr 1))
        for (cx in 0 until cols) {
            val px = min(w - 1, cx * cell + (cell shr 1))
            if (clearAt(img, px, py)) clear[cy * cols + cx] = true
        }
    }

    // Seed: the centre cell, else the nearest clear cell (rings of growing radius, within a quarter of the short side).
    val ccx = min(cols - 1, w / 2 / cell)
    val ccy = min(rows - 1, h / 2 / cell)
    var seed = -1
    if (clear[ccy * cols + ccx]) seed = ccy * cols + ccx
    else {
        val maxR = (min(w, h) / 4) / cell
        var r = 1
        while (r <= maxR && seed < 0) {
            var dy = -r
            while (dy <= r && seed < 0) {
                for (dx in -r..r) {
                    if (max(abs(dx), abs(dy)) != r) continue
                    val x = ccx + dx
                    val y = ccy + dy
                    if (x < 0 || y < 0 || x >= cols || y >= rows) continue
                    if (clear[y * cols + x]) { seed = y * cols + x; break }
                }
                dy++
            }
            r++
        }
    }
    if (seed < 0) return null

    // Flood fill (explicit stack, each cell visited once).
    val seen = BooleanArray(cols * rows)
    val stack = IntArray(cols * rows)
    var sp = 0
    stack[sp++] = seed
    seen[seed] = true
    var minC = cols; var maxC = -1; var minR = rows; var maxR2 = -1; var count = 0
    while (sp > 0) {
        val i = stack[--sp]
        val cx = i % cols
        val cy = i / cols
        count++
        if (cx < minC) minC = cx
        if (cx > maxC) maxC = cx
        if (cy < minR) minR = cy
        if (cy > maxR2) maxR2 = cy
        if (cx > 0 && clear[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
        if (cx < cols - 1 && clear[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
        if (cy > 0 && clear[i - cols] && !seen[i - cols]) { seen[i - cols] = true; stack[sp++] = i - cols }
        if (cy < rows - 1 && clear[i + cols] && !seen[i + cols]) { seen[i + cols] = true; stack[sp++] = i + cols }
    }

    val boxCells = (maxC - minC + 1).toLong() * (maxR2 - minR + 1)
    if (count.toDouble() / boxCells < WindowRules.MIN_FILL) return null

    var x0 = minC * cell
    var x1 = min(w, (maxC + 1) * cell)
    var y0 = minR * cell
    var y1 = min(h, (maxR2 + 1) * cell)

    // Refine to the pixel: an edge moves in while its line is mostly not clear, and out while the next line outside is clear.
    // left/top edges are the first clear line; right/bottom edges the last clear line (inclusive).
    x0 = run {
        var p = x0
        var n = 0
        while (n < cell * 2 && p < w && clearShareCol(img, p, y0, y1) < 0.9) { p++; n++ }
        n = 0
        while (n < cell && p > 0 && clearShareCol(img, p - 1, y0, y1) >= 0.98) { p--; n++ }
        p
    }
    x1 = run {
        var p = x1 - 1
        var n = 0
        while (n < cell * 2 && p > 0 && clearShareCol(img, p, y0, y1) < 0.9) { p--; n++ }
        n = 0
        while (n < cell && p < w - 1 && clearShareCol(img, p + 1, y0, y1) >= 0.98) { p++; n++ }
        p + 1
    }
    y0 = run {
        var p = y0
        var n = 0
        while (n < cell * 2 && p < h && clearShareRow(img, p, x0, x1) < 0.9) { p++; n++ }
        n = 0
        while (n < cell && p > 0 && clearShareRow(img, p - 1, x0, x1) >= 0.98) { p--; n++ }
        p
    }
    y1 = run {
        var p = y1 - 1
        var n = 0
        while (n < cell * 2 && p > 0 && clearShareRow(img, p, x0, x1) < 0.9) { p--; n++ }
        n = 0
        while (n < cell && p < h - 1 && clearShareRow(img, p + 1, x0, x1) >= 0.98) { p++; n++ }
        p + 1
    }

    val nw = (x1 - x0).toDouble() / w
    val nh = (y1 - y0).toDouble() / h
    if (!(nw > 0) || !(nh > 0)) return null
    if (nw < WindowRules.MIN_AXIS_FRACTION || nh < WindowRules.MIN_AXIS_FRACTION) return null
    if (nw * nh < WindowRules.MIN_AREA_FRACTION) return null
    if (nw > WindowRules.MAX_AXIS_FRACTION && nh > WindowRules.MAX_AXIS_FRACTION) return null
    return NormRect(x0.toDouble() / w, y0.toDouble() / h, nw, nh)
}

/** The verdict shown in the kit editor for an L-shaped frame picture. */
sealed interface FrameWindowState {
    object Checking : FrameWindowState
    /** The picture could not be fetched or decoded (not the same as "no window"). */
    data class Unreadable(val reason: String) : FrameWindowState
    object NoWindow : FrameWindowState
    data class Found(val window: NormRect) : FrameWindowState
}

const val NO_WINDOW_WARNING = "No clear window found in this graphic: the picture will not be placed in a window. It is shown as a full-screen overlay and the live picture is not resized."
const val FRAME_NEEDS_SHRINK = "L-shaped ads always resize the picture, so they only run while \"Shrink picture while ads show\" is on."

fun frameWindowText(s: FrameWindowState): String? = when (s) {
    FrameWindowState.Checking -> null
    is FrameWindowState.Unreadable -> "Could not check this graphic for a window (${s.reason.take(80)})."
    FrameWindowState.NoWindow -> NO_WINDOW_WARNING
    is FrameWindowState.Found -> {
        fun pct(v: Double) = Math.round(v * 100).toInt()
        "Window found: ${pct(s.window.w)}% x ${pct(s.window.h)}% of the picture. The live picture is placed inside it."
    }
}
