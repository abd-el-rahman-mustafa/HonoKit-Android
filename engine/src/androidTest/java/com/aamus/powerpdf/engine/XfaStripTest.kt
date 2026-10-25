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
 * Filling in a hybrid XFA form takes the XFA packet out, or Acrobat would show the old values
 * from it instead of the ones typed. Deleting the reference only unlinks the packet: an appended
 * save, or a plain snapshot, keeps every object whether anything points at it or not, so the data
 * it holds — the old field values — stayed in the saved file for anyone to read back.
 * Desktop decisions §100.
 */
@RunWith(AndroidJUnit4::class)
class XfaStripTest {
    private val secret = "Qx4-oldXfaValue-7Km"

    @Before
    fun setUp() = Fixtures.init()

    /** A hybrid XFA form: ordinary AcroForm fields and an XFA packet holding [secret]. */
    private fun hybridForm(): PdfDoc {
        val doc = Fixtures.open(Fixtures.pdf())
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("name", "Tx", Box(40f, 40f, 240f, 64f))))
        val pdf = doc.pdf
        val acro = pdf.trailer.get("Root").get("AcroForm")
        val datasets = "<xfa:datasets xmlns:xfa=\"http://www.xfa.org/schema/xfa-data/1.0/\">" +
            "<xfa:data><form1><name>$secret</name></form1></xfa:data></xfa:datasets>"
        val xfa = pdf.newArray()
        xfa.push(pdf.newString("datasets"))
        xfa.push(pdf.addStream(datasets, pdf.newDictionary()))
        acro.put("XFA", xfa)
        return Fixtures.reopen(doc)
    }

    /** Whether any object of the file still holds the text, streams unpacked. */
    private fun anyObjectHolds(file: File, text: String): Boolean {
        val doc = Fixtures.open(file)
        try {
            val pdf = doc.pdf
            for (n in 1 until pdf.countObjects()) {
                val o = runCatching { pdf.newIndirect(n, 0) }.getOrNull() ?: continue
                val held = runCatching {
                    if (o.isStream) String(o.readStream(), Charsets.ISO_8859_1).contains(text)
                    else o.resolve().toString().contains(text)
                }.getOrDefault(false)
                if (held) return true
            }
            return false
        } finally {
            doc.close()
        }
    }

    private fun saveTo(doc: PdfDoc, final: Boolean): File {
        val f = File.createTempFile("xfastrip", ".pdf")
        doc.save(f, final = final)
        return f
    }

    @Test
    fun theXfaPacketIsInTheFileWhileTheFormStillHasIt() {
        val doc = hybridForm()
        try {
            assertTrue("the packet should be there to start with", anyObjectHolds(saveTo(doc, final = false), secret))
        } finally {
            doc.close()
        }
    }

    @Test
    fun fillingInTheFormLeavesNothingOfThePacketBehind() {
        var doc = hybridForm()
        doc.setField(0, doc.formFields().first { it.name == "name" }.index, text = "Maryam")
        val snapshot = saveTo(doc, final = false)
        doc.close()
        assertFalse("the old XFA values are still in the file", anyObjectHolds(snapshot, secret))
        doc = Fixtures.open(snapshot)
        try {
            assertEquals("Maryam", doc.formFields().first { it.name == "name" }.value)
            assertTrue("the XFA packet should be unlinked", doc.pdf.trailer.get("Root").get("AcroForm").get("XFA").isNull)
        } finally {
            doc.close()
        }
    }

    @Test
    fun theChangesCannotBeAppendedToTheFileAfterTheXfaWent() {
        val doc = hybridForm()
        try {
            doc.setField(0, doc.formFields().first { it.name == "name" }.index, text = "Maryam")
            assertFalse("an appended save would keep the packet", doc.canSaveIncrementally)
        } finally {
            doc.close()
        }
    }
}
