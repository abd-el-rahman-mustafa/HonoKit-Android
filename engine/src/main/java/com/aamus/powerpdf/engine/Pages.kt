package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Rect
import java.io.File

/**
 * Whole-page operations, ported from the desktop's src/engine/pages.ts. The in-place ones run
 * inside a [PdfDoc.change] (one undo step); the ones that make files write a new document.
 */

private fun PdfDoc.assertPages(pages: Collection<Int>) {
    val count = pageCount
    for (p in pages) if (p !in 0 until count) throw PdfException(PdfException.Code.BadArguments, "Page ${p + 1} does not exist (the document has $count).")
}

/** Needs "assemble" or "edit" from the author (desktop §23). */
private fun PdfDoc.requireAssemble() {
    val r = rights()
    if (!r.assemble && !r.edit) throw PdfException(PdfException.Code.Restricted, "The author does not allow rearranging pages.")
}

fun PdfDoc.rotatePages(pages: Collection<Int>, angle: Int) {
    requireAssemble()
    assertPages(pages)
    for (index in pages.toSet()) {
        val page = pdf.findPage(index)
        // From the turn readers show (MuPDF snaps /Rotate 45 to 90), not the raw number.
        page.put("Rotate", (((rotationOf(page) + angle) % 360) + 360) % 360)
    }
}

/**
 * Keeps the listed pages, in the listed order (a page may appear twice): extract, reorder, delete
 * and duplicate in one. Done in place with rearrangePages, which keeps bookmarks (desktop §20).
 */
fun PdfDoc.keepPages(order: List<Int>) {
    requireAssemble()
    rearrange(order)
}

/**
 * [keepPages] without the rights check. After MuPDF's rearrangePages every repeated page gets a
 * page object of its own (P1), the form's field list is rebuilt (desktop §20) and the catalogue's
 * page labels, structure tree and the rest are put back (P3; decisions §50).
 */
private fun PdfDoc.rearrange(order: List<Int>) {
    assertPages(order)
    if (order.isEmpty()) throw PdfException(PdfException.Code.BadArguments, "A document must keep at least one page.")
    val acroForm = pdf.trailer.get("Root").get("AcroForm")
    val before = pdf.snapshotCatalog()
    val tagged = !pdf.trailer.get("Root").get("StructTreeRoot").isNull
    pdf.rearrangePages(order.toIntArray())
    pdf.separateRepeats()
    restoreFormFields(acroForm)
    pdf.restoreCatalog(before, order)
    // The output intent, Info and XMP stay; a PDF/UA or PDF/A-a claim goes with the last tagged content (§52).
    pdf.withdrawClaims(tagged)
    invalidate()
}

fun PdfDoc.deletePages(pages: Collection<Int>) {
    val gone = pages.toSet()
    if (gone.size >= pageCount) throw PdfException(PdfException.Code.BadArguments, "A document must keep at least one page.")
    keepPages((0 until pageCount).filter { it !in gone })
}

/** Moves the listed pages so they sit, in order, before page [before] of the current order. */
fun PdfDoc.movePages(pages: List<Int>, before: Int) {
    val moving = pages.toSet()
    val rest = (0 until pageCount).filter { it !in moving }
    val at = rest.indexOfFirst { it >= before }.let { if (it < 0) rest.size else it }
    keepPages(rest.subList(0, at) + pages.sorted() + rest.subList(at, rest.size))
}

/** Puts pages in a new order: [order] is a permutation of 0 until pageCount. */
fun PdfDoc.reorder(order: List<Int>) = keepPages(order)

/** Inserts a copy of each listed page directly after itself. */
fun PdfDoc.duplicatePages(pages: Collection<Int>) {
    val wanted = pages.toSet()
    val order = ArrayList<Int>()
    for (i in 0 until pageCount) {
        order.add(i)
        if (i in wanted) order.add(i)
    }
    keepPages(order)
}

