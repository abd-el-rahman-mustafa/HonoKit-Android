package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.PDFAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The desktop's review fixes, on Android (desktop decisions §80; §49 here):
 * redaction (R1, R2), forms (F1, F2, XFA), comments (A3, A4), Remove hidden information, signed
 * files refused by Protect/Unlock, and the safe redaction other edits use (E1).
 */
@RunWith(AndroidJUnit4::class)
class AnnotFixTest {
    @Before
    fun setUp() = Fixtures.init()

    private val black = Rgb(0f, 0f, 0f)

    /** "Secret words here" in Helvetica at 60,100 (top-left of the line about 60,80) on a plain page. */
    private val secretLine = "BT /F1 22 Tf 60 400 Td (Secret words here) Tj ET BT /F1 22 Tf 60 250 Td (Public line) Tj ET"
    private val markBox = Box(55f, 75f, 260f, 110f)

    /** The session's commit after a change: appended when it can be, else a full copy (DocumentSession.commit). */
    private fun commit(doc: PdfDoc): Pair<PdfDoc, File> {
        val file = doc.file!!
        return if (doc.canSaveIncrementally) {
            doc.saveIncremental()
            val pw = doc.password
            doc.close()
            Fixtures.open(file, pw) to file
        } else {
            val next = Fixtures.temp("commit")
            doc.save(next, final = false)
            val pw = doc.password
            doc.close()
            Fixtures.open(next, pw) to next
        }
    }

    /** A page with the secret line, a note on it with a pop-up and a reply (and a reply to that), a highlight, a stamp and a field there. */
    private fun covered(): PdfDoc {
        var doc = Fixtures.open(Fixtures.pdf(content = secretLine))
        FormFixtures.addFields(
            doc,
            listOf(
                FormFixtures.Spec("Account", "Tx", Box(200f, 85f, 255f, 105f), more = listOf(Box(300f, 300f, 450f, 320f))),
                FormFixtures.Spec("Keep", "Tx", Box(300f, 400f, 450f, 420f)),
            ),
        )
        doc = Fixtures.reopen(doc)
        for (f in doc.formFields(0)) {
            if (f.name == "Account" && f.box.y0 < 200) doc.setField(0, f.index, text = "SECRETVALUE")
            if (f.name == "Keep") doc.setField(0, f.index, text = "Visible")
        }
        doc = Fixtures.reopen(doc)
        doc.addNote(0, 100f, 85f, "hidden note words", Rgb(1f, 0.8f, 0f), "Test")
        doc.addTextMarkup(0, MarkKind.Highlight, listOf(Box(60f, 80f, 150f, 104f)), Rgb(1f, 1f, 0f), "Test")
        doc.addStamp(0, 150f, 92f, "SECRETSTAMP", null, Rgb(1f, 0f, 0f), "Test")
        doc = Fixtures.reopen(doc)
        addThread(doc, "reply words", "second reply words")
        return Fixtures.reopen(doc)
    }

    /**
     * Gives the page's first note a pop-up, a reply far from it and a reply to that reply. Its own
     * function, so the wrappers it makes are unreachable before the document is closed (§23).
     */
    private fun addThread(doc: PdfDoc, reply: String, second: String) {
        val note = Raw.annots(doc).indexOfFirst { it.get("Subtype").asName() == "Text" }
        val noteRef = doc.pdf.findPage(0).get("Annots").get(note)
        val popup = Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Popup", "Rect" to Raw.nums(doc, 300f, 100f, 450f, 160f), "Parent" to noteRef))
        noteRef.put("Popup", popup)
        val first = Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Text", "Rect" to Raw.nums(doc, 400f, 20f, 420f, 40f), "Contents" to reply, "IRT" to noteRef))
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Text", "Rect" to Raw.nums(doc, 430f, 20f, 450f, 40f), "Contents" to second, "IRT" to first))
    }

