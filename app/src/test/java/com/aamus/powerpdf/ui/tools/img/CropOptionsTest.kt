package com.aamus.powerpdf.ui.tools.img

import com.aamus.powerpdf.engine.img.CropBox
import com.aamus.powerpdf.engine.img.CropMath
import org.junit.Assert.assertEquals
import org.junit.Test

/** Crop image keeps one box for a whole batch: dragged on one picture, it fits the others. */
class CropOptionsTest {
    @Test
    fun freeBoxIsTheSameFractionsOnEveryPicture() {
        val box = CropBox(0.2f, 0.1f, 0.7f, 0.9f)
        val o = withCrop(ImgOptions(ratio = null), box, 800, 600)
        assertEquals(box, cropFor(o, 800, 600))
        assertEquals(box, cropFor(o, 300, 1200))
    }

    @Test
    fun aShapedBoxRoundTripsOnThePictureItWasDraggedOn() {
        val start = ImgOptions(ratio = 1f)
        val dragged = CropMath.fit(1f, 800, 600, cx = 0.3f, cy = 0.6f, scale = 0.5f)
        val o = withCrop(start, dragged, 800, 600)
        val back = cropFor(o, 800, 600)
        assertEquals(dragged.l, back.l, 0.001f)
        assertEquals(dragged.t, back.t, 0.001f)
        assertEquals(dragged.r, back.r, 0.001f)
        assertEquals(dragged.b, back.b, 0.001f)
    }

    @Test
    fun aShapedBoxKeepsItsShapeOnAnotherSize() {
        val o = withCrop(ImgOptions(ratio = 16f / 9), CropMath.fit(16f / 9, 1000, 1000, scale = 0.7f), 1000, 1000)
        for ((w, h) in listOf(400 to 1200, 3000 to 1000, 640 to 480)) {
            val b = cropFor(o, w, h)
            assertEquals("$w × $h", 16f / 9, (b.w * w) / (b.h * h), 0.01f)
            assertEquals("$w × $h fills the same share", 0.7f, b.w / CropMath.fit(16f / 9, w, h).w, 0.001f)
        }
    }
}
