package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StrokeState
import java.util.Date

/**
 * Text boxes and stamps (desktop decisions §44): comment annotations (FreeText, Stamp) whose look
 * is drawn by us, so Arabic is shaped (MuPDF's own drawing of them cannot shape it). What is needed
 * to draw them again is kept in the annotation (its text, colour, and HonoPDF keys), because MuPDF
 * redraws a comment in its own way whenever it is moved or recoloured: [redrawOwn] runs after.
 */
private const val KEY = "HonoPDF"

/**
 * Sets [form] as [a]'s look. The form is drawn upright as the page is shown; on a page with /Rotate
 * it is turned back by the same angle, so it maps onto the annotation's unrotated /Rect without
 * stretching (review finding 2026-10-01).
 */
private fun PdfDoc.setLook(a: PDFAnnotation, form: com.artifex.mupdf.fitz.PDFObject, o: com.artifex.mupdf.fitz.PDFObject = a.getObject()) {
    val page = o.get("P")
    val r = if (page.isDictionary) rotationOf(page) else 0
    if (r != 0) {
        val rad = Math.toRadians(r.toDouble())
        val cos = kotlin.math.round(kotlin.math.cos(rad)).toFloat()
        val sin = kotlin.math.round(kotlin.math.sin(rad)).toFloat()
        form.put("Matrix", Matrix(cos, sin, -sin, cos, 0f, 0f))
    }
    val ap = pdf.newDictionary()
    ap.put("N", form)
    o.put("AP", ap)
}
private const val KEY_SIZE = "HonoPDFSize"
private const val KEY_SUB = "HonoPDFSub"
/** The size we gave it (displayed points): MuPDF's update forces its own shape on a stamp's rect. */
private const val KEY_W = "HonoPDFW"
private const val KEY_H = "HonoPDFH"

/** A text box or callout from another program, taken over when changed here (A3): its frame width. */
private const val KEY_FRAME = "HonoPDFFrame"
/** Marks a callout we draw (its box is /Rect less /RD, its line /CL). */
private const val KEY_CALLOUT = "HonoPDFCallout"

/**
 * The rect goes back to the size we chose, centred where it shows now. (MuPDF gives a stamp its
 * own height-to-width ratio, about 0.26: seen on a phone, stamps were squeezed and their words
 * spilled out.) The size is the upright one: a stamp revised after its page was turned is drawn
 * again upright where it shows (desktop A2), not lying on its side.
 */
private fun keepSize(a: PDFAnnotation) {
    val o = a.getObject()
    val w = o.get(KEY_W).let { if (it.isNumber) it.asFloat() else return }
    val h = o.get(KEY_H).let { if (it.isNumber) it.asFloat() else return }
    val r = a.rect
    val cx = (r.x0 + r.x1) / 2
    val cy = (r.y0 + r.y1) / 2
    a.rect = Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
}

private fun roundRect(x0: Float, y0: Float, x1: Float, y1: Float, r: Float) = Path().apply {
    val k = r * 0.4477f
    moveTo(x0 + r, y0)
    lineTo(x1 - r, y0)
    curveTo(x1 - k, y0, x1, y0 + k, x1, y0 + r)
    lineTo(x1, y1 - r)
    curveTo(x1, y1 - k, x1 - k, y1, x1 - r, y1)
    lineTo(x0 + r, y1)
    curveTo(x0 + k, y1, x0, y1 - k, x0, y1 - r)
    lineTo(x0, y0 + r)
    curveTo(x0, y0 + k, x0 + k, y0, x0 + r, y0)
    closePath()
}

/** Size of a text box's content: the text laid out at [size], wrapped at [maxWidth]. */
private fun textBoxLayout(text: String, colour: Rgb, size: Float, maxWidth: Float): TextBlock {
    val rtl = StampText.isRtl(text)
    return layoutText(text, TextStyle(size, colour, align = if (rtl) HAlign.Right else HAlign.Left, lineHeight = 1.25f), maxWidth)
}

private const val BOX_PAD = 4f

