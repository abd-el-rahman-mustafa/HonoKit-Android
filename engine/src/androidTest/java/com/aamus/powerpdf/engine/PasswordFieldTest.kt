package com.aamus.powerpdf.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * A text field with the Password flag must never have its value stored in the file
 * (PDF 32000-2 §12.7.5.3, Table 231; desktop decisions §99). Acrobat writes the typed value
 * nowhere: the field is saved holding its default value (/DV) or empty, and the masking
 * characters are drawn from the saved value, so the file does not say how long the password was.
 */
@RunWith(AndroidJUnit4::class)
class PasswordFieldTest {
    private val secret = "Zq7-notInAnyFile-9xW"

    @Before
    fun setUp() = Fixtures.init()

    private fun form(password: Boolean = true): PdfDoc {
        val doc = Fixtures.open(Fixtures.pdf())
        FormFixtures.addFields(
            doc,
            listOf(
                FormFixtures.Spec("pin", "Tx", Box(40f, 40f, 240f, 64f), password = password),
                FormFixtures.Spec("name", "Tx", Box(40f, 100f, 240f, 124f)),
            ),
        )
        return Fixtures.reopen(doc)
    }

    /** Saves to a file of its own and gives back both the bytes and the document reopened from them. */
    private fun saved(doc: PdfDoc): Pair<ByteArray, PdfDoc> {
        val f = File.createTempFile("pwfield", ".pdf")
        doc.save(f, final = false)
        val pw = doc.password
        doc.close()
        return f.readBytes() to Fixtures.open(f, pw)
    }

    /** Whether the text is anywhere in the file, as a plain or a hexadecimal string. */
    private fun holds(bytes: ByteArray, text: String): Boolean {
        val plain = text.toByteArray(Charsets.ISO_8859_1)
        val hex = plain.joinToString("") { "%02x".format(it) }
        val forms = listOf(plain, hex.toByteArray(Charsets.ISO_8859_1), hex.uppercase().toByteArray(Charsets.ISO_8859_1))
        return forms.any { f -> (0..bytes.size - f.size).any { i -> bytes.copyOfRange(i, i + f.size).contentEquals(f) } }
    }

    private fun valueOf(doc: PdfDoc, name: String): String = doc.formFields().first { it.name == name }.value

    @Test
    fun aTypedPasswordIsWrittenToNoFile() {
        var doc = form()
        doc.setField(0, doc.formFields().first { it.name == "pin" }.index, text = secret)
        val (bytes, reopened) = saved(doc)
        doc = reopened
        try {
            assertFalse("the typed password is in the file", holds(bytes, secret))
            assertEquals("", valueOf(doc, "pin"))
        } finally {
            doc.close()
        }
    }

    @Test
    fun anOrdinaryFieldBesideItKeepsItsValue() {
        var doc = form()
        doc.setField(0, doc.formFields().first { it.name == "pin" }.index, text = secret)
        doc.setField(0, doc.formFields().first { it.name == "name" }.index, text = "Maryam")
        val (bytes, reopened) = saved(doc)
        doc = reopened
        try {
            assertFalse("the typed password is in the file", holds(bytes, secret))
            assertEquals("Maryam", valueOf(doc, "name"))
        } finally {
            doc.close()
        }
    }

    @Test
    fun aPasswordFieldIsLeftAtItsDefaultValue() {
        var doc = form()
        val fields = doc.pdf.trailer.get("Root").get("AcroForm").get("Fields")
        for (i in 0 until fields.size()) {
            val f = fields.get(i)
            if (f.get("T").isString && f.get("T").asString() == "pin") f.put("DV", doc.pdf.newString("abcde"))
        }
        doc = Fixtures.reopen(doc)
        doc.setField(0, doc.formFields().first { it.name == "pin" }.index, text = secret)
        val (bytes, reopened) = saved(doc)
        doc = reopened
        try {
            assertFalse("the typed password is in the file", holds(bytes, secret))
            assertEquals("abcde", valueOf(doc, "pin"))
        } finally {
            doc.close()
        }
    }

    @Test
    fun aPasswordValueThatCameWithTheFileIsTakenOut() {
        var doc = form()
        val fields = doc.pdf.trailer.get("Root").get("AcroForm").get("Fields")
        for (i in 0 until fields.size()) {
            val f = fields.get(i)
            if (f.get("T").isString && f.get("T").asString() == "pin") f.put("V", doc.pdf.newString(secret))
        }
        val (bytes, reopened) = saved(doc)
        doc = reopened
        try {
            assertFalse("a password value that came with the file is still in it", holds(bytes, secret))
            assertEquals("", valueOf(doc, "pin"))
        } finally {
            doc.close()
        }
    }

    @Test
    fun aPasswordValueHeldByAnAncestorIsTakenOutToo() {
        var doc = form()
        val pdf = doc.pdf
        val fields = pdf.trailer.get("Root").get("AcroForm").get("Fields")
        var at = -1
        var widget = fields.get(0)
        for (i in 0 until fields.size()) {
            val f = fields.get(i)
            if (f.get("T").isString && f.get("T").asString() == "pin") {
                widget = f
                at = i
            }
        }
        // One password field written as a parent holding the value and a widget under it, as a
        // writer that shares a value between widgets makes it.
        val parent = pdf.newDictionary()
        parent.put("T", pdf.newString("pin"))
        parent.put("FT", pdf.newName("Tx"))
        parent.put("Ff", 1 shl 13)
        parent.put("V", pdf.newString(secret))
        val kids = pdf.newArray()
        kids.push(widget)
        parent.put("Kids", kids)
        val ref = pdf.indirect(parent)
        widget.delete("T")
        widget.delete("FT")
        widget.delete("Ff")
        widget.put("Parent", ref)
        fields.put(at, ref)
        val (bytes, reopened) = saved(doc)
        doc = reopened
        try {
            assertTrue("the field should still be there", doc.formFields().any { it.name == "pin" })
            assertFalse("a password value on an ancestor is still in the file", holds(bytes, secret))
        } finally {
            doc.close()
        }
    }
}
