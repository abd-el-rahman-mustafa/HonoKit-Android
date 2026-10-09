package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActualTextTest {

    @Test
    fun theTextIsCutWhereTheLinesEnd() {
        assertEquals(listOf("one two", "three"), ActualText.splitLines("one two three", listOf(6, 5)))
        // Arabic with vowel marks: each mark is a character of its own.
        assertEquals(listOf("يُعَرِّض", "الأمر"), ActualText.splitLines("يُعَرِّض الأمر", listOf(8, 5)))
    }

    @Test
    fun countsThatDoNotAddUpGiveNothing() {
        assertNull(ActualText.splitLines("one two three", listOf(6, 4)))
        assertNull(ActualText.splitLines("one two", listOf(6, 5)))
    }

    @Test
    fun eachLineIsWrappedByItsGlyphs() {
        // As MuPDF's PDF writer writes it: one TJ per line, a font change splitting the second.
        val content = "BT\n/F0 9.8 Tf\n1 0 0 -1 147 9.34 Tm\n[<011e>493<01dd>]TJ\n0 -12.8 Td\n[<0185>]TJ\n/F1 9.8 Tf\n[<0040><0041>]TJ\nET\n"
        val out = ActualText.wrap(content, listOf(2 to "مع", 3 to "في AB"))!!
        assertEquals(2, Regex("BDC").findAll(out).count())
        assertEquals(2, Regex("EMC").findAll(out).count())
        assertTrue(out.contains("/Span<</ActualText<FEFF06450639>>>BDC\n[<011e>493<01dd>]TJ\nEMC"))
        // The second line's span holds both of its arrays and the font change between them.
        assertTrue(out.contains("BDC\n[<0185>]TJ\n/F1 9.8 Tf\n[<0040><0041>]TJ\nEMC"))
    }

    @Test
    fun glyphsThatDoNotMatchLeaveTheContentAlone() {
        val content = "BT\n[<011e><01dd>]TJ\nET\n"
        assertNull(ActualText.wrap(content, listOf(3 to "abc")))
        assertNull(ActualText.wrap(content, listOf(1 to "a", 1 to "b", 1 to "c")))
    }
}
