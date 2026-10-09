package com.aamus.powerpdf.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PageFinderLogicTest {

    @Test
    fun aTallFrameIsCentredOnGrey() {
        // A 2 × 4 white frame: in the 256 square it fills the middle half across, grey at the sides.
        val p = PageFinderLogic.planes(IntArray(8) { 0xFFFFFFFF.toInt() }, 2, 4)
        val s = PageFinderLogic.SIZE
        assertEquals(0.5f, p[128 * s + 10], 0.001f)
        assertEquals(1f, p[128 * s + 128], 0.001f)
        assertEquals(0.5f, p[128 * s + 250], 0.001f)
        // Red plane first (RGB, as DocAligner's own code feeds it).
        val red = PageFinderLogic.planes(IntArray(8) { 0xFFFF0000.toInt() }, 2, 4)
        assertEquals(1f, red[128 * s + 128], 0.001f)
        assertEquals(0f, red[s * s + 128 * s + 128], 0.001f)
    }

    private fun heat(spots: List<Pair<Int, Int>>, size: Int = 128): FloatArray {
        val out = FloatArray(4 * size * size)
        for ((c, xy) in spots.withIndex()) for (dy in -2..2) for (dx in -2..2) {
            val x = xy.first + dx
            val y = xy.second + dy
            out[c * size * size + y * size + x] = 0.9f
        }
        return out
    }

    @Test
    fun spotsBecomeCornersInTheFrame() {
        // A square frame: heatmap positions map straight to fractions.
        val c = PageFinderLogic.corners(heat(listOf(16 to 16, 112 to 16, 112 to 112, 16 to 112)), 4, 128, 128, 256, 256)
        assertNotNull(c)
        c!!
        assertEquals((16.5 / 128).toFloat(), c.corners[0], 0.001f)
        assertEquals((112.5 / 128).toFloat(), c.corners[4], 0.001f)
        assertEquals(0.9f, c.confidence, 0.001f)
    }

    @Test
    fun aTallFrameUndoesThePadding() {
        // 240 × 320: the frame spans x 40..280 of the 320 square, so the square's middle is the frame's middle.
        val c = PageFinderLogic.corners(heat(listOf(64 to 64, 64 to 64, 64 to 64, 64 to 64)), 4, 128, 128, 240, 320)!!
        assertEquals(((64.5 / 128 * 320 - 40) / 240).toFloat(), c.corners[0], 0.001f)
        assertEquals((64.5 / 128).toFloat(), c.corners[1], 0.001f)
    }

    @Test
    fun aMissingCornerMeansNoPage() {
        val h = heat(listOf(16 to 16, 112 to 16, 112 to 112, 16 to 112))
        for (i in 3 * 128 * 128 until 4 * 128 * 128) h[i] = 0.05f
        assertNull(PageFinderLogic.corners(h, 4, 128, 128, 256, 256))
    }
}
