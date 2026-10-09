package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random

/** The edge-and-line page search (PageQuad) on synthetic scenes; real frames: PageEdgesFramesTest. */
class PageQuadTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun inside(x: Double, y: Double, q: List<Pair<Double, Double>>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val (ax, ay) = q[i]
            val (bx, by) = q[(i + 1) % 4]
            val c = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val s = if (c > 0) 1 else if (c < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    /** A page with the corners [q] and rows of writing, over [background]. */
    private fun photo(w: Int, h: Int, q: List<Pair<Double, Double>>, paper: Int, background: (Int, Int, Random) -> Int): IntArray {
        val rnd = Random(1)
        val top = q.minOf { it.second }
        val bottom = q.maxOf { it.second }
        val cx = q.sumOf { it.first } / 4
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (inside(x + 0.5, y + 0.5, q)) {
                val row = ((y - top) / (bottom - top) * 40).toInt()
                val writing = row % 3 == 1 && (y - top) > (bottom - top) * 0.1 && (y - top) < (bottom - top) * 0.9 &&
                    kotlin.math.abs(x - cx) < 0.3 * w && (x / 3) % 4 != 0
                val v = paper + rnd.nextInt(-4, 5)
                if (writing) rgb(40, 40, 45) else rgb(v, v, v - 3)
            } else background(x, y, rnd)
        }
    }

    private fun assertCorners(expected: List<Pair<Double, Double>>, found: PageQuad.Found?, w: Int, h: Int) {
        assertNotNull("no page found", found)
        found!!
        assertEquals(found.toString(), true, found.confident)
        for (k in 0 until 4) {
            assertEquals("corner $k x", expected[k].first / w, found.corners[k * 2].toDouble(), 0.03)
            assertEquals("corner $k y", expected[k].second / h, found.corners[k * 2 + 1].toDouble(), 0.03)
        }
    }

    private val wood: (Int, Int, Random) -> Int = { x, y, r -> val g = ((x + y * 3) / 7) % 9; rgb(140 + g + r.nextInt(-8, 9), 98 + g, 60 + r.nextInt(-5, 6)) }

    /** A light grey desk, as in the owner's office frames. */
    private val greyDesk: (Int, Int, Random) -> Int = { _, _, r -> val v = 185 + r.nextInt(-5, 6); rgb(v, v, v + 2) }

    @Test
    fun tiltedPageOnWood() {
        val q = listOf(60.0 to 50.0, 200.0 to 70.0, 185.0 to 290.0, 35.0 to 260.0)
        assertCorners(q, PageQuad.detect(photo(240, 320, q, 236, wood), 240, 320), 240, 320)
    }

    @Test
    fun whitePageOnALightGreyDesk() {
        val q = listOf(50.0 to 40.0, 195.0 to 55.0, 205.0 to 290.0, 30.0 to 280.0)
        assertCorners(q, PageQuad.detect(photo(240, 320, q, 238, greyDesk), 240, 320), 240, 320)
    }

    @Test
    fun aPageFillingMostOfTheFrameAndTouchingIt() {
        // Held close: the top runs off the frame.
        val q = listOf(15.0 to -10.0, 228.0 to -10.0, 222.0 to 300.0, 22.0 to 308.0)
        val found = PageQuad.detect(photo(240, 320, q, 238, greyDesk), 240, 320)
        assertNotNull(found)
        assertEquals(found.toString(), true, found!!.confident)
        assertEquals(0.0, found.corners[1].toDouble(), 0.03)
        assertEquals(300.0 / 320, found.corners[5].toDouble(), 0.03)
    }

    @Test
    fun aLightWallAboveADarkFloorIsNoPage() {
        val rnd = Random(5)
        val px = IntArray(240 * 320) { i ->
            val x = i % 240
            val y = i / 240
            when {
                y < 190 -> { val v = 215 - x / 8 + rnd.nextInt(-4, 5); rgb(v, v - 2, v - 8) }
                y < 196 -> rgb(230, 230, 230)
                else -> { val v = 70 + rnd.nextInt(-8, 9); rgb(v + 20, v + 10, v) }
            }
        }
        val f = PageQuad.detect(px, 240, 320)
        assertEquals(f?.toString(), false, f?.confident == true)
    }

    @Test
    fun aLampGlowInADarkRoomIsNoPage() {
        val rnd = Random(7)
        val px = IntArray(240 * 320) { i ->
            val x = i % 240
            val y = i / 240
            val d = kotlin.math.hypot((x - 120).toDouble(), (y - 150).toDouble())
            val v = (250 - d * 2.2).toInt().coerceIn(30, 250) + rnd.nextInt(-5, 6)
            rgb(v, v - 5, v - 20)
        }
        val f = PageQuad.detect(px, 240, 320)
        assertEquals(f?.toString(), false, f?.confident == true)
    }

    @Test
    fun aLightBlobIsNoPage() {
        val rnd = Random(9)
        val px = IntArray(240 * 320) { i ->
            val x = i % 240
            val y = i / 240
            val r = 70 + 25 * kotlin.math.sin(kotlin.math.atan2((y - 160).toDouble(), (x - 120).toDouble()) * 3)
            val inside = kotlin.math.hypot((x - 120).toDouble(), (y - 160).toDouble()) < r
            val v = if (inside) 225 + rnd.nextInt(-6, 7) else 50 + rnd.nextInt(-8, 9)
            rgb(v, v, v - 6)
        }
        val f = PageQuad.detect(px, 240, 320)
        assertEquals(f?.toString(), false, f?.confident == true)
    }

    @Test
    fun cornersFromTheModelAreCheckedForShape() {
        // A tilted sheet filling half the frame: fine.
        assertEquals(true, PageQuad.plausible(floatArrayOf(0.2f, 0.15f, 0.8f, 0.2f, 0.85f, 0.85f, 0.15f, 0.8f)))
        // Too small to take by itself.
        assertEquals(false, PageQuad.plausible(floatArrayOf(0.4f, 0.4f, 0.6f, 0.4f, 0.6f, 0.6f, 0.4f, 0.6f)))
        // Crossed corners (not convex) or a sliver.
        assertEquals(false, PageQuad.plausible(floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f, 0.8f, 0.2f, 0.2f, 0.8f)))
        assertEquals(false, PageQuad.plausible(floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.2f, 0.1f, 0.9f)))
    }

    @Test
    fun aDarkKeyboardIsNoPage() {
        // A dark rectangle (keyboard) on a light desk: straight edges, but darker inside, not paper.
        val rnd = Random(11)
        val q = listOf(30.0 to 90.0, 210.0 to 90.0, 210.0 to 200.0, 30.0 to 200.0)
        val px = IntArray(240 * 320) { i ->
            val x = i % 240
            val y = i / 240
            if (inside(x + 0.5, y + 0.5, q)) {
                val key = (x / 12 + y / 12) % 2 == 0
                val v = (if (key) 40 else 25) + rnd.nextInt(-3, 4)
                rgb(v, v, v)
            } else { val v = 185 + rnd.nextInt(-5, 6); rgb(v, v, v) }
        }
        val f = PageQuad.detect(px, 240, 320)
        assertEquals(f?.toString(), false, f?.confident == true)
    }
}
