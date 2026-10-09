package com.aamus.powerpdf.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decisions §52: once settled, the part of a page on screen is drawn at exactly the zoom shown. */
class TileScaleTest {
    @Test
    fun aBaseDrawnAtTheZoomShownNeedsNoDetail() {
        assertFalse(TileScale.needsDetail(2.75f, 2.75f))
        // Float rounding of the same zoom.
        assertFalse(TileScale.needsDetail(2.7500002f, 2.75f))
    }

    @Test
    fun aZoomBetweenStepsGetsAnExactPicture() {
        // Zoomed in less than 8 % past a step: the base was stretched (blurry) before.
        assertTrue(TileScale.needsDetail(2.75f * 1.05f, 2.75f))
        // Zoomed out from a step (1.3 × fit drawn from the 1.41 × base): the base was shrunk.
        assertTrue(TileScale.needsDetail(2.75f * 1.3f, 2.75f * 1.414f))
        // Below fit (0.8 × fit from the 0.71 × base): stretched.
        assertTrue(TileScale.needsDetail(2.75f * 0.8f, 2.75f * 0.707f))
        // Zoomed far in, as before.
        assertTrue(TileScale.needsDetail(2.75f * 4f, 2.75f * 2f))
    }
}
