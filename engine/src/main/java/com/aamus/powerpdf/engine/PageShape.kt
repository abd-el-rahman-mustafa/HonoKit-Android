package com.aamus.powerpdf.engine

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The real shape of a sheet photographed at an angle. The lengths of the four sides as the camera
 * saw them are wrong whenever the phone was tilted: the far edge looks shorter, and a page shot
 * from the side comes out stretched (docs/research-camscanner.md, page shape). Zhang and He,
 * "Whiteboard scanning and image enhancement" (2007): a rectangle's four corners in one picture
 * fix the camera's focal length and, from it, the rectangle's width over its height. The principal
 * point is taken as the picture's centre (true of phone cameras to within a few percent).
 */
object PageShape {

    /**
     * Width over height of the rectangle whose corners [corners] (TL, TR, BR, BL; x, y as
     * fractions of the picture) are seen in a [w] × [h] picture, or the side-length estimate
     * when the corners do not fix the camera (a page shot straight on, or corners that are not a
     * rectangle in perspective). [focal]: the lens's focal length as a fraction of the picture's
     * diagonal when the photo says it (EXIF 35 mm equivalent ÷ [FILM_DIAGONAL_MM]); better than
     * the corners' own estimate, which a few pixels of error move a lot.
     */
    fun aspect(corners: FloatArray, w: Int, h: Int, focal: Double? = null): Double {
        val sides = sideAspect(corners, w, h)
        val cx = w / 2.0
        val cy = h / 2.0
        // Homogeneous points with the picture's centre as origin: m1 TL, m2 TR, m3 BL, m4 BR.
        fun pt(i: Int) = doubleArrayOf(corners[i * 2] * w - cx, corners[i * 2 + 1] * h - cy, 1.0)
        val m1 = pt(0)
        val m2 = pt(1)
        val m4 = pt(2)
        val m3 = pt(3)
        val m14 = cross(m1, m4)
        val k2 = dot(m14, m3) / dot(cross(m2, m4), m3)
        val k3 = dot(m14, m2) / dot(cross(m3, m4), m2)
        if (!k2.isFinite() || !k3.isFinite()) return sides
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val diag = hypot(w.toDouble(), h.toDouble())
        // Seen straight on (opposite sides parallel), the corners say nothing about the focal
        // length, and none is needed: the picture is the sheet scaled.
        val tilt = (abs(n2[2]) * diag / hypot(n2[0], n2[1]) + abs(n3[2]) * diag / hypot(n3[0], n3[1]))
        if (tilt < STRAIGHT) return sqrt((n2[0] * n2[0] + n2[1] * n2[1]) / (n3[0] * n3[0] + n3[1] * n3[1]))
        val f2 = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
        // The lens the photo names; else the corners' estimate, unless it is a focal length no
        // phone camera has (the corners are off, one pair of sides is parallel, or the sheet is
        // not a rectangle): then a typical phone's lens.
        val known = focal?.takeIf { it in MIN_FOCAL..MAX_FOCAL }?.let { it * diag }
        val f = known ?: sqrt(f2).takeIf { f2 > 0 && it in diag * MIN_FOCAL..diag * MAX_FOCAL } ?: (diag * TYPICAL_FOCAL)
        val ff = f * f
        val r2 = (n2[0] * n2[0] / ff + n2[1] * n2[1] / ff + n2[2] * n2[2]) /
            (n3[0] * n3[0] / ff + n3[1] * n3[1] / ff + n3[2] * n3[2])
        val r = sqrt(r2)
        // A result far from what the sides show is a broken solution, not a stronger correction.
        return if (r.isFinite() && r > 0 && r / sides in 0.5..2.0) r else sides
    }

    /** Width over height from the mean lengths of opposite sides (the plain estimate). */
    fun sideAspect(c: FloatArray, w: Int, h: Int): Double {
        fun len(a: Int, b: Int) = hypot((c[a * 2] - c[b * 2]) * w.toDouble(), (c[a * 2 + 1] - c[b * 2 + 1]) * h.toDouble())
        val top = len(0, 1)
        val bottom = len(3, 2)
        val left = len(0, 3)
        val right = len(1, 2)
        val across = (top + bottom) / 2
        val down = (left + right) / 2
        return if (down <= 0) 1.0 else across / down
    }

    private fun abs(v: Double) = kotlin.math.abs(v)

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Below this (sum of each vanishing direction's depth over its spread) the view is straight on. */
    private const val STRAIGHT = 0.02

    /** Focal lengths a phone camera can have, as fractions of the picture's diagonal (≈ 120°–20° across the diagonal). */
    private const val MIN_FOCAL = 0.28
    private const val MAX_FOCAL = 3.0

    /** A typical phone main camera (23–26 mm equivalent): about 85° across the diagonal. */
    private const val TYPICAL_FOCAL = 0.55

    /** The diagonal of a 35 mm film frame, for EXIF's "35 mm equivalent" focal length. */
    const val FILM_DIAGONAL_MM = 43.27
}
