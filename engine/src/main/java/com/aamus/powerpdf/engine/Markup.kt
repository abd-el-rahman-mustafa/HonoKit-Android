package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import java.util.Date

/**
 * Markup as standard PDF annotations (desktop decisions §30): highlight, underline and
 * strike-through over text, pen (ink), shapes, sticky notes. Every reader shows them; they can be
 * selected, recoloured and deleted later here or in any other editor; the page is untouched.
 * Coordinates: points on the page as displayed, top-left origin.
 */
enum class MarkKind { Highlight, Underline, StrikeOut, Ink, Rect, Ellipse, Line, Arrow, Note, Stamp, TextBox, Other }

/** An RGB colour, each part 0–1. */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    fun toArray() = floatArrayOf(r, g, b)

    companion object {
        fun of(argb: Int) = Rgb(((argb shr 16) and 255) / 255f, ((argb shr 8) and 255) / 255f, (argb and 255) / 255f)
    }

    fun toArgb(): Int = (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
}

data class MarkInfo(
    /** Position among the page's annotations; with [box], identifies it. */
    val index: Int,
    val kind: MarkKind,
    val box: Box,
    val colour: Rgb,
    /** Text markup follows its text; everything else could be moved. */
    val movable: Boolean,
    /** The comment written on it (a sticky note's text). */
    val text: String,
    val author: String,
    val modified: Long,
    /** The annotation's object number (0 when it has none): what a reply's [replyTo] names. */
    val id: Int = 0,
    /** A reply (/IRT): the object number of the comment it answers, which may be on another page. */
    val replyTo: Int? = null,
    /** A review state set on the comment it answers (/State: Accepted, Rejected, Completed…). */
    val state: String? = null,
)

private fun kindOf(a: PDFAnnotation): MarkKind = when (a.type) {
    PDFAnnotation.TYPE_HIGHLIGHT -> MarkKind.Highlight
    PDFAnnotation.TYPE_UNDERLINE -> MarkKind.Underline
    PDFAnnotation.TYPE_STRIKE_OUT -> MarkKind.StrikeOut
    PDFAnnotation.TYPE_INK -> MarkKind.Ink
    PDFAnnotation.TYPE_SQUARE -> MarkKind.Rect
    PDFAnnotation.TYPE_CIRCLE -> MarkKind.Ellipse
    PDFAnnotation.TYPE_LINE -> {
        val ends = runCatching { a.lineEndingStyles }.getOrNull()
        if (ends != null && ends.any { it != PDFAnnotation.LINE_ENDING_NONE }) MarkKind.Arrow else MarkKind.Line
    }
    PDFAnnotation.TYPE_TEXT -> MarkKind.Note
    PDFAnnotation.TYPE_STAMP -> MarkKind.Stamp
    PDFAnnotation.TYPE_FREE_TEXT -> MarkKind.TextBox
    else -> MarkKind.Other
}

/** Annotations the interface lists: not form fields, links or popups. */
private fun isMarkup(a: PDFAnnotation): Boolean =
    a.type != PDFAnnotation.TYPE_WIDGET && a.type != PDFAnnotation.TYPE_LINK && a.type != PDFAnnotation.TYPE_POPUP && a.type != PDFAnnotation.TYPE_REDACT

private fun colourOf(a: PDFAnnotation): Rgb {
    val c = runCatching { a.color }.getOrNull() ?: return Rgb(0f, 0f, 0f)
    return when (c.size) {
        1 -> Rgb(c[0], c[0], c[0])
        3 -> Rgb(c[0], c[1], c[2])
        4 -> Rgb(1 - minOf(1f, c[0] + c[3]), 1 - minOf(1f, c[1] + c[3]), 1 - minOf(1f, c[2] + c[3]))
        else -> Rgb(0f, 0f, 0f)
    }
}

internal inline fun <T> PdfDoc.withPdfPage(index: Int, block: (PDFPage) -> T): T = withPage(index) { page ->
    block(page as? PDFPage ?: throw PdfException(PdfException.Code.Unsupported, "Not a PDF page."))
}

