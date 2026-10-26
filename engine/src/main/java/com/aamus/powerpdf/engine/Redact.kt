package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.Rect
import java.io.File
import java.util.Date

/**
 * Redaction, as Acrobat's (desktop decisions §46): mark what must go — areas, text, or what Find
 * finds (words, and patterns such as e-mail addresses and Emirates ID numbers) — review the marks
 * (Redact annotations, a red outline), then apply: MuPDF removes the text, blanks the picture
 * pixels and removes the drawings under each mark; the boxes are painted into the page itself;
 * hidden information can be removed too; and a check reports anything still readable under a mark.
 */
enum class RedactPattern { Email, Phone, EmiratesId, Iban, Card, Date }

data class RedactHit(val page: Int, val box: Box, val text: String, val context: String)

/** [signed]: the file was digitally signed; its signatures no longer hold once it is saved (desktop §80). */
data class RedactResult(val areas: Int, val pages: Int, val leftover: Int, val signed: Boolean = false)

object RedactPatterns {
    private val PATTERNS = mapOf(
        // Letters and digits of any script (müller@bücher.de, иван@пример.рф); a top-level name of letters.
        RedactPattern.Email to Regex("""[\p{L}\p{N}._%+-]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.\p{L}{2,}(?![\p{L}\p{N}])"""),
        // UAE numbers (+971 / 00971 / 0 then 5x or a landline code), and other international numbers
        // (+ or 00, groups split by spaces, dashes or dots), taken whole so no part stays readable.
        // A phone number is a whole run of digit groups: one reached through a separator from a
        // digit before it (the tail of a social security number, a card, a reference) is not one,
        // nor is the head of a run that goes on past it; otherwise the start stayed readable.
        RedactPattern.Phone to Regex("""(?<!\d[\s.-]?)(?:(?:(?:\+|\b00)971[\s.-]?|\b0)(?:5\d|[2-9])[\s.-]?\d{3}[\s.-]?\d{4}\b|(?:\+|\b00)\d{1,3}[\s.-]?\(?\d{1,4}\)?(?:[\s.-]?\d{2,4}){2,5})(?![\s.-]?\d)"""),
        RedactPattern.EmiratesId to Regex("""\b784[\s-]?\d{4}[\s-]?\d{7}[\s-]?\d\b"""),
        // Case-insensitive because lines are matched folded; the checksum keeps it from matching words.
        RedactPattern.Iban to Regex("""\b[a-z]{2}\d{2}(?:\s?[a-z0-9]{4}){2,7}(?:\s?[a-z0-9]{1,4})?\b""", RegexOption.IGNORE_CASE),
        // A whole run of digit groups; cardEnd finds the card in it.
        RedactPattern.Card to Regex("""\b\d(?:[\s-]?\d)*\b"""),
        RedactPattern.Date to Regex("""\b(?:\d{1,2}[/.-]\d{1,2}[/.-](?:\d{4}|\d{2})|\d{4}-\d{2}-\d{2})\b"""),
    )

    /** Card numbers must pass the Luhn check, or every long number would be marked. */
    fun luhn(digits: String): Boolean {
        var sum = 0
        for (i in digits.indices) {
            var d = digits[digits.length - 1 - i] - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return digits.length >= 13 && sum % 10 == 0
    }

    /**
     * Where the card number in a run of digit groups ends, or -1. The run may carry a trailing
     * number (an expiry, a year, a CVV), so the whole run is tried, then each shorter one ending at
     * a group boundary, longest first. At most four digits are dropped, so a longer number (an
     * IBAN's digits) does not match on a card-shaped start.
     */
    fun cardEnd(run: String): Int {
        val total = run.count { it.isDigit() }
        for (e in run.length downTo 1) {
            if (!run[e - 1].isDigit() || (e < run.length && run[e].isDigit())) continue
            val digits = run.substring(0, e).filter { it.isDigit() }
            if (total - digits.length > 4) break
            if (digits.length in 13..19 && luhn(digits)) return e
        }
        return -1
    }

    /** The IBAN check (ISO 13616): the number, rearranged and read in base 36, leaves 1 divided by 97. */
    fun ibanValid(text: String): Boolean {
        val s = text.filterNot { it.isWhitespace() }.uppercase()
        if (s.length < 15 || s.length > 34) return false
        var rest = 0
        for (c in s.substring(4) + s.substring(0, 4)) {
            val v = Character.digit(c, 36)
            if (v < 0) return false
            rest = (if (v > 9) rest * 100 + v else rest * 10 + v) % 97
        }
        return rest == 1
    }

    /** Arabic-Indic digits read as Western ones, character for character. */
    fun westernDigits(s: String): String = buildString(s.length) {
        for (ch in s) append(
            when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                else -> ch
            },
        )
    }