/**
 * MuPDF's rearrangePages empties the form's field list even for an unchanged order, while the
 * widgets stay on the pages (desktop §20). Rebuild the list from the widgets that are left.
 */
private fun PdfDoc.restoreFormFields(acroForm: PDFObject) {
    if (!acroForm.isDictionary) return
    val root = pdf.trailer.get("Root")
    if (!root.get("AcroForm").isDictionary) root.put("AcroForm", acroForm)
    val form = root.get("AcroForm")
    val fields = pdf.newArray()
    val seen = HashSet<Int>()
    for (i in 0 until pageCount) {
        val annots = pdf.findPage(i).get("Annots")
        if (!annots.isArray) continue
        for (k in 0 until annots.size()) {
            var field = annots.get(k)
            if (!field.isDictionary || field.get("Subtype").asName() != "Widget") continue
            var depth = 0
            while (depth < 32 && field.get("Parent").isDictionary) {
                field = field.get("Parent")
                depth++
            }
            if (!field.isIndirect) continue
            if (!seen.add(field.asIndirect())) continue
            fields.append(field)
        }
    }
    form.put("Fields", fields)
}

/** Inserts [count] blank pages at position [at] (0 = before the first page), sized like a neighbour. */
fun PdfDoc.insertBlank(at: Int, count: Int = 1, width: Float? = null, height: Float? = null) {
    requireAssemble()
    val total = pageCount
    if (at < 0 || at > total) throw PdfException(PdfException.Code.BadArguments, "Cannot insert at page ${at + 1}.")
    val neighbour = pageSize((at - 1).coerceIn(0, total - 1))
    val w = width ?: neighbour.width
    val h = height ?: neighbour.height
    repeat(count) { k ->
        val page = pdf.addPage(Rect(0f, 0f, w, h), 0, pdf.newDictionary(), "")
        pdf.insertPage(at + k, page)
    }
}

/** Inserts pages of another PDF at [at]. */
fun PdfDoc.insertFrom(other: PdfDoc, at: Int, pages: List<Int>? = null) {
    requireAssemble()
    val list = pages ?: (0 until other.pageCount).toList()
    other.assertPages(list)
    if (at < 0 || at > pageCount) throw PdfException(PdfException.Code.BadArguments, "Cannot insert at page ${at + 1}.")
    // With their comments, links and filled-in fields (P2).
    pdf.importPages(other.pdf, list, at)
    invalidate()
}

/**
 * Writes the listed pages, in order, to a new file (extract, split). Made from a copy of the whole
 * document kept to those pages, as Acrobat's Extract does: comments, links, form fields, bookmarks
 * to the pages kept, page labels and the structure tree come along (P2, P3). The new file is not
 * encrypted (as before).
 */
fun PdfDoc.writePages(pages: List<Int>, target: File) {
    assertPages(pages)
    if (pages.isEmpty()) throw PdfException(PdfException.Code.BadArguments, "Choose at least one page.")
    target.parentFile?.mkdirs()
    // Created exclusively under a random name beside the target (decisions §52).
    val whole = Staging.create(target.absoluteFile.parentFile ?: File("."), "." + target.name + ".", ".part")
    try {
        // A snapshot of the document as it is now; after a redaction nothing removed may come along.
        pdf.save(whole.absolutePath, if (rewriteFully) "$SAVE_SNAPSHOT,garbage" else SAVE_SNAPSHOT)
        val copy = PdfEngine.open(whole, password)
        try {
            copy.rearrange(pages)
            copy.pdf.save(target.absolutePath, if (copy.isEncrypted) "$SAVE_FINAL,decrypt" else SAVE_FINAL)
        } finally {
            copy.close()
        }
    } finally {
        whole.delete()
    }
}

object PdfFiles {
    /** "1 - 3" is one range: the spaces around a dash go before the list is split at spaces. */
    private val DASH = Regex("\\s*-\\s*")
    private val SPACES = Regex("\\s+")