fun PdfDoc.marks(pageIndex: Int): List<MarkInfo> = withPdfPage(pageIndex) { page ->
    val out = ArrayList<MarkInfo>()
    page.annots().forEachIndexed { index, a ->
        if (!isMarkup(a)) return@forEachIndexed
        val b = a.bounds
        val kind = kindOf(a)
        // Who it answers (/IRT) and the review state it sets (decisions §52).
        val obj = a.getObject()
        val id = maxOf(0, runCatching { annotNumber(page, a) }.getOrDefault(0))
        val replyTo = runCatching { numOf(obj.get("IRT")).takeIf { it > 0 } }.getOrNull()
        val state = runCatching { obj.get("State").let { if (it.isString) it.asString() else if (it.isName) it.asName() else "" } }.getOrDefault("").takeIf { it.isNotEmpty() && replyTo != null }
        out.add(
            MarkInfo(
                index = index,
                kind = kind,
                box = Box(b.x0, b.y0, b.x1, b.y1),
                colour = colourOf(a),
                movable = kind != MarkKind.Highlight && kind != MarkKind.Underline && kind != MarkKind.StrikeOut && kind != MarkKind.Other,
                text = runCatching { a.contents ?: "" }.getOrDefault(""),
                author = runCatching { a.author ?: "" }.getOrDefault(""),
                // No /M: unknown (MuPDF answers 1970 for it).
                modified = runCatching { if (obj.get("M").isNull) 0L else maxOf(0L, a.modificationDate?.time ?: 0L) }.getOrDefault(0L),
                id = id,
                replyTo = replyTo,
                state = state,
            )
        )
    }
    out
}

private fun PDFAnnotation.finish(author: String, opacity: Float? = null) {
    this.author = author.ifBlank { "HonoPDF" }
    val now = Date()
    creationDate = now
    modificationDate = now
    if (opacity != null && opacity < 1f) this.opacity = opacity
    update()
}

/**
 * Highlight / underline / strike through the given line boxes (from a text selection over our
 * own repaired lines, so Arabic is covered exactly as it was selected). Returns false if empty.
 */
fun PdfDoc.addTextMarkup(pageIndex: Int, kind: MarkKind, boxes: List<Box>, colour: Rgb, author: String): Boolean {
    if (boxes.isEmpty()) return false
    val type = when (kind) {
        MarkKind.Highlight -> PDFAnnotation.TYPE_HIGHLIGHT
        MarkKind.Underline -> PDFAnnotation.TYPE_UNDERLINE
        MarkKind.StrikeOut -> PDFAnnotation.TYPE_STRIKE_OUT
        else -> throw PdfException(PdfException.Code.BadArguments, "Not a text markup: $kind")
    }
    return withPdfPage(pageIndex) { page ->
        val a = page.newAnnot(type)
        a.color = colour.toArray()
        a.quadPoints = boxes.map { b -> Quad(b.x0, b.y0, b.x1, b.y0, b.x0, b.y1, b.x1, b.y1) }.toTypedArray()
        a.finish(author)
        true
    }
}

/**
 * Freehand pen: one or more strokes of points. Each stroke is stored as the smooth curve through its
 * points ([Smooth.stroke], decisions §52): MuPDF and other readers join the listed
 * points with straight lines, so a stroke drawn with a finger showed corners.
 */
fun PdfDoc.addInk(pageIndex: Int, strokes: List<List<Pair<Float, Float>>>, colour: Rgb, width: Float, opacity: Float, author: String): Boolean {
    val list = strokes.filter { it.isNotEmpty() }
    if (list.isEmpty()) return false
    return withPdfPage(pageIndex) { page ->
        val a = page.newAnnot(PDFAnnotation.TYPE_INK)
        a.color = colour.toArray()
        a.borderWidth = width
        // A single touch is a dot: a stroke needs two points to be drawn.
        a.inkList = list.map { s ->
            val pts = if (s.size == 1) listOf(s[0], s[0].first + 0.01f to s[0].second + 0.01f) else Smooth.stroke(s)
            pts.map { Point(it.first, it.second) }.toTypedArray()
        }.toTypedArray()
        a.finish(author, opacity)
        true
    }
}

