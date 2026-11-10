package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RowBreaksTest {

    // Built by code point so no editor or tool turns them into something else.
    private fun ch(cp: Int) = cp.toChar().toString()
    private val ls = ch(0x2028) // LINE SEPARATOR
    private val ps = ch(0x2029) // PARAGRAPH SEPARATOR
    private val vt = ch(0x0b) // Word's manual line break (Shift+Enter) on the clipboard
    private val ff = ch(0x0c)
    private val nel = ch(0x85)
    private val rs = ch(0x1e)

    @Test
    fun everyMandatoryBreakEndsALine() {
        assertEquals(listOf("a", "b"), hardLines("a\nb"))
        assertEquals(listOf("a", "b"), hardLines("a\r\nb"))
        assertEquals(listOf("a", "b"), hardLines("a\rb"))
        for (brk in listOf(ls, ps, vt, ff, nel, rs)) assertEquals("break U+%04X".format(brk[0].code), listOf("a", "b"), hardLines("a${brk}b"))
    }

    @Test
    fun keepsEmptyLinesAndCountsCrLfOnce() {
        assertEquals(listOf("a", "", "b"), hardLines("a\r\n\r\nb"))
        assertEquals(listOf("a", "", "b"), hardLines("a$ls${ls}b"))
        assertEquals(listOf(""), hardLines(""))
    }

    @Test
    fun aStampLineNeverCarriesABreakCharacter() {
        // StampText.html makes one paragraph per hard line; a separator left inside a paragraph is
        // drawn as a glyph no font has.
        val html = StampText.html("TOP${ls}BOTTOM${vt}END", TextStyle(12f, Rgb(0f, 0f, 0f)))
        assertEquals(3, Regex("<p ").findAll(html).count())
        for (brk in listOf(ls, vt)) assertEquals(false, html.contains(brk))
    }
}
