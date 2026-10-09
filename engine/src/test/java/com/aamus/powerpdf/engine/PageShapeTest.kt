package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PageShapeTest {

    /**
     * The corners of a [pw] × [ph] sheet, [dist] in front of a camera with focal length [f]
     * (pixels) and a [w] × [h] picture, turned [tiltX] degrees about the across axis and [tiltY]
     * about the up axis, as fractions of the picture (TL, TR, BR, BL).
     */
    private fun seen(pw: Double, ph: Double, tiltX: Double, tiltY: Double, f: Double, w: Int, h: Int, dist: Double, shiftX: Double = 0.0): FloatArray {
        val ax = Math.toRadians(tiltX)
        val ay = Math.toRadians(tiltY)
        val out = FloatArray(8)
        val pts = listOf(-pw / 2 to -ph / 2, pw / 2 to -ph / 2, pw / 2 to ph / 2, -pw / 2 to ph / 2)
        for ((i, p) in pts.withIndex()) {
            var x = p.first
            var y = p.second
            var z = 0.0
            // About the x axis, then the y axis.
            val y1 = y * cos(ax) - z * sin(ax)
            val z1 = y * sin(ax) + z * cos(ax)
            y = y1; z = z1
            val x2 = x * cos(ay) + z * sin(ay)
            val z2 = -x * sin(ay) + z * cos(ay)
            x = x2 + shiftX; z = z2 + dist
            out[i * 2] = ((f * x / z + w / 2.0) / w).toFloat()
            out[i * 2 + 1] = ((f * y / z + h / 2.0) / h).toFloat()
        }
        return out
    }

    private val a4 = 210.0 / 297.0

    @Test
    fun anA4SheetShotFromTheSideKeepsItsShape() {
        // Like the comparison photo: the phone well off to one side and tilted back.
        val c = seen(210.0, 297.0, tiltX = 30.0, tiltY = 35.0, f = 3000.0, w = 3072, h = 4096, dist = 420.0)
        val sides = PageShape.sideAspect(c, 3072, 4096)
        val real = PageShape.aspect(c, 3072, 4096)
        assertTrue("the side lengths are off ($sides)", abs(sides - a4) / a4 > 0.08)
        assertEquals(a4, real, a4 * 0.01)
    }

    @Test
    fun tiltedOnlyBackwardsKeepsItsShape() {
        val c = seen(210.0, 297.0, tiltX = 40.0, tiltY = 0.0, f = 2800.0, w = 3072, h = 4096, dist = 450.0)
        // One pair of sides parallel: the corners cannot fix the lens, so a typical one is used.
        assertEquals(a4, PageShape.aspect(c, 3072, 4096), a4 * 0.03)
        // With the lens the photo names, exact.
        assertEquals(a4, PageShape.aspect(c, 3072, 4096, focal = 2800.0 / 5120.0), a4 * 0.003)
    }

    @Test
    fun straightOnIsTheSidesShape() {
        val c = seen(210.0, 297.0, tiltX = 0.0, tiltY = 0.0, f = 3000.0, w = 3072, h = 4096, dist = 400.0)
        assertEquals(a4, PageShape.aspect(c, 3072, 4096), a4 * 0.005)
    }

    @Test
    fun aLandscapeCardOffCentre() {
        val c = seen(85.6, 53.98, tiltX = 25.0, tiltY = -20.0, f = 3000.0, w = 3072, h = 4096, dist = 260.0, shiftX = 20.0)
        assertEquals(85.6 / 53.98, PageShape.aspect(c, 3072, 4096), 85.6 / 53.98 * 0.02)
    }

    @Test
    fun cornersThatAreNoRectangleFallBackToTheSides() {
        // A kite: no camera sees a rectangle like this.
        val c = floatArrayOf(0.5f, 0.1f, 0.9f, 0.5f, 0.5f, 0.9f, 0.1f, 0.5f)
        val a = PageShape.aspect(c, 1000, 1000)
        assertTrue(a.isFinite() && a > 0)
        assertEquals(PageShape.sideAspect(c, 1000, 1000), a, 0.6)
    }
}