    /**
     * Joins documents into one. Each page keeps its comments, links and form fields (P2; a field
     * whose name is taken is renamed). Known limit (desktop features): the inputs' bookmarks are
     * not carried over. The first file's PDF/X output intent and identification come along only
     * when every file declares the same (decisions §52); PDF/A and PDF/UA are never claimed.
     */
    fun merge(inputs: List<PdfDoc>, target: File) {
        if (inputs.isEmpty()) throw PdfException(PdfException.Code.BadArguments, "Nothing to merge.")
        val out = PDFDocument()
        try {
            inputs.forEachIndexed { i, src ->
                out.importPages(src.pdf, (0 until src.pageCount).toList(), out.countPages())
                if (i == 0) copyMetadata(src.pdf, out)
            }
            out.carryPrintStandard(inputs.map { it.pdf.printStandard() })
            target.parentFile?.mkdirs()
            out.save(target.absolutePath, SAVE_FINAL)
        } finally {
            PdfEngine.dispose(out)
        }
    }

    /** A new document of [count] blank pages, [width] × [height] points (desktop: New blank document). */
    fun blank(target: File, width: Float, height: Float, count: Int) {
        if (count < 1 || width < 36 || height < 36) throw PdfException(PdfException.Code.BadArguments, "A new document needs at least one page.")
        val out = PDFDocument()
        try {
            repeat(count) {
                val page = out.addPage(Rect(0f, 0f, width, height), 0, out.newDictionary(), "")
                out.insertPage(-1, page)
            }
            target.parentFile?.mkdirs()
            out.save(target.absolutePath, SAVE_FINAL)
        } finally {
            PdfEngine.dispose(out)
        }
    }

    /**
     * Parses "1-3, 5, 7-9" (1-based) into zero-based inclusive ranges. Null if not valid for
     * [count] pages. Ranges may also be separated by spaces only ("1-3 5-7"), and a dash may have
     * spaces around it ("1 - 3"), as on the desktop (P8).
     */
    fun parseRanges(text: String, count: Int): List<IntRange>? {
        val norm = text.map { ch ->
            when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                '–', '—', '−' -> '-'
                '،', ';' -> ','
                else -> ch
            }
        }.joinToString("").replace(DASH, "-")
        val parts = norm.split(',').flatMap { it.trim().split(SPACES) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = ArrayList<IntRange>()
        for (p in parts) {
            val ab = p.split('-')
            val a = ab[0].toIntOrNull() ?: return null
            val b = if (ab.size > 1) ab[1].toIntOrNull() ?: return null else a
            if (ab.size > 2 || a < 1 || b < a || b > count) return null
            out.add((a - 1)..(b - 1))
        }
        return out
    }
}

internal fun copyMetadata(from: PDFDocument, to: PDFDocument) {
    for (key in listOf("info:Title", "info:Author", "info:Subject", "info:Keywords")) {
        runCatching {
            val v = from.getMetaData(key)
            if (!v.isNullOrEmpty()) to.setMetaData(key, v)
        }
    }
}

/** The page's visible box in its own coordinates: CropBox if present, else MediaBox. */
internal fun boxOf(page: PDFObject): FloatArray {
    // As MuPDF and readers take it (P4): the CropBox clipped to the MediaBox, the MediaBox when
    // there is no CropBox (or they do not meet), US Letter when there is not even a MediaBox.
    val media = rectOf(page.getInheritable("MediaBox")) ?: floatArrayOf(0f, 0f, 612f, 792f)
    val crop = rectOf(page.getInheritable("CropBox")) ?: return media
    val both = floatArrayOf(maxOf(media[0], crop[0]), maxOf(media[1], crop[1]), minOf(media[2], crop[2]), minOf(media[3], crop[3]))
    return if (both[2] > both[0] && both[3] > both[1]) both else media
}

