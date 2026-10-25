package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** A redacted picture does not stay in the file through resources the page shares with others. */
@RunWith(AndroidJUnit4::class)
class RedactResourcesTest {
    @Before
    fun setUp() = Fixtures.init()

    private val w = 8
    // Samples no other object in the file has: 8 x 8 RGB.
    private val samples = "PIXELSECRET-".repeat(20).substring(0, w * w * 3)
    private val image = "<< /Type /XObject /Subtype /Image /Width $w /Height $w /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length LEN >>"
    private val draw = "q 200 0 0 200 100 100 cm /Im0 Do Q"
    private val nothing = "BT ET"

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
        return Fixtures.temp("shared").also { it.writeBytes(out.toByteArray()) }
    }

    private fun redacted(src: java.io.File): String {
        val doc = Fixtures.open(src)
        val out = Fixtures.temp("redacted")
        try {
            doc.markForRedaction(listOf(0 to Box(80f, 80f, 320f, 320f)), "Test")
            assertEquals(0, doc.applyRedactions(Rgb(0f, 0f, 0f), null, sanitize = false).leftover)
            doc.save(out, final = true)
        } finally {
            doc.close()
        }
        return Fixtures.allStreams(out)
    }

    @Test
    fun aPictureInheritedFromThePageTreeDoesNotSurvive() {
        val src = build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /Resources << /XObject << /Im0 5 0 R >> >> /MediaBox [0 0 400 400] >>",
                "<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>",
                "<< /Type /Page /Parent 2 0 R /Contents 7 0 R >>",
                image, "<< /Length LEN >>", "<< /Length LEN >>"
            ),
            mapOf(5 to samples, 6 to draw, 7 to nothing)
        )
        assertTrue(samples in Fixtures.allStreams(src))
        assertFalse(samples in redacted(src))
    }

    @Test
    fun aPictureInAResourceDictionarySharedWithAnotherPageDoesNotSurvive() {
        val src = build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 400 400] >>",
                "<< /Type /Page /Parent 2 0 R /Resources 5 0 R /Contents 7 0 R >>",
                "<< /Type /Page /Parent 2 0 R /Resources 5 0 R /Contents 8 0 R >>",
                "<< /XObject << /Im0 6 0 R >> >>", image, "<< /Length LEN >>", "<< /Length LEN >>"
            ),
            mapOf(6 to samples, 7 to draw, 8 to nothing)
        )
        assertFalse(samples in redacted(src))
    }

    /**
     * A form on a page that was not redacted draws the picture through the dictionary all three
     * share. Reading only forms without /Resources of their own missed that, and the name was
     * unlinked, so the picture vanished from a page with nothing redacted on it (desktop §102).
     */
    @Test
    fun aPictureAFormOnAnotherPageDrawsThroughTheSharedResourcesIsKept() {
        val src = build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 400 400] >>",
                "<< /Type /Page /Parent 2 0 R /Resources 9 0 R /Contents 7 0 R >>",
                "<< /Type /Page /Parent 2 0 R /Resources 9 0 R /Contents 8 0 R >>",
                "<< /Type /XObject /Subtype /Form /BBox [0 0 400 400] /Resources 9 0 R /Length LEN >>",
                image, "<< /Length LEN >>", "<< /Length LEN >>",
                "<< /XObject << /Im0 6 0 R /Fx 5 0 R >> >>"
            ),
            mapOf(5 to draw, 6 to samples, 7 to draw, 8 to "q /Fx Do Q")
        )
        assertTrue(samples in redacted(src))
    }

    /**
     * An annotation written as a dictionary of its own in /Annots, with no object number: it used
     * to be skipped, so a comment under a mark stayed in the file, and an inline reply kept the
     * comment it answered — its text included — in it while the page looked clean (desktop §102).
     */
    @Test
    fun aCommentWrittenStraightIntoThePageListIsTakenOffAMark() {
        val secret = "TopSecretCommentText"
        val src = build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 400 400] >>",
                "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Annots [<< /Type /Annot /Subtype /Text /Rect [100 100 120 120] /Contents ($secret) >>] >>",
                "<< /Length LEN >>"
            ),
            mapOf(4 to nothing)
        )
        assertTrue(secret in Fixtures.allStreams(src))
        assertFalse(secret in redacted(src))
    }

    @Test
    fun aReplyWrittenStraightIntoThePageListGoesWithTheCommentItAnswers() {
        val secret = "TopSecretCommentText"
        val src = build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 400 400] >>",
                "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Annots [5 0 R << /Type /Annot /Subtype /Text /Rect [360 360 380 380] /Contents (Reply) /IRT 5 0 R >>] >>",
                "<< /Length LEN >>",
                "<< /Type /Annot /Subtype /Text /Rect [100 100 120 120] /Contents ($secret) >>"
            ),
            mapOf(4 to nothing)
        )
        assertTrue(secret in Fixtures.allStreams(src))
        val out = redacted(src)
        assertFalse(secret in out)
        assertFalse("(Reply)" in out)
    }

    @Test
    fun aPictureAnotherPageStillDrawsIsKept() {
        val src = build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /Resources << /XObject << /Im0 5 0 R >> >> /MediaBox [0 0 400 400] >>",
                "<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>",
                "<< /Type /Page /Parent 2 0 R /Contents 7 0 R >>",
                image, "<< /Length LEN >>", "<< /Length LEN >>"
            ),
            mapOf(5 to samples, 6 to draw, 7 to draw)
        )
        assertTrue(samples in redacted(src))
    }
}
