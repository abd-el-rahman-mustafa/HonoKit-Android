package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The fixes of 2026-10-08/09 that need MuPDF (desktop decisions §82; §52 here): replies and review
 * states keep their comment (A4), the PDF/X output intent and identification, smooth pen strokes.
 * The rest is in the JVM tests (UpdatesLogicTest, TextDiffTest).
 *
 * MuPDF wrappers are only held inside small functions that return plain values (decisions §23).
 */
@RunWith(AndroidJUnit4::class)
class UpdatesFixTest {
    @Before
    fun setUp() = Fixtures.init()

    private val black = Rgb(0f, 0f, 0f)
    private val yellow = Rgb(0.98f, 0.8f, 0.08f)

    private fun text(page: Int) = "BT /F1 20 Tf 60 400 Td (Page ${page + 1}) Tj ET"

    // --- A4: replies and review states keep their parent ----------------------------------------

    /** Page 1: a comment and an unrelated one. Page 2: a reply to the first, its review state, a reply to the reply, an unrelated note. */
    private fun threaded(): File {
        var doc = Fixtures.open(Fixtures.pages(2, ::text))
        doc.addNote(0, 60f, 60f, "Parent", yellow, "Test")
        doc.addNote(0, 300f, 60f, "Alone", yellow, "Test")
        doc = Fixtures.reopen(doc)
        addReplies(doc)
        val f = Fixtures.temp("threaded")
        doc.save(f, final = false)
        doc.close()
        return f
    }

    private fun addReplies(doc: PdfDoc) {
        val annots = doc.pdf.findPage(0).get("Annots")
        val parent = (0 until annots.size()).map { annots.get(it) }.first { it.get("Contents").let { c -> c.isString && c.asString() == "Parent" } }
        fun note(text: String, x: Float, vararg more: Pair<String, Any>) =
            Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Text", "Rect" to Raw.nums(doc, x, 420f, x + 20f, 440f), "Contents" to text, *more), page = 1)
        val reply = note("Reply", 60f, "IRT" to parent)
        note("Accepted set by Reviewer", 60f, "IRT" to parent, "State" to "Accepted", "StateModel" to "Review")
        note("Reply to reply", 60f, "IRT" to reply)
        note("Other", 300f)
    }

    private fun texts(doc: PdfDoc, page: Int) = doc.marks(page).map { it.text }

    @Test
    fun repliesAndReviewStatesAreReadWithTheCommentTheyAnswer() {
        val doc = Fixtures.open(threaded())
        try {
            val parent = doc.marks(0).first { it.text == "Parent" }
            assertTrue(parent.id > 0)
            val two = doc.marks(1)
            val reply = two.first { it.text == "Reply" }
            assertEquals(parent.id, reply.replyTo)
            assertNull(reply.state)
            assertEquals(reply.id, two.first { it.text == "Reply to reply" }.replyTo)
            val state = two.first { it.text.startsWith("Accepted") }
            assertEquals(parent.id, state.replyTo)
            assertEquals("Accepted", state.state)
            assertNull(two.first { it.text == "Other" }.replyTo)
            // No /M: no date (MuPDF answered 1970).
            assertEquals(0L, reply.modified)
        } finally {
            doc.close()
        }
    }

    @Test
    fun deletingACommentRemovesItsRepliesAndStatesOnAnotherPage() {
        var doc = Fixtures.open(threaded())
        try {
            val parent = doc.marks(0).first { it.text == "Parent" }
            doc.deleteMark(0, parent.index, parent.box)
            doc = Fixtures.reopen(doc)
            assertEquals(listOf("Alone"), texts(doc, 0))
            assertEquals(listOf("Other"), texts(doc, 1))
        } finally {
            doc.close()
        }
    }