    /** Where each pattern matches in [text] (already folded): start and end (exclusive). */
    fun matches(text: String, pattern: RedactPattern): List<IntRange> =
        PATTERNS.getValue(pattern).findAll(text).mapNotNull { m ->
            val v = m.value
            when {
                pattern == RedactPattern.Card -> cardEnd(v).takeIf { it > 0 }?.let { m.range.first until m.range.first + it }
                pattern == RedactPattern.Iban && !ibanValid(v) -> null
                pattern == RedactPattern.Phone && v.count { it.isDigit() } < 8 -> null
                else -> m.range
            }
        }.toList()
}

/**
 * Turkish İ and ı fold to i one for one (lowercase() makes İ two characters); Greek final ς to σ
 * (lowercase() writes ς at the end of a word, but a lone Σ on the page as σ); white space as one space.
 */
private fun foldForRedact(s: String): String =
    RedactPatterns.westernDigits(s).filterNot { it in 'ً'..'ٰ' || it == 'ٟ' || it == 'ـ' }.replace('İ', 'i').replace('ı', 'i').lowercase().replace('ς', 'σ').replace(Regex("\\s+"), " ")

/**
 * A line's glyphs folded for matching, and for each folded character the glyph it came from
 * (folding can drop characters, such as vowel marks).
 */
internal fun foldLineForRedact(glyphs: List<String>): Pair<String, List<Int>> {
    val map = ArrayList<Int>()
    val folded = StringBuilder()
    glyphs.forEachIndexed { i, c ->
        for (ch in foldForRedact(c)) {
            // A phrase still matches where the page has two spaces in a row.
            if (ch == ' ' && folded.endsWith(' ')) continue
            folded.append(ch)
            map.add(i)
        }
    }
    return folded.toString() to map
}

/**
 * Finds words or patterns to redact. Words are matched whole or as part of a word, ignoring case
 * and Arabic vowel marks; patterns run on each line with Arabic-Indic digits read as digits.
 */
fun PdfDoc.findToRedact(words: List<String>, patterns: Set<RedactPattern>, wholeWords: Boolean): List<RedactHit> {
    val hits = ArrayList<RedactHit>()
    val needles = words.map { foldForRedact(it.trim()) }.filter { it.isNotEmpty() }
    for (p in 0 until pageCount) {
        for (line in lines(p)) {
            val (text, map) = foldLineForRedact(line.glyphs.map { it.c })
            fun add(start: Int, endExclusive: Int) {
                val first = map.getOrNull(start) ?: return
                val last = map.getOrNull(endExclusive - 1) ?: return
                val range = minOf(first, last)..maxOf(first, last)
                val gs = range.map { line.glyphs[it] }.filter { it.width > 0.01f || it.c.isNotBlank() }
                if (gs.isEmpty()) return
                val box = Box(gs.minOf { it.x0 }, gs.minOf { it.y0 }, gs.maxOf { it.x1 }, gs.maxOf { it.y1 })
                hits.add(RedactHit(p, box, range.joinToString("") { line.glyphs[it].c }, line.text.trim().take(120)))
            }
            fun edge(i: Int) = i < 0 || i >= text.length || !Character.isLetterOrDigit(text[i])
            for (needle in needles) {
                var from = 0
                while (true) {
                    val at = text.indexOf(needle, from)
                    if (at < 0) break
                    val end = at + needle.length
                    if (!wholeWords || (edge(at - 1) && edge(end))) add(at, end)
                    from = end
                }
            }
            for (pattern in patterns) for (r in RedactPatterns.matches(text, pattern)) add(r.first, r.last + 1)
        }
    }
    return hits
}

