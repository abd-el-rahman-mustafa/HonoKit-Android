package com.aamus.powerpdf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Files tab's list: PDFs and pictures HonoKit made, with a filter (decisions §46). */
class SavedListTest {
    private data class F(val name: String, val mime: String?, val modified: Long)

    @Test fun kindsFromTypeAndName() {
        assertEquals(SavedList.Kind.Pdf, SavedList.kindOf("a.pdf", null))
        assertEquals(SavedList.Kind.Pdf, SavedList.kindOf("a", "application/pdf"))
        assertEquals(SavedList.Kind.Picture, SavedList.kindOf("a (compressed).webp", null))
        assertEquals(SavedList.Kind.Picture, SavedList.kindOf("page-1", "image/png"))
        assertEquals(SavedList.Kind.Picture, SavedList.kindOf("x.HEIC", null))
        // Text and Markdown exports are not listed.
        assertNull(SavedList.kindOf("notes.txt", "text/plain"))
        assertNull(SavedList.kindOf("notes.md", "text/markdown"))
    }

    @Test fun filterAndNewestFirst() {
        val files = listOf(F("old.pdf", null, 1), F("pic.jpg", "image/jpeg", 5), F("new.pdf", null, 9), F("cut.png", null, 3))
        val kind = { f: F -> SavedList.kindOf(f.name, f.mime)!! }
        assertEquals(listOf("new.pdf", "pic.jpg", "cut.png", "old.pdf"), SavedList.arrange(files, SavedList.Show.All, kind) { it.modified }.map { it.name })
        assertEquals(listOf("new.pdf", "old.pdf"), SavedList.arrange(files, SavedList.Show.Pdfs, kind) { it.modified }.map { it.name })
        assertEquals(listOf("pic.jpg", "cut.png"), SavedList.arrange(files, SavedList.Show.Pictures, kind) { it.modified }.map { it.name })
    }
}
