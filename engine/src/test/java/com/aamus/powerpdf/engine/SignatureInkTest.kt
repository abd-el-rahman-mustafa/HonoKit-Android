package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SignatureInkTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Paper with noise and uneven light, a dark shadow along the left edge, and a blue stroke. */
    private fun photo(w: Int, h: Int): IntArray {
        val rnd = Random(5)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val paper = 225 - y * 25 / h + rnd.nextInt(-6, 7)
            // A wave from x 60 to 240 around y 100, 5 px thick.
            val wave = 100 + 30 * kotlin.math.sin(x / 18.0)
            when {
                x < 4 -> rgb(40, 40, 40)
                x in 60..240 && kotlin.math.abs(y - wave) < 2.5 -> rgb(30, 50, 140)
                else -> rgb(paper, paper, paper - 5)
            }
        }
    }

    @Test
    fun keepsTheInkCropsAndClearsThePaper() {
        val w = 300
        val h = 200
        val r = SignatureInk.extract(photo(w, h), w, h, ink = 0x111111)
        assertNotNull(r)
        r!!
        // Cropped to the stroke (60–240 wide, about 70–130 high) plus a margin; the shadow is gone.
        assertTrue("width ${r.width}", r.width in 180..205)
        assertTrue("height ${r.height}", r.height in 60..90)
        // Paper is transparent, ink opaque and in the chosen colour.
        assertEquals(0, r.argb[0] ushr 24)
        val inked = r.argb.filter { (it ushr 24) == 255 }
        assertTrue(inked.size > 300)
        assertTrue(inked.all { (it and 0xFFFFFF) == 0x111111 })
    }

    @Test
    fun blankPaperHasNoSignature() {
        val rnd = Random(9)
        val w = 200
        val h = 120
        val px = IntArray(w * h) { val v = 220 + rnd.nextInt(-5, 6); rgb(v, v, v) }
        assertNull(SignatureInk.extract(px, w, h, ink = null))
    }
}
