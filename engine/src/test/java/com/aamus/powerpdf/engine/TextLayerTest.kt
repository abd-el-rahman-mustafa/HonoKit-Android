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
    fun eachWordIsWrittenOverItsOwnPlace() {
        // "ab cd" with the picture's words at 10–30 and 50–90: each word stretched to its span, and
        // the space stretched over the gap between them.
        val c = TextLayer.content(listOf(RecognisedLine("ab cd", 10f, 20f, 80f, 10f, listOf(floatArrayOf(10f, 30f), floatArrayOf(50f, 90f)))), 100f)
        // ab: two ems of 7.2 in 20 pt = 138.89%; the leading space one such em before x = 10.
        assertTrue(c, c.contains("/HonoPDFOcr 7.200 Tf 138.89 Tz 1 0 0 1 0.000 72.200 Tm <002000610062> Tj"))
        // The space: the 20 pt gap from x = 30.
        assertTrue(c, c.contains("/HonoPDFOcr 7.200 Tf 277.78 Tz 1 0 0 1 30.000 72.200 Tm <0020> Tj"))
        // cd: 40 pt, with the closing space.
        assertTrue(c, c.contains("/HonoPDFOcr 7.200 Tf 277.78 Tz 1 0 0 1 50.000 72.200 Tm <006300640020> Tj"))
    }

    @Test
    fun anArabicLinesFirstWordGoesToTheRightHandSpan() {
        // Logical "سلام عليكم" shows right to left: "عليكم" (reversed, so meem U+0645 first) on the left.
        val c = TextLayer.content(listOf(RecognisedLine("سلام عليكم", 0f, 20f, 100f, 10f, listOf(floatArrayOf(0f, 50f), floatArrayOf(60f, 100f)))), 100f)
        assertTrue(c, c.contains("Tm <00200645"))
        // "سلام" reversed starts with meem too, at the right-hand span.
        assertTrue(c, c.contains("1 0 0 1 60.000 72.200 Tm <0645"))
    }

    @Test
    fun aLineWhoseGapsDidNotFitIsWrittenWhole() {
        val c = TextLayer.content(listOf(RecognisedLine("ab cd", 10f, 20f, 40f, 10f, listOf(floatArrayOf(10f, 50f)))), 100f)
        assertEquals(1, c.lines().count { it.endsWith("Tj") })
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
