package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aamus.powerpdf.engine.Fixtures.Shape
import com.artifex.mupdf.fitz.PDFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The second pass of the desktop's review fixes (desktop test/pagesfix.test.ts,
 * editfix.test.ts, pictures.test.ts, compressocr.test.ts; decisions §50): pages (P1–P6), overlays
 * (E4, E5), edits (E1, E2, E6), the text layer, Compress and signed files.
 *
 * MuPDF wrappers (PDFObject) are only ever held inside small functions that return plain values,
 * so none is alive when its document is closed (docs/testing.md, decisions §23).
 */
@RunWith(AndroidJUnit4::class)
class PagesFixTest {
    @Before
    fun setUp() = Fixtures.init()

    private val black = Rgb(0f, 0f, 0f)
    private val yellow = Rgb(0.98f, 0.8f, 0.08f)

    private fun text(page: Int) = "BT /F1 20 Tf 60 400 Td (Page ${page + 1}) Tj ET"

    // --- helpers returning plain values ---------------------------------------------------------

    private fun pageNum(doc: PdfDoc, i: Int) = doc.pdf.findPage(i).asIndirect()

    private fun contentNums(doc: PdfDoc, i: Int): List<Int> {
        val c = doc.pdf.findPage(i).get("Contents")
        return if (c.isArray) (0 until c.size()).map { c.get(it).asIndirect() } else listOf(c.asIndirect())
    }

    private fun annotNums(doc: PdfDoc, i: Int): List<Int> {
        val a = doc.pdf.findPage(i).get("Annots")
        return if (a.isArray) (0 until a.size()).map { a.get(it).asIndirect() } else emptyList()
    }

    /** Each annotation of page [i]: subtype and the object number its /P names. */
    private fun annotOwners(doc: PdfDoc, i: Int): List<Pair<String, Int>> =
        Raw.annots(doc, i).map { (if (it.get("Subtype").isName) it.get("Subtype").asName() else "") to (if (it.get("P").isIndirect) it.get("P").asIndirect() else -1) }

    private fun fieldNames(doc: PdfDoc): List<String> {
        val f = doc.pdf.trailer.get("Root").get("AcroForm").get("Fields")
        return if (f.isArray) (0 until f.size()).map { f.get(it).get("T").asString() } else emptyList()
    }

    private fun linkTargets(doc: PdfDoc, i: Int): List<Int> = Raw.annots(doc, i).filter { it.get("Subtype").asName() == "Link" }.map { a ->
        val d = if (!a.get("Dest").isNull) a.get("Dest") else a.get("A").get("D")
        if (d.isArray && d.get(0).isIndirect) d.get(0).asIndirect() else -1
    }

    private fun rootKeys(doc: PdfDoc): Set<String> = doc.pdf.trailer.get("Root").iterator().asSequence().map { it.asName() }.toSet()

    /** The page-label ranges: "0", "S=r", "2", "S=D St=3"… */
    private fun labelNums(doc: PdfDoc): List<String> {
        val nums = doc.pdf.trailer.get("Root").get("PageLabels").get("Nums")
        if (!nums.isArray) return emptyList()
        return (0 until nums.size()).map { k ->
            val v = nums.get(k)
            if (v.isNumber) v.asInteger().toString()
            else "S=" + v.get("S").asName() + (if (v.get("St").isNumber) " St=" + v.get("St").asInteger() else "")
        }
    }

    private fun structKids(doc: PdfDoc): Int {
        val k = doc.pdf.trailer.get("Root").get("StructTreeRoot").get("K")
        return if (k.isArray) k.size() else if (k.isNull) 0 else 1
    }

    private fun rotate(doc: PdfDoc, i: Int) = rotationOf(doc.pdf.findPage(i))

    /** The deepest q nesting reached by page [i]'s own content streams. */
    private fun maxDepth(doc: PdfDoc, i: Int): Int {
        val text = String(contentOf(doc.pdf.findPage(i)).let { b -> b.asByteArray().also { b.destroy() } }, Charsets.ISO_8859_1)
        var depth = 0
        var max = 0
        // Our overlays and these pages hold no strings with a lone q or Q in them.
        for (t in text.split(Regex("\\s+"))) {
            if (t == "q") max = maxOf(max, ++depth) else if (t == "Q") depth--
        }
        return max
    }