    /** R2 and R1: what lies on the marks goes with them, and no object of the file keeps it. */
    @Test
    fun redactionRemovesTextCommentsRepliesStampsAndFieldsFromTheFile() {
        var doc = covered()
        val before = Fixtures.allStreams(doc.file!!)
        for (word in listOf("Secret", "SECRETSTAMP", "SECRETVALUE", "hidden note words", "reply words")) assertTrue("fixture lacks $word", word in before)
        doc.markForRedaction(listOf(0 to markBox), "Test")
        // As the session does: the mark is appended to the working copy.
        var (d1, _) = commit(doc)
        doc = d1
        val r = doc.applyRedactions(black, null, sanitize = false)
        assertEquals(0, r.leftover)
        assertFalse(r.signed)
        assertFalse("a redaction must not be appended", doc.canSaveIncrementally)
        val (d2, file) = commit(doc)
        doc = d2
        try {
            val after = Fixtures.allStreams(file)
            for (word in listOf("Secret", "SECRETSTAMP", "SECRETVALUE", "hidden note words", "reply words", "second reply")) assertFalse("$word is still in the file", word in after)
            assertTrue("the public line went too", "Public line" in Fixtures.shownText(doc))
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            assertEquals("the file was appended to", 1, Regex("%%EOF").findAll(text).count())
            // The field outside the mark stays; the one with a widget on the mark goes whole.
            assertEquals(listOf("Keep"), doc.formFields(0).map { it.name })
            assertEquals(emptyList<String>(), Raw.subtypes(doc).filter { it == "Text" || it == "Popup" || it == "Stamp" || it == "Highlight" })
            assertEquals(0, doc.redactionMarks().size)
        } finally {
            doc.close()
        }
    }

    /** The label's own letters are not counted as left over: only our overlay is set aside in the check. */
    @Test
    fun aLabelWithTheRedactedWordsStillChecksClean() {
        val doc = Fixtures.open(Fixtures.pdf(content = secretLine))
        try {
            doc.markForRedaction(listOf(0 to markBox), "Test")
            assertEquals(0, doc.applyRedactions(black, "Secret words here", sanitize = false).leftover)
        } finally {
            doc.close()
        }
    }

    @Test
    fun aSignedFileSaysSoAndRefusesProtectAndUnlock() {
        val src = Fixtures.pdf(content = secretLine) { pdf, _ ->
            val acro = pdf.newDictionary()
            acro.put("Fields", pdf.newArray())
            acro.put("SigFlags", 3)
            pdf.trailer.get("Root").put("AcroForm", pdf.indirect(acro))
        }
        val doc = Fixtures.open(src)
        try {
            assertTrue(doc.isSigned())
            for (attempt in listOf<() -> Unit>({ PdfSecurity.protect(doc, Fixtures.temp("p"), ProtectOptions("pw")) }, { PdfSecurity.unlock(doc, Fixtures.temp("u")) })) {
                try {
                    attempt()
                    fail("a signed file was given a new password or freed of its own")
                } catch (e: PdfException) {
                    assertEquals(PdfException.Code.Signed, e.code)
                }
            }
            doc.markForRedaction(listOf(0 to markBox), "Test")
            assertTrue(doc.applyRedactions(black, null, sanitize = false).signed)
        } finally {
            doc.close()
        }
        val plain = Fixtures.open(Fixtures.pdf())
        try {
            assertFalse(plain.isSigned())
            PdfSecurity.protect(plain, Fixtures.temp("p"), ProtectOptions("pw"))
        } finally {
            plain.close()
        }
    }

