package com.aamus.powerpdf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Swatches are named for screen readers (decisions §43): the app's own palettes. */
class ColourWordTest {
    private fun of(argb: Long) = ColourWord.of(argb.toInt())

    @Test fun markColours() {
        assertEquals(ColourWord.Yellow, of(0xFFFFD600))
        assertEquals(ColourWord.Green, of(0xFF7ED957))
        assertEquals(ColourWord.LightBlue, of(0xFF4FC3F7))
        assertEquals(ColourWord.Pink, of(0xFFFF80AB))
        assertEquals(ColourWord.Red, of(0xFFE34850))
        assertEquals(ColourWord.Blue, of(0xFF1473E6))
        assertEquals(ColourWord.Violet, of(0xFF7E57E6))
        assertEquals(ColourWord.Black, of(0xFF1F1F1F))
    }

    @Test fun otherPalettes() {
        assertEquals(ColourWord.Grey, of(0xFF808080))
        assertEquals(ColourWord.Green, of(0xFF2D9D78))
        assertEquals(ColourWord.Green, of(0xFF16A34A))
        assertEquals(ColourWord.Red, of(0xFFE11D48))
        assertEquals(ColourWord.White, of(0xFFF2F2F2))
        assertEquals(ColourWord.White, of(0xFFFFFFFF))
        assertEquals(ColourWord.Black, of(0xFF000000))
        assertEquals(ColourWord.Orange, of(0xFFF59E0B))
        assertEquals(ColourWord.Brown, of(0xFF7C4A1E))
    }
}
