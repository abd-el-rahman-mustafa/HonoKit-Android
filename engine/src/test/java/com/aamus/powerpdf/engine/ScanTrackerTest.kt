package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTrackerTest {
    private fun page(dx: Float = 0f, dy: Float = 0f, k: Float = 1f) =
        floatArrayOf(0.2f, 0.15f, 0.8f, 0.15f, 0.8f, 0.85f, 0.2f, 0.85f).also { q ->
            for (i in 0 until 4) {
                q[i * 2] = 0.5f + (q[i * 2] - 0.5f) * k + dx
                q[i * 2 + 1] = 0.5f + (q[i * 2 + 1] - 0.5f) * k + dy
            }
        }

    @Test
    fun holdingStillTakesThePictureOnce() {
        val t = ScanTracker(steadyMillis = 800)
        var now = 0L
        val states = ArrayList<ScanTracker.State>()
        repeat(40) {
            states.add(t.next(page(), now).state)
            now += 50
        }
        assertEquals(1, states.count { it == ScanTracker.State.Capture })
        // Steady first, then the capture, then waiting for another page.
        assertTrue(states.indexOf(ScanTracker.State.Steady) < states.indexOf(ScanTracker.State.Capture))
        assertEquals(ScanTracker.State.Taken, states.last())
    }

    @Test
    fun movingNeverCaptures() {
        val t = ScanTracker()
        var now = 0L
        repeat(60) { i ->
            val r = t.next(page(dx = if (i % 2 == 0) 0.05f else -0.05f), now)
            assertTrue(r.state != ScanTracker.State.Capture)
            now += 50
        }
    }

    @Test
    fun aPageThatFlickersInAndOutNeverCaptures() {
        // Found in only two of every three frames (an arm passing, a shadow): never steady enough.
        val t = ScanTracker(steadyMillis = 400)
        var now = 0L
        repeat(90) { i ->
            val r = t.next(if (i % 3 == 2) null else page(), now)
            assertTrue("frame $i", r.state != ScanTracker.State.Capture)
            now += 50
        }
    }

    @Test
    fun noPageMeansSearching() {
        val t = ScanTracker()
        repeat(5) { assertEquals(ScanTracker.State.Searching, t.next(null, it * 50L).state) }
    }

    @Test
    fun theNextPageIsTakenAfterTheFirstGoesAway() {
        val t = ScanTracker(steadyMillis = 500)
        var now = 0L
        var captures = 0
        fun feed(q: FloatArray?, n: Int) = repeat(n) {
            if (t.next(q, now).state == ScanTracker.State.Capture) captures++
            now += 50
        }
        feed(page(), 20)
        feed(null, 6) // the page is turned
        feed(page(k = 0.9f), 20)
        assertEquals(2, captures)
    }

    @Test
    fun aBriefMissKeepsThePage() {
        val t = ScanTracker()
        t.next(page(), 0)
        assertEquals(ScanTracker.State.Moving, t.next(null, 50).state)
    }

    @Test
    fun areaIsTheFractionOfTheFrame() {
        assertEquals(0.6f * 0.7f, ScanTracker.area(page()), 0.001f)
    }
}
