package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayerTest {

    @Test
    fun latinStaysInOrder() {
        assertEquals("Hello 123", TextLayer.visualOrder("Hello 123"))
    }

    @Test
    fun arabicIsReversedForThePage() {
        // Logical "سلام" is laid out right to left: the page holds its glyphs left to right, reversed.
        assertEquals("مالس", TextLayer.visualOrder("سلام"))
        // A number inside Arabic keeps its own order.
        assertEquals("2 ةنس", TextLayer.visualOrder("سنة 2"))
    }

    @Test
    fun contentIsInvisibleTextSqueezedToTheLine() {
        val c = TextLayer.content(listOf(RecognisedLine("ab", 10f, 20f, 40f, 10f)), 100f)
        assertTrue(c.startsWith("BT 3 Tr\n"))
        assertTrue(c.endsWith("ET\n"))
        // Size 7.2 (72% of the height); two ems of 7.2 squeezed into 40 pt = 277.78%; baseline at
        // 100 − 27.8; a space on each side, outside the box (one em of 20 pt before x = 10).
        assertTrue(c, c.contains("/HonoPDFOcr 7.200 Tf 277.78 Tz 1 0 0 1 -10.000 72.200 Tm <0020006100620020> Tj"))
    }

    @Test
    fun emptyAndFlatLinesAreLeftOut() {
        val c = TextLayer.content(listOf(RecognisedLine("  ", 1f, 1f, 5f, 5f), RecognisedLine("x", 1f, 1f, 0f, 5f)), 50f)
        assertEquals("BT 3 Tr\nET\n", c)
    }

    @Test
    fun toUnicodeMapsEveryCodeUnitToItself() {
        val t = TextLayer.toUnicodeText()
        assertTrue(t.contains("<0000> <FFFF> <0000>"))
        assertTrue(t.contains("/CMapName /Adobe-Identity-UCS def"))
    }
}