/** Draws a text box's appearance into [a], whose rect is already set. */
private fun PdfDoc.drawTextBox(a: PDFAnnotation, text: String, colour: Rgb, size: Float, o: com.artifex.mupdf.fitz.PDFObject = a.getObject()) {
    val r = a.rect
    val w = r.x1 - r.x0
    val h = r.y1 - r.y0
    val block = textBoxLayout(text, colour, size, w - 2 * BOX_PAD)
    val frame = o.get(KEY_FRAME).let { if (it.isNumber) it.asFloat() else 0f }
    try {
        val xo = drawForm(w, h) { dev ->
            if (frame > 0f) dev.strokePath(roundRect(frame / 2, frame / 2, w - frame / 2, h - frame / 2, 0f), StrokeState(StrokeState.LINE_CAP_BUTT, StrokeState.LINE_JOIN_MITER, frame, 10f), Matrix(), ColorSpace.DeviceRGB, colour.toArray(), 1f, 0)
            if (!block.isEmpty) {
                val x = if (StampText.isRtl(text)) w - BOX_PAD - block.width else BOX_PAD
                block.draw(dev, Matrix.Translate(x, BOX_PAD))
            }
        }
        setLook(a, xo, o)
    } finally {
        block.destroy()
    }
}

/**
 * A text box (FreeText) at ([x], [y]): the top-left corner for left-to-right text, the top-right
 * for right-to-left. Lines wrap at [maxWidth]. Returns its box.
 */
fun PdfDoc.addTextBox(pageIndex: Int, x: Float, y: Float, text: String, colour: Rgb, size: Float, maxWidth: Float, author: String): Box {
    if (!rights().annotate) throw PdfException(PdfException.Code.Restricted, "The author does not allow adding comments.")
    if (text.isBlank()) throw PdfException(PdfException.Code.BadArguments, "No text.")
    val block = textBoxLayout(text, colour, size, maxWidth)
    val w = block.width + 2 * BOX_PAD + 1
    val h = block.height + 2 * BOX_PAD + 1
    block.destroy()
    // Kept on the page: a right-to-left box grows leftwards from where it was placed.
    val pageW = pageSize(pageIndex).width
    val left = (if (StampText.isRtl(text)) x - w else x).coerceIn(0f, maxOf(0f, pageW - w))
    var box = Box(0f, 0f, 0f, 0f)
    withPdfPage(pageIndex) { page ->
        val a = page.newAnnot(PDFAnnotation.TYPE_FREE_TEXT)
        a.rect = Rect(left, y, left + w, y + h)
        a.contents = text
        a.color = colour.toArray()
        a.author = author.ifBlank { "HonoPDF" }
        a.creationDate = Date()
        a.modificationDate = Date()
        a.getObject().put(KEY, true)
        a.getObject().put(KEY_SIZE, size)
        a.getObject().put(KEY_W, w)
        a.getObject().put(KEY_H, h)
        a.update()
        a.rect = Rect(left, y, left + w, y + h)
        drawTextBox(a, text, colour, size)
        box = Box(left, y, left + w, y + h)
    }
    invalidate(listOf(pageIndex))
    return box
}

/** Draws a stamp's appearance into [a]: a rounded frame, the title in bold, the smaller line under it. */
private fun PdfDoc.drawStamp(a: PDFAnnotation, title: String, sub: String?, colour: Rgb, o: com.artifex.mupdf.fitz.PDFObject = a.getObject()) {
    val r = a.rect
    val w = r.x1 - r.x0
    val h = r.y1 - r.y0
    val main = layoutText(title, TextStyle(STAMP_SIZE, colour, bold = true, align = HAlign.Center), 100_000f)
    val small = sub?.takeIf { it.isNotBlank() }?.let { layoutText(it, TextStyle(STAMP_SIZE * 0.45f, colour, align = HAlign.Center), 100_000f) }
    try {
        val xo = drawForm(w, h) { dev ->
            dev.strokePath(roundRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, 6f), StrokeState(StrokeState.LINE_CAP_ROUND, StrokeState.LINE_JOIN_ROUND, 2.5f, 10f), Matrix(), ColorSpace.DeviceRGB, colour.toArray(), 1f, 0)
            val gap = 3f
            val total = main.height + (small?.let { it.height + gap } ?: 0f)
            var top = (h - total) / 2
            main.draw(dev, Matrix.Translate((w - main.width) / 2, top))
            top += main.height + gap
            small?.draw(dev, Matrix.Translate((w - small.width) / 2, top))
        }
        setLook(a, xo, o)
    } finally {
        main.destroy()
        small?.destroy()
    }
}

private const val STAMP_SIZE = 18f

