package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Two comments whose /IRT entries point at each other, and longer loops, have no comment at the
 * head of the chain: the panel shows each as a comment of its own. Removing one must then leave
 * the others where they are — the deleter used to follow /IRT whatever it led to, so deleting one
 * card took every comment of the loop with it. Desktop decisions §101.
 *
 * A reply to a redaction mark goes with the mark when the mark is cleared or un-marked, instead
 * of being left answering a mark that is no longer there (desktop §102).
 */
@RunWith(AndroidJUnit4::class)
class CommentLoopTest {
    @Before
    fun setUp() = Fixtures.init()

    /** One page of [count] notes, each a reply to the next, the last one a reply to the first. */
    private fun looped(count: Int): PdfDoc {
        val doc = Fixtures.open(Fixtures.pdf())
        doc.withPdfPage(0) { page ->
            val notes = (0 until count).map { i ->
                val a = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
                a.rect = com.artifex.mupdf.fitz.Rect(60f + i * 40, 60f, 80f + i * 40, 80f)
                a.contents = ('A' + i).toString()
                a.update()
                a
            }
            for (i in 0 until count) notes[i].getObject().put("IRT", notes[(i + 1) % count].getObject())
        }
        return Fixtures.reopen(doc)
    }

    private fun textsOf(doc: PdfDoc): List<String> = doc.marks(0).map { it.text }.sorted()

    @Test
    fun twoCommentsPointingAtEachOtherAreCommentsOfTheirOwn() {
        var doc = looped(2)
        try {
            assertEquals(listOf("A", "B"), textsOf(doc))
            val a = doc.marks(0).first { it.text == "A" }
            doc.deleteMark(0, a.index, a.box)
            doc = Fixtures.reopen(doc)
            assertEquals(listOf("B"), textsOf(doc))
        } finally {
            doc.close()
        }
    }

    @Test
    fun theRestOfALongerLoopStaysWhereItIs() {
        var doc = looped(3)
        try {
            assertEquals(listOf("A", "B", "C"), textsOf(doc))
            val a = doc.marks(0).first { it.text == "A" }
            doc.deleteMark(0, a.index, a.box)
            doc = Fixtures.reopen(doc)
            assertEquals(listOf("B", "C"), textsOf(doc))
        } finally {
            doc.close()
        }
    }

    @Test
    fun aRealReplyStillGoesWithTheCommentItAnswers() {
        var doc = Fixtures.open(Fixtures.pdf())
        doc.withPdfPage(0) { page ->
            fun note(text: String, at: Float): PDFAnnotation {
                val a = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
                a.rect = com.artifex.mupdf.fitz.Rect(at, 60f, at + 20f, 80f)
                a.contents = text
                a.update()
                return a
            }
            val parent = note("Parent", 60f)
            val reply = note("Reply", 100f)
            reply.getObject().put("IRT", parent.getObject())
            val deep = note("Reply to reply", 140f)
            deep.getObject().put("IRT", reply.getObject())
            note("Alone", 200f)
        }
        doc = Fixtures.reopen(doc)
        try {
            val parent = doc.marks(0).first { it.text == "Parent" }
            doc.deleteMark(0, parent.index, parent.box)
            doc = Fixtures.reopen(doc)
            assertEquals(listOf("Alone"), textsOf(doc))
        } finally {
            doc.close()
        }
    }

    /** A page with one mark and a reply to that mark, as another program may leave it. */
    private fun markedWithAReply(): PdfDoc {
        val doc = Fixtures.open(Fixtures.pdf())
        doc.markForRedaction(listOf(0 to Box(80f, 80f, 320f, 320f)), "Tester")
        doc.withPdfPage(0) { page ->
            val mark = with(doc) { page.annots() }.first { it.type == PDFAnnotation.TYPE_REDACT }
            val reply = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
            reply.rect = com.artifex.mupdf.fitz.Rect(360f, 360f, 380f, 380f)
            reply.contents = "A reply to the mark"
            reply.update()
            reply.getObject().put("IRT", mark.getObject())
        }
        return Fixtures.reopen(doc)
    }

    @Test
    fun clearingTheMarksTakesAReplyToAMarkWithIt() {
        var doc = markedWithAReply()
        try {
            doc.clearRedactionMarks()
            doc = Fixtures.reopen(doc)
            assertEquals(emptyList<String>(), textsOf(doc))
            assertFalse("the mark is still there", doc.redactionMarks().isNotEmpty())
        } finally {
            doc.close()
        }
    }

    @Test
    fun unMarkingTakesAReplyToThatMarkWithIt() {
        var doc = markedWithAReply()
        try {
            doc.removeRedactionMark(0, doc.redactionMarks().first().second)
            doc = Fixtures.reopen(doc)
            assertEquals(emptyList<String>(), textsOf(doc))
        } finally {
            doc.close()
        }
    }
}
