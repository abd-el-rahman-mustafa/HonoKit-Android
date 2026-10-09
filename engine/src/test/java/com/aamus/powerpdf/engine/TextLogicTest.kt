package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLogicTest {

    /** Builds a line of glyphs laid out right to left, each [w] wide; a width of 0 makes a ligature partner. */
    private fun rtlLine(chars: List<Pair<String, Float>>, y0: Float = 100f, y1: Float = 112f, right: Float = 300f): TextLine {
        var x = right
        val glyphs = chars.map { (c, w) ->
            val g = Glyph(c, x - w, y0, x, y1)
            x -= w
            g
        }
        return TextLine(glyphs, glyphs.minOf { it.x0 }, y0, glyphs.maxOf { it.x1 }, y1)
    }

    private fun ltrLine(text: String, y0: Float, x0: Float = 50f, w: Float = 6f): TextLine {
        val glyphs = text.mapIndexed { i, ch -> Glyph(ch.toString(), x0 + i * w, y0, x0 + (i + 1) * w, y0 + 12) }
        return TextLine(glyphs, x0, y0, x0 + text.length * w, y0 + 12)
    }

    @Test
    fun repairsLamAlefOrder() {
        // MuPDF reports «على» as ع (wide), ى (zero width), ل (wide carrying the ligature): «عىل».
        val glyphs = mutableListOf(
            Glyph("ع", 90f, 0f, 100f, 10f),
            Glyph("ى", 80f, 0f, 80f, 10f),
            Glyph("ل", 70f, 0f, 80f, 10f),
        )
        TextLogic.repairLigatureOrder(glyphs)
        assertEquals("على", glyphs.joinToString("") { it.c })
    }

    @Test
    fun repairsAlefLamInAlIttihad() {
        // «الاتحاد» comes out as «االتحاد»: ا, ا(zero), ل ... — the zero-width alef belongs after the lam.
        val glyphs = mutableListOf(
            Glyph("ا", 95f, 0f, 100f, 10f),
            Glyph("ا", 90f, 0f, 90f, 10f),
            Glyph("ل", 85f, 0f, 95f, 10f),
            Glyph("ت", 80f, 0f, 85f, 10f),
            Glyph("ح", 75f, 0f, 80f, 10f),
            Glyph("ا", 70f, 0f, 75f, 10f),
            Glyph("د", 65f, 0f, 70f, 10f),
        )
        TextLogic.repairLigatureOrder(glyphs)
        assertEquals("الاتحاد", glyphs.joinToString("") { it.c })
    }

    @Test
    fun leavesAPartnerOfThePreviousGlyphInPlace() {
        // «إنترنت» written with IBM Plex: «تر» is one glyph — ت wide, then ر at zero width on its
        // left edge (read with mupdf.js from a file this app wrote). The ن after it is its own glyph.
        val glyphs = mutableListOf(
            Glyph("إ", 191.7f, 0f, 195.3f, 10f),
            Glyph("ن", 187.7f, 0f, 191.7f, 10f),
            Glyph("ت", 178.5f, 0f, 187.7f, 10f),
            Glyph("ر", 187.7f, 0f, 187.7f, 10f),
            Glyph("ن", 174.4f, 0f, 178.4f, 10f),
            Glyph("ت", 159.7f, 0f, 174.4f, 10f),
        )
        TextLogic.repairLigatureOrder(glyphs)
        assertEquals("إنترنت", glyphs.joinToString("") { it.c })
    }

    @Test
    fun leavesLatinAlone() {
        val glyphs = mutableListOf(Glyph("f", 0f, 0f, 0f, 10f), Glyph("i", 0f, 0f, 5f, 10f))
        TextLogic.repairLigatureOrder(glyphs)
        assertEquals("fi", glyphs.joinToString("") { it.c })
    }

    @Test
    fun foldsArabicForSearch() {
        assertEquals(TextLogic.foldString("الى"), TextLogic.foldString("إلي"))
        assertEquals(TextLogic.foldString("أحمد"), TextLogic.foldString("احمد"))
        assertEquals("كتاب", TextLogic.foldString("كتـــاب")) // tatweel
        assertEquals("محمد", TextLogic.foldString("مُحَمَّد")) // vowel marks
        assertEquals("pdf", TextLogic.foldString("PDF"))
    }

    @Test
    fun searchFindsArabicWithDifferentSpelling() {
        val line = rtlLine(listOf("إ" to 5f, "ل" to 5f, "ى" to 5f, " " to 3f, "ا" to 5f, "ل" to 5f, "م" to 5f))
        val hits = TextLogic.searchLines(listOf(line), "الي")
        assertEquals(1, hits.size)
        val hitsEmpty = TextLogic.searchLines(listOf(line), "   ")
        assertTrue(hitsEmpty.isEmpty())
    }

    @Test
    fun searchReturnsOneBoxPerMatch() {
        val lines = listOf(ltrLine("the cat and the hat", 100f), ltrLine("THE end", 120f))
        val hits = TextLogic.searchLines(lines, "the")
        assertEquals(3, hits.size)
        assertEquals(50f, hits[0].x0, 0.01f)
    }

    @Test
    fun selectsWordAndLine() {
        val lines = listOf(ltrLine("hello brave world", 100f))
        val word = TextLogic.selectText(lines, 50f + 8 * 6f to 106f, 50f + 8 * 6f to 106f, TextLogic.Unit.Word)
        assertNotNull(word)
        assertEquals("brave", word!!.text)
        val line = TextLogic.selectText(lines, 55f to 106f, 55f to 106f, TextLogic.Unit.Line)
        assertEquals("hello brave world", line!!.text)
    }

    @Test
    fun selectsAcrossLinesInReadingOrder() {
        val lines = listOf(ltrLine("first line", 100f), ltrLine("second line", 120f))
        // Dragged upwards: still reading order.
        val sel = TextLogic.selectText(lines, 50f + 3 * 6f to 126f, 50f + 6 * 6f to 106f)
        assertEquals("line\nsec", sel!!.text)
        assertEquals(2, sel.boxes.size)
    }

    @Test
    fun rightToLeftHandlesSitOnReadingEdges() {
        val line = rtlLine(listOf("س" to 6f, "ل" to 6f, "ا" to 6f, "م" to 6f))
        val sel = TextLogic.spanOf(listOf(line), 0, 0, 0, 3)!!
        assertTrue(sel.rtlStart)
        // Start handle on the right edge of the first letter, end handle on the left edge of the last.
        assertEquals(300f, sel.startHandle.first, 0.01f)
        assertEquals(276f, sel.endHandle.first, 0.01f)
    }

    @Test
    fun noLineFarAway() {
        val lines = listOf(ltrLine("text", 100f))
        assertNull(TextLogic.selectText(lines, 50f to 400f, 50f to 400f))
    }

    /**
     * Lays out tokens as a screen shows them. For a right-to-left line the tokens are listed from
     * the right edge leftwards; for a left-to-right line from the left edge rightwards. A token
     * marked rtl draws its characters right to left, others left to right. The glyphs are then
     * shuffled, as MuPDF's order is not to be trusted.
     */
    private fun screenLine(rtlLine: Boolean, vararg tokens: Pair<String, Boolean>): List<Glyph> {
        val w = 5f
        val out = ArrayList<Glyph>()
        var edge = if (rtlLine) 1000f else 0f
        for ((text, rtl) in tokens) {
            val n = text.length
            val left = if (rtlLine) edge - n * w else edge
            text.forEachIndexed { k, ch ->
                val x = if (rtl) left + (n - 1 - k) * w else left + k * w
                out.add(Glyph(ch.toString(), x, 0f, x + w, 10f))
            }
            edge = if (rtlLine) left else left + n * w
        }
        return out.shuffled(java.util.Random(7))
    }

    private fun r(s: String) = s to true
    private fun l(s: String) = s to false

    @Test
    fun mixedRightToLeftLineInReadingOrder() {
        val g = screenLine(true, r("وفيه"), r(" "), l("٢٨"), r(" "), r("صفحة"), r(" "), r("و"), l("15"), r(" "), r("صورة"), r("."))
        assertEquals("وفيه ٢٨ صفحة و15 صورة.", TextLogic.logicalOrder(g, true).joinToString("") { it.c })
    }

    @Test
    fun emailAndSeparateNumbersInArabicLine() {
        val g = screenLine(
            true, r("البريد"), r(":"), r(" "), l("info@example.com"), r(" "), r("—"), r(" "), r("الهاتف"), r(":"), r(" "),
            l("800"), r(" "), l("1234"), r(" "), l("567"),
        )
        assertEquals("البريد: info@example.com — الهاتف: 800 1234 567", TextLogic.logicalOrder(g, true).joinToString("") { it.c })
    }

    @Test
    fun latinWordsInArabicSentence() {
        val g = screenLine(
            true, r("سطر"), r(" "), r("مختلط"), r(":"), r(" "), r("يعمل"), r(" "), r("تطبيق"), r(" "), l("PowerPDF"), r(" "),
            r("دون"), r(" "), r("على"), r(" "), l("Android"), r(" "), r("و"), l("Windows"), r("."),
        )
        assertEquals("سطر مختلط: يعمل تطبيق PowerPDF دون على Android وWindows.", TextLogic.logicalOrder(g, true).joinToString("") { it.c })
    }

    @Test
    fun numberJoinsLatinWord() {
        val g = screenLine(true, r("نص"), r(" "), l("PDF 2026"), r(" "), r("نص"))
        assertEquals("نص PDF 2026 نص", TextLogic.logicalOrder(g, true).joinToString("") { it.c })
    }

    @Test
    fun timeStaysWhole() {
        val g = screenLine(true, r("الساعة"), r(" "), l("3:30"), r(" "), r("مساء"))
        assertEquals("الساعة 3:30 مساء", TextLogic.logicalOrder(g, true).joinToString("") { it.c })
    }

    @Test
    fun arabicWordInEnglishLine() {
        val g = screenLine(false, l("Hello"), l(" "), r("مرحبا"), l(" "), l("world"), l(" "), l("2026"))
        assertEquals("Hello مرحبا world 2026", TextLogic.logicalOrder(g, false).joinToString("") { it.c })
    }

    @Test
    fun arabicLineEndingInPunctuation() {
        // MuPDF gave ".)قال: «…» (وهذا صحيح": the final ")." came first.
        val g = screenLine(false, l(".)"), r("صحيح"), l(" "), r("وهذا"), l("("), l(" "), r("قال"))
        // The PDF maps each bracket glyph to its logical character, so ")" sits at the far left.
        assertEquals("قال (وهذا صحيح).", TextLogic.logicalOrder(g, false).joinToString("") { it.c })
    }

    @Test
    fun pureLinesAreLeftAlone() {
        val ar = listOf(Glyph("ب", 0f, 0f, 5f, 10f), Glyph("ا", 10f, 0f, 15f, 10f))
        assertTrue(TextLogic.logicalOrder(ar, true) === ar)
        val en = listOf(Glyph("a", 0f, 0f, 5f, 10f), Glyph("1", 5f, 0f, 10f, 10f))
        assertTrue(TextLogic.logicalOrder(en, true) === en)
    }

    @Test
    fun arabicIndicDigits() {
        assertEquals("صفحة ٣ من ٢٨", TextLogic.toArabicIndic("صفحة 3 من 28"))
    }

    /** Glyphs of one run from [x0] on the band [y0]–[y0]+12, in logical order; RTL runs laid right to left. */
    private fun run(text: String, x0: Float, y0: Float = 100f, w: Float = 6f, rtl: Boolean = false): List<Glyph> {
        val n = text.length
        return text.mapIndexed { i, ch ->
            val k = if (rtl) n - 1 - i else i
            Glyph(ch.toString(), x0 + k * w, y0, x0 + (k + 1) * w, y0 + 12f)
        }
    }

    @Test
    fun piecesOfOneLineAreJoinedInReadingOrder() {
        // An Arabic sentence drawn in two runs: the right-hand run is read first.
        val left = run("كتاب", 40f, rtl = true)
        val right = run("على", 72f, rtl = true)
        val joined = TextLogic.joinPieces(listOf(left, right))
        assertEquals(1, joined.size)
        assertEquals("على كتاب", joined[0].glyphs.joinToString("") { it.c })
        assertEquals(listOf(0, 1), joined[0].members)
    }

    @Test
    fun latinPiecesJoinLeftToRightWithASpace() {
        val joined = TextLogic.joinPieces(listOf(run("world", 75f), run("Hello", 40f)))
        assertEquals(1, joined.size)
        assertEquals("Hello world", joined[0].glyphs.joinToString("") { it.c })
    }

    @Test
    fun arabicLetterReachingUnderItsNeighbourKeepsItsPlace() {
        // «إنترنت PDF»: ر has a long tail under the ن after it, so the ن's centre lies right of
        // the ر's. MuPDF gives the Arabic letters in reading order; that order is kept.
        fun g(c: String, x0: Float, x1: Float) = Glyph(c, x0, 0f, x1, 10f)
        val line = listOf(
            g("إ", 94f, 100f), g("ن", 88f, 94f), g("ت", 82f, 88f), g("ر", 66f, 82f), g("ن", 72f, 80f), g("ت", 60f, 66f),
            g(" ", 20f, 60f), g("P", 0f, 5f), g("D", 5f, 10f), g("F", 10f, 15f),
        )
        assertEquals("إنترنت PDF", TextLogic.logicalOrder(line, rtlBase = true).joinToString("") { it.c })
    }

    @Test
    fun linesApartOrColumnsApartStaySeparate() {
        val below = run("next", 40f, y0 = 116f)
        val farColumn = run("price", 200f)
        val joined = TextLogic.joinPieces(listOf(run("item", 40f), below, farColumn))
        assertEquals(3, joined.size)
    }
}