    /** F1: a password field shows bullets, never the characters. */
    @Test
    fun aPasswordFieldIsDrawnAsBullets() {
        var doc = Fixtures.open(Fixtures.pdf())
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("Pin", "Tx", Box(40f, 40f, 240f, 64f), password = true)))
        doc = Fixtures.reopen(doc)
        try {
            doc.setField(0, 0, text = "abc123")
            doc = Fixtures.reopen(doc)
            val shown = Fixtures.shownText(doc)
            assertFalse("the password shows: $shown", "abc" in shown)
            assertTrue("no bullets: $shown", "••••••" in shown)
            assertEquals("abc123", doc.formFields(0).single().value)
        } finally {
            doc.close()
        }
    }

    /** F2: a field shown twice gets the value drawn in both places. */
    @Test
    fun everyWidgetOfAFieldIsDrawn() {
        var doc = Fixtures.open(Fixtures.pdf())
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("Name", "Tx", Box(40f, 40f, 240f, 64f), more = listOf(Box(40f, 300f, 240f, 324f)), border = false)))
        doc = Fixtures.reopen(doc)
        try {
            doc.setField(0, 0, text = "أحمد Ahmed")
            doc = Fixtures.reopen(doc)
            val g = Fixtures.render(doc)
            assertTrue("first widget empty", g.inkIn(40, 40, 240, 64) > 30)
            assertTrue("second widget empty", g.inkIn(40, 300, 240, 324) > 30)
        } finally {
            doc.close()
        }
    }

    /** A field without an appearance of its own (MuPDF draws one in memory) is filled, drawn and keeps its value. */
    @Test
    fun aFieldWithoutAnAppearanceKeepsItsValue() {
        var doc = Fixtures.open(Fixtures.pdf())
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("Notes", "Tx", Box(40f, 140f, 300f, 200f), multiline = true, appearance = false)))
        doc = Fixtures.reopen(doc)
        try {
            Fixtures.render(doc)
            doc.setField(0, 0, text = "ملاحظات")
            doc = Fixtures.reopen(doc)
            assertEquals("ملاحظات", doc.formFields(0).single().value)
            assertTrue(Fixtures.render(doc).inkIn(40, 140, 300, 200) > 30)
        } finally {
            doc.close()
        }
    }

    /** A field, and an XFA form beside it; [signed] sets SigFlags, [dynamic] NeedsRendering. */
    private fun addXfa(doc: PdfDoc, signed: Boolean, dynamic: Boolean) {
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("Name", "Tx", Box(40f, 40f, 240f, 64f))))
        val acro = doc.pdf.trailer.get("Root").get("AcroForm")
        val xfa = doc.pdf.newArray()
        xfa.push(doc.pdf.newString("template"))
        xfa.push(Raw.stream(doc, "<template/>"))
        acro.put("XFA", xfa)
        if (signed) acro.put("SigFlags", 3)
        if (dynamic) doc.pdf.trailer.get("Root").put("NeedsRendering", true)
    }

    /** A checkbox with no look of its own gets one that is saved (MuPDF's drawing of it stayed in memory). */
    @Test
    fun aCheckboxWithoutALookGetsOneThatIsSaved() {
        var doc = Fixtures.open(Fixtures.pdf())
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("Agree", "Btn", Box(260f, 40f, 280f, 60f), appearance = false, border = false)))
        doc = Fixtures.reopen(doc)
        try {
            doc.setField(0, 0, checked = true)
            doc = Fixtures.reopen(doc)
            assertTrue(doc.formFields(0).single().checked)
            assertTrue("no saved look for the check", savedOnLook(doc))
            assertTrue("the check does not show", Fixtures.render(doc).inkIn(260, 40, 280, 60) > 10)
        } finally {
            doc.close()
        }
    }

    private fun savedOnLook(doc: PdfDoc): Boolean = Raw.annots(doc).single().get("AP").get("N").get("Yes").isStream

    /** Hybrid XFA goes when the form is filled (not on a signed file); dynamic XFA is refused. */
    @Test
    fun xfaFormsAreHandledAsAcrobatDoes() {
        fun withXfa(signed: Boolean, dynamic: Boolean): PdfDoc {
            val doc = Fixtures.open(Fixtures.pdf())
            addXfa(doc, signed, dynamic)
            return Fixtures.reopen(doc)
        }
        var doc = withXfa(signed = false, dynamic = false)
        try {
            assertEquals(XfaKind.Hybrid, doc.xfaKind())
            doc.setField(0, 0, text = "Ahmed")
            doc = Fixtures.reopen(doc)
            assertEquals(null, doc.xfaKind())
        } finally {
            doc.close()
        }
        doc = withXfa(signed = true, dynamic = false)
        try {
            doc.setField(0, 0, text = "Ahmed")
            doc = Fixtures.reopen(doc)
            assertEquals("a signed form keeps its XFA", XfaKind.Hybrid, doc.xfaKind())
        } finally {
            doc.close()
        }
        doc = withXfa(signed = false, dynamic = true)
        try {
            assertEquals(XfaKind.Dynamic, doc.xfaKind())
            try {
                doc.setField(0, 0, text = "Ahmed")
                fail("a dynamic XFA form was filled")
            } catch (e: PdfException) {
                assertEquals(PdfException.Code.DynamicXfa, e.code)
            }
        } finally {
            doc.close()
        }
    }

    /** A4: deleting a comment deletes its pop-up and its replies (and theirs). */
    @Test
    fun deletingACommentDeletesItsRepliesAndPopup() {
        var doc = Fixtures.open(Fixtures.pdf())
        doc.addNote(0, 100f, 100f, "note", Rgb(1f, 0.8f, 0f), "Test")
        doc.addShape(0, MarkKind.Rect, 300f, 300f, 400f, 350f, black, 2f, "Test")
        doc = Fixtures.reopen(doc)
        addThread(doc, "reply", "reply to reply")
        doc = Fixtures.reopen(doc)
        try {
            assertTrue(Raw.subtypes(doc).count { it == "Text" } == 3 && "Popup" in Raw.subtypes(doc))
            val m = doc.marks(0).first { it.kind == MarkKind.Note && it.text == "note" }
            doc.deleteMark(0, m.index, m.box)
            doc = Fixtures.reopen(doc)
            assertEquals(listOf("Square"), Raw.subtypes(doc))
        } finally {
            doc.close()
        }
    }

    /** A text box made in another program, changed here: drawn by us, so Arabic shows (A3). */
    private fun foreignFreeText(callout: Boolean): PdfDoc {
        val doc = Fixtures.open(Fixtures.pdf())
        addFreeText(doc, callout)
        return Fixtures.reopen(doc)
    }

    private fun addFreeText(doc: PdfDoc, callout: Boolean) {
        val ap = Raw.stream(doc, "q 0 0 1 RG 1 w 0.5 0.5 199 39 re S Q", "Type" to "/XObject", "Subtype" to "/Form", "BBox" to Raw.nums(doc, 0f, 0f, 200f, 40f))
        val d = Raw.dict(
            doc,
            "Type" to "/Annot", "Subtype" to "/FreeText", "Contents" to "Hello", "DA" to "/Helv 14 Tf 0 0 1 rg",
            "BS" to Raw.dict(doc, "W" to 1), "AP" to Raw.dict(doc, "N" to ap),
        )
        if (callout) {
            // Box at 200..400 x 360..400 (PDF), tip at 60,250.
            d.put("Rect", Raw.nums(doc, 50f, 240f, 400f, 400f))
            d.put("RD", Raw.nums(doc, 150f, 0f, 0f, 120f))
            d.put("IT", doc.pdf.newName("FreeTextCallout"))
            d.put("CL", Raw.nums(doc, 60f, 250f, 120f, 380f, 200f, 380f))
            val le = doc.pdf.newArray(); le.push(doc.pdf.newName("OpenArrow"))
            d.put("LE", le)
        } else {
            d.put("Rect", Raw.nums(doc, 200f, 360f, 400f, 400f))
        }
        Raw.addAnnot(doc, d)
    }

    @Test
    fun aForeignTextBoxIsRedrawnByUsWithArabic() {
        var doc = foreignFreeText(callout = false)
        try {
            val m = doc.marks(0).single()
            assertEquals(MarkKind.TextBox, m.kind)
            doc.setMarkText(0, m.index, m.box, "مرحبا بالعالم")
            doc = Fixtures.reopen(doc)
            assertTrue("no Arabic drawn: ${Fixtures.shownText(doc)}", "مرحبا" in Fixtures.shownText(doc))
            val g = Fixtures.render(doc)
            assertTrue(g.inkIn(200, 100, 400, 140) > 50)
            // Moving keeps our drawing (no MuPDF redraw).
            val m2 = doc.marks(0).single()
            doc.moveMark(0, m2.index, m2.box, -100f, 200f)
            doc = Fixtures.reopen(doc)
            assertTrue("moved box lost its Arabic", "مرحبا" in Fixtures.shownText(doc))
            assertTrue(Fixtures.render(doc).inkIn(100, 300, 300, 340) > 50)
        } finally {
            doc.close()
        }
    }

    @Test
    fun aForeignCalloutKeepsItsLineAndMovesIt() {
        var doc = foreignFreeText(callout = true)
        try {
            val m = doc.marks(0).single()
            doc.setMarkText(0, m.index, m.box, "انظر هنا")
            doc = Fixtures.reopen(doc)
            assertTrue("no Arabic drawn", "انظر" in Fixtures.shownText(doc))
            // The tip (60,250 in PDF = 60,250 shown) has the arrow.
            assertTrue("no callout line at the tip", Fixtures.render(doc).inkIn(55, 235, 80, 260) > 5)
            val m2 = doc.marks(0).single()
            doc.moveMark(0, m2.index, m2.box, 30f, 20f)
            doc = Fixtures.reopen(doc)
            assertEquals(listOf(90f, 230f), tipOf(doc))
            assertTrue("no callout line at the moved tip", Fixtures.render(doc).inkIn(85, 255, 110, 280) > 5)
        } finally {
            doc.close()
        }
    }

    /** A callout's tip (/CL, PDF space), read in a function of its own so no wrapper outlives the document. */
    private fun tipOf(doc: PdfDoc): List<Float> = Raw.annots(doc).single().get("CL").let { listOf(it.get(0).asFloat(), it.get(1).asFloat()) }

    /** Remove hidden information: attachments, /AF, /PieceInfo, /Thumb, scripts and risky actions go; navigation stays. */
    @Test
    fun hiddenInformationGoesButNavigationStays() {
        var doc = Fixtures.open(Fixtures.pdf(content = secretLine))
        addHiddenThings(doc)
        doc = Fixtures.reopen(doc)
        try {
            doc.markForRedaction(listOf(0 to markBox), "Test")
            doc.applyRedactions(black, null, sanitize = true)
            doc = Fixtures.reopen(doc)
            checkHiddenGone(doc)
        } finally {
            doc.close()
        }
    }

    /** Metadata, scripts, attachments, private data, thumbnails, links, bookmarks and a field with a script. */
    private fun addHiddenThings(doc: PdfDoc) {
        val pdf = doc.pdf
        val root = pdf.trailer.get("Root")
        pdf.trailer.put("Info", pdf.indirect(Raw.dict(doc, "Author" to "Someone")))
        root.put("OpenAction", Raw.action(doc, "JavaScript", "JS" to "app.alert(1)"))
        root.put("PieceInfo", Raw.dict(doc, "App" to Raw.dict(doc, "Private" to "x")))
        root.put("AF", pdf.newArray())
        val files = Raw.dict(doc, "Names" to pdf.newArray())
        root.put("Names", Raw.dict(doc, "EmbeddedFiles" to files, "JavaScript" to Raw.dict(doc, "Names" to pdf.newArray())))
        val page = pdf.findPage(0)
        page.put("Thumb", Raw.stream(doc, ""))
        page.put("PieceInfo", Raw.dict(doc, "App" to Raw.dict(doc, "Private" to "x")))
        page.put("AA", Raw.dict(doc, "O" to Raw.action(doc, "JavaScript", "JS" to "1")))
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Link", "Rect" to Raw.nums(doc, 10f, 10f, 50f, 30f), "A" to Raw.action(doc, "URI", "URI" to "https://example.com")))
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Link", "Rect" to Raw.nums(doc, 60f, 10f, 100f, 30f), "A" to Raw.action(doc, "JavaScript", "JS" to "app.alert(2)")))
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Link", "Rect" to Raw.nums(doc, 110f, 10f, 150f, 30f), "A" to Raw.action(doc, "Launch", "F" to "calc.exe")))
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/FileAttachment", "Rect" to Raw.nums(doc, 160f, 10f, 180f, 30f), "Contents" to "attached"))
        // Bookmarks: one to the page, one that launches a program.
        val outlines = pdf.indirect(Raw.dict(doc, "Type" to "/Outlines"))
        val dest = pdf.newArray(); dest.push(page); dest.push(pdf.newName("Fit"))
        val first = pdf.indirect(Raw.dict(doc, "Title" to "Start", "Parent" to outlines, "Dest" to dest))
        val second = pdf.indirect(Raw.dict(doc, "Title" to "Run", "Parent" to outlines, "A" to Raw.action(doc, "Launch", "F" to "calc.exe"), "Prev" to first))
        first.put("Next", second)
        outlines.put("First", first); outlines.put("Last", second); outlines.put("Count", 2)
        root.put("Outlines", outlines)
        FormFixtures.addFields(doc, listOf(FormFixtures.Spec("Name", "Tx", Box(300f, 400f, 450f, 420f))))
        val acro = root.get("AcroForm")
        acro.get("Fields").get(0).put("AA", Raw.dict(doc, "K" to Raw.action(doc, "JavaScript", "JS" to "3")))
        acro.put("XFA", pdf.newArray())
    }

    private fun checkHiddenGone(doc: PdfDoc) {
        val r = doc.pdf.trailer.get("Root")
        assertTrue(doc.pdf.trailer.get("Info").isNull)
        for (key in listOf("OpenAction", "PieceInfo", "AF")) assertTrue("$key stayed", r.get(key).isNull)
        assertTrue(r.get("Names").get("EmbeddedFiles").isNull)
        assertTrue(r.get("Names").get("JavaScript").isNull)
        val p = doc.pdf.findPage(0)
        for (key in listOf("Thumb", "PieceInfo", "AA")) assertTrue("page $key stayed", p.get(key).isNull)
        val links = Raw.annots(doc).filter { it.get("Subtype").asName() == "Link" }
        assertEquals(3, links.size)
        assertEquals(listOf("URI"), links.mapNotNull { l -> l.get("A").takeIf { it.isDictionary }?.get("S")?.asName() })
        assertFalse("FileAttachment" in Raw.subtypes(doc))
        val o = r.get("Outlines").get("First")
        assertEquals("Start", o.get("Title").asString())
        assertTrue(o.get("Dest").isArray)
        assertTrue("the launch stayed on a bookmark", o.get("Next").get("A").isNull)
        assertEquals("Run", o.get("Next").get("Title").asString())
        assertTrue(r.get("AcroForm").get("XFA").isNull)
        assertTrue(r.get("AcroForm").get("Fields").get(0).get("AA").isNull)
    }

    /** E1: the redaction other edits use removes only what is under its boxes: pending marks and links stay. */
    @Test
    fun safeRedactKeepsPendingMarksAndLinks() {
        var doc = Fixtures.open(Fixtures.pdf(content = secretLine))
        Raw.addAnnot(doc, Raw.dict(doc, "Type" to "/Annot", "Subtype" to "/Link", "Rect" to Raw.nums(doc, 60f, 395f, 250f, 420f), "A" to Raw.action(doc, "URI", "URI" to "https://example.com")))
        doc = Fixtures.reopen(doc)
        // A mark the person drew but did not apply, over the public line.
        doc.markForRedaction(listOf(0 to Box(55f, 225f, 200f, 260f)), "Test")
        doc = Fixtures.reopen(doc)
        try {
            // Held open, as an edit does while it works on a page.
            doc.withPdfPage(0) { _ -> doc.safeRedact(0, listOf(markBox)) }
            doc = Fixtures.reopen(doc)
            val text = Fixtures.shownText(doc)
            assertFalse("the words under the box stayed: $text", "Secret" in text)
            assertTrue("the words under the pending mark were removed: $text", "Public" in text)
            assertEquals(1, doc.redactionMarks().size)
            assertEquals(listOf("Link", "Redact"), Raw.subtypes(doc).sorted())
            // And on a page not held open.
            doc.safeRedact(0, listOf(Box(55f, 225f, 70f, 260f)), keepText = true)
            doc = Fixtures.reopen(doc)
            assertEquals(1, doc.redactionMarks().size)
            assertTrue("Public" in Fixtures.shownText(doc))
        } finally {
            doc.close()
        }
    }

    /** The page's own reference to an annotation, and so its object number, is found (the removals rely on it). */
    @Test
    fun annotationNumbersAreFound() {
        var doc = Fixtures.open(Fixtures.pdf())
        doc.addNote(0, 100f, 100f, "note", Rgb(1f, 0.8f, 0f), "Test")
        doc = Fixtures.reopen(doc)
        try {
            doc.withPdfPage(0) { page ->
                val a: PDFAnnotation = with(doc) { page.annots() }.single()
                assertNotNull(annotEntry(page, a.getObject()))
                assertTrue(annotNumber(page, a) > 0)
            }
        } finally {
            doc.close()
        }
    }
}