    @Test
    fun redactingACommentRemovesItsRepliesOnAnotherPage() {
        var doc = Fixtures.open(threaded())
        try {
            val parent = doc.marks(0).first { it.text == "Parent" }
            doc.markForRedaction(listOf(0 to Box(parent.box.x0 - 2, parent.box.y0 - 2, parent.box.x1 + 2, parent.box.y1 + 2)), "Test")
            doc = Fixtures.reopen(doc)
            doc.applyRedactions(black, null, sanitize = false)
            doc = Fixtures.reopen(doc)
            assertEquals(listOf("Alone"), texts(doc, 0))
            assertEquals(listOf("Other"), texts(doc, 1))
        } finally {
            doc.close()
        }
    }

    /** The comment on page [at], its reply, state and reply-to-reply on the page after. */
    private fun assertAttached(doc: PdfDoc, at: Int) {
        val parent = doc.marks(at).first { it.text == "Parent" }
        val two = doc.marks(at + 1)
        val reply = two.first { it.text == "Reply" }
        assertEquals("reply", parent.id, reply.replyTo)
        assertEquals("state", parent.id, two.first { it.text.startsWith("Accepted") }.replyTo)
        assertEquals("reply to reply", reply.id, two.first { it.text == "Reply to reply" }.replyTo)
    }

    @Test
    fun combineAndInsertKeepAReplyOnAnotherPageAttached() {
        val src = threaded()
        val merged = Fixtures.temp("merged")
        val one = Fixtures.open(Fixtures.pages(1, ::text))
        val donor = Fixtures.open(src)
        try {
            PdfFiles.merge(listOf(one, donor), merged)
        } finally {
            one.close()
            donor.close()
        }
        val m = Fixtures.open(merged)
        try {
            assertAttached(m, 1)
        } finally {
            m.close()
        }
        var host = Fixtures.open(Fixtures.pages(1, ::text))
        val other = Fixtures.open(src)
        try {
            host.insertFrom(other, 1)
            host = Fixtures.reopen(host)
            assertAttached(host, 1)
        } finally {
            host.close()
            other.close()
        }
    }

    @Test
    fun aStateWithoutItsCommentIsLeftOutAndAReplyBecomesAComment() {
        var host = Fixtures.open(Fixtures.pages(1, ::text))
        val other = Fixtures.open(threaded())
        try {
            host.insertFrom(other, 1, listOf(1))
            host = Fixtures.reopen(host)
            val marks = host.marks(1)
            assertNull(marks.first { it.text == "Reply" }.replyTo)
            assertFalse(marks.any { it.text.startsWith("Accepted") })
            assertEquals(marks.first { it.text == "Reply" }.id, marks.first { it.text == "Reply to reply" }.replyTo)
        } finally {
            host.close()
            other.close()
        }
    }

    // --- PDF/X output intent and identification ----------------------------------------------------

    private val ns = "xmlns:pdfxid=\"http://www.npes.org/pdfx/ns/id/\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\""

    private class Print(
        val condition: String? = null,
        val profile: String = "\u0001\u0002\u0003\u0004",
        val version: String? = null,
        val claims: String = "",
        val taggedFirst: Boolean = false,
    )

    private fun printPdf(o: Print): File = Fixtures.pages(2, ::text) { pdf -> addPrint(pdf, o) }

