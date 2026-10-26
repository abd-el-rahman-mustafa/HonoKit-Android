package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * A drawing under a redaction mark must leave nothing behind in the content stream. MuPDF removes
 * a drawing only when a mark covers it whole unless it is told otherwise, so a drawing that reaches
 * past the mark's edge — an outlined word, a signature, a line of a chart — kept every one of its
 * drawing instructions in the file, under the painted box, for anyone to read back (desktop §105).
 */
@RunWith(AndroidJUnit4::class)
class RedactLineArtTest {
    @Before
    fun setUp() = Fixtures.init()

    // Each bar has a size no other has, so it can be told apart in the rewritten stream.
    private val straddling = "q 0 0 0 rg 100 200 200 40 re f Q" // reaches from outside the mark into it
    private val crossing = "q 0 0 0 rg 20 150 357 3 re f Q" // crosses the mark's column
    private val away = "q 0 0 0 rg 20 380 360 2 re f Q" // nowhere near the mark

    private fun build(): java.io.File {
        val content = "$straddling\n$crossing\n$away\n"
        val out = ByteArrayOutputStream()
        fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 400 400] >>",
            "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>",
            "<< /Length ${content.length} >>"
        )
        put("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets.add(out.size())
            val n = i + 1
            if (n == 4) put("$n 0 obj\n$body\nstream\n$content\nendstream\nendobj\n")
            else put("$n 0 obj\n$body\nendobj\n")
        }
        val at = out.size()
        put("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) put("${o.toString().padStart(10, '0')} 00000 n \n")
        put("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$at\n%%EOF\n")
        return Fixtures.temp("lineart").also { it.writeBytes(out.toByteArray()) }
    }

    @Test
    fun aDrawingOnlyPartlyUnderAMarkLeavesNoGeometryBehind() {
        val src = build()
        val doc = Fixtures.open(src)
        val out = Fixtures.temp("redacted")
        try {
            // The same area under either convention: the mark's y runs 100 to 300 on a 400-high page.
            doc.markForRedaction(listOf(0 to Box(200f, 100f, 340f, 300f)), "Test")
            doc.applyRedactions(Rgb(0f, 0f, 0f), null, sanitize = false)
            doc.save(out, final = true)
        } finally {
            doc.close()
        }
        val streams = Fixtures.allStreams(out)
        // The straddling bar and the crossing rule are gone, geometry and all.
        assertFalse("200 40" in streams)
        assertFalse("357 3" in streams)
        // A drawing the mark never touches stays.
        assertTrue("360 2" in streams)
    }
}
