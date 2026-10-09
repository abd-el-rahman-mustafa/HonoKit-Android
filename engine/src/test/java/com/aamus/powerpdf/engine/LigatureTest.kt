package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of the ligature repair (decisions §22) and of joining a paragraph's lines that need no MuPDF. */
class LigatureTest {

    // --- joining lines ---------------------------------------------------------------------

    @Test
    fun linesAreJoinedBySpaces() {
        assertEquals("the quick brown fox", TextLogic.joinLines(listOf("the quick", "brown fox")))
    }

    @Test
    fun aWordBrokenAtTheLineEndIsWholeAgain() {
        // Seen in a magazine page: «late 18th-cen-» / «tury novel».
        assertEquals("a late 18th-century novel", TextLogic.joinLines(listOf("a late 18th-cen-", "tury novel")))
        assertEquals("very different", TextLogic.joinLines(listOf("very dif-", "ferent")))
    }

    @Test
    fun aCompoundThePageWritesWithItsHyphenKeepsIt() {
        val written = TextLogic.wordsOf(listOf("a well-known name", "another line"))
        assertEquals("the well-known writer", TextLogic.joinLines(listOf("the well-", "known writer"), written = written))
        // The page writes the whole word too: it is a line break, not a compound.
        val both = TextLogic.wordsOf(listOf("re-cover the costs", "recover"))
        assertEquals("recover it", TextLogic.joinLines(listOf("re-", "cover it"), written = both))
    }

    @Test
    fun compoundsBrokenAtTheirHyphenKeepIt() {
        // Seen in the round-trip test on a newspaper page: «two-time», «morale-boosting».
        assertEquals("a two-time champion", TextLogic.joinLines(listOf("a two-", "time champion")))
        assertEquals("self-sufficiency", TextLogic.joinLines(listOf("self-", "sufficiency")))
        val page = listOf("the morale of the team", "a boosting effect", "a morale-", "boosting win")
        assertEquals("a morale-boosting win", TextLogic.joinLines(listOf("a morale-", "boosting win"), written = TextLogic.wordsOf(page)))
    }

    @Test
    fun thePiecesOfABrokenWordAreNotWordsOfTheirOwn() {
        val page = listOf("a very differ-", "ent story", "of a differ-", "ent kind")
        val written = TextLogic.wordsOf(page)
        assertTrue("differ" !in written && "ent" !in written)
        assertEquals("a very different story", TextLogic.joinLines(listOf("a very differ-", "ent story"), written = written))
    }

    @Test
    fun capitalsAndDigitsKeepTheHyphenWithoutASpace() {
        assertEquals("in Abu-Dhabi today", TextLogic.joinLines(listOf("in Abu-", "Dhabi today")))
        assertEquals("the 2026-27 season", TextLogic.joinLines(listOf("the 2026-", "27 season")))
    }

    @Test
    fun aSpacedDashStaysSpaced() {
        assertEquals("narrative – about", TextLogic.joinLines(listOf("narrative –", "about")))
        assertEquals("and - more", TextLogic.joinLines(listOf("and -", "more")))
    }

    @Test
    fun aDashOrSlashRightAfterAWordClosesUp() {
        assertEquals("word—next", TextLogic.joinLines(listOf("word—", "next")))
        assertEquals("and/or", TextLogic.joinLines(listOf("and/", "or")))
    }

    @Test
    fun aSoftHyphenIsDropped() {
        assertEquals("international", TextLogic.joinLines(listOf("inter­", "national")))
    }

    @Test
    fun rightToLeftLinesAreOnlyJoinedBySpaces() {
        assertEquals("ذهب أحمد- إلى", TextLogic.joinLines(listOf("ذهب أحمد-", "إلى"), rtl = listOf(true, true)))
    }

