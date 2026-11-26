package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFDocument
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * File attachments (the catalogue's /Names /EmbeddedFiles) come along with pages inserted from
 * another file and with combined files (desktop test/attachcarry.test.ts).
 *
 * MuPDF wrappers are only held inside small functions that return plain values (decisions §23).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentCarryTest {
    @Before
    fun setUp() = Fixtures.init()

    private fun text(page: Int) = "BT /F1 20 Tf 60 400 Td (Page ${page + 1}) Tj ET"

    /** One page with the given attachments, name → text. */
    private fun withAttachments(files: Map<String, String>): File = Fixtures.pages(1, ::text) { pdf -> attach(pdf, files) }

    private fun attach(pdf: PDFDocument, files: Map<String, String>) {
        val list = pdf.newArray()
        for ((name, body) in files.toSortedMap()) {
            val buf = Buffer()
            val stream = try {
                buf.writeBytes(body.toByteArray(Charsets.UTF_8))
                val dict = pdf.newDictionary()
                dict.put("Type", pdf.newName("EmbeddedFile"))
                pdf.addRawStream(buf, dict)
            } finally {
                buf.destroy()
            }
            val ef = pdf.newDictionary()
            ef.put("F", stream)
            val spec = pdf.newDictionary()
            spec.put("Type", pdf.newName("Filespec"))
            spec.put("F", pdf.newString(name))
            spec.put("UF", pdf.newString(name))
            spec.put("EF", ef)
            list.push(pdf.newString(name))
            list.push(pdf.addObject(spec))
        }
        val tree = pdf.newDictionary()
        tree.put("Names", list)
        val names = pdf.newDictionary()
        names.put("EmbeddedFiles", tree)
        pdf.trailer.get("Root").put("Names", names)
    }

    /** The document's attachments: name → text. */
    private fun attachments(doc: PdfDoc): Map<String, String> {
        val names = doc.pdf.trailer.get("Root").get("Names")
        if (!names.isDictionary) return emptyMap()
        return nameTree(names.get("EmbeddedFiles")).associate { (k, spec) ->
            k.asString() to String(spec.get("EF").get("F").readStream(), Charsets.UTF_8)
        }
    }

    @Test
    fun insertFromFileKeepsBothFilesAttachments() {
        val other = Fixtures.open(withAttachments(mapOf("notes.txt" to "theirs", "سجل.txt" to "عربي")))
        var doc = Fixtures.open(withAttachments(mapOf("notes.txt" to "mine", "data.csv" to "a,b")))
        try {
            doc.insertFrom(other, 1)
            doc = Fixtures.reopen(doc)
            assertEquals(
                mapOf("notes.txt" to "mine", "data.csv" to "a,b", "notes (2).txt" to "theirs", "سجل.txt" to "عربي"),
                attachments(doc),
            )
        } finally {
            doc.close()
            other.close()
        }
    }

    @Test
    fun combineKeepsEveryFilesAttachments() {
        val a = Fixtures.open(withAttachments(mapOf("a.txt" to "A")))
        val b = Fixtures.open(withAttachments(mapOf("b.txt" to "B", "a.txt" to "A2")))
        try {
            val merged = Fixtures.temp("merged")
            PdfFiles.merge(listOf(a, b), merged)
            val m = Fixtures.open(merged)
            try {
                assertEquals(mapOf("a.txt" to "A", "b.txt" to "B", "a (2).txt" to "A2"), attachments(m))
            } finally {
                m.close()
            }
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun noAttachmentTreeIsWrittenWhenNoFileHasOne() {
        val other = Fixtures.open(Fixtures.pages(1, ::text))
        var doc = Fixtures.open(Fixtures.pages(1, ::text))
        try {
            doc.insertFrom(other, 0)
            doc = Fixtures.reopen(doc)
            assertEquals(emptyMap<String, String>(), attachments(doc))
            assertEquals(false, doc.pdf.trailer.get("Root").get("Names").isDictionary)
        } finally {
            doc.close()
            other.close()
        }
    }
}
