package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PageEdgesTest {

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

    /**
     * A photo: [background] with noise, a page with the corners [q] (pixels), lit unevenly, with
     * rows of dark "writing" inside.
     */
    private fun photo(w: Int, h: Int, q: List<Pair<Double, Double>>, background: (Int, Int, Random) -> Int, seed: Int = 1): IntArray {
        val rnd = Random(seed)
        val out = IntArray(w * h)
        val cx = q.sumOf { it.first } / 4
        val top = q.minOf { it.second }
        val bottom = q.maxOf { it.second }
        for (y in 0 until h) for (x in 0 until w) {
            out[y * w + x] = if (inside(x + 0.5, y + 0.5, q)) {
                val light = 238 - ((y - top) / (bottom - top) * 30).toInt() + rnd.nextInt(-6, 7)
                val row = ((y - top) / (bottom - top) * 40).toInt()
                val writing = row % 3 == 1 && (y - top) > (bottom - top) * 0.1 && (y - top) < (bottom - top) * 0.9 &&
                    kotlin.math.abs(x - cx) < 0.3 * w && (x / 3) % 4 != 0
                if (writing) rgb(40, 40, 45) else rgb(light, light, light - 4)
            } else background(x, y, rnd)
        }
        return out
    }

    private fun assertCorners(expected: List<Pair<Double, Double>>, found: FloatArray?, w: Int, h: Int) {
        assertNotNull("no page found", found)
        found!!
        for (k in 0 until 4) {
            assertEquals("corner $k x", expected[k].first / w, found[k * 2].toDouble(), 0.03)
            assertEquals("corner $k y", expected[k].second / h, found[k * 2 + 1].toDouble(), 0.03)
        }
    }

    private val wood: (Int, Int, Random) -> Int = { x, y, r -> val g = ((x + y * 3) / 7) % 9; rgb(140 + g + r.nextInt(-8, 9), 98 + g, 60 + r.nextInt(-5, 6)) }

    @Test
    fun tiltedPageOnWood() {
        val w = 240
        val h = 320
        val q = listOf(60.0 to 50.0, 200.0 to 70.0, 185.0 to 290.0, 35.0 to 260.0)
        assertCorners(q, PageEdges.find(photo(w, h, q, wood), w, h), w, h)
    }

    @Test
    fun pageOnDarkDeskSeenAtAnAngle() {
        val w = 320
        val h = 240
        // Perspective: the far edge (top) narrower than the near one.
        val q = listOf(95.0 to 30.0, 225.0 to 32.0, 290.0 to 225.0, 30.0 to 220.0)
        val desk: (Int, Int, Random) -> Int = { _, _, r -> val v = 55 + r.nextInt(-10, 11); rgb(v, v + 2, v + 6) }
        assertCorners(q, PageEdges.find(photo(w, h, q, desk), w, h), w, h)
    }

    @Test
    fun pageTouchingTheEdgeOfThePhoto() {
        val w = 240
        val h = 320
        val q = listOf(20.0 to 0.0, 230.0 to 0.0, 215.0 to 300.0, 30.0 to 310.0)
        assertCorners(q, PageEdges.find(photo(w, h, q, wood), w, h), w, h)
    }

    @Test
    fun pageOnPatternedCloth() {
        val w = 240
        val h = 320
        val q = listOf(50.0 to 60.0, 195.0 to 45.0, 210.0 to 270.0, 40.0 to 285.0)
        // Dark blue and red checks with thin stripes: edges everywhere.
        val cloth: (Int, Int, Random) -> Int = { x, y, r ->
            val check = ((x / 9) + (y / 9)) % 2 == 0
            val stripe = x % 13 == 0 || y % 17 == 0
            when {
                stripe -> rgb(150, 140, 60)
                check -> rgb(30 + r.nextInt(10), 40, 110)
                else -> rgb(120, 30 + r.nextInt(10), 40)
            }
        }
        assertCorners(q, PageEdges.find(photo(w, h, q, cloth), w, h), w, h)
    }

    @Test
    fun darkStripeAlongThePageEdgeStaysPage() {
        val w = 240
        val h = 320
        val q = listOf(40.0 to 40.0, 200.0 to 40.0, 200.0 to 280.0, 40.0 to 280.0)
        val base = photo(w, h, q, wood)
        // A red letterhead stripe down the page's left edge.
        for (y in 40 until 280) for (x in 40 until 52) base[y * w + x] = rgb(200, 10, 40)
        assertCorners(q, PageEdges.find(base, w, h), w, h)
    }

    @Test
    fun noPageInPlainNoise() {
        val w = 200
        val h = 200
        val rnd = Random(3)
        val px = IntArray(w * h) { val v = 120 + rnd.nextInt(-20, 21); rgb(v, v, v) }
        assertNull(PageEdges.find(px, w, h))
    }

    // The live camera's paper check (auto capture only fires on a confident detection).

    private fun confident(px: IntArray, w: Int, h: Int): Boolean = PageEdges.detect(px, w, h)?.confident == true

    @Test
    fun realPagesAreConfident() {
        val w = 240
        val h = 320
        val tilted = listOf(60.0 to 50.0, 200.0 to 70.0, 185.0 to 290.0, 35.0 to 260.0)
        assertEquals(PageEdges.detect(photo(w, h, tilted, wood), w, h).toString(), true, confident(photo(w, h, tilted, wood), w, h))
        val desk: (Int, Int, Random) -> Int = { _, _, r -> val v = 55 + r.nextInt(-10, 11); rgb(v, v + 2, v + 6) }
        val angled = listOf(95.0 to 30.0, 225.0 to 32.0, 290.0 to 225.0, 30.0 to 220.0)
        assertEquals(true, confident(photo(320, 240, angled, desk), 320, 240))
    }

    @Test
    fun aPageRunningOffThePhotoIsStillAPage() {
        val w = 240
        val h = 320
        val q = listOf(20.0 to 0.0, 230.0 to 0.0, 215.0 to 300.0, 30.0 to 310.0)
        val d = PageEdges.detect(photo(w, h, q, wood), w, h)
        // Held close, a page touches the frame: the side along it is not measured, not held against it.
        assertNotNull(d)
        assertEquals(d.toString(), true, d!!.confident)
    }

    @Test
    fun aLightWallAboveADarkFloorIsNoPage() {
        val w = 240
        val h = 320
        val rnd = Random(5)
        // A wall lit from one side over the top 60%, a dark floor below, a skirting line between.
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                y < 190 -> { val v = 215 - x / 8 + rnd.nextInt(-4, 5); rgb(v, v - 2, v - 8) }
                y < 196 -> rgb(230, 230, 230)
                else -> { val v = 70 + rnd.nextInt(-8, 9); rgb(v + 20, v + 10, v) }
            }
        }
        assertEquals(PageEdges.detect(px, w, h)?.toString(), false, confident(px, w, h))
    }

    @Test
    fun aLampGlowInADarkRoomIsNoPage() {
        val w = 240
        val h = 320
        val rnd = Random(7)
        // A soft round glow: light in the middle fading into the dark.
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val d = kotlin.math.hypot((x - 120).toDouble(), (y - 150).toDouble())
            val v = (250 - d * 2.2).toInt().coerceIn(30, 250) + rnd.nextInt(-5, 6)
            rgb(v, v - 5, v - 20)
        }
        assertEquals(PageEdges.detect(px, w, h)?.toString(), false, confident(px, w, h))
    }

    @Test
    fun aLightBlobIsNoPage() {
        val w = 240
        val h = 320
        val rnd = Random(9)
        // A light, irregular shape (a pillow, a patch of sun) on a dark sofa.
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = 70 + 25 * kotlin.math.sin(kotlin.math.atan2((y - 160).toDouble(), (x - 120).toDouble()) * 3)
            val inside = kotlin.math.hypot((x - 120).toDouble(), (y - 160).toDouble()) < r
            val v = if (inside) 225 + rnd.nextInt(-6, 7) else 50 + rnd.nextInt(-8, 9)
            rgb(v, v, v - 6)
        }
        assertEquals(PageEdges.detect(px, w, h)?.toString(), false, confident(px, w, h))
    }

    @Test
    fun pageFillingThePhotoIsLeftWhole() {
        val w = 200
        val h = 260
        val q = listOf(-5.0 to -5.0, 205.0 to -5.0, 205.0 to 265.0, -5.0 to 265.0)
        assertNull(PageEdges.find(photo(w, h, q, wood), w, h))
    }
}
