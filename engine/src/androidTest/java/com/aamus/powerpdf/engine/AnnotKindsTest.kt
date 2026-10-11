package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Only comments are listed as comments: media, 3D, printer marks and watermarks are left out, as Acrobat does. */
@RunWith(AndroidJUnit4::class)
class AnnotKindsTest {
    @Before
    fun setUp() = Fixtures.init()

    @Test
    fun mediaPrinterMarksAndWatermarksAreNotComments() {
        var doc = Fixtures.open(Fixtures.pdf())
        val kinds = listOf("Text", "Screen", "Movie", "RichMedia", "3D", "PrinterMark", "TrapNet", "Watermark", "Square")
        kinds.forEachIndexed { i, kind ->
            val x = 50f + i * 30f
            Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/$kind", "Rect" to Raw.nums(doc, x, 50f, x + 20f, 70f), "Contents" to kind))
        }
        doc = Fixtures.reopen(doc)
        try {
            assertEquals(listOf("Text", "Square"), doc.marks(0).map { it.text })
        } finally {
            doc.close()
        }
    }

    /** Acrobat saves a comment's line breaks as CR; they show as line breaks. */
    @Test
    fun commentLineBreaksSavedAsCarriageReturnsShow() {
        var doc = Fixtures.open(Fixtures.pdf())
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Text", "Rect" to Raw.nums(doc, 50f, 50f, 70f, 70f), "Contents" to "first line\r\rsecond paragraph"))
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Square", "Rect" to Raw.nums(doc, 90f, 50f, 120f, 70f), "Contents" to "one\r\ntwo"))
        doc = Fixtures.reopen(doc)
        try {
            assertEquals(listOf("first line\n\nsecond paragraph", "one\ntwo"), doc.marks(0).map { it.text })
        } finally {
            doc.close()
        }
    }
}
