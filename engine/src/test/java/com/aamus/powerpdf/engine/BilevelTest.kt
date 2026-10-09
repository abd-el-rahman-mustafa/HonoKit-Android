package com.aamus.powerpdf.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilevelTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun pixelsArePackedEightToAByteWithPaperAsOne() {
        // One row of 10 pixels: ink, paper, ink, then paper; the second byte holds 2 pixels.
        val row = intArrayOf(black, white, black, white, white, white, white, white, black, white)
        val packed = Bilevel.pack(row, 10, 1)
        assertArrayEquals(byteArrayOf(0b01011111.toByte(), 0b01000000.toByte()), packed)
    }

    @Test
    fun rowsStartOnAByte() {
        val px = IntArray(3 * 2) { white }
        assertEquals(2, Bilevel.pack(px, 3, 2).size)
    }

    @Test
    fun aTextPageCompressesFarBelowItsPixels() {
        val w = 2400
        val h = 3400
        val px = IntArray(w * h) { i -> val y = i / w; if ((y / 30) % 2 == 1 && y % 30 < 12 && (i % w) % 40 < 30) black else white }
        val packed = Bilevel.pack(px, w, h)
        val deflated = Bilevel.deflate(packed)
        assertTrue("deflated ${deflated.size}", deflated.size < 60_000)
    }
}
