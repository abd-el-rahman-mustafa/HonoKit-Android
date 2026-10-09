package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which lines of a paragraph an edit leaves untouched (desktop decisions §58). */
class KeptLinesTest {

    private fun line(text: String, i: Int) = StyledLine(
        text = text, box = Box(10f, 10f + i * 12f, 200f, 20f + i * 12f), baseline = 19f + i * 12f, start = 10f,
        rtl = false, size = 10f, colour = 0, bold = false, italic = false, kind = FontKind.Serif,
        horizontal = true, mixed = false, tabular = false,
    )

    private fun para(vararg lines: String): Paragraph {
        val ls = lines.mapIndexed { i, t -> line(t, i) }
        return paragraphsOf(ls).single()
    }

    private val p = para(
        "The quick brown fox jumps over",
        "the lazy dog while the river runs",
        "slowly past old stone walls and",
        "green fields under a grey sky.",
    )

    @Test
    fun oneWordChangedInTheMiddleKeepsTheLinesAround() {
        val k = keptLines(p, p.text.replace("river", "stream"))
        assertEquals(1, k.head)
        assertEquals(2, k.tail)
        assertEquals("the lazy dog while the stream runs", p.text.replace("river", "stream").substring(k.from, k.to).trim())
    }

    @Test
    fun aChangeInTheFirstLineKeepsOnlyTheLinesAfter() {
        val k = keptLines(p, p.text.replace("quick", "slow"))
        assertEquals(0, k.head)
        assertEquals(3, k.tail)
    }

    @Test
    fun aChangeInTheLastLineKeepsTheLinesBefore() {
        val k = keptLines(p, p.text.replace("grey", "blue"))
        assertEquals(3, k.head)
        assertEquals(0, k.tail)
    }

    @Test
    fun atLeastOneLineIsAlwaysSetAgain() {
        val k = keptLines(p, p.text + " Then rain.")
        assertEquals(3, k.head)
        assertEquals(0, k.tail)
    }

    @Test
    fun aPartOfTheParagraphKeepsItsColumn() {
        val part = p.part(1, 3)
        assertEquals(2, part.lines.size)
        assertEquals("the lazy dog while the river runs slowly past old stone walls and", part.text)
        assertEquals(true, part.part)
        assertEquals(p.restStart, part.firstStart)
    }
}