    private fun addLink(doc: PdfDoc, page: Int, box: List<Float>, toPage: Int) {
        val d = doc.pdf.newArray()
        d.push(doc.pdf.findPage(toPage))
        d.push(doc.pdf.newName("XYZ"))
        d.push(0f); d.push(500f); d.push(0f)
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Link", "Rect" to Raw.nums(doc, *box.toFloatArray()), "Dest" to d), page)
    }

    // --- P1 ---------------------------------------------------------------------------------------

    @Test
    fun aDuplicatedPageIsAPageOfItsOwn() {
        var doc = Fixtures.open(Fixtures.pages(2, ::text))
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("Name", "Tx", Box(40f, 40f, 240f, 64f))))
        doc = Fixtures.reopen(doc)
        doc.addNote(0, 300f, 120f, "a note", yellow, "Test")
        doc = Fixtures.reopen(doc)
        try {
            doc.duplicatePages(listOf(0))
            doc = Fixtures.reopen(doc)
            assertEquals(3, doc.pageCount)
            assertNotEquals("the copy is the same page object", pageNum(doc, 0), pageNum(doc, 1))
            assertTrue("the copy shares content streams", contentNums(doc, 0).intersect(contentNums(doc, 1).toSet()).isEmpty())
            assertTrue("the copy shares annotations", annotNums(doc, 0).intersect(annotNums(doc, 1).toSet()).isEmpty())
            // Each copy's annotations point at their own page.
            assertTrue(annotOwners(doc, 1).all { it.second == pageNum(doc, 1) })
            assertTrue(annotOwners(doc, 0).none { it.second == pageNum(doc, 1) })
            // The widget is a second widget of the same field (as Acrobat), still one field.
            assertEquals(listOf("Name"), fieldNames(doc))
            assertEquals(listOf("Name"), doc.formFields(1).map { it.name })
            // Turning or marking the copy leaves the original.
            doc.rotatePages(listOf(1), 90)
            doc.addNote(1, 50f, 50f, "only on the copy", yellow, "Test")
            doc = Fixtures.reopen(doc)
            assertEquals(0, rotate(doc, 0))
            assertEquals(90, rotate(doc, 1))
            assertEquals(1, doc.marks(0).size)
            assertEquals(2, doc.marks(1).size)
            assertTrue("Page 1" in doc.pageText(1))
        } finally {
            doc.close()
        }
    }

    // --- P2 ---------------------------------------------------------------------------------------

    /** Two pages: page 1 has a link to page 2 and to itself, a note and a field "Name". */
    private fun donor(): File {
        var doc = Fixtures.open(Fixtures.pages(2, ::text))
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("Name", "Tx", Box(40f, 40f, 240f, 64f))))
        doc = Fixtures.reopen(doc)
        doc.setField(0, doc.formFields(0).single().index, text = "Donor value")
        doc = Fixtures.reopen(doc)
        addLink(doc, 0, listOf(60f, 300f, 200f, 320f), 1)
        addLink(doc, 0, listOf(60f, 200f, 200f, 220f), 0)
        doc = Fixtures.reopen(doc)
        doc.addNote(0, 300f, 120f, "donor note", yellow, "Test")
        val f = Fixtures.temp("donor")
        doc.save(f, final = false)
        doc.close()
        return f
    }

    private fun host(): PdfDoc {
        val doc = Fixtures.open(Fixtures.pages(1, ::text))
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("Name", "Tx", Box(40f, 40f, 240f, 64f))))
        return Fixtures.reopen(doc)
    }

    @Test
    fun insertedPagesKeepTheirCommentsLinksAndFields() {
        val other = Fixtures.open(donor())
        var doc = host()
        try {
            doc.insertFrom(other, 1)
            doc = Fixtures.reopen(doc)
            assertEquals(3, doc.pageCount)
            val kinds = Raw.subtypes(doc, 1).sorted()
            assertEquals(listOf("Link", "Link", "Popup", "Text", "Widget"), kinds)
            assertTrue(annotOwners(doc, 1).all { it.second == pageNum(doc, 1) })
            // The links lead to the copies of their pages.
            assertEquals(setOf(pageNum(doc, 2), pageNum(doc, 1)), linkTargets(doc, 1).toSet())
            // The field came along, renamed so the two forms do not share a value.
            assertEquals(listOf("Name", "Name_2"), fieldNames(doc))
            assertEquals("Donor value", doc.formFields(1).single().value)
            assertEquals("", doc.formFields(0).single().value)
        } finally {
            doc.close()
            other.close()
        }
    }

    @Test
    fun aLinkToAPageNotCopiedIsLeftOutAndMergeKeepsComments() {
        val other = Fixtures.open(donor())
        var doc = host()
        try {
            doc.insertFrom(other, 0, listOf(0))
            doc = Fixtures.reopen(doc)
            assertEquals(listOf(pageNum(doc, 0)), linkTargets(doc, 0))
            assertTrue("Text" in Raw.subtypes(doc, 0))
            val merged = Fixtures.temp("merged")
            PdfFiles.merge(listOf(doc, other), merged)
            val m = Fixtures.open(merged)
            try {
                assertEquals(4, m.pageCount)
                // In page order: the inserted copy (Name_2), the host's own (Name), the donor's again (taken twice: Name_3).
                assertEquals(listOf("Name_2", "Name", "Name_3"), fieldNames(m))
                assertTrue("Text" in Raw.subtypes(m, 2))
                assertEquals(setOf(pageNum(m, 3), pageNum(m, 2)), linkTargets(m, 2).toSet())
            } finally {
                m.close()
            }
        } finally {
            doc.close()
            other.close()
        }
    }

    // --- P3 ---------------------------------------------------------------------------------------

    /** Four pages with page labels (i, ii, 1, 2), a language, viewer preferences, mark info and a structure tree. */
    private fun catalogued(): File = Fixtures.pages(4, ::text) { pdf -> addCatalogue(pdf) }

    private fun addCatalogue(pdf: PDFDocument) {
        val root = pdf.trailer.get("Root")
        val nums = pdf.newArray()
        nums.push(0)
        nums.push(pdf.newDictionary().also { it.put("S", pdf.newName("r")) })
        nums.push(2)
        nums.push(pdf.newDictionary().also { it.put("S", pdf.newName("D")) })
        root.put("PageLabels", pdf.newDictionary().also { it.put("Nums", nums) })
        root.put("Lang", pdf.newString("en-GB"))
        root.put("ViewerPreferences", pdf.newDictionary().also { it.put("DisplayDocTitle", true) })
        root.put("MarkInfo", pdf.newDictionary().also { it.put("Marked", true) })
        val tree = pdf.indirect(pdf.newDictionary().also { it.put("Type", pdf.newName("StructTreeRoot")) })
        val kids = pdf.newArray()
        for (p in listOf(0, 3)) {
            val e = pdf.newDictionary()
            e.put("Type", pdf.newName("StructElem"))
            e.put("S", pdf.newName("P"))
            e.put("P", tree)
            e.put("Pg", pdf.findPage(p))
            e.put("K", 0)
            kids.push(pdf.indirect(e))
        }
        tree.put("K", kids)
        root.put("StructTreeRoot", tree)
    }

    @Test
    fun aLabelTreeRepeatingOneBranchIsReadOnce() {
        // Each level lists the level below 40 times: walked naively, that is 40^8 leaves.
        val file = Fixtures.pages(3, ::text) { pdf ->
            val nums = pdf.newArray()
            nums.push(0)
            nums.push(pdf.newDictionary().also { it.put("S", pdf.newName("r")) })
            var node = pdf.indirect(pdf.newDictionary().also { it.put("Nums", nums) })
            repeat(8) {
                val kids = pdf.newArray()
                repeat(40) { kids.push(node) }
                node = pdf.indirect(pdf.newDictionary().also { it.put("Kids", kids) })
            }
            pdf.trailer.get("Root").put("PageLabels", node)
        }
        var doc = Fixtures.open(file)
        try {
            val started = System.currentTimeMillis()
            doc.movePages(listOf(0), 3)
            assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 5000)
            doc = Fixtures.reopen(doc)
            assertEquals(3, doc.pageCount)
            assertTrue("PageLabels" in rootKeys(doc))
        } finally {
            doc.close()
        }
    }

    @Test
    fun rearrangingKeepsLabelsLanguageAndStructure() {
        var doc = Fixtures.open(catalogued())
        try {
            doc.deletePages(listOf(3))
            doc = Fixtures.reopen(doc)
            val keys = rootKeys(doc)
            for (k in listOf("PageLabels", "Lang", "ViewerPreferences", "MarkInfo", "StructTreeRoot")) assertTrue("$k lost: $keys", k in keys)
            assertEquals(listOf("0", "S=r", "2", "S=D"), labelNums(doc))
            assertEquals("the element on the deleted page stays", 1, structKids(doc))
            // Moving page 1 to the end: each page keeps its label (ii, 1, i).
            doc.movePages(listOf(0), 3)
            doc = Fixtures.reopen(doc)
            assertEquals(listOf("0", "S=r St=2", "1", "S=D", "2", "S=r"), labelNums(doc))
            doc.duplicatePages(listOf(0))
            doc = Fixtures.reopen(doc)
            assertTrue("Lang" in rootKeys(doc))
            val out = Fixtures.temp("extract")
            doc.writePages(listOf(1, 2), out)
            val ex = Fixtures.open(out)
            try {
                assertEquals(2, ex.pageCount)
                assertTrue("Lang" in rootKeys(ex) && "PageLabels" in rootKeys(ex))
            } finally {
                ex.close()
            }
        } finally {
            doc.close()
        }
    }

    // --- P4–P6: odd page boxes --------------------------------------------------------------------

    private fun stamped(build: (PDFDocument, com.artifex.mupdf.fitz.PDFObject) -> Unit, shape: Shape = Shape(0), crop: Margins? = null): Fixtures.Grey {
        var doc = Fixtures.open(Fixtures.pdf(shape, build = build))
        try {
            if (crop != null) doc.cropPages(listOf(0), crop)
            doc.addText(0, 60f, 120f, 300f, "Added words", TextStyle(22f, black))
            doc.addPageNumbers(listOf(0), "Page {n}", 1, TextStyle(14f, black), VPos.Bottom, HAlign.Right, 28f, false)
            doc.addWatermark(listOf(0), "DRAFT", TextStyle(40f, black), 30f, VPos.Middle, HAlign.Center)
            doc = Fixtures.reopen(doc)
            return Fixtures.render(doc)
        } finally {
            doc.close()
        }
    }

    private fun box(pdf: PDFDocument, vararg v: Float) = pdf.newArray().also { a -> v.forEach { a.push(it) } }

    @Test
    fun oddPageBoxesAreDrawnOnAsTheViewerShowsThem() {
        val plain = stamped({ _, _ -> })
        assertTrue(plain.inked > 300)
        val bad = ArrayList<String>()
        fun check(name: String, got: Fixtures.Grey, want: Fixtures.Grey = plain) {
            val d = Fixtures.difference(want, got)
            if (d > 0.03f) bad.add("$name: ${(d * 100).toInt()}% differs (${got.width}×${got.height})")
        }
        // P4: a CropBox reaching past the MediaBox counts only where they overlap.
        check("crop past media", stamped({ pdf, page -> page.put("CropBox", box(pdf, -50f, -60f, 560f, 570f)) }))
        // P5: /UserUnit 2 on a 250 pt MediaBox shows as 500 units.
        check("user unit", stamped({ pdf, page ->
            page.put("MediaBox", box(pdf, 0f, 0f, 250f, 250f))
            page.put("UserUnit", 2f)
        }))
        // P6: /Rotate 45 is shown as 90.
        check("rotate 45", stamped({ _, page -> page.put("Rotate", 45) }), stamped({ _, _ -> }, Shape(90)))
        // Cropping such pages: the same as cropping a plain page.
        val m = Margins(60f, 40f, 10f, 20f)
        val plainCrop = stamped({ _, _ -> }, crop = m)
        check("cropped, crop past media", stamped({ pdf, page -> page.put("CropBox", box(pdf, -50f, -60f, 560f, 570f)) }, crop = m), plainCrop)
        check("cropped, rotate 45", stamped({ _, page -> page.put("Rotate", 45) }, crop = m), stamped({ _, _ -> }, Shape(90), crop = m))
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun aCropNeverReachesPastTheMediaBox() {
        var doc = Fixtures.open(Fixtures.pdf(build = { pdf, page -> page.put("CropBox", box(pdf, -50f, -60f, 560f, 570f)) }))
        try {
            doc.cropPages(listOf(0), Margins(-30f, 10f, 10f, 10f))
            doc = Fixtures.reopen(doc)
            val crop = cropOf(doc)
            assertTrue("crop ${crop.toList()}", crop[0] >= 0f && crop[1] >= 0f && crop[2] <= 500f && crop[3] <= 500f)
            assertEquals(490f, crop[3], 0.01f)
        } finally {
            doc.close()
        }
    }

    private fun cropOf(doc: PdfDoc): FloatArray {
        val c = doc.pdf.findPage(0).get("CropBox")
        return FloatArray(4) { c.get(it).asFloat() }
    }

    /** N1 (owner's decision, as Acrobat): numbers on pages 3–4 of 4 start at the first chosen page. */
    @Test
    fun pageNumbersOnARangeCountWithinIt() {
        var doc = Fixtures.open(Fixtures.pages(4, ::text))
        try {
            doc.addPageNumbers(listOf(2, 3), "No {n} of {N}", 1, TextStyle(14f, black), VPos.Bottom, HAlign.Center, 28f, false)
            doc = Fixtures.reopen(doc)
            assertTrue(doc.pageText(2), "No 1 of 2" in doc.pageText(2))
            assertTrue(doc.pageText(3), "No 2 of 2" in doc.pageText(3))
            assertFalse("No " in doc.pageText(0))
        } finally {
            doc.close()
        }
    }

    // --- E4, E5: overlays -------------------------------------------------------------------------

    @Test
    fun thirtyEditsKeepTheNestingShallow() {
        var doc = Fixtures.open(Fixtures.pdf(content = text(0)))
        try {
            repeat(30) { k -> doc.addText(0, 40f, 20f + k * 14f, 300f, "Line $k", TextStyle(10f, black)) }
            doc = Fixtures.reopen(doc)
            assertTrue("nesting ${maxDepth(doc, 0)}", maxDepth(doc, 0) <= 3)
            assertTrue("Line 29" in doc.pageText(0))
        } finally {
            doc.close()
        }
    }

    @Test
    fun aPageThatLeavesItsStateOpenDoesNotShrinkTheOverlay() {
        val own = "BT /F1 20 Tf 60 60 Td (Own text) Tj ET"
        fun drawn(content: String): Fixtures.Grey {
            var doc = Fixtures.open(Fixtures.pdf(content = content))
            try {
                doc.addText(0, 60f, 120f, 300f, "Added words", TextStyle(22f, black))
                doc.addPicture(0, Fixtures.arrowPng(), 250f, 200f, 410f, 300f)
                doc = Fixtures.reopen(doc)
                return Fixtures.render(doc)
            } finally {
                doc.close()
            }
        }
        val plain = drawn("q 0.5 0 0 0.5 0 0 cm $own Q")
        val bad = ArrayList<String>()
        for ((name, content) in listOf(
            "unclosed q" to "q 0.5 0 0 0.5 0 0 cm $own",
            "two unclosed q" to "q q 0.5 0 0 0.5 0 0 cm $own",
            "stray Q" to "Q q 0.5 0 0 0.5 0 0 cm $own Q",
            "a scale, then an unclosed q" to "0.5 0 0 0.5 0 0 cm q $own",
            "a stray Q, then a scale" to "Q 0.5 0 0 0.5 0 0 cm $own",
        )) {
            val got = drawn(content)
            val d = Fixtures.difference(plain, got)
            if (d > 0.03f) bad.add("$name: ${(d * 100).toInt()}% differs")
        }
        assertEquals(emptyList<String>(), bad)
    }

    // --- E1, E6: edits ------------------------------------------------------------------------------

    private val twoLines = "BT /F1 22 Tf 60 400 Td (Secret words here) Tj ET BT /F1 22 Tf 60 250 Td (Public line) Tj ET"

    @Test
    fun erasingKeepsPendingMarksAndLinks() {
        var doc = Fixtures.open(Fixtures.pdf(content = twoLines))
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Link", "Rect" to Raw.nums(doc, 60f, 395f, 250f, 420f), "A" to Raw.action(doc, "URI", "URI" to "https://example.com")))
        doc = Fixtures.reopen(doc)
        doc.markForRedaction(listOf(0 to Box(55f, 225f, 200f, 260f)), "Test")
        doc = Fixtures.reopen(doc)
        try {
            doc.eraseArea(0, Box(55f, 75f, 260f, 110f))
            doc = Fixtures.reopen(doc)
            val shown = Fixtures.shownText(doc)
            assertFalse(shown, "Secret" in shown)
            assertTrue("the pending mark was applied: $shown", "Public" in shown)
            assertEquals(1, doc.redactionMarks().size)
            assertEquals(listOf("Link", "Redact"), Raw.subtypes(doc).sorted())
        } finally {
            doc.close()
        }
    }

    /** E6: a content stream two pages share is never rewritten in place by an edit on one of them. */
    @Test
    fun anEditLeavesAStreamSharedWithAnotherPage() {
        val f = Fixtures.pages(2, { "BT /F1 16 Tf 18 TL 60 400 Td (First line) Tj T* (Second line) Tj ET" }) { pdf -> share(pdf) }
        var doc = Fixtures.open(f)
        try {
            val before = streamBytes(doc, 1)
            doc.eraseArea(0, Box(55f, 88f, 200f, 98f))
            doc = Fixtures.reopen(doc)
            assertEquals("page 2's content changed", before, streamBytes(doc, 1))
            assertTrue(doc.pageText(1).contains("First line") && doc.pageText(1).contains("Second line"))
            assertFalse(doc.pageText(0).contains("First line"))
            assertTrue(doc.pageText(0).contains("Second line"))
        } finally {
            doc.close()
        }
    }

    private fun resKeys(doc: PdfDoc, i: Int): String = doc.pdf.findPage(i).getInheritable("Resources").toString(true, true).take(300) +
        " contents=" + doc.pdf.findPage(i).get("Contents").toString(true, true).take(100)

    private fun share(pdf: PDFDocument) {
        pdf.findPage(1).put("Contents", pdf.findPage(0).get("Contents"))
    }

    private fun streamBytes(doc: PdfDoc, i: Int): String {
        val c = doc.pdf.findPage(i).get("Contents")
        val s = if (c.isArray) c.get(0) else c
        return String(s.readStream(), Charsets.ISO_8859_1)
    }

    // --- E1, E2: pictures ---------------------------------------------------------------------------

    /** A page showing the arrow picture turned a quarter and mirrored, as an image object or inline. */
    private fun turnedPicture(inline: Boolean): File = Fixtures.pdf(content = twoLines) { pdf, page ->
        val image = com.artifex.mupdf.fitz.Image(Fixtures.arrowPng())
        val ref = pdf.addImage(image)
        image.destroy()
        val xo = pdf.newDictionary()
        xo.put("Im1", ref)
        val res = page.get("Resources")
        res.put("XObject", xo)
        // Unit square → 50 wide, 80 tall, turned and mirrored; at 300,150 (PDF space).
        val draw = if (inline) "" else "q 0 80 50 0 300 150 cm /Im1 Do Q"
        val contents = page.get("Contents")
        val old = String(contents.readStream(), Charsets.ISO_8859_1)
        val inlineImage = if (!inline) "" else "q 0 80 50 0 300 150 cm BI /W 2 /H 2 /CS /G /BPC 8 ID @@@@ EI Q"
        contents.writeStream("$old $draw $inlineImage")
    }

    private fun region(g: Fixtures.Grey, x0: Int, y0: Int, w: Int, h: Int) = Fixtures.Grey(IntArray(w * h) { k -> g.px[(y0 + k / w) * g.width + x0 + k % w] }, w, h)

    @Test
    fun aMovedPictureKeepsItsTurnAndItsBytes() {
        var doc = Fixtures.reopen(Fixtures.open(turnedPicture(inline = false)))
        try {
            val pic = doc.pictures(0).single()
            val before = Fixtures.render(doc)
            val rawBefore = imageBytes(doc)
            val to = Box(pic.box.x0 - 200f, pic.box.y0 + 20f, pic.box.x1 - 200f, pic.box.y1 + 20f)
            doc.movePicture(0, pic.box, to)
            doc = Fixtures.reopen(doc)
            val after = Fixtures.render(doc)
            val w = pic.box.width.toInt() - 2
            val h = pic.box.height.toInt() - 2
            val d = Fixtures.difference(region(before, pic.box.x0.toInt() + 1, pic.box.y0.toInt() + 1, w, h), region(after, to.x0.toInt() + 1, to.y0.toInt() + 1, w, h))
            assertTrue("the moved picture looks different: ${(d * 100).toInt()}%", d < 0.05f)
            assertEquals("the picture's data was re-encoded", rawBefore, imageBytes(doc))
        } finally {
            doc.close()
        }
    }

    /** The raw (encoded) data of the first image object found on page 1. */
    private fun imageBytes(doc: PdfDoc): Int {
        for (n in 1 until doc.pdf.countObjects()) {
            val o = doc.pdf.newIndirect(n, 0)
            if (o.isStream && o.get("Subtype").let { it.isName && it.asName() == "Image" } && o.get("Width").asInteger() == 80) return o.readRawStream().contentHashCode()
        }
        return 0
    }

    @Test
    fun deletingAnInlinePictureKeepsPendingMarksAndLinks() {
        var doc = Fixtures.open(turnedPicture(inline = true))
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Link", "Rect" to Raw.nums(doc, 60f, 395f, 250f, 420f), "A" to Raw.action(doc, "URI", "URI" to "https://example.com")))
        doc = Fixtures.reopen(doc)
        doc.markForRedaction(listOf(0 to Box(55f, 225f, 200f, 260f)), "Test")
        doc = Fixtures.reopen(doc)
        try {
            val pic = doc.pictures(0).single()
            doc.deletePicture(0, pic.box)
            doc = Fixtures.reopen(doc)
            assertEquals(0, doc.pictures(0).size)
            assertEquals(1, doc.redactionMarks().size)
            assertEquals(listOf("Link", "Redact"), Raw.subtypes(doc).sorted())
            assertTrue("Public" in doc.pageText(0) && "Secret" in doc.pageText(0))
        } finally {
            doc.close()
        }
    }

    // --- The text layer -----------------------------------------------------------------------------

    @Test
    fun recognisingAgainReplacesTheTextLayer() {
        var doc = Fixtures.open(Fixtures.pdf(content = "BT /F1 12 Tf 60 60 Td (Own words) Tj ET"))
        try {
            doc.addTextLayer(mapOf(0 to listOf(RecognisedLine("Hello world", 60f, 100f, 200f, 24f))))
            doc = Fixtures.reopen(doc)
            doc.addTextLayer(mapOf(0 to listOf(RecognisedLine("Hello again", 60f, 100f, 200f, 24f))))
            doc = Fixtures.reopen(doc)
            val text = doc.pageText(0)
            assertEquals(text, 1, Regex("Hello").findAll(text).count())
            assertTrue(text, "again" in text && "Own words" in text)
        } finally {
            doc.close()
        }
    }

    // --- Compress -----------------------------------------------------------------------------------

    /** A page with a thumbnail, /PieceInfo, a soft-masked photo-like picture and a palette picture. */
    private fun heavy(): File = Fixtures.pdf(content = "") { pdf, page -> addHeavy(pdf, page) }

    private fun addHeavy(pdf: PDFDocument, page: com.artifex.mupdf.fitz.PDFObject) {
        val rnd = java.util.Random(3)
        val w = 400
        val h = 300
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        // Noisy colour and a noisy alpha channel: both compress badly with Flate, so JPEG would be "smaller".
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, ((0x80 + rnd.nextInt(128)) shl 24) or (((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or rnd.nextInt(256)))
        val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bmp.recycle()
        val image = com.artifex.mupdf.fitz.Image(png)
        val photo = pdf.addImage(image)
        image.destroy()
        val palette = pdf.newDictionary()
        palette.put("Type", pdf.newName("XObject"))
        palette.put("Subtype", pdf.newName("Image"))
        palette.put("Width", w)
        palette.put("Height", h)
        palette.put("BitsPerComponent", 8)
        val cs = pdf.newArray()
        cs.push(pdf.newName("Indexed")); cs.push(pdf.newName("DeviceRGB")); cs.push(255); cs.push(pdf.newByteString(ByteArray(768) { (rnd.nextInt(256)).toByte() }))
        palette.put("ColorSpace", cs)
        val buf = com.artifex.mupdf.fitz.Buffer()
        buf.writeBytes(ByteArray(w * h) { rnd.nextInt(256).toByte() })
        val indexed = pdf.addStream(buf, palette)
        buf.destroy()
        val xo = pdf.newDictionary()
        xo.put("Photo", photo)
        xo.put("Pal", indexed)
        page.get("Resources").put("XObject", xo)
        page.get("Contents").writeStream("q 400 0 0 300 50 50 cm /Photo Do Q q 200 0 0 150 50 360 cm /Pal Do Q")
        page.put("Thumb", pdf.addStream("thumbnail", pdf.newDictionary()))
        page.put("PieceInfo", pdf.newDictionary().also { it.put("Illustrator", pdf.newDictionary()) })
        pdf.trailer.get("Root").put("PieceInfo", pdf.newDictionary())
    }

    /** Filter and colour space of every image object, and the hash of each soft mask's raw data. */
    private fun images(doc: PdfDoc): List<String> {
        val out = ArrayList<String>()
        for (n in 1 until doc.pdf.countObjects()) {
            val o = doc.pdf.newIndirect(n, 0)
            if (!o.isStream || !o.get("Subtype").let { it.isName && it.asName() == "Image" }) continue
            val cs = o.get("ColorSpace").let { if (it.isArray) it.get(0).asName() else if (it.isName) it.asName() else "" }
            val filter = o.get("Filter").let { if (it.isName) it.asName() else "" }
            val smaskOf = o.get("SMask").isIndirect
            out.add("$cs $filter${if (smaskOf) " masked" else ""}${if (cs == "DeviceGray" && !smaskOf) " #" + o.readRawStream().contentHashCode() else ""}")
        }
        return out.sorted()
    }

    @Test
    fun compressDropsThumbnailsAndLeavesMasksAndPalettes() {
        val src = heavy()
        val doc = Fixtures.open(src)
        try {
            val before = images(doc)
            val target = Fixtures.temp("small")
            PdfCompress.compress(doc, src, target, CompressLevel.High)
            val small = Fixtures.open(target)
            try {
                val pageKeys = small.pdf.findPage(0).iterator().asSequence().map { it.asName() }.toSet()
                assertFalse("Thumb" in pageKeys)
                assertFalse("PieceInfo" in pageKeys)
                assertFalse("PieceInfo" in rootKeys(small))
                val after = images(small)
                // The palette picture is untouched; the soft mask keeps its data; the photo became JPEG.
                assertTrue(after.toString(), after.any { it.startsWith("Indexed ") && !it.contains("DCTDecode") })
                // The soft mask is never turned into a JPEG (its edges would ring).
                assertTrue("the soft mask was re-encoded: $before → $after", after.any { it.startsWith("DeviceGray") && !it.contains("DCTDecode") })
                assertTrue(after.toString(), after.any { it.contains("DCTDecode masked") })
            } finally {
                small.close()
            }
        } finally {
            doc.close()
        }
    }

    // --- Signed files ---------------------------------------------------------------------------------

    /** What Save does for a signed file (DocumentSession): every change appended, the signed bytes kept. */
    @Test
    fun aChangeToASignedFileIsAppended() {
        val src = Fixtures.pdf(content = twoLines) { pdf, _ ->
            val acro = pdf.newDictionary()
            acro.put("Fields", pdf.newArray())
            acro.put("SigFlags", 3)
            pdf.trailer.get("Root").put("AcroForm", pdf.indirect(acro))
        }
        val original = src.readBytes()
        val work = Fixtures.temp("work")
        src.copyTo(work, overwrite = true)
        var doc = Fixtures.open(work)
        try {
            assertTrue(doc.isSigned())
            assertTrue(doc.canSaveIncrementally)
            doc.addNote(0, 100f, 100f, "a comment", yellow, "Test")
            doc.saveIncremental()
            doc.close()
            doc = Fixtures.open(work)
            doc.fillAndAppend()
            val now = work.readBytes()
            assertTrue("the signed bytes changed", now.size > original.size && now.copyOf(original.size).contentEquals(original))
            assertTrue(doc.isSigned())
            assertEquals(2, doc.marks(0).size)
        } finally {
            doc.close()
        }
    }

    private fun PdfDoc.fillAndAppend() {
        addNote(0, 200f, 100f, "another", yellow, "Test")
        saveIncremental()
    }
}