/** Adds redaction marks (Redact annotations, a red outline until applied). */
fun PdfDoc.markForRedaction(marks: List<Pair<Int, Box>>, author: String) {
    requireEdit()
    for ((p, boxes) in marks.groupBy({ it.first }, { it.second })) {
        withPdfPage(p) { page ->
            for (b in boxes) {
                // A little room around text, so the tops and tails of letters go too.
                val a = page.newAnnot(PDFAnnotation.TYPE_REDACT)
                a.rect = Rect(b.x0 - 0.5f, b.y0 - 0.5f, b.x1 + 0.5f, b.y1 + 0.5f)
                a.author = author.ifBlank { "HonoPDF" }
                a.creationDate = Date()
                a.update()
            }
        }
    }
    invalidate()
}

/** Every mark waiting to be applied, as (page, box). */
fun PdfDoc.redactionMarks(): List<Pair<Int, Box>> {
    val out = ArrayList<Pair<Int, Box>>()
    for (p in 0 until pageCount) withPdfPage(p) { page ->
        page.annots().forEach { a ->
            if (a.type == PDFAnnotation.TYPE_REDACT) a.rect.let { r -> out.add(p to Box(r.x0, r.y0, r.x1, r.y1)) }
        }
    }
    return out
}

/** Removes the one mark at [box] (as [redactionMarks] gave it). */
fun PdfDoc.removeRedactionMark(pageIndex: Int, box: Box) {
    var num = -1
    withPdfPage(pageIndex) { page ->
        val mark = page.annots().firstOrNull { a ->
            a.type == PDFAnnotation.TYPE_REDACT && a.rect.let { r -> kotlin.math.abs(r.x0 - box.x0) < 1 && kotlin.math.abs(r.y0 - box.y0) < 1 && kotlin.math.abs(r.x1 - box.x1) < 1 && kotlin.math.abs(r.y1 - box.y1) < 1 }
        } ?: throw PdfException(PdfException.Code.BadArguments, "That mark is no longer there.")
        num = annotNumber(page, mark)
        page.deleteAnnotation(mark)
    }
    // What belongs to the mark goes with it: a reply to it, and its pop-up, would otherwise be
    // left answering a mark that is no longer there, and keep it in the file (desktop §102).
    if (num > 0) removeThreads(listOf(num))
    invalidate(listOf(pageIndex))
}

fun PdfDoc.clearRedactionMarks() {
    val marks = ArrayList<Int>()
    for (p in 0 until pageCount) withPdfPage(p) { page ->
        for (a in page.annots().filter { it.type == PDFAnnotation.TYPE_REDACT }) {
            annotNumber(page, a).takeIf { it > 0 }?.let { marks.add(it) }
            page.deleteAnnotation(a)
        }
    }
    // What belongs to the marks goes with them (desktop §102).
    if (marks.isNotEmpty()) removeThreads(marks)
    invalidate()
}

/**
 * Actions that run something, send or fetch data, or open another file — what Acrobat's Remove
 * Hidden Information takes out as "links, actions and JavaScripts". Going to a page (GoTo) and web
 * addresses (URI) stay: they are what a reader sees and follows.
 */
private val RISKY_ACTIONS = setOf("JavaScript", "Launch", "SubmitForm", "ImportData", "Rendition", "GoToE", "GoToR", "Movie", "Sound")

/** Whether an action, or one chained after it (/Next), is risky. */
private fun risky(action: PDFObject, depth: Int = 0): Boolean {
    if (depth > 32) return true
    if (action.isArray) return (0 until action.size()).any { risky(action.get(it), depth + 1) }
    if (!action.isDictionary) return false
    val s = action.get("S")
    if (s.isName && s.asName() in RISKY_ACTIONS) return true
    val next = action.get("Next")
    return !next.isNull && risky(next, depth + 1)
}

/** Takes the scripts and risky actions off a dictionary (an annotation, a field, a bookmark). */
private fun disarm(obj: PDFObject) {
    if (!obj.isDictionary) return
    obj.delete("AA")
    val a = obj.get("A")
    if (!a.isNull && risky(a)) obj.delete("A")
}

