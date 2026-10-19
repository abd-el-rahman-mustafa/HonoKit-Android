package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Redaction leaves no removed text, and no removed comment, in the structure tree (tagged PDF). */
@RunWith(AndroidJUnit4::class)
class RedactStructTreeTest {
    @Before
    fun setUp() = Fixtures.init()

    // The first line sits at y 300 of a 500-point page: 200 from the top.
    private val first = Box(40f, 184f, 220f, 206f)

    private val content = "/P << /MCID 0 >> BDC BT /F1 12 Tf 50 300 Td (Account 98765) Tj ET EMC\n" +
        "/P << /MCID 1 >> BDC BT /F1 12 Tf 50 150 Td (Public line) Tj ET EMC"

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

    private fun elem(pdf: PDFDocument, s: String, parent: PDFObject, page: PDFObject?, k: PDFObject?, vararg text: Pair<String, String>): PDFObject {
        val d = pdf.newDictionary()
        d.put("Type", pdf.newName("StructElem"))
        d.put("S", pdf.newName(s))
        d.put("P", parent)
        if (page != null) d.put("Pg", page)
        if (k != null) d.put("K", k)
        for ((key, value) in text) d.put(key, pdf.newString(value))
        return pdf.indirect(d)
    }

    /** Catalog entries of a tagged file; returns the tree's root. */
    private fun tagged(pdf: PDFDocument, page: PDFObject): PDFObject {
        val root = pdf.indirect(pdf.newDictionary().also { it.put("Type", pdf.newName("StructTreeRoot")) })
        val catalog = pdf.trailer.get("Root")
        catalog.put("StructTreeRoot", root)
        catalog.put("MarkInfo", pdf.newDictionary().also { it.put("Marked", true) })
        page.put("StructParents", 0)
        return root
    }

    private fun array(pdf: PDFDocument, vararg items: PDFObject): PDFObject = pdf.newArray().also { a -> items.forEach { a.append(it) } }

    private fun parentTree(pdf: PDFDocument, vararg pairs: Pair<Int, PDFObject>): PDFObject {
        val nums = pdf.newArray()
        for ((k, v) in pairs) {
            nums.append(pdf.newInteger(k))
            nums.append(v)
        }
        return pdf.newDictionary().also { it.put("Nums", nums) }
    }

    @Test
    fun theTagOfRemovedTextLosesItsActualAndAlternateText() {
        val src = Fixtures.pdf(content = content) { pdf, page ->
            val root = tagged(pdf, page)
            val doc = elem(pdf, "Document", root, null, null)
            val gone = elem(pdf, "P", doc, page, pdf.newInteger(0), "ActualText" to "Account HIDDEN-98765", "Alt" to "Alt 98765", "E" to "Exp 98765")
            val kept = elem(pdf, "P", doc, page, pdf.newInteger(1), "ActualText" to "Kept description")
            doc.put("K", array(pdf, gone, kept))
            root.put("K", doc)
            root.put("ParentTree", parentTree(pdf, 0 to array(pdf, gone, kept)))
        }
        val all = redacted(src)
        assertFalse(all.contains("98765"))
        assertTrue(all.contains("Kept description"))
    }

    @Test
    fun anElementThatInheritsItsPageAndTheElementsAroundItLoseTheirs() {
        val src = Fixtures.pdf(content = content) { pdf, page ->
            val root = tagged(pdf, page)
            val sect = elem(pdf, "Sect", root, page, null, "ActualText" to "Section 98765")
            val mcr = pdf.newDictionary().also {
                it.put("Type", pdf.newName("MCR"))
                it.put("MCID", 0)
            }
            val span = elem(pdf, "Span", sect, null, mcr, "Alt" to "Inherited 98765")
            val kept = elem(pdf, "P", sect, null, pdf.newInteger(1), "ActualText" to "Kept description")
            val figure = elem(pdf, "Figure", root, page, pdf.newArray(), "Alt" to "Unrelated figure")
            sect.put("K", array(pdf, span, kept))
            root.put("K", array(pdf, sect, figure))
            root.put("ParentTree", parentTree(pdf, 0 to array(pdf, span, kept)))
        }
        val all = redacted(src)
        assertFalse(all.contains("98765"))
        assertTrue(all.contains("Kept description"))
        assertTrue(all.contains("Unrelated figure"))
    }

    @Test
    fun aCommentRemovedWithTheTextIsNotKeptByItsTag() {
        val src = Fixtures.pdf(content = content) { pdf, page ->
            val root = tagged(pdf, page)
            val note = pdf.newDictionary()
            note.put("Type", pdf.newName("Annot"))
            note.put("Subtype", pdf.newName("Text"))
            note.put("Rect", array(pdf, pdf.newInteger(60), pdf.newInteger(290), pdf.newInteger(80), pdf.newInteger(310)))
            note.put("Contents", pdf.newString("Secret note 31337"))
            note.put("P", page)
            note.put("StructParent", 1)
            val annot = pdf.indirect(note)
            page.put("Annots", array(pdf, annot))
            val objr = pdf.newDictionary().also {
                it.put("Type", pdf.newName("OBJR"))
                it.put("Obj", annot)
            }
            val tag = elem(pdf, "Annot", root, page, objr)
            val a = elem(pdf, "P", root, page, pdf.newInteger(0))
            val b = elem(pdf, "P", root, page, pdf.newInteger(1))
            root.put("K", array(pdf, tag, a, b))
            root.put("ParentTree", parentTree(pdf, 0 to array(pdf, a, b), 1 to tag))
        }
        assertFalse(redacted(src).contains("31337"))
    }
}