/** A rectangle, ellipse, line or arrow. */
fun PdfDoc.addShape(pageIndex: Int, kind: MarkKind, x0: Float, y0: Float, x1: Float, y1: Float, colour: Rgb, width: Float, author: String): Boolean =
    withPdfPage(pageIndex) { page ->
        when (kind) {
            MarkKind.Rect, MarkKind.Ellipse -> {
                val a = page.newAnnot(if (kind == MarkKind.Rect) PDFAnnotation.TYPE_SQUARE else PDFAnnotation.TYPE_CIRCLE)
                a.rect = Rect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
                a.color = colour.toArray()
                a.borderWidth = width
                a.finish(author)
            }
            MarkKind.Line, MarkKind.Arrow -> {
                val a = page.newAnnot(PDFAnnotation.TYPE_LINE)
                a.setLine(Point(x0, y0), Point(x1, y1))
                a.color = colour.toArray()
                a.borderWidth = width
                if (kind == MarkKind.Arrow) a.setLineEndingStyles(PDFAnnotation.LINE_ENDING_NONE, PDFAnnotation.LINE_ENDING_OPEN_ARROW)
                a.finish(author)
            }
            else -> throw PdfException(PdfException.Code.BadArguments, "Not a shape: $kind")
        }
        true
    }

/** The page's transform (its PDF space → the page as shown) as [a, b, c, d, e, f], for [Upright]. */
internal fun PDFPage.shownTransform(): FloatArray = transform.let { floatArrayOf(it.a, it.b, it.c, it.d, it.e, it.f) }

/**
 * A sticky note (desktop §34): a note icon whose top-left corner is at ([x], [y]) as shown,
 * holding a comment. Readers draw the icon upright from /Rect's PDF-space top-left corner, so on
 * a turned page that corner is put there (desktop §78 noteRect).
 */
fun PdfDoc.addNote(pageIndex: Int, x: Float, y: Float, text: String, colour: Rgb, author: String): Boolean = withPdfPage(pageIndex) { page ->
    val a = page.newAnnot(PDFAnnotation.TYPE_TEXT)
    val r = Upright.noteRect(page.shownTransform(), x, y)
    a.rect = Rect(r[0], r[1], r[2], r[3])
    a.icon = "Comment"
    a.color = colour.toArray()
    a.contents = text
    a.finish(author)
    true
}

/** Finds the annotation the interface means: at [index], and still where it was shown. */
private fun PdfDoc.find(page: PDFPage, index: Int, box: Box): PDFAnnotation {
    val all = page.annots()
    val a = all.getOrNull(index)
    fun same(x: PDFAnnotation): Boolean {
        val b = x.bounds
        return kotlin.math.abs(b.x0 - box.x0) < 0.5f && kotlin.math.abs(b.y0 - box.y0) < 0.5f && kotlin.math.abs(b.x1 - box.x1) < 0.5f && kotlin.math.abs(b.y1 - box.y1) < 0.5f
    }
    if (a != null && same(a)) return a
    return all.firstOrNull { same(it) } ?: throw PdfException(PdfException.Code.BadArguments, "That mark is no longer there.")
}

/** An object's number, or -1 when it is not an indirect object. */
internal fun numOf(o: com.artifex.mupdf.fitz.PDFObject?): Int = runCatching { if (o != null && o.isIndirect) o.asIndirect() else -1 }.getOrDefault(-1)

private fun sameNumbers(a: com.artifex.mupdf.fitz.PDFObject, b: com.artifex.mupdf.fitz.PDFObject): Boolean =
    a.isArray && b.isArray && a.size() == b.size() && (0 until a.size()).all { kotlin.math.abs(a.get(it).asFloat() - b.get(it).asFloat()) < 0.01f }

/**
 * The page's /Annots entry for an annotation's dictionary: an indirect reference, so its object
 * number is known (replies, pop-ups and fields refer to it by number). MuPDF hands out that
 * reference; when it does not (an annotation written inline in /Annots has no number), the entry
 * is found by /Subtype and /Rect.
 */