    @Test
    fun aVowelMarkStartingALineBelongsToTheLineBefore() {
        // Read from an Arabic newspaper: the tanween of «أسيراً» came at the start of the next line.
        assertEquals("لتبادل ٣٧٠ أسيراً وساطة", TextLogic.joinLines(listOf("لتبادل ٣٧٠ أسيرا", "ًوساطة"), rtl = listOf(true, true)))
    }

    @Test
    fun emptyLinesAreSkipped() {
        assertEquals("one two", TextLogic.joinLines(listOf("one", "  ", "two")))
    }

    // --- lines printed twice ----------------------------------------------------------------

    private fun line(text: String, x0: Float, y0: Float) = text.mapIndexed { i, ch -> Glyph(ch.toString(), x0 + i * 6f, y0, x0 + (i + 1) * 6f, y0 + 12f) }

    @Test
    fun aPartialSecondCopyOfALineIsLeftOut() {
        // A newspaper headline with a partial copy on top: «Guardiola quit» and «Guar».
        val lines = listOf(line("Guar", 170f, 90f), line("Guardiola quit", 170f, 90f), line("other words", 170f, 120f))
        assertEquals(listOf(1, 2), TextLogic.distinctLines(lines))
    }

    @Test
    fun linesThatOnlyShareSomeLettersAreKept() {
        val lines = listOf(line("Guardiola", 170f, 90f), line("Guardian", 170f, 90f), line("Guardiola", 170f, 300f))
        assertEquals(listOf(0, 1, 2), TextLogic.distinctLines(lines))
    }

    private fun sized(text: String, x0: Float, h: Float, w: Float) = text.mapIndexed { i, ch -> Glyph(ch.toString(), x0 + i * w, 100f - h, x0 + (i + 1) * w, 100f) }

    @Test
    fun twoHeadlinesOnOneBaselineAreTwoLines() {
        val big = sized("Mubarak", 200f, 36f, 18f)
        val small = sized("Zebra", 200f + 7 * 18f + 16f, 21f, 10f)
        val parts = TextLogic.splitAtSizeJumps(big + small)
        assertEquals(listOf("Mubarak", "Zebra"), parts.map { p -> p.joinToString("") { it.c } })
    }

    @Test
    fun aDropCapTouchingItsWordStaysInTheLine() {
        val cap = sized("A", 100f, 46f, 30f)
        val rest = sized("ctress", 130f, 9f, 5f)
        assertEquals(1, TextLogic.splitAtSizeJumps(cap + rest).size)
    }

    @Test
    fun arabicQuotesAcrossLinesAreTurnedBack() {
        // Read from an Arabic newspaper: the quotation starts on one line and ends on the next.
        assertEquals("قال «مدينة صحة» أمس", TextLogic.unmirrorText("قال »مدينة صحة« أمس"))
        // Already right, and Latin text, are left alone.
        assertEquals("قال «مدينة صحة» أمس", TextLogic.unmirrorText("قال «مدينة صحة» أمس"))
        assertEquals("German »so« quotes", TextLogic.unmirrorText("German »so« quotes"))
    }

    @Test
    fun aLoneMarkAmongArabicLettersIsTurnedBack() {
        // The quotation began in the previous paragraph; a lone brace used as a marker.
        assertEquals("واستدامة».", TextLogic.unmirrorText("واستدامة«."))
        assertEquals("{ منظمة الصحة", TextLogic.unmirrorText("} منظمة الصحة"))
        // Next to Latin text it stays as drawn.
        assertEquals("نص (WHO) آخر", TextLogic.unmirrorText("نص (WHO) آخر"))
    }

    // --- choosing the letters -------------------------------------------------------------

    private fun s(text: String, shape: Float, width: Float = 1f) = LigatureLogic.Scored(text, shape, width)

    @Test
    fun aClearMatchIsTaken() {
        // EuclidFlex on a phone-tested magazine page: «ft» 0.89 against «tt» 0.71.
        val c = LigatureLogic.choose('t', listOf(s("ft", 0.89f, 0.87f), s("tt", 0.71f, 0.86f), s("st", 0.47f, 0.81f)), 0.636f, 1.71f)!!
        assertEquals("ft", c.text)
        assertFalse(c.guessed)
    }