private fun rectOf(box: PDFObject): FloatArray? {
    if (!box.isArray || box.size() < 4) return null
    val v = FloatArray(4) { box.get(it).asFloat() }
    if (v.any { !it.isFinite() }) return null
    val r = floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    return if (r[2] > r[0] && r[3] > r[1]) r else null
}

/** Margins as the page is displayed (rotation applied), in points. */
data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val NONE = Margins(0f, 0f, 0f, 0f)
    }
}

/** /Rotate as MuPDF applies it (P6): a whole number, snapped to the nearest quarter turn (45 → 90). */
internal fun rotationOf(page: PDFObject): Int {
    val r = page.getInheritable("Rotate")
    var n = if (r.isNumber) r.asFloat().toInt() else 0
    n = ((n % 360) + 360) % 360
    n = 90 * ((n + 45) / 90)
    return if (n >= 360) 0 else n
}

/**
 * Cuts [m] from the visible area of the listed pages (desktop pages.ts cropPages, decisions §41 there):
 * the CropBox is set, so nothing is deleted and a later crop can widen it again up to the MediaBox.
 * Margins are as the page is shown, in MuPDF's own frame (its bounds and transform, so /UserUnit,
 * /Rotate and a CropBox reaching past the MediaBox count as the viewer shows them; P4–P6); the new
 * CropBox never reaches past the MediaBox.
 */
fun PdfDoc.cropPages(pages: Collection<Int>, m: Margins) {
    requireAssemble()
    assertPages(pages)
    for (index in pages.toSet()) {
        val rect = withPage(index) { loaded ->
            val b = loaded.bounds
            if (b.x1 - m.right - (b.x0 + m.left) < 18 || b.y1 - m.bottom - (b.y0 + m.top) < 18) {
                throw PdfException(PdfException.Code.BadArguments, "Cropping page ${index + 1} would leave almost nothing.")
            }
            val t = (loaded as com.artifex.mupdf.fitz.PDFPage).transform
            Rect(b.x0 + m.left, b.y0 + m.top, b.x1 - m.right, b.y1 - m.bottom).transform(Matrix.Inverted(t))
        }
        val page = pdf.findPage(index)
        var v = floatArrayOf(minOf(rect.x0, rect.x1), minOf(rect.y0, rect.y1), maxOf(rect.x0, rect.x1), maxOf(rect.y0, rect.y1))
        // Never past the MediaBox (a negative margin, say): readers clip to it anyway.
        rectOf(page.getInheritable("MediaBox"))?.let { md ->
            v = floatArrayOf(maxOf(v[0], md[0]), maxOf(v[1], md[1]), minOf(v[2], md[2]), minOf(v[3], md[3]))
        }
        val arr = pdf.newArray()
        for (x in v) arr.push(Math.round(x * 10000f) / 10000f)
        page.put("CropBox", arr)
    }
    invalidate()
}

/** The white margins around a page's content (a small render scanned for ink), with 4 pt to spare. */
fun PdfDoc.whiteMargins(index: Int): Margins {
    val size = pageSize(index)
    val widthPx = minOf(600, maxOf(100, size.width.toInt()))
    val bmp = renderWidth(index, widthPx)
    try {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val lum = (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000
            if (lum < 240) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return Margins.NONE
        val s = w / size.width
        val pad = 4f
        var l = maxOf(0f, x0 / s - pad)
        var t = maxOf(0f, y0 / s - pad)
        var r = maxOf(0f, (w - 1 - x1) / s - pad)
        var b = maxOf(0f, (h - 1 - y1) / s - pad)
        // Very little content (a footer alone) still leaves a page at least 36 pt each way.
        val shortW = 36f - (size.width - l - r)
        if (shortW > 0) { l = maxOf(0f, l - shortW / 2); r = maxOf(0f, r - shortW / 2) }
        val shortH = 36f - (size.height - t - b)
        if (shortH > 0) { t = maxOf(0f, t - shortH / 2); b = maxOf(0f, b - shortH / 2) }
        return Margins(l, t, r, b)
    } finally {
        bmp.recycle()
    }
}