internal fun annotEntry(page: PDFPage, obj: com.artifex.mupdf.fitz.PDFObject): com.artifex.mupdf.fitz.PDFObject? {
    if (runCatching { obj.isIndirect }.getOrDefault(false)) return obj
    val annots = page.getObject().get("Annots")
    if (!annots.isArray) return null
    var byRect: com.artifex.mupdf.fitz.PDFObject? = null
    val rect = obj.get("Rect")
    val subtype = obj.get("Subtype").let { if (it.isName) it.asName() else "" }
    for (i in 0 until annots.size()) {
        val r = annots.get(i)
        if (!r.isIndirect) continue
        val d = r.resolve()
        if (byRect == null && d.get("Subtype").let { it.isName && it.asName() == subtype } && sameNumbers(d.get("Rect"), rect)) byRect = r
    }
    return byRect
}

/** The object number of an annotation on [page], or -1. */
internal fun annotNumber(page: PDFPage, a: PDFAnnotation): Int = numOf(annotEntry(page, a.getObject()))

/**
 * Removes the annotations numbered [targets] from [page], with their pop-ups and the replies to
 * them (/IRT), and the replies' replies (desktop markup.ts removeAnnotationObjects, A4). Returns
 * every object number removed.
 */
internal fun PdfDoc.removeAnnotationObjects(page: PDFPage, targets: Collection<Int>): Set<Int> {
    val gone = targets.filter { it > 0 }.toMutableSet()
    val annots = page.getObject().get("Annots")
    if (!annots.isArray) return gone
    collectThreads((0 until annots.size()).map { annots.get(it) }, gone)
    dropAnnotations(page, gone)
    return gone
}

/**
 * Adds to [gone] the pop-ups of the annotations in it, the replies (/IRT) and review states that
 * answer them, and their pop-ups, among [items], until nothing more is found. True when it grew.
 */
private fun collectThreads(items: List<com.artifex.mupdf.fitz.PDFObject>, gone: MutableSet<Int>): Boolean {
    fun ref(o: com.artifex.mupdf.fitz.PDFObject, key: String) = runCatching { numOf(o.get(key)) }.getOrDefault(-1)
    var grew = false
    var changed = true
    while (changed) {
        changed = false
        for (item in items) {
            val n = numOf(item)
            if (n < 0) continue
            if (n in gone) {
                val popup = ref(item, "Popup")
                if (popup > 0 && gone.add(popup)) changed = true
            } else {
                val subtype = item.get("Subtype")
                val isPopup = subtype.isName && subtype.asName() == "Popup"
                // A reply to a removed one, or its pop-up (only a pop-up's /Parent is an annotation).
                if (ref(item, "IRT") in gone || (isPopup && ref(item, "Parent") in gone)) {
                    gone.add(n)
                    changed = true
                }
            }
        }
        if (changed) grew = true
    }
    return grew
}

/** Takes the annotations numbered [gone] off [page]. */
private fun PdfDoc.dropAnnotations(page: PDFPage, gone: Set<Int>) {
    // Through MuPDF where it knows the annotation, so the open page forgets it too.
    for (a in page.annots()) if (runCatching { annotNumber(page, a) }.getOrDefault(-1) in gone) runCatching { page.deleteAnnotation(a) }
    val left = page.getObject().get("Annots")
    if (left.isArray) for (i in left.size() - 1 downTo 0) if (numOf(left.get(i)) in gone) left.delete(i)
}

/**
 * The same across the whole document (A4; desktop markup.ts removeThreads): a reply
 * or a review state may sit on another page than the comment it answers (Acrobat keeps them with
 * the comment, but files made elsewhere, and pages moved later, do not). The pages' /Annots are
 * read until nothing more is found; only the pages holding something to remove are opened, and
 * their drawings are forgotten. Returns every object number removed.
 */