/**
 * Removes what a document carries besides its pages (Acrobat's Remove Hidden Information; desktop
 * redact.ts sanitize): metadata, attached files (the attachments list, file attachment
 * annotations and associated files /AF), scripts and risky actions (document, pages, links,
 * fields, bookmarks), other programs' private data (/PieceInfo), page thumbnails, and an XFA form
 * (which also carries the form's data). Links to pages, web links and bookmarks stay.
 */
internal fun PdfDoc.sanitize() {
    val trailer = pdf.trailer
    val root = trailer.get("Root")
    trailer.delete("Info")
    if (root.isDictionary) {
        for (key in listOf("Metadata", "OpenAction", "AA", "AF", "PieceInfo", "Collection", "NeedsRendering")) root.delete(key)
        val names = root.get("Names")
        if (names.isDictionary) {
            names.delete("EmbeddedFiles")
            names.delete("JavaScript")
        }
        val acro = root.get("AcroForm")
        if (acro.isDictionary) {
            acro.delete("XFA")
            // Every field, its kids too (a script can sit on a parent field).
            val seen = HashSet<Int>()
            fun walk(field: PDFObject, depth: Int) {
                if (depth > 32 || !field.isDictionary) return
                val n = numOf(field)
                if (n > 0 && !seen.add(n)) return
                disarm(field)
                val kids = field.get("Kids")
                if (kids.isArray) for (i in 0 until kids.size()) walk(kids.get(i), depth + 1)
            }
            val fields = acro.get("Fields")
            if (fields.isArray) for (i in 0 until fields.size()) walk(fields.get(i), 0)
        }
        // Bookmarks stay; a script or a launch behind one does not.
        val outlines = root.get("Outlines")
        if (outlines.isDictionary) {
            val seen = HashSet<Int>()
            fun walk(first: PDFObject, depth: Int) {
                var item = first
                var k = 0
                while (item.isDictionary && k < 100_000 && depth < 64) {
                    val n = numOf(item)
                    if (n > 0 && !seen.add(n)) return
                    disarm(item)
                    walk(item.get("First"), depth + 1)
                    item = item.get("Next")
                    k++
                }
            }
            walk(outlines.get("First"), 0)
        }
    }
    for (p in 0 until pageCount) {
        val page = pdf.findPage(p)
        for (key in listOf("AA", "Metadata", "PieceInfo", "Thumb", "AF")) page.delete(key)
        val annots = page.get("Annots")
        if (!annots.isArray) continue
        // An attached file goes with its pop-up (the pop-up is listed on the page too).
        val gone = HashSet<Int>()
        val popups = HashSet<Int>()
        for (i in 0 until annots.size()) {
            val annot = annots.get(i)
            if (!annot.isDictionary) continue
            val type = annot.get("Subtype")
            if (type.isName && type.asName() == "FileAttachment") {
                gone.add(i)
                numOf(annot.get("Popup")).takeIf { it > 0 }?.let { popups.add(it) }
                continue
            }
            disarm(annot)
            annot.delete("AF")
            annot.delete("PieceInfo")
        }
        for (i in 0 until annots.size()) if (numOf(annots.get(i)) in popups) gone.add(i)
        for (i in gone.sortedDescending()) annots.delete(i)
    }
}

private fun overlaps(a: Box, b: Box) = minOf(a.x1, b.x1) > maxOf(a.x0, b.x0) && minOf(a.y1, b.y1) > maxOf(a.y0, b.y0)

/**
 * What lies on a page's marks besides its content (R2): every annotation that touches a mark —
 * notes, highlights, stamps, ink, shapes, text boxes, links — with their pop-ups and replies, and
 * every form field with a widget there (the whole field: its value is shared by all its widgets).
 * Readers draw these above the black box, and their text stays in the file.
 */
