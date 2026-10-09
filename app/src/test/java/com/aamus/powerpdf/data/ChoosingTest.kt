package com.aamus.powerpdf.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The chooser sheet's recent files (decisions §45). */
class ChoosingTest {
    private fun r(id: String, kind: String, at: Long, starred: Boolean = false) =
        RecentFile(id = id, uri = "content://x/$id", name = "$id", size = 1, pages = 1, openedAt = at, cached = null, starred = starred, kind = kind)

    private val list = listOf(
        r("a.pdf", RecentFile.KIND_PDF, 10),
        r("b.jpg", RecentFile.KIND_IMAGE, 20),
        r("c.pdf", RecentFile.KIND_PDF, 30),
        r("d.pdf", RecentFile.KIND_PDF, 5, starred = true),
        r("e.png", RecentFile.KIND_IMAGE, 40),
    )

    @Test fun pdfToolsSeeOnlyPdfsStarredFirstThenNewest() {
        assertEquals(listOf("d.pdf", "c.pdf", "a.pdf"), Choosing.recentsFor(Choosing.Kind.Pdf, list).map { it.id })
    }

    @Test fun imageToolsSeeOnlyPictures() {
        assertEquals(listOf("e.png", "b.jpg"), Choosing.recentsFor(Choosing.Kind.Image, list).map { it.id })
    }

    @Test fun atMostThirty() {
        val many = (1..50).map { r("p$it", RecentFile.KIND_PDF, it.toLong()) }
        assertEquals(Choosing.MAX_RECENT, Choosing.recentsFor(Choosing.Kind.Pdf, many).size)
        assertEquals("p50", Choosing.recentsFor(Choosing.Kind.Pdf, many).first().id)
    }

    @Test fun toggleKeepsPickingOrder() {
        assertEquals(listOf("b", "a"), Choosing.toggle(Choosing.toggle(emptyList(), "b"), "a"))
        assertEquals(listOf("a"), Choosing.toggle(listOf("b", "a"), "b"))
    }
}