    private fun addPrint(pdf: PDFDocument, o: Print) {
        val root = pdf.trailer.get("Root")
        if (o.condition != null) {
            val profile = pdf.addStream(o.profile, pdf.newDictionary().also { it.put("N", 3) })
            val intent = pdf.newDictionary()
            intent.put("Type", pdf.newName("OutputIntent"))
            intent.put("S", pdf.newName("GTS_PDFX"))
            intent.put("OutputConditionIdentifier", pdf.newString(o.condition))
            intent.put("DestOutputProfile", profile)
            val list = pdf.newArray()
            list.push(pdf.indirect(intent))
            root.put("OutputIntents", list)
        }
        if (o.version != null) {
            if (!pdf.trailer.get("Info").isDictionary) pdf.trailer.put("Info", pdf.indirect(pdf.newDictionary()))
            val info = pdf.trailer.get("Info")
            info.put("GTS_PDFXVersion", pdf.newString(o.version))
            info.put("Trapped", pdf.newName("False"))
        }
        if (o.version != null || o.claims.isNotEmpty()) {
            val v = if (o.version != null) " pdfxid:GTS_PDFXVersion=\"${o.version}\"" else ""
            val xmp = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" $ns$v ${o.claims}/></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"
            val d = pdf.newDictionary()
            d.put("Type", pdf.newName("Metadata"))
            d.put("Subtype", pdf.newName("XML"))
            root.put("Metadata", pdf.addStream(xmp, d))
        }
        if (o.taggedFirst) {
            val tree = pdf.indirect(pdf.newDictionary().also { it.put("Type", pdf.newName("StructTreeRoot")) })
            val e = pdf.newDictionary()
            e.put("Type", pdf.newName("StructElem"))
            e.put("S", pdf.newName("P"))
            e.put("P", tree)
            e.put("Pg", pdf.findPage(0))
            e.put("K", 0)
            val kids = pdf.newArray()
            kids.push(pdf.indirect(e))
            tree.put("K", kids)
            root.put("StructTreeRoot", tree)
            root.put("MarkInfo", pdf.newDictionary().also { it.put("Marked", true) })
        }
    }

    private data class Declared(val intents: List<String>, val version: String, val trapped: String, val xmp: String)

    private fun declared(file: File): Declared {
        val doc = Fixtures.open(file)
        try {
            return read(doc)
        } finally {
            doc.close()
        }
    }

    private fun read(doc: PdfDoc): Declared {
        val root = doc.pdf.trailer.get("Root")
        val info = doc.pdf.trailer.get("Info")
        val list = root.get("OutputIntents")
        val intents = if (!list.isArray) emptyList() else (0 until list.size()).map { i ->
            val it = list.get(i)
            val p = it.get("DestOutputProfile")
            it.get("S").asName() + "|" + it.get("OutputConditionIdentifier").asString() + "|" + (if (p.isStream) p.readStream().joinToString("") { b -> "%02x".format(b) } else "")
        }
        return Declared(
            intents,
            if (info.isDictionary && info.get("GTS_PDFXVersion").isString) info.get("GTS_PDFXVersion").asString() else "",
            if (info.isDictionary && info.get("Trapped").isName) info.get("Trapped").asName() else "",
            doc.pdf.metadataXml(),
        )
    }

    private fun merge(vararg files: File): File {
        val docs = files.map { Fixtures.open(it) }
        val out = Fixtures.temp("merged")
        try {
            PdfFiles.merge(docs, out)
        } finally {
            docs.forEach { it.close() }
        }
        return out
    }

    @Test
    fun combineKeepsThePrintStandardWhenEveryFileDeclaresTheSame() {
        val x4 = printPdf(Print("FOGRA39", version = "PDF/X-4", claims = "pdfaid:part=\"2\" pdfaid:conformance=\"B\""))
        val d = declared(merge(x4, x4))
        assertEquals(listOf("GTS_PDFX|FOGRA39|01020304"), d.intents)
        assertEquals("PDF/X-4", d.version)
        assertEquals("False", d.trapped)
        assertTrue(d.xmp, "<pdfxid:GTS_PDFXVersion>PDF/X-4</pdfxid:GTS_PDFXVersion>" in d.xmp)
        // A new identification: the first file's PDF/A claim is not carried to a file made of others' pages.
        assertFalse(d.xmp, "pdfaid" in d.xmp)
        assertFalse(d.xmp, "pdfuaid" in d.xmp)
    }

