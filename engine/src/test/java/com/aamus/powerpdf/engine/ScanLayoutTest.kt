package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLayoutTest {

    private fun rgb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun aCardPrintsAtItsRealSize() {
        val (w, h) = ScanLayout.cardSize(1000, 620)
        assertEquals(85.6f, w, 0.01f)
        assertEquals(53.98f, h, 0.01f)
        // Held upright, it stays upright.
        val (w2, h2) = ScanLayout.cardSize(640, 1000)
        assertEquals(53.98f, w2, 0.01f)
        assertEquals(85.6f, h2, 0.01f)
    }

    @Test
    fun aPassportPageIsNotSquashedIntoACard() {
        val (w, h) = ScanLayout.cardSize(1250, 880)
        assertEquals(125f, w, 0.01f)
        assertEquals(88f, h, 0.01f)
    }

    @Test
    fun anOddShapeKeepsItsShape() {
        val (w, h) = ScanLayout.cardSize(1000, 1000)
        assertEquals(85.6f, w, 0.01f)
        assertEquals(85.6f, h, 0.01f)
    }

    /** An open book: two light pages with text lines, a darker fold at [fold] of the width. */
    private fun book(w: Int, h: Int, fold: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val d = kotlin.math.abs(x - fold)
        when {
            d < 12 -> rgb(120 + d * 8)
            y % 14 in 5..7 && (x / 7) % 5 != 4 && d > 30 -> rgb(40)
            else -> rgb(235)
        }
    }

    @Test
    fun theSpineIsFoundAtTheFold() {
        val w = 1200
        val h = 800
        val s = ScanLayout.spine(book(w, h, 640), w, h)
        assertEquals(640f / w, s, 0.01f)
    }

    @Test
    fun noFoldMeansTheMiddle() {
        val w = 1200
        val h = 800
        val px = IntArray(w * h) { rgb(230) }
        assertEquals(0.5f, ScanLayout.spine(px, w, h), 0.0001f)
    }

    @Test
    fun brightStretchesADullPhoto() {
        // A dull card: everything between 70 and 180.
        val w = 200
        val h = 100
        val px = IntArray(w * h) { i -> rgb(70 + (i % w) * 110 / w) }
        ScanFilters.apply(px, w, h, ScanLook.Bright)
        val lo = px.minOf { it and 255 }
        val hi = px.maxOf { it and 255 }
        assertTrue("range $lo..$hi", lo < 10 && hi > 245)
    }
}