private fun PdfDoc.removeCovered(pageIndex: Int, boxes: List<Box>) {
    val annots = ArrayList<Int>()
    val widgets = ArrayList<PDFObject>()
    withPdfPage(pageIndex) { page ->
        // An entry written as a dictionary of its own in /Annots has no object number to collect:
        // it is taken off the page here, or a comment under a mark stayed in the file (desktop §102).
        val inline = ArrayList<PDFAnnotation>()
        for (a in page.annots()) {
            val type = a.type
            // The marks themselves go when applied; a pop-up goes with its note.
            if (type == PDFAnnotation.TYPE_REDACT || type == PDFAnnotation.TYPE_POPUP || type == PDFAnnotation.TYPE_WIDGET) continue
            val b = a.bounds
            if (boxes.none { overlaps(it, Box(b.x0, b.y0, b.x1, b.y1)) }) continue
            val num = annotNumber(page, a)
            if (num > 0) annots.add(num) else inline.add(a)
        }
        for (a in inline) runCatching { page.deleteAnnotation(a) }
        for (w in page.widgetList()) {
            val b = w.bounds
            if (boxes.any { overlaps(it, Box(b.x0, b.y0, b.x1, b.y1)) }) widgets.add(annotEntry(page, w.getObject()) ?: w.getObject())
        }
    }
    if (widgets.isNotEmpty()) removeFieldsOf(widgets)
    // Their replies and review states go too, also those on other pages (they may quote the redacted words).
    if (annots.isNotEmpty()) removeThreads(annots)
}

/** XObject names in a page's resources. */
private fun xobjectNames(page: PDFObject): Set<String> {
    val res = page.getInheritable("Resources")
    val dict = if (res.isDictionary) res.get("XObject") else null
    if (dict == null || !dict.isDictionary) return emptySet()
    return dict.iterator().asSequence().map { it.asName() }.toSet()
}

private fun textOf(o: PDFObject, key: String): String = runCatching {
    val v = o.getInheritable(key)
    if (v.isString) v.asString() else if (v.isName) v.asName() else ""
}.getOrDefault("")

/**
 * Characters still readable on the marks in [check] (a copy of the result): text on the page —
 * only the overlay XObjects named in [overlays] (our boxes and labels) are blanked first, in this
 * copy — and the comments and field values of anything still lying on a mark.
 */
private fun leftoverUnder(check: PdfDoc, marks: List<Pair<Int, Box>>, overlays: Map<Int, Set<String>>): Int {
    var leftover = 0
    for ((p, list) in marks.groupBy({ it.first }, { it.second })) {
        val page = check.pdf.findPage(p)
        val res = page.getInheritable("Resources")
        val dict = if (res.isDictionary) res.get("XObject") else null
        if (dict != null && dict.isDictionary) for (name in overlays[p].orEmpty()) {
            val x = dict.get(name)
            if (x.isStream) x.writeStream("")
        }
        check.invalidate(listOf(p))
        for (line in check.lines(p)) for (g in line.glyphs) {
            if (g.c.isBlank()) continue
            if (list.any { g.cx > it.x0 && g.cx < it.x1 && g.cy > it.y0 && g.cy < it.y1 }) leftover++
        }
        check.withPdfPage(p) { pg ->
            for (a in check.run { pg.annots() }.toList() + check.run { pg.widgetList() }.toList()) {
                val type = a.type
                if (type == PDFAnnotation.TYPE_POPUP || type == PDFAnnotation.TYPE_REDACT) continue
                val b = a.bounds
                if (list.none { overlaps(it, Box(b.x0, b.y0, b.x1, b.y1)) }) continue
                val obj = a.getObject()
                val words = (textOf(obj, "Contents") + textOf(obj, "V") + textOf(obj, "RC")).filterNot { it.isWhitespace() }
                leftover += maxOf(1, words.codePointCount(0, words.length))
            }
        }
    }
    return leftover
}

/**
 * Applies every redaction mark: what lies under them is removed — text, picture pixels, drawings
 * (MuPDF), and the comments, replies and form fields there (R2) — the boxes painted in [fill]
 * with an optional [label] (shaped, Arabic included), hidden information removed if [sanitize].
 * The next save writes the whole file again with garbage collection, so no earlier revision keeps
 * what was removed (R1); a signed file's signatures no longer hold ([RedactResult.signed]).
 * Returns how many areas went, and how many characters could still be read under them in a copy
 * of the result (must be 0).
 */
