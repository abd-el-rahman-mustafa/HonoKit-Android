package com.aamus.powerpdf.engine

import android.content.Context
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.DisplayListDevice
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Story
import com.artifex.mupdf.fitz.TreeArchive

/**
 * Writing new content onto existing pages: watermarks, page numbers, added text, pictures
 * (signatures). docs/decisions.md §7.
 *
 * Text is laid out by MuPDF's HTML engine ("Story"), which shapes Arabic with HarfBuzz and orders
 * mixed-direction lines with its bidi code — MuPDF's plain showString cannot shape Arabic (desktop
 * spikes S4). Each stamp is drawn onto a small overlay PDF of its own, one page per target page in
 * the page's displayed frame; the overlay page is then placed on the real page as a form XObject
 * (desktop §22's placeOverlay). The overlay's fonts are subset before placing.
 */
object StampFonts {
    private var archive: TreeArchive? = null
    private var bytes: Map<String, ByteArray> = emptyMap()

    /** Loads the bundled fonts (IBM Plex Sans Arabic, Noto Sans; SIL OFL). Call once at start. */
    fun init(context: Context) {
        if (bytes.isNotEmpty()) return
        fun read(id: Int) = context.resources.openRawResource(id).use { it.readBytes() }
        bytes = mapOf(
            "plex-r.ttf" to read(R.font.plex_arabic_regular),
            "plex-b.ttf" to read(R.font.plex_arabic_bold),
            "noto-r.ttf" to read(R.font.noto_sans_regular),
            "noto-b.ttf" to read(R.font.noto_sans_bold),
        )
    }

    /** A bundled font file by its archive name ("noto-r.ttf"). */
    internal fun bytesOf(name: String): ByteArray {
        check(bytes.isNotEmpty()) { "StampFonts.init was not called." }
        return bytes.getValue(name)
    }

    /** Must be used on the engine thread. */
    internal fun archive(): TreeArchive {
        archive?.let { return it }
        check(bytes.isNotEmpty()) { "StampFonts.init was not called." }
        val a = TreeArchive()
        bytes.forEach { (name, data) ->
            val b = Buffer()
            b.writeBytes(data)
            a.add(name, b)
        }
        archive = a
        return a
    }

    const val CSS = """
@font-face { font-family: "Plex"; src: url(plex-r.ttf); }
@font-face { font-family: "Plex"; font-weight: bold; src: url(plex-b.ttf); }
@font-face { font-family: "Noto"; src: url(noto-r.ttf); }
@font-face { font-family: "Noto"; font-weight: bold; src: url(noto-b.ttf); }
body { margin: 0; padding: 0; }
p { margin: 0; padding: 0; font-family: "Noto"; }
.ar { font-family: "Plex"; }
"""
}

enum class HAlign { Left, Center, Right }
enum class VPos { Top, Middle, Bottom }

data class TextStyle(
    val sizePt: Float,
    val colour: Rgb,
    val bold: Boolean = false,
    val opacity: Float = 1f,
    val align: HAlign = HAlign.Left,
    val lineHeight: Float = 1.25f,
)

object StampText {
    private fun isArabicScript(cp: Int) = cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF

