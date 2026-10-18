package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplacementTextTest {
    @Test
    fun aSpanRewrittenWithOtherSpacingAndEscapesIsTheSameSpan() {
        val a = ReplacementText.spans("/Span << /ActualText (A\\051B) /MCID 2 >> BDC BT 1 0 0 1 50 300 Tm (Hello) Tj ET EMC")
        val b = ReplacementText.spans("/Span<</ActualText(A\\)B)/MCID 2>>BDC\nBT 50 300 TD [(Hel) -20 (lo)] TJ ET EMC")
        assertEquals(1, a.size)
        assertEquals(a[0].key, b[0].key)
    }

    @Test
    fun aSpanWhoseTextChangedIsAnotherSpan() {
        val a = ReplacementText.spans("/Span << /ActualText (X) >> BDC BT (Account 98765) Tj ET EMC")
        val b = ReplacementText.spans("/Span << /ActualText (X) >> BDC BT (Account) Tj ET EMC")
        val emptied = ReplacementText.spans("/Span << /ActualText (X) >> BDC q EMC")
        assertNotEquals(a[0].key, b[0].key)
        assertNotEquals(a[0].key, emptied[0].key)
    }

    @Test
    fun nestedSpansCountWhatTheirChildrenDraw() {
        val spans = ReplacementText.spans("/Sect << /Alt (Outer) >> BDC /Span << /MCID 1 >> BDC BT (inner) Tj ET EMC /Im1 Do EMC")
        assertEquals(2, spans.size)
        assertEquals("inner", spans[0].text.toString())
        assertEquals(listOf("Do/Im1"), spans[0].objects)
        assertEquals("inner", spans[1].text.toString())
    }

    @Test
    fun aNamedPropertyListIsKnownByItsName() {
        val span = ReplacementText.spans("/Span /MC0 BDC BT (x) Tj ET EMC").single()
        assertEquals("MC0", span.named)
    }

    @Test
    fun onlyTheReplacementTextEntriesAreRemoved() {
        val out = ReplacementText.withoutKeys("<< /ActualText (Secret) /Lang (en) /Alt <414243> /MCID 7 /E (x) /T (t) /Extra << /ActualText (deep) >> >>")!!
        assertFalse(out.contains("Secret"))
        assertFalse(out.contains("414243"))
        assertFalse(out.contains("/E "))
        assertFalse(out.contains("/T "))
        assertTrue(out.contains("/Lang (en)"))
        assertTrue(out.contains("/MCID 7"))
        // Only the span's own entries: a nested dictionary is left as written.
        assertTrue(out.contains("/Extra << /ActualText (deep) >>"))
        assertNull(ReplacementText.withoutKeys("<< /MCID 7 /Lang (en) >>"))
    }
}