internal fun PdfDoc.removeThreads(targets: Collection<Int>): Set<Int> {
    val gone = targets.filter { it > 0 }.toMutableSet()
    if (gone.isEmpty()) return gone
    val pages = (0 until pageCount).map { i ->
        val annots = pdf.findPage(i).get("Annots")
        if (annots.isArray) (0 until annots.size()).map { annots.get(it) } else emptyList()
    }
    val all = pages.flatten()
    collectThreads(all, gone)
    val touched = pages.indices.filter { i -> pages[i].any { numOf(it) in gone } }
    for (i in touched) withPdfPage(i) { page ->
        dropAnnotations(page, gone)
        page.update()
    }
    invalidate(touched)
    return gone
}

/** Deletes a comment, its pop-up, its replies and review states, wherever they are (A4). */
fun PdfDoc.deleteMark(pageIndex: Int, index: Int, box: Box) {
    val n = withPdfPage(pageIndex) { page ->
        val a = find(page, index, box)
        val n = annotNumber(page, a)
        if (n < 0) {
            page.deleteAnnotation(a)
            page.update()
        }
        n
    }
    if (n > 0) removeThreads(listOf(n))
}

fun PdfDoc.recolourMark(pageIndex: Int, index: Int, box: Box, colour: Rgb) = withPdfPage(pageIndex) { page ->
    val a = find(page, index, box)
    // Stamps and text boxes we draw (and other programs' text boxes, taken over): never MuPDF's drawing (A2, A3).
    if (isDrawn(a) || isForeignFreeText(a)) return@withPdfPage reviseDrawn(page, a, null, colour)
    a.color = colour.toArray()
    a.modificationDate = Date()
    a.update()
    redrawOwn(a, page)
}

fun PdfDoc.setMarkText(pageIndex: Int, index: Int, box: Box, text: String) = withPdfPage(pageIndex) { page ->
    val a = find(page, index, box)
    if (isDrawn(a) || isForeignFreeText(a)) return@withPdfPage reviseDrawn(page, a, text, null)
    a.contents = text
    a.modificationDate = Date()
    a.update()
    redrawOwn(a, page)
}

/** Moves a movable mark (ink, shape, note) by dx, dy points. */
fun PdfDoc.moveMark(pageIndex: Int, index: Int, box: Box, dx: Float, dy: Float) = withPdfPage(pageIndex) { page ->
    val a = find(page, index, box)
    // Ours keep their look and move in PDF space; a callout's line (/CL) moves with it (A3).
    if (isDrawn(a) || isForeignFreeText(a)) return@withPdfPage moveDrawn(page, a, dx, dy)
    when (a.type) {
        PDFAnnotation.TYPE_INK -> {
            val ink = a.inkList
            a.inkList = ink.map { s -> s.map { Point(it.x + dx, it.y + dy) }.toTypedArray() }.toTypedArray()
        }
        PDFAnnotation.TYPE_LINE -> {
            val l = a.line
            a.setLine(Point(l[0].x + dx, l[0].y + dy), Point(l[1].x + dx, l[1].y + dy))
        }
        else -> {
            val r = a.rect
            a.rect = Rect(r.x0 + dx, r.y0 + dy, r.x1 + dx, r.y1 + dy)
        }
    }
    a.modificationDate = Date()
    a.update()
    // Our own text boxes and stamps are drawn again as ours (MuPDF just redrew them its way).
    redrawOwn(a, page)
}

/** The words under a highlight, underline or strike-through, in reading order ("" for other marks). */
fun PdfDoc.markedText(pageIndex: Int, index: Int, box: Box): String {
    val quads = withPdfPage(pageIndex) { page ->
        runCatching { find(page, index, box).takeIf { it.hasQuadPoints() }?.quadPoints }.getOrNull()
    } ?: return ""
    val areas = quads.map { q ->
        Box(minOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x), minOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y), maxOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x), maxOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y))
    }
    val out = StringBuilder()
    for (line in lines(pageIndex)) {
        val inside = line.glyphs.filter { g -> areas.any { it.contains(g.cx, g.cy) } }
        val words = inside.joinToString("") { it.c }.trim()
        if (words.isEmpty()) continue
        if (out.isNotEmpty()) out.append(' ')
        out.append(words)
    }
    return out.toString()
}