    private fun isStrong(cp: Int): Boolean {
        val d = Character.getDirectionality(cp)
        return d == Character.DIRECTIONALITY_LEFT_TO_RIGHT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    /** Right to left when the first letter with a direction is Arabic or Hebrew. */
    fun isRtl(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val d = Character.getDirectionality(cp)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false
            i += Character.charCount(cp)
        }
        return false
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * One paragraph per line of [text]. Arabic runs get the Arabic face; everything else the Latin
     * face. Digits and spaces stay with the run they are in (MuPDF's bidi orders them).
     */
    fun html(text: String, style: TextStyle): String {
        val colour = String.format("#%06x", style.colour.toArgb() and 0xFFFFFF)
        val weight = if (style.bold) "bold" else "normal"
        val out = StringBuilder()
        for (line in text.split('\n')) {
            val rtl = isRtl(line)
            // MuPDF mirrors text-align inside a right-to-left paragraph (seen on a phone: "right"
            // put Arabic lines on the left), so the visual side is asked for the other way round.
            val align = when (style.align) {
                HAlign.Left -> if (rtl) "right" else "left"
                HAlign.Center -> "center"
                HAlign.Right -> if (rtl) "left" else "right"
            }
            out.append("<p dir=\"").append(if (rtl) "rtl" else "ltr").append("\" style=\"font-size:")
                .append(style.sizePt).append("pt;line-height:").append(style.lineHeight)
                .append(";color:").append(colour).append(";font-weight:").append(weight)
                .append(";text-align:").append(align).append("\">")
            if (line.isEmpty()) out.append("&#160;")
            var i = 0
            var arabic: Boolean? = null
            val run = StringBuilder()
            fun flush() {
                if (run.isEmpty()) return
                if (arabic == true) out.append("<span class=\"ar\">").append(esc(run.toString())).append("</span>")
                else out.append(esc(run.toString()))
                run.setLength(0)
            }
            while (i < line.length) {
                val cp = line.codePointAt(i)
                val n = Character.charCount(cp)
                if (isStrong(cp) || isArabicScript(cp)) {
                    val a = isArabicScript(cp)
                    if (arabic != null && a != arabic) flush()
                    arabic = a
                }
                run.append(line, i, i + n)
                i += n
            }
            flush()
            out.append("</p>")
        }
        return out.toString()
    }
}

/**
 * A laid-out block of text: recorded into a display list, so its real ink box is known (MuPDF's
 * "filled" rectangle can report the whole box width for block elements) and it can be drawn
 * again at any position, scale or angle.
 */
internal class TextBlock(private val list: DisplayList, bounds: Rect) {
    val x0 = bounds.x0
    val y0 = bounds.y0
    val width = maxOf(0f, bounds.x1 - bounds.x0)
    val height = maxOf(0f, bounds.y1 - bounds.y0)
    val isEmpty get() = width <= 0f || height <= 0f

    /** Draws with the ink box's top-left corner at the origin of [m]. */
    fun draw(dev: com.artifex.mupdf.fitz.Device, m: Matrix) {
        list.run(dev, Matrix(Matrix.Translate(-x0, -y0), m), null)
    }

    fun destroy() = list.destroy()
}

internal fun layoutText(text: String, style: TextStyle, maxWidth: Float): TextBlock {
    val story = Story(StampText.html(text, style), StampFonts.CSS, 12f, StampFonts.archive())
    try {
        val where = Rect(0f, 0f, maxWidth, 100_000f)
        story.place(where, Rect())
        val list = DisplayList(where)
        val dev = DisplayListDevice(list)
        try {
            story.draw(dev, Matrix())
            dev.close()
        } finally {
            dev.destroy()
        }
        val ink = inkBox(list)
        android.util.Log.i("PowerPDF", "stamp layout: ink=$ink maxWidth=$maxWidth text=${text.take(40)}")
        return TextBlock(list, ink ?: Rect(0f, 0f, 0f, 0f))
    } finally {
        story.destroy()
    }
}

/**
 * The box the text actually occupies. A display list's own bounds are the box it was recorded
 * in (checked on a phone: a watermark measured that way shrank to nothing), so the characters'
 * boxes are united instead. Each character also counts from its baseline by its size: the bold
 * faces report almost flat character boxes (seen on a phone: stamps 1.5 pt tall for 18 pt type).
 */
private fun inkBox(list: DisplayList): Rect? {
    val stext = list.toStructuredText("preserve-whitespace")
    try {
        // Computed by hand: MuPDF's Rect.union gave an infinite box on a phone.
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        fun take(x: Float, y: Float) {
            if (!x.isFinite() || !y.isFinite() || kotlin.math.abs(x) > 1e6f || kotlin.math.abs(y) > 1e6f) return
            x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
        }
        stext.walk(object : com.artifex.mupdf.fitz.StructuredTextWalker {
            override fun onChar(c: Int, origin: com.artifex.mupdf.fitz.Point?, font: com.artifex.mupdf.fitz.Font?, size: Float, q: com.artifex.mupdf.fitz.Quad?, argb: Int, flags: Int, bidi: Int) {
                if (Character.isWhitespace(c)) return
                if (q != null) {
                    take(q.ul_x, q.ul_y); take(q.ur_x, q.ur_y); take(q.ll_x, q.ll_y); take(q.lr_x, q.lr_y)
                }
                if (origin != null && size > 0) {
                    take(origin.x, origin.y - size * 0.8f)
                    take(origin.x, origin.y + size * 0.22f)
                }
            }
            override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) {}
            override fun beginTextBlock(bbox: Rect?, flags: Int) {}
            override fun endTextBlock() {}
            override fun beginLine(bbox: Rect?, wmode: Int, dir: com.artifex.mupdf.fitz.Point?) {}
            override fun endLine() {}
            override fun beginStruct(standard: String?, raw: String?, index: Int) {}
            override fun endStruct() {}
            override fun onVector(bbox: Rect?, info: com.artifex.mupdf.fitz.StructuredTextWalker.VectorInfo?, argb: Int) {}
        })
        return if (x1 > x0 && y1 > y0) Rect(x0, y0, x1, y1) else null
    } finally {
        stext.destroy()
    }
}

/** A target page as the reader sees it. */
internal class Frame(val index: Int, val width: Float, val height: Float)

/** How the overlay for one page is drawn: device space is the page as displayed (points, y down). */
internal typealias OverlayPainter = (device: com.artifex.mupdf.fitz.Device, frame: Frame) -> Unit

/**
 * Builds an overlay (one page per frame), then places each overlay page on its target page.
 * [opacity] applies to the whole stamp.
 */
internal fun PdfDoc.stamp(pages: Collection<Int>, opacity: Float, actual: List<Pair<Int, String>>? = null, paint: OverlayPainter) {
    val targets = pages.toSortedSet().toList()
    if (targets.isEmpty()) return
    val frames = targets.map { i -> pageSize(i).let { Frame(i, it.width, it.height) } }
    val buffer = Buffer()
    val writer = DocumentWriter(buffer, "pdf", "")
    try {
        for (f in frames) {
            val dev = writer.beginPage(Rect(0f, 0f, f.width, f.height))
            try {
                paint(dev, f)
                writer.endPage()
            } finally {
                // The Java wrapper holds its own reference to the page's device. Left to the
                // garbage collector, it was released on another thread after the writer was
                // gone: the native crash after a few dozen edits (decisions §23).
                dev.destroy()
            }
        }
        writer.close()
    } finally {
        writer.destroy()
    }
    val bytes = buffer.asByteArray()
    if ("reopen" in EngineDebug.skip) {
        buffer.destroy()
        return
    }
    val overlay = PdfEngine.openedAsPdf(Document.openDocument(bytes, "application/pdf"))
        ?: throw PdfException(PdfException.Code.Unsupported, "The stamp could not be built.")
    android.util.Log.i("PowerPDF", "stamp overlay: ${bytes.size} bytes, ${overlay.countPages()} pages")
    try {
        if ("subset" !in EngineDebug.skip) runCatching { overlay.subsetFonts() }
        if ("place" !in EngineDebug.skip) placeOverlay(overlay, frames, opacity, actual)
    } finally {
        PdfEngine.dispose(overlay)
        buffer.destroy()
    }
}

internal fun contentOf(page: PDFObject): Buffer {
    val out = Buffer()
    val contents = page.get("Contents")
    if (contents.isArray) {
        for (i in 0 until contents.size()) {
            out.writeBytes(contents.get(i).readStream())
            out.writeByte('\n'.code.toByte())
        }
    } else if (contents.isStream) {
        out.writeBytes(contents.readStream())
    }
    return out
}

/**
 * Appends [item] to this array. Never PDFObject.push(PDFObject): in MuPDF 1.28.5 it releases a
 * reference it never took (platform/java/jni/pdfobject.c, PDFObject_pushPDFObject), so the item was
 * freed while the array still held it and memory was corrupted at a random later moment — the
 * native crashes after a few dozen edits (decisions §23). A placeholder is pushed and replaced;
 * put() keeps a reference of its own.
 */
internal fun PDFObject.append(item: PDFObject) {
    push(false)
    put(size() - 1, item)
}

/** Copies a dictionary (keys and values; values are shared references). */
private fun PDFDocument.copyDict(d: PDFObject): PDFObject {
    val out = newDictionary()
    if (d.isDictionary) for (key in d) out.put(key, d.get(key))
    return out
}

/** Page-local copies of the page's resources and of one sub-dictionary, with [name] → [value] added. */
internal fun PDFDocument.addResource(page: PDFObject, kind: String, prefix: String, value: PDFObject): String {
    // Resources are often shared by every page, or inherited: copy before extending.
    val resources = copyDict(page.getInheritable("Resources"))
    val sub = copyDict(resources.get(kind))
    var name = prefix
    var n = 1
    while (!sub.get(name).isNull) name = "$prefix${n++}"
    sub.put(name, value)
    resources.put(kind, sub)
    page.put("Resources", resources)
    return name
}

internal fun Matrix.pdf() = listOf(a, b, c, d, e, f).joinToString(" ") { String.format(java.util.Locale.US, "%.4f", it) }

/** The tag in the dictionary of our q/Q wrapper streams: /HonoWrap /Open or /Close. */
private const val WRAP = "HonoWrap"

/** The tag in the dictionary of each stream that draws one of our overlays (stamps, text, pictures). */
internal const val OVERLAY = "HonoOverlay"

private fun wrapTag(stream: PDFObject): String? = stream.get(WRAP).let { if (it.isName) it.asName() else null }

/**
 * The page's content streams, its own content wrapped in q/Q once, so what is drawn after it starts
 * from the default graphics state (desktop stamp.ts wrapContents, review items E4 and E5). The
 * wrapper streams are tagged; while the page's content is still inside them (only our overlays
 * after them), the next overlay is simply appended, so nesting does not grow with each stamp (E4:
 * thirty edits nested thirty deep, past what readers allow). The wrapper also balances the page's
 * own q/Q: extra q in front for stray Qs, extra Q after for unclosed qs, so a scale or clip the page
 * leaves open never reaches the overlay (E5).
 */
private fun PDFDocument.wrapContents(page: PDFObject): List<PDFObject> {
    val old = page.get("Contents")
    val list = ArrayList<PDFObject>()
    if (old.isArray) for (i in 0 until old.size()) list.add(old.get(i))
    else if (!old.isNull) list.add(old)
    if (list.isEmpty()) return list
    val tags = list.map { wrapTag(it) }
    val open = tags.indexOf("Open")
    val close = tags.lastIndexOf("Close")
    // Page content outside the wrapper (put in front of it or after it by another tool) means wrapping again.
    val outside = tags.indices.any { i -> tags[i] == null && !list[i].get(OVERLAY).isBoolean && (i < open || i > close) }
    if (open >= 0 && close > open && !outside) return list
    val text = StringBuilder()
    for (s in list) if (s.isStream) text.append(String(s.readStream(), Charsets.ISO_8859_1)).append('\n')
    val (low, end) = ContentFix.qDepth(text.toString())
    val opening = newDictionary()
    opening.put(WRAP, newName("Open"))
    val closing = newDictionary()
    closing.put(WRAP, newName("Close"))
    val before = addStream("q\n".repeat(1 - low), opening)
    val after = addStream("\n" + "Q\n".repeat(maxOf(0, 1 - low + end)), closing)
    // (A PDFObject is Iterable: "+ after" would add the keys of its dictionary.)
    return listOf(before) + list + listOf(after)
}

/**
 * Appends drawing to a page after its own content, which is wrapped once in balanced q/Q (see
 * [wrapContents]), so the drawing does not inherit a changed graphics state (a scale, a clip).
 * The stream added is tagged [OVERLAY] (and [tag], when given); it is returned.
 */
internal fun PDFDocument.appendContent(page: PDFObject, content: String, tag: String? = null): PDFObject {
    val dict = newDictionary()
    dict.put(OVERLAY, true)
    if (tag != null) dict.put(tag, true)
    val drawn = addStream("\n$content\n", dict)
    val contents = newArray()
    for (s in wrapContents(page)) contents.append(s)
    contents.append(drawn)
    page.put("Contents", contents)
    return drawn
}

/** The matrix taking the page as displayed (points, y down) to the page's own PDF space. */
internal fun PdfDoc.displayToUser(index: Int): Matrix = withPage(index) { page ->
    val t = (page as PDFPage).transform
    Matrix.Inverted(t)
}

private fun PdfDoc.opacityState(opacity: Float): PDFObject? {
    if (opacity >= 0.999f) return null
    val gs = pdf.newDictionary()
    gs.put("Type", pdf.newName("ExtGState"))
    gs.put("ca", opacity)
    gs.put("CA", opacity)
    return pdf.addObject(gs)
}

/** [actual]: each line's glyph count and text, written as ActualText around it (one-frame overlays). */
internal fun PdfDoc.placeOverlay(overlay: PDFDocument, frames: List<Frame>, opacity: Float, actual: List<Pair<Int, String>>? = null, tag: String? = null) {
    // Destroyed here, on the engine thread: a graft map holds both documents, and left to the
    // garbage collector it dropped them on another thread while this one was working — the
    // native crash found by the round-trip test after a few dozen edits (decisions §23).
    val graft = pdf.newPDFGraftMap()
    try {
        placeOverlay(overlay, frames, opacity, graft, actual?.takeIf { frames.size == 1 }, tag)
    } finally {
        graft.destroy()
    }
    invalidate()
}

private fun PdfDoc.placeOverlay(overlay: PDFDocument, frames: List<Frame>, opacity: Float, graft: com.artifex.mupdf.fitz.PDFGraftMap, actual: List<Pair<Int, String>>?, tag: String?) {
    frames.forEachIndexed { k, frame ->
        val source = overlay.findPage(k)
        val form = pdf.newDictionary()
        form.put("Type", pdf.newName("XObject"))
        form.put("Subtype", pdf.newName("Form"))
        form.put("BBox", Rect(0f, 0f, frame.width, frame.height))
        if (tag != null) form.put(tag, true)
        val resources = source.get("Resources")
        form.put("Resources", graft.graftObject(resources))
        var content = contentOf(source)
        // The real words of each line, for whoever reads the text (decisions §24).
        // Off (decisions §24): MuPDF matched line-long ActualText only partly against right-to-left
        // glyphs and the reading came back shuffled. Kept for a word-level attempt.
        if (actual != null && ActualText.enabled) {
            val wrapped = ActualText.wrap(String(content.asByteArray(), Charsets.ISO_8859_1), actual)
            android.util.Log.i("PowerPDF", "actual text: ${if (wrapped != null) "${actual.size} lines" else "did not match, left out"}")
            if (wrapped != null) {
                content.destroy()
                content = Buffer().apply { writeBytes(wrapped.toByteArray(Charsets.ISO_8859_1)) }
            }
        }
        if ("logcontent" in EngineDebug.skip) String(content.asByteArray(), Charsets.ISO_8859_1).chunked(900).take(6).forEach { android.util.Log.i("PowerPDF", "overlay content: $it") }
        val xobject = pdf.addStream(content, form)
        val contentLength = content.length
        content.destroy()
        // The overlay is destroyed next; its objects go with it, not later on another thread.
        resources.destroy()
        source.destroy()

        val page = pdf.findPage(frame.index)
        val name = pdf.addResource(page, "XObject", "HonoPDFStamp", xobject)
        val gsName = opacityState(opacity)?.let { pdf.addResource(page, "ExtGState", "HonoPDFGS", it) }
        // Overlay space (y up, the displayed frame) → displayed page (y down) → the page's own space.
        val m = Matrix(Matrix(1f, 0f, 0f, -1f, 0f, frame.height), displayToUser(frame.index))
        val gs = gsName?.let { "/$it gs " } ?: ""
        pdf.appendContent(page, "q $gs${m.pdf()} cm /$name Do Q", tag)
        android.util.Log.i("PowerPDF", "stamp placed on page ${frame.index}: $name, matrix ${m.pdf()}, form $contentLength bytes")
    }
}

/** Where a stamp sits on a page: the block's top-left corner in displayed points. */
private fun anchor(frame: Frame, w: Float, h: Float, v: VPos, a: HAlign, margin: Float): Pair<Float, Float> {
    val x = when (a) {
        HAlign.Left -> margin
        HAlign.Center -> (frame.width - w) / 2
        HAlign.Right -> frame.width - margin - w
    }
    val y = when (v) {
        VPos.Top -> margin
        VPos.Middle -> (frame.height - h) / 2
        VPos.Bottom -> frame.height - margin - h
    }
    return x to y
}

/** Needs the author's "edit" right (desktop §23). */
internal fun PdfDoc.requireEdit() {
    val r = rights()
    if (!r.edit) throw PdfException(PdfException.Code.Restricted, "The author does not allow changing the content.")
}

/**
 * A text watermark: each line centred on the anchor, turned by [rotation] degrees
 * counter-clockwise as seen on the page. Too long for the page at that angle: shrunk to fit.
 */
fun PdfDoc.addWatermark(pages: Collection<Int>, text: String, style: TextStyle, rotation: Float, v: VPos, a: HAlign) {
    requireEdit()
    if (text.isBlank()) throw PdfException(PdfException.Code.BadArguments, "A watermark needs some text.")
    val block = layoutText(text, style.copy(align = HAlign.Center), 100_000f)
    try {
        if (block.isEmpty) throw PdfException(PdfException.Code.Unsupported, "The watermark text could not be drawn.")
        stamp(pages, style.opacity) { dev, frame ->
            // Room along the angle inside a 24 pt margin: the chord through the page's middle.
            val rad = Math.toRadians(rotation.toDouble())
            val c = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
            val s = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
            val w = frame.width - 48
            val h = frame.height - 48
            val room = minOf(if (c > 1e-4f) w / c else Float.MAX_VALUE, if (s > 1e-4f) h / s else Float.MAX_VALUE)
            val k = if (block.width > room) room / block.width else 1f
            val bw = block.width * k
            val bh = block.height * k
            val (x, y) = when (v) {
                VPos.Middle -> (frame.width - bw) / 2 to (frame.height - bh) / 2
                else -> anchor(frame, bw, bh, v, a, 36f)
            }
            // Ink box → centred on the origin → scaled to fit → turned → onto the anchor.
            val m = Matrix(
                Matrix(Matrix(Matrix.Translate(-block.width / 2, -block.height / 2), Matrix.Scale(k)), Matrix.Rotate(-rotation)),
                Matrix.Translate(x + bw / 2, y + bh / 2),
            )
            block.draw(dev, m)
        }
    } finally {
        block.destroy()
    }
}

/**
 * A picture watermark (a logo, a "confidential" stamp image): centred on each page,
 * [widthFraction] of the page's width, turned by [rotation] degrees counter-clockwise, at [opacity].
 */
fun PdfDoc.addPictureWatermark(pages: Collection<Int>, imageBytes: ByteArray, widthFraction: Float, opacity: Float, rotation: Float) {
    requireEdit()
    val image = try {
        Image(imageBytes)
    } catch (e: Throwable) {
        throw PdfException(PdfException.Code.Unsupported, "That picture could not be read.")
    }
    try {
        val iw = image.width.toFloat().coerceAtLeast(1f)
        val ih = image.height.toFloat().coerceAtLeast(1f)
        stamp(pages, opacity) { dev, frame ->
            val w = frame.width * widthFraction
            val h = w * ih / iw
            // The image's unit square (y down) → centred on the origin → sized → turned → page centre.
            val m = Matrix(
                Matrix(Matrix(Matrix.Translate(-0.5f, -0.5f), Matrix.Scale(w, h)), Matrix.Rotate(-rotation)),
                Matrix.Translate(frame.width / 2, frame.height / 2),
            )
            dev.fillImage(image, m, 1f, 0)
        }
    } finally {
        image.destroy()
    }
}

object PageNumbers {
    /**
     * The label of the chosen page at [position] (0 = the first chosen page) of [count] chosen
     * pages: {n} is [startAt] + position, {N} the last number shown in the range (N1: "Start at 1"
     * on pages 3–10 shows 1 on page 3).
     */
    fun label(template: String, startAt: Int, position: Int, count: Int, arabicDigits: Boolean): String {
        fun digits(n: Int) = if (arabicDigits) TextLogic.toArabicIndic(n.toString()) else n.toString()
        return template.replace("{n}", digits(startAt + position)).replace("{N}", digits(startAt + count - 1))
    }
}

/**
 * Page numbers from a template ({n} = number, {N} = last number), optionally in Arabic-Indic
 * digits. Counted within [pages]: the first chosen page shows [startAt] (N1).
 */
fun PdfDoc.addPageNumbers(pages: Collection<Int>, template: String, startAt: Int, style: TextStyle, v: VPos, a: HAlign, margin: Float, arabicDigits: Boolean) {
    requireEdit()
    // N1 (owner's decision, as Acrobat): numbers count through the chosen pages, from the first one.
    val chosen = pages.toSortedSet().toList()
    stamp(pages, style.opacity) { dev, frame ->
        val label = PageNumbers.label(template, startAt, chosen.indexOf(frame.index), chosen.size, arabicDigits)
        val block = layoutText(label, style.copy(align = HAlign.Left), frame.width - 2 * margin)
        try {
            val (x, y) = anchor(frame, block.width, block.height, v, a, margin)
            block.draw(dev, Matrix.Translate(x, y))
        } finally {
            block.destroy()
        }
    }
}

/**
 * Text typed by the person at a point. [x], [y] is the top of the first line at its start edge:
 * the left edge for left-to-right text, the right edge for right-to-left text. Lines wrap at
 * [maxWidth]. Returns the box it occupies.
 */
fun PdfDoc.addText(pageIndex: Int, x: Float, y: Float, maxWidth: Float, text: String, style: TextStyle): Box {
    requireEdit()
    if (text.isBlank()) throw PdfException(PdfException.Code.BadArguments, "No text.")
    val rtl = StampText.isRtl(text)
    val aligned = style.copy(align = if (rtl) HAlign.Right else HAlign.Left)
    var box = Box(0f, 0f, 0f, 0f)
    val block = layoutText(text, aligned, maxWidth)
    try {
        if (block.isEmpty) throw PdfException(PdfException.Code.Unsupported, "The text could not be drawn.")
        stamp(listOf(pageIndex), style.opacity) { dev, _ ->
            val left = if (rtl) x - block.width else x
            block.draw(dev, Matrix.Translate(left, y))
            box = Box(left, y, left + block.width, y + block.height)
        }
    } finally {
        block.destroy()
    }
    return box
}

/** Measures text as [addText] would lay it out (for the on-screen box). */
fun PdfDoc.measureText(text: String, style: TextStyle, maxWidth: Float): Size {
    val b = layoutText(text, style, maxWidth)
    val s = Size(b.width, b.height)
    b.destroy()
    return s
}

/**
 * Puts a picture (a signature: a PNG with a transparent background) on a page, upright to the
 * reader, in the box [x0],[y0]–[x1],[y1] (displayed points). Page content, as desktop §31.
 */
fun PdfDoc.addPicture(pageIndex: Int, imageBytes: ByteArray, x0: Float, y0: Float, x1: Float, y1: Float) {
    requireEdit()
    val image = Image(imageBytes)
    try {
        val ref = pdf.addImage(image)
        val page = pdf.findPage(pageIndex)
        val name = pdf.addResource(page, "XObject", "HonoPDFImage", ref)
        // Image unit square (y up) → the box on the displayed page → the page's own space.
        val d = Matrix(x1 - x0, 0f, 0f, -(y1 - y0), x0, y1)
        val m = Matrix(d, displayToUser(pageIndex))
        pdf.appendContent(page, "q ${m.pdf()} cm /$name Do Q")
    } finally {
        image.destroy()
    }
    invalidate()
}

/** Draws an image object of this document into [box] (displayed points) on a page, as page content. */
internal fun PdfDoc.placeImageObject(pageIndex: Int, ref: PDFObject, box: Box) =
    // Image unit square (y up) → the box on the displayed page.
    placeImageObject(pageIndex, ref, Matrix(box.x1 - box.x0, 0f, 0f, -(box.y1 - box.y0), box.x0, box.y1))

/** Draws an image object with [d]: its unit square (y up) → the displayed page; then into the page's own space. */
internal fun PdfDoc.placeImageObject(pageIndex: Int, ref: PDFObject, d: Matrix) {
    val page = pdf.findPage(pageIndex)
    val name = pdf.addResource(page, "XObject", "HonoPDFImage", ref)
    val m = Matrix(d, displayToUser(pageIndex))
    pdf.appendContent(page, "q ${m.pdf()} cm /$name Do Q")
    invalidate()
}