/** A stamp ("Approved", «معتمد») centred at ([cx], [cy]), with an optional smaller line (the date). */
fun PdfDoc.addStamp(pageIndex: Int, cx: Float, cy: Float, title: String, sub: String?, colour: Rgb, author: String): Box {
    if (!rights().annotate) throw PdfException(PdfException.Code.Restricted, "The author does not allow adding comments.")
    if (title.isBlank()) throw PdfException(PdfException.Code.BadArguments, "A stamp needs a word.")
    val main = layoutText(title, TextStyle(STAMP_SIZE, colour, bold = true), 100_000f)
    val small = sub?.takeIf { it.isNotBlank() }?.let { layoutText(it, TextStyle(STAMP_SIZE * 0.45f, colour), 100_000f) }
    val w = maxOf(main.width, small?.width ?: 0f) + 28f
    val h = main.height + (small?.let { it.height + 3f } ?: 0f) + 18f
    main.destroy()
    small?.destroy()
    val size = pageSize(pageIndex)
    val x0 = (cx - w / 2).coerceIn(0f, maxOf(0f, size.width - w))
    val y0 = (cy - h / 2).coerceIn(0f, maxOf(0f, size.height - h))
    withPdfPage(pageIndex) { page ->
        val a = page.newAnnot(PDFAnnotation.TYPE_STAMP)
        a.rect = Rect(x0, y0, x0 + w, y0 + h)
        a.contents = title
        a.color = colour.toArray()
        a.author = author.ifBlank { "HonoPDF" }
        a.creationDate = Date()
        a.modificationDate = Date()
        a.getObject().put(KEY, true)
        if (sub != null) a.getObject().put(KEY_SUB, pdf.newString(sub))
        a.getObject().put(KEY_W, w)
        a.getObject().put(KEY_H, h)
        a.update()
        a.rect = Rect(x0, y0, x0 + w, y0 + h)
        drawStamp(a, title, sub, colour)
    }
    invalidate(listOf(pageIndex))
    return Box(x0, y0, x0 + w, y0 + h)
}

/**
 * After MuPDF redrew one of our text boxes or stamps in its own way, or after its text or colour
 * changed, draws it again as ours. [page] is the page it is on (its turn, and its own reference to
 * the annotation); without it the annotation's /P is looked up.
 */
internal fun PdfDoc.redrawOwn(a: PDFAnnotation, page: PDFPage? = null) {
    val o = page?.let { annotEntry(it, a.getObject()) } ?: a.getObject()
    if (!o.get(KEY).isBoolean) return
    val c = numbers(o.get("C"), 3)
    val colour = if (c != null) Rgb(c[0], c[1], c[2]) else runCatching { a.color }.getOrNull()?.takeIf { it.size >= 3 }?.let { Rgb(it[0], it[1], it[2]) } ?: Rgb(0f, 0f, 0f)
    val text = o.get("Contents").let { if (it.isString) it.asString() else "" }
    val size = o.get(KEY_SIZE).let { if (it.isNumber) it.asFloat() else 12f }
    if (o.get(KEY_CALLOUT).isBoolean) {
        drawCallout(a, o, page?.shownTransform() ?: transformOf(o), text, colour, size)
        return
    }
    keepSize(a)
    when (a.type) {
        PDFAnnotation.TYPE_FREE_TEXT -> drawTextBox(a, text, colour, size, o)
        PDFAnnotation.TYPE_STAMP -> drawStamp(a, text, o.get(KEY_SUB).let { if (it.isString) it.asString() else null }, colour, o)
    }
}

/** Whether we draw [a]'s look ourselves (our stamps and text boxes, and text boxes taken over). */
internal fun isDrawn(a: PDFAnnotation): Boolean = a.getObject().get(KEY).isBoolean

/** A text box or callout from another program (Acrobat): MuPDF's own drawing of it blanks Arabic. */
internal fun isForeignFreeText(a: PDFAnnotation): Boolean = a.type == PDFAnnotation.TYPE_FREE_TEXT && !isDrawn(a)

private fun numbers(o: PDFObject, n: Int): FloatArray? =
    if (o.isArray && o.size() == n) FloatArray(n) { o.get(it).asFloat() } else null

private fun PdfDoc.floats(vararg v: Float) = pdf.newArray().also { arr -> v.forEach { arr.push(it) } }

/** The transform of the page an annotation is on (by its /P), for callers without the page. */
private fun PdfDoc.transformOf(o: PDFObject): FloatArray {
    for (i in 0 until pageCount) {
        val found = withPdfPage(i) { p -> if (numOf(annotEntry(p, o)) > 0) p.shownTransform() else null }
        if (found != null) return found
    }
    return floatArrayOf(1f, 0f, 0f, -1f, 0f, 0f)
}

