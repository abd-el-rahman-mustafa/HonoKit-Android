package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * A font keeps the shape of the characters it drew. A Type 3 font's glyph programs are content
 * streams, each free to draw a picture of its own, so a scanned or bitmap-font page whose text was
 * redacted left the ink of the redacted characters in the saved file. A font the redacted pages no
 * longer name must go with them, and a font another page still draws must stay.
 */
@RunWith(AndroidJUnit4::class)
class RedactFontsTest {
    @Before
    fun setUp() = Fixtures.init()

    // Samples no other object in the file has, one line of 8-bit grey each.
    private val inkA = "GLYPH-A-INK-PAYLOAD"
    private val inkB = "GLYPH-B-INK-PAYLOAD"
    private fun picture(payload: String) = "<< /Type /XObject /Subtype /Image /Width ${payload.length} /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Length LEN >>"
    private fun glyph(name: String) = "1000 0 0 0 1000 1000 d0\nq 1000 0 0 1000 0 0 cm /$name Do Q"
    private val type3 =
        "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1000] /FontMatrix [0.001 0 0 0.001 0 0] " +
            "/FirstChar 65 /LastChar 66 /Widths [1000 1000] /Encoding << /Differences [65 /Ga /Gb] >> " +
            "/CharProcs << /Ga 7 0 R /Gb 8 0 R >> /Resources << /XObject << /ImA 9 0 R /ImB 10 0 R >> >> >>"
    private val noText = "0 0 0 rg 10 10 40 40 re f"

    /** A hand-built file, so the resource layout is exactly what the test needs. */
    private fun build(objects: List<String>, streams: Map<Int, String>): java.io.File {
        val out = ByteArrayOutputStream()
        fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        put("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets.add(out.size())
            val n = i + 1
            val s = streams[n]
            if (s != null) put("$n 0 obj\n${body.replace("LEN", s.length.toString())}\nstream\n$s\nendstream\nendobj\n")
            else put("$n 0 obj\n$body\nendobj\n")
        }
        val at = out.size()
        put("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) put("${o.toString().padStart(10, '0')} 00000 n \n")
        put("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$at\n%%EOF\n")
        return Fixtures.temp("fonts").also { it.writeBytes(out.toByteArray()) }
    }

    /**
     * Two pages sharing one /Resources, which is where a redaction leaves a font behind: the
     * rewritten page gets its own pruned resources, while the dictionary it drew through keeps
     * every name the second page's presence justifies. The first page draws "AB" with a Type 3
     * font whose two glyphs are pictures; what the second page draws is the argument.
     */
    private fun shared(second: String) = build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 11 0 R] /Count 2 /MediaBox [0 0 300 300] >>",
            "<< /Type /Page /Parent 2 0 R /Resources 5 0 R /Contents 4 0 R >>",
            "<< /Length LEN >>",
            "<< /Font << /T3 6 0 R >> >>",
            type3,
            "<< /Length LEN >>",
            "<< /Length LEN >>",
            picture(inkA),
            picture(inkB),
            "<< /Type /Page /Parent 2 0 R /Resources 5 0 R /Contents 12 0 R >>",
            "<< /Length LEN >>"
        ),
        mapOf(4 to "BT /T3 24 Tf 20 200 Td (AB) Tj ET", 7 to glyph("ImA"), 8 to glyph("ImB"), 9 to inkA, 10 to inkB, 12 to second)
    )

    /**
     * No page's own content names /T3 after the redaction, but another Type 3 font in the same
     * dictionary draws with it from inside a glyph program, which a reader resolves against the
     * page's resources (PDF 32000 §9.6.5). The font and its glyphs must stay.
     */
    private fun nested() = build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 11 0 R] /Count 2 /MediaBox [0 0 300 300] >>",
            "<< /Type /Page /Parent 2 0 R /Resources 5 0 R /Contents 4 0 R >>",
            "<< /Length LEN >>",
            "<< /Font << /T3 6 0 R /T4 13 0 R >> >>",
            type3,
            "<< /Length LEN >>",
            "<< /Length LEN >>",
            picture(inkA),
            picture(inkB),
            "<< /Type /Page /Parent 2 0 R /Resources 5 0 R /Contents 12 0 R >>",
            "<< /Length LEN >>",
            "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1000] /FontMatrix [0.001 0 0 0.001 0 0] " +
                "/FirstChar 65 /LastChar 65 /Widths [1000] /Encoding << /Differences [65 /Ha] >> /CharProcs << /Ha 14 0 R >> >>",
            "<< /Length LEN >>"
        ),
        mapOf(
            4 to "BT /T3 24 Tf 20 200 Td (AB) Tj ET",
            7 to glyph("ImA"),
            8 to glyph("ImB"),
            9 to inkA,
            10 to inkB,
            12 to "BT /T4 24 Tf 20 100 Td (A) Tj ET",
            14 to "1000 0 0 0 1000 1000 d0\nBT /T3 10 Tf 0 0 Td (AB) Tj ET"
        )
    )

    /** The mark covers both characters, so the redacted page draws no text afterwards. */
    private fun redacted(src: java.io.File): String {
        val doc = Fixtures.open(src)
        val out = Fixtures.temp("redacted")
        try {
            doc.markForRedaction(listOf(0 to Box(16f, 72f, 72f, 104f)), "Test")
            assertEquals(0, doc.applyRedactions(Rgb(0f, 0f, 0f), null, sanitize = false).leftover)
            doc.save(out, final = true)
        } finally {
            doc.close()
        }
        return Fixtures.allStreams(out)
    }

    @Test
    fun aType3FontsGlyphPicturesDoNotSurviveInASharedDictionary() {
        val src = shared(noText)
        assertTrue(inkA in Fixtures.allStreams(src))
        val out = redacted(src)
        assertFalse(inkA in out)
        assertFalse(inkB in out)
    }

    @Test
    fun aFontAnotherPageStillDrawsWithIsKept() {
        assertTrue(inkB in redacted(shared("BT /T3 24 Tf 20 200 Td (B) Tj ET")))
    }

    @Test
    fun aFontAnotherPagesGlyphProgramDrawsWithIsKept() {
        val out = redacted(nested())
        assertTrue(inkA in out)
        assertTrue(inkB in out)
    }
}
