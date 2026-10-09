package com.aamus.powerpdf.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round

/**
 * Page geometry for markup on turned pages (desktop decisions §78, upright.ts), as plain maths over
 * a page transform [a, b, c, d, e, f] (MuPDF's: the page's own PDF space → the page as shown,
 * points, y down), so it runs in JVM tests.
 */
object Upright {
    private fun tidy(v: Float): Float = if (abs(v - round(v)) < 1e-4f) round(v) else v

    /**
     * The /Matrix an appearance needs to look upright on a page shown turned by /Rotate: from the
     * drawing's own space (y up, upright as shown) to PDF space. Identity on unturned pages.
     */
    fun uprightMatrix(t: FloatArray): FloatArray {
        val (a, b, c, d) = listOf(t[0], t[1], t[2], t[3])
        val det = a * d - b * c
        return floatArrayOf(tidy(d / det), tidy(-b / det), tidy(c / det), tidy(-a / det), 0f, 0f)
    }

    /** How far the page is shown turned, as a widget's /MK /R names it: 0, 90, 180 or 270. */
    fun shownTurn(t: FloatArray): Int {
        val m = uprightMatrix(t)
        val deg = (round(Math.toDegrees(atan2(m[1].toDouble(), m[0].toDouble())) / 90.0) * 90).toInt()
        return ((deg % 360) + 360) % 360
    }

    /** The /Matrix for an appearance turned by /MK /R [degrees] (snapped to a quarter turn). */
    fun turnMatrix(degrees: Int): FloatArray {
        val r = ((Math.round(degrees / 90f) * 90 % 360) + 360) % 360
        val (cos, sin) = when (r) {
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> 1f to 0f
        }
        return floatArrayOf(cos, sin, -sin, cos, 0f, 0f)
    }

    fun isIdentity(m: FloatArray) = m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 1f && m[4] == 0f && m[5] == 0f

    /** A point of the page as shown → the page's own PDF space. */
    fun toPdf(t: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val (a, b, c, d) = listOf(t[0], t[1], t[2], t[3])
        val det = a * d - b * c
        val px = x - t[4]
        val py = y - t[5]
        return (d * px - c * py) / det to (-b * px + a * py) / det
    }

    /** A point of PDF space → the page as shown. */
    fun toShown(t: FloatArray, x: Float, y: Float): Pair<Float, Float> = (t[0] * x + t[2] * y + t[4]) to (t[1] * x + t[3] * y + t[5])

    /** A move on the page as shown → the same move in PDF space. */
    fun moveToPdf(t: FloatArray, dx: Float, dy: Float): Pair<Float, Float> {
        val (a, b, c, d) = listOf(t[0], t[1], t[2], t[3])
        val det = a * d - b * c
        return (d * dx - c * dy) / det to (-b * dx + a * dy) / det
    }

    /** The bounds, as shown, of a PDF-space rectangle [x0, y0, x1, y1]. */
    fun shownBounds(t: FloatArray, r: FloatArray): FloatArray {
        val pts = listOf(toShown(t, r[0], r[1]), toShown(t, r[2], r[1]), toShown(t, r[0], r[3]), toShown(t, r[2], r[3]))
        return floatArrayOf(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
    }

    /** The bounds, in PDF space, of a rectangle as shown. */
    fun pdfBounds(t: FloatArray, r: FloatArray): FloatArray {
        val pts = listOf(toPdf(t, r[0], r[1]), toPdf(t, r[2], r[1]), toPdf(t, r[0], r[3]), toPdf(t, r[2], r[3]))
        return floatArrayOf(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
    }

    /**
     * A sticky note's /Rect, as shown, for an icon whose top-left corner is to be at ([x], [y]) as
     * shown. Readers draw a note's icon upright from the PDF-space top-left corner of /Rect
     * (PDF 32000 §12.5.6.4, NoRotate; MuPDF too), so that corner is put there. No /Matrix: a
     * reader would turn the icon twice.
     */
    fun noteRect(t: FloatArray, x: Float, y: Float, size: Float = 20f): FloatArray {
        val (px, py) = toPdf(t, x, y)
        return shownBounds(t, floatArrayOf(px, py - size, px + size, py))
    }
}
