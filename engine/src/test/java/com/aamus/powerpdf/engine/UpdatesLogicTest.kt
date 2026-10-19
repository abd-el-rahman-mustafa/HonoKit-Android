package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.math.acos
import kotlin.math.hypot

/**
 * The fixes of 2026-10-08/09 that need no MuPDF (desktop decisions §82; §52 here): smooth pen
 * strokes, comment threads, exclusive staging files and the XMP claims. The MuPDF parts are in the
 * instrumented UpdatesFixTest.
 */
class UpdatesLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // --- Pen strokes as smooth curves -------------------------------------------------------

    /** The largest turn, in degrees, between consecutive segments of a stroke. */
    private fun sharpestTurn(points: List<Pair<Float, Float>>): Double {
        var worst = 0.0
        for (i in 1 until points.size - 1) {
            val ax = (points[i].first - points[i - 1].first).toDouble()
            val ay = (points[i].second - points[i - 1].second).toDouble()
            val bx = (points[i + 1].first - points[i].first).toDouble()
            val by = (points[i + 1].second - points[i].second).toDouble()
            val n = hypot(ax, ay) * hypot(bx, by)
            val cos = if (n == 0.0) 1.0 else (ax * bx + ay * by) / n
            worst = maxOf(worst, Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0))))
        }
        return worst
    }

    private val zigzag = listOf(100f to 100f, 150f to 60f, 200f to 100f, 250f to 140f, 300f to 100f)

    @Test
    fun aStrokeBecomesASmoothCurveThroughEveryPointDrawn() {
        assertTrue(sharpestTurn(zigzag) > 70)
        val s = Smooth.stroke(zigzag)
        assertTrue("only ${s.size} points", s.size > zigzag.size * 4)
        for (p in zigzag) assertTrue("$p is not on the curve", s.any { hypot((it.first - p.first).toDouble(), (it.second - p.second).toDouble()) < 0.01 })
        assertTrue("turns by ${sharpestTurn(s)}°", sharpestTurn(s) < 25)
        assertEquals(zigzag.first(), s.first())
        assertEquals(zigzag.last(), s.last())
    }

    @Test
    fun twoPointsAndADotStayAsTheyAre() {
        val two = listOf(50f to 50f, 150f to 50f)
        assertEquals(two, Smooth.stroke(two))
        assertEquals(listOf(5f to 5f), Smooth.stroke(listOf(5f to 5f)))
        // Repeated points count once: still a straight two-point stroke.
        assertEquals(listOf(1f to 1f, 1f to 1f, 9f to 9f), Smooth.stroke(listOf(1f to 1f, 1f to 1f, 9f to 9f)))
    }

    @Test
    fun aVeryLongStrokeStaysASensibleSize() {
        val long = (0 until 2000).map { i -> (i * 5f) to (if (i % 2 == 0) 0f else 40f) }
        val s = Smooth.stroke(long)
        assertTrue("${s.size} points", s.size < 2000 * 64 && s.size <= 4000 + 2 * 2000)
    }

    // --- A4: comment threads -------------------------------------------------------------

    private data class Item(val id: Int, val replyTo: Int? = null, val state: String? = null, val text: String, val page: Int = 0)

    private fun threads(items: List<Item>) = CommentThreads(items, { it.id }, { it.replyTo }, { it.state })

    @Test
    fun repliesAndStatesGoUnderTheirCommentFromAnyPage() {
        val items = listOf(
            Item(10, text = "Parent"),
            Item(11, text = "Alone"),
            Item(20, replyTo = 10, text = "Reply", page = 1),
            Item(21, replyTo = 10, state = "Accepted", text = "Accepted set by Reviewer", page = 1),
            Item(22, replyTo = 20, text = "Reply to reply", page = 1),
            Item(23, text = "Other", page = 1),
        )
        val t = threads(items)
        assertEquals(listOf("Parent", "Alone", "Other"), t.top.map { it.text })
        val parent = t.top[0]
        assertEquals(listOf(Triple("Reply", 1, 1), Triple("Reply to reply", 2, 1)), t.repliesOf(parent).map { (r, d) -> Triple(r.text, d, r.page) })
        assertEquals(listOf("Accepted"), t.statesOf(parent).map { it.state })
        assertEquals(emptyList<Item>(), t.statesOf(t.top[1]))
    }

    @Test
    fun aLoopOrAMissingParentShowsTheRepliesOnTheirOwn() {
        val loop = threads(listOf(Item(1, replyTo = 2, text = "a"), Item(2, replyTo = 1, text = "b")))
        assertEquals(listOf("a", "b"), loop.top.map { it.text })
        val orphan = threads(listOf(Item(5, replyTo = 99, text = "orphan"), Item(6, replyTo = 6, text = "self")))
        assertEquals(listOf("orphan", "self"), orphan.top.map { it.text })
    }

    // --- Staging files are created exclusively -----------------------------------------

    @Test
    fun aStagingFileIsNewAndSkipsANameThatIsTaken() {
        val dir = tmp.newFolder("stage")
        val planted = File(dir, ".out.first.tmp").apply { writeText("planted") }
        val f = Staging.create(dir, ".out.", ".tmp", listOf("first", "second").iterator())
        assertEquals(".out.second.tmp", f.name)
        assertEquals(0L, f.length())
        assertEquals("planted", planted.readText())
    }

    @Test
    fun noFileIsMadeWhenEveryNameIsTaken() {
        val dir = tmp.newFolder("full")
        File(dir, "x.tmp").writeText("planted")
        try {
            Staging.create(dir, "", ".tmp", listOf("x", "x").iterator())
            fail("a taken name was used")
        } catch (_: IOException) {
        }
        assertEquals(listOf("x.tmp"), dir.list()!!.toList())
        assertEquals("planted", File(dir, "x.tmp").readText())
    }

    @Test
    fun randomNamesDiffer() {
        val dir = tmp.newFolder("random")
        val a = Staging.create(dir, "w-", ".pdf")
        val b = Staging.create(dir, "w-", ".pdf")
        assertFalse(a == b)
        assertTrue(a.exists() && b.exists())
    }

    // --- What a document claims in its XMP ---------------------------------------------------

    private val ns = "xmlns:pdfxid=\"http://www.npes.org/pdfx/ns/id/\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\""

    private fun packet(claims: String) =
        "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" $ns $claims/></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"

    @Test
    fun xmpValuesAreReadAsAttributesOrElements() {
        assertEquals("PDF/X-4", Xmp.value(packet("pdfxid:GTS_PDFXVersion=\"PDF/X-4\""), "pdfxid:GTS_PDFXVersion"))
        assertEquals("A", Xmp.value("<pdfaid:conformance>A</pdfaid:conformance>", "pdfaid:conformance"))
        assertEquals("", Xmp.value(packet(""), "pdfaid:part"))
    }

    @Test
    fun withoutTaggedContentPdfUaAndPdfALevelAClaimsGo() {
        val xmp = packet("pdfxid:GTS_PDFXVersion=\"PDF/X-4\" pdfaid:part=\"2\" pdfaid:conformance=\"A\" pdfuaid:part=\"1\"")
        val out = Xmp.withdrawn(xmp)
        assertTrue("PDF/X-4" in out)
        for (gone in listOf("pdfuaid:part", "pdfaid:part", "pdfaid:conformance")) assertFalse("$gone kept: $out", gone in out)
        // Elements too.
        val elems = "<rdf:Description><pdfuaid:part>1</pdfuaid:part><pdfaid:part>3</pdfaid:part><pdfaid:conformance>a</pdfaid:conformance></rdf:Description>"
        assertEquals("<rdf:Description></rdf:Description>", Xmp.withdrawn(elems))
    }

    @Test
    fun theIdentificationIsReadByItsNamespaceWhateverItsPrefix() {
        val ns = "xmlns:ida=\"http://www.aiim.org/pdfa/ns/id/\" xmlns:idua=\"http://www.aiim.org/pdfua/ns/id/\" xmlns:idx=\"http://www.npes.org/pdfx/ns/id/\""
        val xmp = "<rdf:Description rdf:about=\"\" $ns idx:GTS_PDFXVersion=\"PDF/X-4\" ida:part=\"2\" ida:conformance=\"A\" idua:part=\"1\"/>"
        assertEquals("PDF/X-4", Xmp.valueIn(xmp, Xmp.PDFX_NS, "pdfxid", "GTS_PDFXVersion"))
        val out = Xmp.withdrawn(xmp)
        assertTrue("PDF/X-4" in out)
        for (gone in listOf("idua:part", "ida:part", "ida:conformance")) assertFalse("$gone kept: $out", gone in out)
    }

    @Test
    fun aPdfALevelBClaimStays() {
        val xmp = packet("pdfaid:part=\"2\" pdfaid:conformance=\"B\" pdfuaid:part=\"1\"")
        val out = Xmp.withdrawn(xmp)
        assertTrue("pdfaid:conformance=\"B\"" in out && "pdfaid:part=\"2\"" in out)
        assertFalse("pdfuaid" in out.substringAfter("xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\""))
    }

    @Test
    fun aCombinedFileClaimsPdfXOnly() {
        val p = Xmp.pdfxPacket("PDF/X-4", "", "False", "Brochure <A&B>")
        assertTrue("<pdfxid:GTS_PDFXVersion>PDF/X-4</pdfxid:GTS_PDFXVersion>" in p)
        assertTrue("<pdf:Trapped>False</pdf:Trapped>" in p)
        assertTrue("Brochure &lt;A&amp;B&gt;" in p)
        assertFalse("pdfaid" in p)
        assertFalse("pdfuaid" in p)
        assertFalse("GTS_PDFXConformance" in p)
    }
}