    @Test
    fun aCloseSecondMakesItAGuess() {
        val c = LigatureLogic.choose('f', listOf(s("ff", 0.9f), s("ft", 0.85f)), 0.58f, 1.95f)!!
        assertEquals("ff", c.text)
        assertTrue(c.guessed)
    }

    @Test
    fun noConvincingMatchChangesNothing() {
        assertNull(LigatureLogic.choose('t', listOf(s("tt", 0.6f), s("st", 0.5f)), 0.6f, 1.7f))
        // A small-capital «t» is wider than the letter but no ligature: no letters are invented.
        assertNull(LigatureLogic.choose('t', emptyList(), 0.45f, 1.4f))
    }

    @Test
    fun withoutAnFToCompareFiAndFlAreStillSafe() {
        // Helvetica-Narrow drew «fi» on a page where «f» never appears alone.
        val c = LigatureLogic.choose('i', emptyList(), 0.41f, 2.25f)!!
        assertEquals("fi", c.text)
        assertTrue(c.guessed)
        assertEquals("ffl", LigatureLogic.choose('l', emptyList(), 0.82f, 3.6f)!!.text)
        // Not wide enough to hide an «f».
        assertNull(LigatureLogic.choose('i', emptyList(), 0.3f, 1.4f))
    }

    @Test
    fun aGlyphWithoutAnyLetterNeedsAClearMatch() {
        assertEquals("fi", LigatureLogic.choose(null, listOf(s("fi", 0.97f), s("ffi", 0.4f)), 0.5f, 0f)!!.text)
        assertNull(LigatureLogic.choose(null, listOf(s("fi", 0.85f), s("fl", 0.8f)), 0.5f, 0f))
    }

    @Test
    fun candidatesStartOrEndWithTheLetterAndNeedItsGlyphs() {
        val all = { _: Char -> true }
        assertEquals(listOf("ft", "fft", "tt", "st", "ct"), LigatureLogic.candidates('t', all))
        assertEquals(listOf("tt", "st", "ct"), LigatureLogic.candidates('t') { it != 'f' })
        assertTrue(LigatureLogic.candidates('i', all).containsAll(listOf("fi", "ffi")))
    }

    @Test
    fun aGlyphIsSplitIntoItsLetters() {
        val parts = LigatureLogic.split(Glyph("t", 10f, 0f, 16f, 12f), "ft")
        assertEquals(listOf("f", "t"), parts.map { it.c })
        assertEquals(10f, parts[0].x0, 0.001f)
        assertEquals(13f, parts[0].x1, 0.001f)
        assertEquals(16f, parts[1].x1, 0.001f)
        assertEquals(listOf("i"), LigatureLogic.split(Glyph("x", 0f, 0f, 1f, 1f), "i").map { it.c })
    }

    // --- comparing drawings ----------------------------------------------------------------

    private fun mask(vararg rows: String) = Mask(rows[0].length, rows.size, BooleanArray(rows.size * rows[0].length) { rows[it / rows[0].length][it % rows[0].length] == '#' })

    @Test
    fun sameDrawingIsOneAndStretchingIsAllowed() {
        val a = mask(
            "..##....",
            "..##....",
            "..####..",
        )
        assertEquals(1f, LigatureLogic.similarity(a, a), 0.001f)
        val wide = mask(
            "..####........",
            "..####........",
            "..########....",
        )
        assertTrue(LigatureLogic.similarity(a, wide) > 0.9f)
    }

    @Test
    fun differentDrawingsScoreLow() {
        val tall = mask(
            "#......#",
            "#......#",
            "#......#",
            "#......#",
        )
        val low = mask(
            "........",
            "........",
            "########",
            "........",
        )
        assertTrue(LigatureLogic.similarity(tall, low) < 0.3f)
        assertEquals(0f, LigatureLogic.similarity(tall, mask("....")), 0f)
    }
}