    @Test
    fun combineClaimsNothingWhenTheFilesDisagreeOrOneIsNotPdfX() {
        val x4 = printPdf(Print("FOGRA39", version = "PDF/X-4"))
        val other = printPdf(Print("GRACoL", version = "PDF/X-4"))
        val otherProfile = printPdf(Print("FOGRA39", profile = "\u0009\u0009\u0009", version = "PDF/X-4"))
        val plain = Fixtures.pages(1, ::text)
        for (second in listOf(other, otherProfile, plain)) {
            val d = declared(merge(x4, second))
            assertEquals(emptyList<String>(), d.intents)
            assertEquals("", d.version)
            assertFalse("pdfxid" in d.xmp)
        }
        // A document without a print standard gains none.
        assertEquals(Declared(emptyList(), "", "", ""), declared(merge(plain, plain)))
    }

    @Test
    fun combineTakesThePdfXVersionFromTheXmpWhenOnlyTheXmpHasIt() {
        val xmpOnly = printPdf(Print("FOGRA39", claims = "pdfxid:GTS_PDFXVersion=\"PDF/X-4\""))
        val d = declared(merge(xmpOnly, xmpOnly))
        assertEquals("PDF/X-4", d.version)
        assertTrue("PDF/X-4" in d.xmp)
    }

    @Test
    fun extractKeepsTheOutputIntentAndDropsPdfUaAndPdfALevelAWithTheTaggedContent() {
        val tagged = printPdf(Print("FOGRA39", version = "PDF/X-4", claims = "pdfaid:part=\"2\" pdfaid:conformance=\"A\" pdfuaid:part=\"1\"", taggedFirst = true))
        val doc = Fixtures.open(tagged)
        try {
            val both = Fixtures.temp("both")
            doc.writePages(listOf(0, 1), both)
            val kept = declared(both)
            assertEquals(listOf("GTS_PDFX|FOGRA39|01020304"), kept.intents)
            assertTrue("pdfuaid:part" in kept.xmp)
            assertTrue("pdfaid:conformance" in kept.xmp)
            val second = Fixtures.temp("second")
            doc.writePages(listOf(1), second)
            val d = declared(second)
            assertEquals(listOf("GTS_PDFX|FOGRA39|01020304"), d.intents)
            assertEquals("PDF/X-4", d.version)
            assertTrue(d.xmp, "PDF/X-4" in d.xmp)
            for (gone in listOf("pdfuaid:part", "pdfaid:conformance", "pdfaid:part")) assertFalse("$gone kept: ${d.xmp}", gone in d.xmp)
        } finally {
            doc.close()
        }
    }

    // --- Pen strokes as smooth curves --------------------------------------------------------------

    private fun inkLists(doc: PdfDoc): List<List<Pair<Float, Float>>> = doc.withPdfPage(0) { page ->
        with(doc) { page.annots() }.first { it.type == PDFAnnotation.TYPE_INK }.inkList.map { s -> s.map { it.x to it.y } }
    }

    @Test
    fun aPenStrokeIsStoredAsTheSmoothCurveThroughItsPoints() {
        val zigzag = listOf(100f to 100f, 150f to 60f, 200f to 100f, 250f to 140f, 300f to 100f)
        var doc = Fixtures.open(Fixtures.pdf())
        try {
            doc.addInk(0, listOf(zigzag, listOf(50f to 300f, 150f to 300f), listOf(200f to 400f)), black, 2f, 1f, "Test")
            doc = Fixtures.reopen(doc)
            val lists = inkLists(doc)
            assertEquals(3, lists.size)
            val curve = lists[0]
            assertTrue("only ${curve.size} points", curve.size > zigzag.size * 4)
            for (p in zigzag) assertTrue("$p is not on the curve", curve.any { kotlin.math.abs(it.first - p.first) < 0.02f && kotlin.math.abs(it.second - p.second) < 0.02f })
            assertEquals("a straight stroke stays two points", 2, lists[1].size)
            assertEquals("a dot stays two points", 2, lists[2].size)
        } finally {
            doc.close()
        }
    }
}
