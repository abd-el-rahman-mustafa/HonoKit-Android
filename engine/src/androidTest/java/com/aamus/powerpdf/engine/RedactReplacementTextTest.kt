package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The replacement text (/ActualText, /Alt) of removed text does not stay in the file. */
@RunWith(AndroidJUnit4::class)
class RedactReplacementTextTest {
    @Before
    fun setUp() = Fixtures.init()

    // The first line sits at y 300 of a 500-point page: 200 from the top.
    private val first = Box(40f, 184f, 220f, 206f)

    private fun redacted(src: File): String {
        val doc = Fixtures.open(src)
        val out = Fixtures.temp("redacted")
        try {
            doc.markForRedaction(listOf(0 to first), "Test")
            assertEquals(0, doc.applyRedactions(Rgb(0f, 0f, 0f), null, sanitize = false).leftover)
            doc.save(out, final = true)
        } finally {
            doc.close()
        }
        return Fixtures.allStreams(out)
    }

    @Test
    fun theRemovedTextsReplacementTextGoesAndOtherSpansKeepTheirs() {
        val src = Fixtures.pdf(
            content = "/Span << /ActualText (Account HIDDEN-98765) >> BDC BT /F1 12 Tf 50 300 Td (Account 98765) Tj ET EMC\n" +
                "/Span << /ActualText (Kept description) >> BDC BT /F1 12 Tf 50 150 Td (Public line) Tj ET EMC",
        )
        val all = redacted(src)
        assertFalse(all.contains("98765"))
        assertTrue(all.contains("Kept description"))
    }

    @Test
    fun aSpanHoldingTheRemovedTextLosesItsDescriptionButKeepsItsMcid() {
        val src = Fixtures.pdf(
            content = "/Sect << /Alt <4f75746572204e616d65> /Lang (en) >> BDC\n" +
                "/Span << /E (Expanded 4242) /MCID 7 >> BDC BT /F1 12 Tf 50 300 Td (Code 4242) Tj ET EMC\n" +
                "BT /F1 12 Tf 50 150 Td (Other line) Tj ET EMC",
        )
        val all = redacted(src)
        assertFalse(all.contains("4242"))
        assertFalse(all.contains("Outer Name"))
        assertTrue(Regex("MCID\\s*7").containsMatchIn(all))
        assertTrue(all.contains("Other line"))
    }

    @Test
    fun aNamedPropertyListIsRetired() {
        val src = Fixtures.pdf(content = "/Span /MC0 BDC BT /F1 12 Tf 50 300 Td (Account 98765) Tj ET EMC") { pdf, page ->
            val list = pdf.newDictionary()
            list.put("ActualText", pdf.newString("Account 98765"))
            list.put("MCID", 3)
            val props = pdf.newDictionary()
            props.put("MC0", list)
            page.get("Resources").put("Properties", props)
        }
        val all = redacted(src)
        assertFalse(all.contains("98765"))
        assertTrue(Regex("MCID\\s*3").containsMatchIn(all))
    }

    @Test
    fun eraseAreaClearsTheReplacementTextOfWhatItErased() {
        val src = Fixtures.pdf(
            content = "/Span << /ActualText (Erased 5555) >> BDC BT /F1 12 Tf 50 300 Td (Erased 5555) Tj ET EMC\n" +
                "/Span << /ActualText (Stays here) >> BDC BT /F1 12 Tf 50 150 Td (Stays here) Tj ET EMC",
        )
        val doc = Fixtures.open(src)
        val out = Fixtures.temp("erased")
        try {
            doc.eraseArea(0, first)
            doc.save(out, final = true)
        } finally {
            doc.close()
        }
        val all = Fixtures.allStreams(out)
        assertFalse(all.contains("5555"))
        assertEquals(2, Regex("Stays here").findAll(all).count())
    }
}