/**
 * A text box or callout made by another program, changed here (desktop A3, adoptFreeText): it
 * becomes one of ours — the same box, text size and colour, frame, and a callout's line (/CL) —
 * and is drawn by us, so Arabic is shaped. Rich text (/RC) and its style (/DS) go: other readers
 * would show the old words.
 */
private fun PdfDoc.adoptFreeText(o: PDFObject, t: FloatArray) {
    val r = numbers(o.get("Rect"), 4) ?: throw PdfException(PdfException.Code.Unsupported, "This text box cannot be changed.")
    val rd = numbers(o.get("RD"), 4) ?: floatArrayOf(0f, 0f, 0f, 0f)
    val da = o.get("DA").let { if (it.isString) it.asString() else "" }
    val size = DA_SIZE.find(da)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it > 0f } ?: 12f
    val textColour = DA_RGB.find(da)?.let { m -> Rgb(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat()) }
        ?: DA_GREY.find(da)?.let { m -> m.groupValues[1].toFloat().let { g -> Rgb(g, g, g) } }
    // The frame: /BS /W, else /Border's width, else 1 (the default) — none for typewriter text.
    val bs = o.get("BS")
    val border = o.get("Border")
    val intent = o.get("IT").let { if (it.isName) it.asName() else "" }
    val frame = when {
        bs.isDictionary && bs.get("W").isNumber -> bs.get("W").asFloat()
        border.isArray && border.size() >= 3 -> border.get(2).asFloat()
        intent == "FreeTextTypeWriter" -> 0f
        else -> 1f
    }
    o.put(KEY, true)
    o.put(KEY_SIZE, size)
    o.put(KEY_FRAME, frame)
    o.delete("RC")
    o.delete("DS")
    // Our drawing takes its colour from /C; the text's colour was in /DA.
    if (textColour != null) o.put("C", floats(textColour.r, textColour.g, textColour.b))
    val cl = numbers(o.get("CL"), 6) ?: numbers(o.get("CL"), 4)
    if (intent == "FreeTextCallout" && cl != null) {
        o.put(KEY_CALLOUT, true)
        return
    }
    val inner = floatArrayOf(minOf(r[0], r[2]) + rd[0], minOf(r[1], r[3]) + rd[3], maxOf(r[0], r[2]) - rd[2], maxOf(r[1], r[3]) - rd[1])
    o.put("Rect", floats(*inner))
    o.delete("RD")
    o.delete("CL")
    val shown = Upright.shownBounds(t, inner)
    o.put(KEY_W, shown[2] - shown[0])
    o.put(KEY_H, shown[3] - shown[1])
}

/**
 * Draws a callout (a taken-over one, A3): its text in the box (/Rect less /RD), the line /CL from
 * the box to the tip with an arrow there, upright as the page is shown. /Rect grows to hold the
 * line; /RD keeps the box.
 */