fun PdfDoc.applyRedactions(fill: Rgb, label: String?, sanitize: Boolean): RedactResult {
    requireEdit()
    val marks = redactionMarks()
    if (marks.isEmpty()) throw PdfException(PdfException.Code.BadArguments, "Mark something to redact first.")
    val signed = isSigned()
    val pages = marks.map { it.first }.distinct()
    for (p in pages) removeCovered(p, marks.filter { it.first == p }.map { it.second })
    // Collected before the marks are applied and taken off the pages; their replies go after.
    val markObjects = ArrayList<Int>()
    for (p in pages) withPdfPage(p) { page ->
        for (a in page.annots().filter { it.type == PDFAnnotation.TYPE_REDACT }) annotNumber(page, a).takeIf { it > 0 }?.let { markObjects.add(it) }
    }
    val drawn = xobjectsDrawn(pages)
    for (p in pages) {
        // MuPDF's rewrite of the page misreads T*, ' and " after a Td (desktop §58).
        runCatching { explicitLeading(p) }
        // The removed text's /ActualText and /Alt go with it.
        clearReplacementText(p) {
            withPdfPage(p) { page ->
                // A drawing goes when it touches a mark at all, not only when a mark covers it
                // whole: a drawing that reaches past the mark's edge — an outlined word, a
                // signature, a line of a chart — otherwise keeps every one of its drawing
                // instructions in the file, under the painted box, for anyone to read back
                // (desktop §105).
                page.applyRedactions(false, PDFPage.REDACT_IMAGE_PIXELS, PDFPage.REDACT_LINE_ART_REMOVE_IF_TOUCHED, PDFPage.REDACT_TEXT_REMOVE)
            }
        }
    }
    // A reply to a mark goes with the mark (desktop §102): left behind, its /IRT would keep the
    // mark dictionary — the area it covered, and who marked it — in the saved file.
    if (markObjects.isNotEmpty()) removeThreads(markObjects)
    // What went may still be named by resources the page shared with others (inherited, shared).
    unlinkRemoved(drawn)
    invalidate()
    // The boxes (and labels) are drawn onto the pages themselves, so they cannot be removed.
    val before = pages.associateWith { xobjectNames(pdf.findPage(it)) }
    val light = fill.r * 0.3f + fill.g * 0.59f + fill.b * 0.11f > 0.55f
    val ink = if (light) Rgb(0f, 0f, 0f) else Rgb(1f, 1f, 1f)
    stamp(pages, 1f) { dev, frame ->
        for ((_, b) in marks.filter { it.first == frame.index }) {
            val path = Path().apply {
                moveTo(b.x0, b.y0); lineTo(b.x1, b.y0); lineTo(b.x1, b.y1); lineTo(b.x0, b.y1); closePath()
            }
            dev.fillPath(path, false, Matrix(), ColorSpace.DeviceRGB, fill.toArray(), 1f, 0)
            if (!label.isNullOrBlank()) {
                val h = b.y1 - b.y0
                var size = minOf(12f, h * 0.7f)
                var block = layoutText(label, TextStyle(size, ink, bold = true, align = HAlign.Center), 100_000f)
                if (block.width > b.x1 - b.x0 - 4 && block.width > 0) {
                    size = maxOf(3f, size * (b.x1 - b.x0 - 4) / block.width)
                    block.destroy()
                    block = layoutText(label, TextStyle(size, ink, bold = true, align = HAlign.Center), 100_000f)
                }
                try {
                    if (!block.isEmpty) block.draw(dev, Matrix.Translate((b.x0 + b.x1 - block.width) / 2, (b.y0 + b.y1 - block.height) / 2))
                } finally {
                    block.destroy()
                }
            }
        }
    }
    val overlays = pages.associateWith { p -> xobjectNames(pdf.findPage(p)) - before[p].orEmpty() }
    if (sanitize) sanitize()
    // A removed comment's tag would still write it, its text included, into the file.
    dropRemovedAnnotRefs()
    invalidate()
    // R1: the next save (the session's version, or Save) is a full, garbage-collected rewrite.
    rewriteFully = true
    // Check a copy of the result: nothing readable is left under any mark.
    val copy = File.createTempFile("hk-redact", ".pdf", file?.parentFile)
    var leftover: Int
    try {
        pdf.save(copy.absolutePath, "garbage,compress")
        val check = PdfEngine.open(copy, password)
        try {
            leftover = leftoverUnder(check, marks, overlays)
        } finally {
            check.close()
        }
    } finally {
        copy.delete()
    }
    return RedactResult(marks.size, pages.size, leftover, signed)
}
