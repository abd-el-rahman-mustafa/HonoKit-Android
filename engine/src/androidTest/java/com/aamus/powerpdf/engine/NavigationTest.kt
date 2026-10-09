package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Links on pages and the outline, read from PDFs made here as other programs write them
 * (decisions §51; desktop src/engine/navigation.ts).
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @Before
    fun setUp() = Fixtures.init()

    private fun PDFDocument.nums(vararg v: Float): PDFObject = newArray().also { a -> v.forEach { a.push(it) } }

    private fun PDFDocument.link(page: Int, rect: PDFObject, action: PDFObject? = null, dest: PDFObject? = null) {
        val d = newDictionary()
        d.put("Type", newName("Annot"))
        d.put("Subtype", newName("Link"))
        d.put("Rect", rect)
        d.put("Border", nums(0f, 0f, 0f))
        if (action != null) d.put("A", action)
        if (dest != null) d.put("Dest", dest)
        val p = findPage(page)
        var annots = p.get("Annots")
        if (!annots.isArray) {
            p.put("Annots", newArray())
            annots = p.get("Annots")
        }
        val ref = indirect(d)
        ref.put("P", p)
        annots.push(ref)
    }

    private fun PDFDocument.uri(address: String): PDFObject = newDictionary().also {
        it.put("S", newName("URI"))
        it.put("URI", newString(address))
    }

    /** [page /XYZ left top 0] in PDF space (y up). */
    private fun PDFDocument.xyz(page: Int, left: Float, top: Float): PDFObject = newArray().also {
        it.push(findPage(page))
        it.push(newName("XYZ"))
        it.push(left)
        it.push(top)
        it.push(0)
    }

    private fun PDFDocument.fit(page: Int): PDFObject = newArray().also {
        it.push(findPage(page))
        it.push(newName("Fit"))
    }

    @Test
    fun pageLinksAreRead() {
        val file = Fixtures.pages(3, { i -> "BT /F1 24 Tf 50 400 Td (Page ${i + 1}) Tj ET" }) { pdf ->
            pdf.link(0, pdf.nums(50f, 400f, 250f, 430f), dest = pdf.xyz(2, 0f, 300f))
            pdf.link(0, pdf.nums(50f, 300f, 250f, 330f), action = pdf.uri("https://www.example.com/a"))
            pdf.link(0, pdf.nums(50f, 200f, 250f, 230f), action = pdf.uri("mailto:someone@example.com"))
            pdf.link(1, pdf.nums(10f, 10f, 60f, 60f), dest = pdf.fit(0))
        }
        val doc = Fixtures.open(file)
        try {
            val links = doc.pageLinks(0)
            assertEquals(3, links.size)
            val toPage = links.first { it.page != null }
            assertEquals(2, toPage.page)
            // Displayed coordinates: top-left origin, so PDF y 400..430 is 70..100 on a 500 pt page.
            assertEquals(70f, toPage.box.y0, 0.5f)
            assertEquals(100f, toPage.box.y1, 0.5f)
            assertEquals(50f, toPage.box.x0, 0.5f)
            val point = toPage.point
            assertNotNull(point)
            assertEquals(200f, point!!.y, 0.5f)
            val uris = links.mapNotNull { it.uri }.toSet()
            assertEquals(setOf("https://www.example.com/a", "mailto:someone@example.com"), uris)
            uris.forEach { assertNotNull(it, LinkRules.safeLink(it)) }
            val back = doc.pageLinks(1).single()
            assertEquals(0, back.page)
            assertNull(back.point)
            assertTrue(doc.pageLinks(2).isEmpty())
            assertTrue(doc.pageLinks(7).isEmpty())
        } finally {
            doc.close()
        }
    }

    @Test
    fun linkBoxesFollowATurnedPage() {
        val file = Fixtures.pdf(Fixtures.Shape(90)) { pdf, _ ->
            pdf.link(0, pdf.nums(0f, 0f, 100f, 50f), action = pdf.uri("https://example.com"))
        }
        val doc = Fixtures.open(file)
        try {
            val l = doc.pageLinks(0).single()
            // Turned a quarter, the 100 × 50 link shows 50 wide and 100 tall, inside the page.
            assertEquals(50f, l.box.width, 0.5f)
            assertEquals(100f, l.box.height, 0.5f)
            assertTrue(l.box.x0 >= -0.5f && l.box.y0 >= -0.5f && l.box.x1 <= Fixtures.SIZE + 0.5f && l.box.y1 <= Fixtures.SIZE + 0.5f)
        } finally {
            doc.close()
        }
    }

    @Test
    fun outlineIsReadAsATree() {
        val file = Fixtures.pages(3, { "" }) { pdf ->
            val root = pdf.indirect(pdf.newDictionary())
            root.put("Type", pdf.newName("Outlines"))
            fun item(title: String, parent: PDFObject): PDFObject = pdf.indirect(pdf.newDictionary()).also {
                it.put("Title", pdf.newString(title))
                it.put("Parent", parent)
            }
            val ch1 = item("Chapter  1\n", root)
            val ch2 = item("الفصل الثاني", root)
            val web = item("Website", root)
            val s11 = item("Section 1.1", ch1)
            val s12 = item("Section 1.2", ch1)
            ch1.put("Dest", pdf.fit(0))
            s11.put("Dest", pdf.xyz(1, 0f, 450f))
            s12.put("Dest", pdf.fit(2))
            ch2.put("Dest", pdf.fit(2))
            web.put("A", pdf.uri("https://example.org/"))
            root.put("First", ch1); root.put("Last", web); root.put("Count", 3)
            ch1.put("Next", ch2); ch2.put("Prev", ch1); ch2.put("Next", web); web.put("Prev", ch2)
            ch1.put("First", s11); ch1.put("Last", s12); ch1.put("Count", 2) // open
            s11.put("Next", s12); s12.put("Prev", s11)
            pdf.trailer.get("Root").put("Outlines", root)
        }
        val doc = Fixtures.open(file)
        try {
            val tree = doc.outline()
            assertEquals(listOf("Chapter 1", "الفصل الثاني", "Website"), tree.map { it.title })
            assertEquals(0, tree[0].page)
            assertTrue(tree[0].open)
            assertEquals(listOf("Section 1.1", "Section 1.2"), tree[0].children.map { it.title })
            assertEquals(1, tree[0].children[0].page)
            assertEquals(50f, tree[0].children[0].point!!.y, 0.5f)
            assertEquals(2, tree[0].children[1].page)
            assertEquals(2, tree[1].page)
            assertNull(tree[2].page)
            assertEquals("https://example.org/", tree[2].uri)
        } finally {
            doc.close()
        }
    }

    @Test
    fun noOutlineIsEmpty() {
        val doc = Fixtures.open(Fixtures.pages(2, { "" }))
        try {
            assertTrue(doc.outline().isEmpty())
        } finally {
            doc.close()
        }
    }
}
