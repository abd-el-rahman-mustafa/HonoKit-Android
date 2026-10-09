package com.aamus.powerpdf.engine.img

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {
    private val eps = 0.001f

    private fun px(b: CropBox, w: Float, h: Float) = listOf(b.l * w, b.t * h, b.r * w, b.b * h)

    private fun near(a: CropBox, b: CropBox) = assertEquals(listOf(a.l, a.t, a.r, a.b).toString(), 0f, listOf(a.l - b.l, a.t - b.t, a.r - b.r, a.b - b.b).maxOf { kotlin.math.abs(it) }, eps)

    private fun assertInside(b: CropBox) {
        assertTrue("inside: $b", b.l >= -eps && b.t >= -eps && b.r <= 1 + eps && b.b <= 1 + eps && b.l < b.r && b.t < b.b)
    }

    @Test
    fun fitIsTheLargestCentredBoxOfThatShape() {
        val b = CropMath.fit(1f, 400, 200)
        assertEquals(listOf(100f, 0f, 300f, 200f), px(b, 400f, 200f).map { Math.round(it).toFloat() })
        val wide = CropMath.fit(16f / 9, 1000, 1000)
        assertEquals(1000f / (1000f * 9 / 16), (wide.w * 1000) / (wide.h * 1000), 0.01f)
    }

    @Test
    fun fitStaysInsideWhenTheCentreIsNearAnEdge() {
        val b = CropMath.fit(1f, 400, 400, cx = 0.95f, cy = 0.05f, scale = 0.5f)
        assertInside(b)
        assertEquals(1f, b.r, eps)
        assertEquals(0f, b.t, eps)
    }

    @Test
    fun turningFourTimesAndMirroringTwiceGiveTheSameBox() {
        val b = CropBox(0.1f, 0.2f, 0.6f, 0.9f)
        var t = b
        repeat(4) { t = CropMath.turn(t, clockwise = true) }
        near(b, t)
        near(b, CropMath.turn(CropMath.turn(b, true), false))
        near(b, CropMath.mirror(CropMath.mirror(b, true), true))
        // A box at the top left goes to the top right after a clockwise turn.
        val cw = CropMath.turn(CropBox(0f, 0f, 0.5f, 0.25f), true)
        near(CropBox(0.75f, 0f, 1f, 0.5f), cw)
    }

    @Test
    fun handlesAreFoundByReach() {
        val b = CropBox(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(CropHandle.TopLeft, CropMath.handleAt(b, 102f, 98f, 400f, 400f, 20f))
        assertEquals(CropHandle.Right, CropMath.handleAt(b, 305f, 200f, 400f, 400f, 20f))
        assertEquals(CropHandle.Move, CropMath.handleAt(b, 200f, 200f, 400f, 400f, 20f))
        assertNull(CropMath.handleAt(b, 10f, 10f, 400f, 400f, 20f))
    }

    @Test
    fun freeCornerFollowsTheFingerAndStopsAtThePicture() {
        val b = CropMath.drag(CropBox(0.25f, 0.25f, 0.75f, 0.75f), CropHandle.BottomRight, 50f, 500f, 400f, 400f, null, 40f)
        assertEquals(listOf(100f, 100f, 350f, 400f), px(b, 400f, 400f))
    }

    @Test
    fun freeEdgeKeepsTheSmallestSize() {
        val b = CropMath.drag(CropBox(0.25f, 0.25f, 0.75f, 0.75f), CropHandle.Left, 300f, 0f, 400f, 400f, null, 40f)
        assertEquals(260f, b.l * 400, 0.01f)
    }

    @Test
    fun moveStaysInside() {
        val b = CropMath.drag(CropBox(0.25f, 0.25f, 0.75f, 0.75f), CropHandle.Move, -500f, 30f, 400f, 400f, null, 40f)
        assertEquals(listOf(0f, 130f, 200f, 330f), px(b, 400f, 400f))
    }

    @Test
    fun lockedShapeIsKeptForCornersAndEdges() {
        val start = CropMath.fit(4f / 3, 800, 600, scale = 0.5f)
        for (h in CropHandle.entries) {
            for ((dx, dy) in listOf(120f to 10f, -300f to 40f, 900f to -900f, -15f to -200f)) {
                val b = CropMath.drag(start, h, dx, dy, 800f, 600f, 4f / 3, 48f)
                assertInside(b)
                assertEquals("$h $dx $dy", 4f / 3, (b.w * 800) / (b.h * 600), 0.01f)
                assertTrue("$h min size", b.w * 800 >= 47f && b.h * 600 >= 47f)
            }
        }
    }

    @Test
    fun lockedCornerGrowsTowardsTheFinger() {
        val start = CropMath.fit(1f, 400, 400, scale = 0.5f) // 100..300
        val b = CropMath.drag(start, CropHandle.BottomRight, 50f, 10f, 400f, 400f, 1f, 40f)
        assertEquals(listOf(100f, 100f, 350f, 350f), px(b, 400f, 400f).map { Math.round(it).toFloat() })
    }
}
