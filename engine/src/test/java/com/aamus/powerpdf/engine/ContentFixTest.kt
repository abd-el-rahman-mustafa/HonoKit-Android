package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentFixTest {

    private fun fix(s: String, state: ContentFix.Leading = ContentFix.Leading()) = ContentFix.explicitLeading(s, state)

    @Test
    fun nothingToChange() {
        assertNull(fix("BT /F1 9 Tf 10 20 Td (a) Tj ET"))
    }

    @Test
    fun nextLineUsesTheLeading() {
        assertEquals("BT 9.5 TL 10 20 Td (a) Tj 0 -9.5 Td (b) Tj ET", fix("BT 9.5 TL 10 20 Td (a) Tj T* (b) Tj ET"))
    }

    @Test
    fun quoteBecomesMoveAndShow() {
        assertEquals("BT 12 TL 0 -12 Td (x) Tj 0 -12 Td <0041> Tj ET", fix("BT 12 TL (x) ' <0041> ' ET"))
    }

    @Test
    fun doubleQuoteSetsSpacingFirst() {
        assertEquals("BT 10 TL 1 Tw 0.5 Tc 0 -10 Td (y) Tj ET", fix("BT 10 TL 1 0.5 (y) \" ET"))
    }

    @Test
    fun capitalTdSetsTheLeading() {
        assertEquals("BT 195.59 -9.5 Td 9.5 TL (a) Tj 0 -9.5 Td (b) Tj ET", fix("BT 195.59 -9.5 TD (a) Tj T* (b) Tj ET"))
    }

    @Test
    fun qAndQRestoreTheLeading() {
        assertEquals("q 5 TL q 7 TL Q 0 -5 Td Q", fix("q 5 TL q 7 TL Q T* Q"))
    }

    @Test
    fun leadingCarriesAcrossStreams() {
        val state = ContentFix.Leading()
        fix("BT 11 TL ET", state)
        assertEquals("BT 0 -11 Td (c) Tj ET", fix("BT T* (c) Tj ET", state))
    }

    @Test
    fun operatorsInsideStringsAreLeftAlone() {
        assertNull(fix("BT (T* and ' and 5 TL) Tj [(TD) 20 (x)] TJ ET"))
        assertEquals("BT 3 TL (a\\) T*) Tj 0 -3 Td ET", fix("BT 3 TL (a\\) T*) Tj T* ET"))
    }

    @Test
    fun inlineImagesAreSkipped() {
        assertEquals("BI /W 1 /H 1 ID T* TL ' EI 0 0 Td", fix("BI /W 1 /H 1 ID T* TL ' EI T*"))
    }

    /** E6: "EI" bytes inside the picture's data do not end it; only an EI standing alone does. */
    @Test
    fun anEiInsideInlineImageDataIsNotTheEnd() {
        assertEquals("BT 4 TL ET BI /W 1 /H 1 ID xEIy T* EIQ T* EI 0 -4 Td", fix("BT 4 TL ET BI /W 1 /H 1 ID xEIy T* EIQ T* EI T*"))
        // A producer that writes no space before EI: the first EI followed by a delimiter.
        assertEquals("BI /W 1 /H 1 ID abcEI 0 0 Td", fix("BI /W 1 /H 1 ID abcEI T*"))
    }

    /** E5: how a stream moves the q/Q stack, strings and inline images skipped. */
    @Test
    fun qDepthCountsSavesAndRestores() {
        assertEquals(0 to 0, ContentFix.qDepth("q 1 0 0 1 5 5 cm Q"))
        assertEquals(0 to 2, ContentFix.qDepth("q q 0.5 0 0 0.5 0 0 cm"))
        assertEquals(-1 to -1, ContentFix.qDepth("Q 1 0 0 1 5 5 cm"))
        assertEquals(-1 to 0, ContentFix.qDepth("Q q"))
        assertEquals(0 to 0, ContentFix.qDepth("BT (q q Q) Tj ET BI /W 1 /H 1 ID Q Q EI % q\n"))
    }
}