private fun PdfDoc.drawCallout(a: PDFAnnotation, o: PDFObject, t: FloatArray, text: String, colour: Rgb, size: Float) {
    val r = numbers(o.get("Rect"), 4) ?: return
    val rd = numbers(o.get("RD"), 4) ?: floatArrayOf(0f, 0f, 0f, 0f)
    val cl = numbers(o.get("CL"), 6) ?: numbers(o.get("CL"), 4) ?: return
    val innerPdf = floatArrayOf(minOf(r[0], r[2]) + rd[0], minOf(r[1], r[3]) + rd[3], maxOf(r[0], r[2]) - rd[2], maxOf(r[1], r[3]) - rd[1])
    val box = Upright.shownBounds(t, innerPdf)
    val line = (0 until cl.size / 2).map { Upright.toShown(t, cl[2 * it], cl[2 * it + 1]) }
    val m = 4f
    val ux0 = minOf(box[0], line.minOf { it.first } - m)
    val uy0 = minOf(box[1], line.minOf { it.second } - m)
    val ux1 = maxOf(box[2], line.maxOf { it.first } + m)
    val uy1 = maxOf(box[3], line.maxOf { it.second } + m)
    val w = ux1 - ux0
    val h = uy1 - uy0
    val bw = box[2] - box[0]
    val bh = box[3] - box[1]
    val frame = o.get(KEY_FRAME).let { if (it.isNumber) it.asFloat() else 1f }
    val block = textBoxLayout(text, colour, size, bw - 2 * BOX_PAD)
    val ends = o.get("LE")
    val arrow = ends.isArray && ends.size() > 0 && ends.get(0).isName && ends.get(0).asName() != "None"
    try {
        val xo = drawForm(w, h) { dev ->
            val bx = box[0] - ux0
            val by = box[1] - uy0
            val ink = StrokeState(StrokeState.LINE_CAP_ROUND, StrokeState.LINE_JOIN_ROUND, maxOf(frame, 1f), 10f)
            if (frame > 0f) dev.strokePath(roundRect(bx + frame / 2, by + frame / 2, bx + bw - frame / 2, by + bh - frame / 2, 0f), ink, Matrix(), ColorSpace.DeviceRGB, colour.toArray(), 1f, 0)
            val path = Path()
            line.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.first - ux0, p.second - uy0) else path.lineTo(p.first - ux0, p.second - uy0) }
            dev.strokePath(path, ink, Matrix(), ColorSpace.DeviceRGB, colour.toArray(), 1f, 0)
            if (arrow && line.size >= 2) {
                val tx = line[0].first - ux0
                val ty = line[0].second - uy0
                val nx = line[1].first - ux0
                val ny = line[1].second - uy0
                val len = kotlin.math.hypot(nx - tx, ny - ty).takeIf { it > 0.01f } ?: 1f
                val ux = (nx - tx) / len
                val uy = (ny - ty) / len
                val head = Path()
                head.moveTo(tx + 8 * ux - 4 * uy, ty + 8 * uy + 4 * ux)
                head.lineTo(tx, ty)
                head.lineTo(tx + 8 * ux + 4 * uy, ty + 8 * uy - 4 * ux)
                dev.strokePath(head, ink, Matrix(), ColorSpace.DeviceRGB, colour.toArray(), 1f, 0)
            }
            if (!block.isEmpty) {
                val x = if (StampText.isRtl(text)) bx + bw - BOX_PAD - block.width else bx + BOX_PAD
                block.draw(dev, Matrix.Translate(x, by + BOX_PAD))
            }
        }
        setLook(a, xo, o)
    } finally {
        block.destroy()
    }
    val all = Upright.pdfBounds(t, floatArrayOf(ux0, uy0, ux1, uy1))
    o.put("Rect", floats(*all))
    o.put("RD", floats(innerPdf[0] - all[0], all[3] - innerPdf[3], all[2] - innerPdf[2], innerPdf[1] - all[1]))
}

/**
 * Changes the text or colour of a stamp or text box we draw (or another program's text box or
 * callout, taken over first, A3) without letting MuPDF draw it again its own way: the values are
 * written into the annotation and the look drawn by us, upright where it shows now (A2).
 */
internal fun PdfDoc.reviseDrawn(page: PDFPage, a: PDFAnnotation, text: String?, colour: Rgb?) {
    val o = annotEntry(page, a.getObject()) ?: a.getObject()
    if (isForeignFreeText(a)) adoptFreeText(o, page.shownTransform())
    if (text != null) {
        o.put("Contents", pdf.newString(text))
        o.delete("RC")
    }
    if (colour != null) o.put("C", floats(colour.r, colour.g, colour.b))
    o.put("M", pdf.newString(pdfDate()))
    redrawOwn(a, page)
}

/**
 * Moves a stamp, text box or callout (ours or another program's) by (dx, dy) as shown: /Rect and
 * /CL shift in PDF space and the look stays as it is (MuPDF's setter would draw it its own way).
 */
internal fun PdfDoc.moveDrawn(page: PDFPage, a: PDFAnnotation, dx: Float, dy: Float) {
    val o = annotEntry(page, a.getObject()) ?: a.getObject()
    val (px, py) = Upright.moveToPdf(page.shownTransform(), dx, dy)
    numbers(o.get("Rect"), 4)?.let { r -> o.put("Rect", floats(r[0] + px, r[1] + py, r[2] + px, r[3] + py)) }
    val cl = o.get("CL")
    if (cl.isArray && cl.size() >= 4) o.put("CL", floats(*FloatArray(cl.size()) { cl.get(it).asFloat() + if (it % 2 == 0) px else py }))
    o.put("M", pdf.newString(pdfDate()))
}

/** A PDF date for now (UTC), written directly so MuPDF does not mark the annotation for a drawing of its own. */
internal fun pdfDate(now: Date = Date()): String =
    java.text.SimpleDateFormat("'D:'yyyyMMddHHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(now)
