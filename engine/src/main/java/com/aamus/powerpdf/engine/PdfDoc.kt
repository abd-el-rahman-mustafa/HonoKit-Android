package com.aamus.powerpdf.engine

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * The only way the app reaches MuPDF (AGPL). No MuPDF type appears in a public signature of this
 * module, so replacing the engine touches one folder (desktop decisions §4).
 *
 * A document is not safe to use from two threads at once. Every call goes through [PdfEngine.run],
 * which runs it on the one engine thread.
 */
/** Debug builds: steps of an edit to leave out while hunting a fault (docs/testing.md, roundtrip). */
object EngineDebug {
    @Volatile
    var skip: Set<String> = emptySet()
}

object PdfEngine {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-engine").apply { priority = Thread.NORM_PRIORITY + 1 } }
    val dispatcher = thread.asCoroutineDispatcher()

    suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }

    /** Opens a PDF file. Throws [PdfException] a person can act on. */
    fun open(file: File, password: String? = null): PdfDoc {
        val doc = try {
            Document.openDocument(file.absolutePath)
        } catch (e: Throwable) {
            throw PdfException(PdfException.Code.Corrupt, "This file could not be opened as a PDF: ${e.message}")
        }
        val pdf = openedAsPdf(doc) ?: throw PdfException(PdfException.Code.NotPdf, "This is not a PDF document.")
        return PdfDoc.adopt(pdf, file, password)
    }

    /**
     * The PDF behind [doc], and [doc] released. asPDF() hands back a second wrapper with its own
     * reference; the first one, left to the garbage collector, freed the whole document later on
     * another thread while this one was working — native crashes after a few dozen edits, found
     * by the round-trip test (decisions §23). Null (and [doc] released) when it is not a PDF.
     */
    internal fun openedAsPdf(doc: Document): PDFDocument? = try {
        doc.asPDF()
    } finally {
        doc.destroy()
    }

    /**
     * Lets the garbage collector release the MuPDF objects a piece of work left behind, now, while
     * this thread waits (decisions §23). Released later on the collector's own threads while this
     * one was working, they crashed the app natively after a few dozen edits; collected after each
     * change, they never did in the round-trip test. Call on the engine thread after a change.
     */
    fun settle() {
        Runtime.getRuntime().gc()
        System.runFinalization()
    }

    /**
     * Destroys a document only after the garbage collector has released, on this thread, the
     * Java wrappers of its objects (decisions §23). Every change reopens the document; a wrapper
     * finalized later, after its document was gone, crashed the app natively.
     */
    internal fun dispose(pdf: PDFDocument) {
        settle()
        pdf.destroy()
    }

    /** A new, empty PDF (no pages) to build into. */
    fun create(): PdfDoc = PdfDoc.adopt(PDFDocument(), null, null, fresh = true)
}

class PdfDoc private constructor(
    internal val pdf: PDFDocument,
    /** The file the document was opened from (the session's working copy), if any. */
    val file: File?,
    /** The password it was opened with; saving keeps the file's encryption. */
    val password: String?,
    /** Whether a password was needed to open it at all (asked once, before authenticating). */
    val needsOpenPassword: Boolean,
) : Closeable {

    companion object {
        internal fun adopt(pdf: PDFDocument, file: File?, password: String?, fresh: Boolean = false): PdfDoc {
            if (fresh) return PdfDoc(pdf, file, null, false)
            val given = !password.isNullOrEmpty()
            // Asked once, before authenticating: MuPDF answers needsPassword() by trying the empty
            // password, which silently drops rights a permissions password granted (desktop §24).
            val needs = pdf.needsPassword()
            if (needs) {
                if (!given) {
                    pdf.destroy()
                    throw PdfException(PdfException.Code.PasswordRequired, "This PDF is protected by a password.")
                }
                if (!Passwords.authenticate(password!!) { pdf.authenticatePassword(it) }) {
                    pdf.destroy()
                    throw PdfException(PdfException.Code.WrongPassword, "That password is not correct.")
                }
            } else if (given && isEncrypted(pdf) && !Passwords.authenticate(password!!) { pdf.authenticatePassword(it) }) {
                pdf.destroy()
                throw PdfException(PdfException.Code.WrongPassword, "That password is not correct.")
            }
            // MuPDF "repairs" almost anything, including a text file, into an empty document.
            if (pdf.countPages() == 0) {
                pdf.destroy()
                throw PdfException(PdfException.Code.Corrupt, "This file has no readable pages.")
            }
            return PdfDoc(pdf, file, password, needs)
        }

        internal fun isEncrypted(pdf: PDFDocument): Boolean = try {
            pdf.trailer.get("Encrypt").isDictionary
        } catch (_: Throwable) {
            false
        }
    }

    private var closed = false
    private val displayLists = object : LinkedHashMap<Int, DisplayList>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, DisplayList>?): Boolean {
            if (size > 6) {
                eldest?.value?.destroy()
                return true
            }
            return false
        }
    }
    private val textCache = object : LinkedHashMap<Int, List<TextLine>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<TextLine>>?) = size > 40
    }
    private val fixCache = object : LinkedHashMap<Int, TextFixes>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, TextFixes>?) = size > 40
    }

    /** Glyphs of page [index] whose text the file got wrong (Ligatures.kt), found once per version. */
    internal fun textFixes(index: Int, page: com.artifex.mupdf.fitz.Page): TextFixes =
        fixCache[index] ?: runCatching { findTextFixes(page) }
            .onFailure { android.util.Log.w("PowerPDF", "ligature check failed on page ${index + 1}", it) }
            .getOrDefault(TextFixes.NONE)
            .also { fixCache[index] = it }

    val pageCount: Int get() = pdf.countPages()

    val isEncrypted: Boolean get() = isEncrypted(pdf)

    /**
     * What the author allows. Read from the file's own permission bits (/P), because MuPDF's
     * Android binding answered "no" for assemble and forms on a file that allows everything
     * (P = -4; checked on a phone, mupdf.js said yes). MuPDF's answer still counts: when it allows
     * something the bits forbid, the permissions (owner) password was given, and everything is
     * allowed.
     */
    fun rights(): Rights {
        fun has(p: Int) = runCatching { pdf.hasPermission(p) }.getOrDefault(false)
        val encrypt = runCatching { pdf.trailer.get("Encrypt") }.getOrNull()
        if (encrypt == null || !encrypt.isDictionary) return Rights(false, true, true, true, true, true, true, true)
        val p = encrypt.get("P").let { if (it.isNumber) it.asInteger() else -1 }
        val revision = encrypt.get("R").let { if (it.isNumber) it.asInteger() else 4 }
        fun bit(b: Int) = (p and (1 shl (b - 1))) != 0
        // ISO 32000 table 22 (bit numbers from 1). Before revision 3, assembling and forms follow
        // "modify" and "annotate".
        val print = bit(3)
        val edit = bit(4)
        val copy = bit(5)
        val annotate = bit(6)
        val forms = if (revision >= 3) bit(9) else annotate
        val assemble = if (revision >= 3) bit(11) else edit
        val owner = (has(Document.PERMISSION_EDIT) && !edit) || (has(Document.PERMISSION_COPY) && !copy) ||
            (has(Document.PERMISSION_PRINT) && !print) || (has(Document.PERMISSION_ANNOTATE) && !annotate)
        if (owner) return Rights(true, true, true, true, true, true, true, true)
        val r = Rights(true, false, edit, assemble, annotate, forms, copy, print)
        return r.copy(full = edit && assemble && annotate && forms && copy && print)
    }

    /** Size of each page as displayed (rotation applied), in points. */
    fun pageSizes(): List<Size> = (0 until pageCount).map { pageSize(it) }

    fun pageSize(index: Int): Size = withPage(index) { page ->
        val b = page.bounds
        Size(b.x1 - b.x0, b.y1 - b.y0)
    }

    fun title(): String? = try {
        pdf.getMetaData(Document.META_INFO_TITLE)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    internal inline fun <T> withPage(index: Int, block: (Page) -> T): T {
        check(!closed) { "The document is closed." }
        if (index !in 0 until pageCount) throw PdfException(PdfException.Code.BadArguments, "Page ${index + 1} does not exist.")
        val page = pdf.loadPage(index)
        pageDepth++
        try {
            return block(page)
        } finally {
            if (--pageDepth == 0) releaseAnnots()
            page.destroy()
        }
    }

    /** Pages open now (pages can be opened inside a page's work). */
    @PublishedApi
    internal var pageDepth = 0

    /**
     * Java wrappers of annotations and form fields handed out while a page is open ([annots],
     * [newAnnot], [widgetList]). Released when the last page closes, on this thread: left to the
     * garbage collector, a redaction mark's wrapper, released on another thread after its page was
     * gone, crashed the app natively (the round-trip test; decisions §23).
     */
    private val heldAnnots = ArrayList<com.artifex.mupdf.fitz.PDFAnnotation>()

    @PublishedApi
    internal fun releaseAnnots() {
        heldAnnots.forEach { runCatching { it.destroy() } }
        heldAnnots.clear()
    }

    internal fun com.artifex.mupdf.fitz.PDFPage.annots(): Array<com.artifex.mupdf.fitz.PDFAnnotation> =
        (annotations ?: emptyArray()).also { heldAnnots.addAll(it) }

    internal fun com.artifex.mupdf.fitz.PDFPage.widgetList(): Array<com.artifex.mupdf.fitz.PDFWidget> =
        (widgets ?: emptyArray()).also { heldAnnots.addAll(it) }

    internal fun com.artifex.mupdf.fitz.PDFPage.newAnnot(type: Int): com.artifex.mupdf.fitz.PDFAnnotation =
        createAnnotation(type).also { heldAnnots.add(it) }

    // --- rendering ---------------------------------------------------------------------------

    private fun displayList(index: Int): DisplayList =
        // true = with annotations and form fields (false leaves the comments out: seen on a phone).
        displayLists[index] ?: withPage(index) { it.toDisplayList(true) }.also { displayLists[index] = it }

    /**
     * Draws part of a page into [bitmap]. The page is drawn at [scale] pixels per point; the
     * bitmap's top-left corner is pixel ([left], [top]) of that drawing. [cookie] lets a stale
     * request be abandoned half way.
     */
    fun render(index: Int, scale: Float, bitmap: Bitmap, left: Int, top: Int, cancel: RenderCancel? = null) {
        val list = displayList(index)
        val device = AndroidDrawDevice(bitmap, left, top)
        try {
            // The scissor is in pixels (after the matrix): only this tile is drawn.
            val scissor = Rect(left.toFloat(), top.toFloat(), (left + bitmap.width).toFloat(), (top + bitmap.height).toFloat())
            list.run(device, Matrix(scale, scale), scissor, cancel?.cookie)
            device.close()
        } finally {
            device.destroy()
        }
    }

    /** A whole page at a given width in pixels. */
    fun renderWidth(index: Int, widthPx: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val size = pageSize(index)
        val scale = widthPx / size.width
        val h = maxOf(1, (size.height * scale).toInt())
        val bitmap = Bitmap.createBitmap(widthPx, h, config)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        render(index, scale, bitmap, 0, 0)
        return bitmap
    }

    /** Forget cached drawings and text after the document changed. */
    fun invalidate(pages: Collection<Int>? = null) {
        if (pages == null) {
            displayLists.values.forEach { it.destroy() }
            displayLists.clear()
            textCache.clear()
            fixCache.clear()
        } else {
            pages.forEach { displayLists.remove(it)?.destroy(); textCache.remove(it); fixCache.remove(it) }
        }
    }

    // --- text -----------------------------------------------------------------------------------

    /** The page's lines, characters in reading order, with the Arabic ligature order repaired. */
    fun lines(index: Int): List<TextLine> {
        textCache[index]?.let { return it }
        val raw = ArrayList<TextLine>()
        withPage(index) { page ->
            val fixes = textFixes(index, page)
            val stext = page.toStructuredText("preserve-whitespace")
            try {
                var current = ArrayList<Glyph>()
                var lineBox = Rect()
                stext.walk(object : StructuredTextWalker {
                    override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: com.artifex.mupdf.fitz.Image?) {}
                    override fun beginTextBlock(bbox: Rect?, flags: Int) {}
                    override fun endTextBlock() {}
                    override fun beginLine(bbox: Rect, wmode: Int, dir: com.artifex.mupdf.fitz.Point?) {
                        current = ArrayList()
                        lineBox = Rect(bbox)
                    }
                    override fun onChar(c: Int, origin: com.artifex.mupdf.fitz.Point?, font: com.artifex.mupdf.fitz.Font?, size: Float, q: Quad, argb: Int, flags: Int, bidi: Int) {
                        val x0 = minOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x)
                        val x1 = maxOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x)
                        val y0 = minOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y)
                        val y1 = maxOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y)
                        val g = Glyph(String(Character.toChars(c)), x0, y0, x1, y1)
                        val fix = if (fixes.isEmpty || origin == null || font == null) null else fixes.at(font.name, origin.x, origin.y)
                        if (fix != null) current.addAll(LigatureLogic.split(g, fix.text)) else current.add(g)
                    }
                    override fun endLine() {
                        TextLogic.dropPhantoms(current)
                        TextLogic.repairLigatureOrder(current)
                        val pieces = TextLogic.splitAtSizeJumps(current)
                        if (pieces.size <= 1) raw.add(TextLine(current, lineBox.x0, lineBox.y0, lineBox.x1, lineBox.y1))
                        // Two headlines side by side on one baseline are two lines.
                        else pieces.forEach { g -> raw.add(TextLine(g, g.minOf { it.x0 }, g.minOf { it.y0 }, g.maxOf { it.x1 }, g.maxOf { it.y1 })) }
                    }
                    override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                    override fun endStruct() {}
                    override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
                })
            } finally {
                stext.destroy()
            }
        }
        // A second copy printed on top of a line is the same text, not more of it.
        val distinct = TextLogic.distinctLines(raw.map { it.glyphs }).map { raw[it] }
        // A line drawn run by run comes as several; joined back into one.
        val lines = TextLogic.joinPieces(distinct.map { it.glyphs }).map { j ->
            val m = j.members.map { distinct[it] }
            TextLine(j.glyphs, m.minOf { it.x0 }, m.minOf { it.y0 }, m.maxOf { it.x1 }, m.maxOf { it.y1 })
        }
        // Mixed-direction lines come in screen-order pieces; put them in reading order.
        val rtl = TextLogic.pageIsRtl(lines)
        val ordered = lines.map { l ->
            val g = TextLogic.unmirrorPairs(TextLogic.logicalOrder(l.glyphs, rtl))
            if (g === l.glyphs) l else TextLine(g, l.x0, l.y0, l.x1, l.y1)
        }
        textCache[index] = ordered
        return ordered
    }

    fun pageText(index: Int): String = lines(index).joinToString("\n") { it.text }

    /** Whether the page has any text at all (a scan has none). */
    fun hasText(index: Int): Boolean = lines(index).any { l -> l.glyphs.any { !it.c.isBlank() } }

    // --- changes -------------------------------------------------------------------------------

    /**
     * Runs one change. Undo is not MuPDF's journal: on Android 1.28.5 a stream created inside a
     * journal operation lost its data at the next operation (seen on a phone: stamps vanished).
     * The session keeps saved versions instead (docs/decisions.md §9).
     */
    fun <T> change(@Suppress("UNUSED_PARAMETER") label: String, block: () -> T): T {
        try {
            return block()
        } finally {
            invalidate()
        }
    }

    /** Debug: whether each part of a page's content, and each stamp, still holds a stream. */
    fun streamReport(index: Int): String {
        val page = pdf.findPage(index)
        val c = page.get("Contents")
        val parts = if (c.isArray) (0 until c.size()).joinToString("") { if (c.get(it).isStream) "S" else "x" } else if (c.isStream) "S" else "x"
        val xo = page.get("Resources").get("XObject")
        val stamps = if (xo.isDictionary) xo.iterator().asSequence().map { k -> "${k.asName()}:" + (if (xo.get(k).isStream) "S" else "x") }.filter { it.startsWith("HonoPDF") }.joinToString(" ") else ""
        return "contents=$parts $stamps incremental=$canSaveIncrementally"
    }

    /**
     * Set by applying redactions (R1, desktop §80): the next save must write the whole file again
     * with garbage collection. Appending (an incremental save) or a plain snapshot would keep the
     * old content streams — the redacted words — in the file for anyone to read back.
     */
    internal var rewriteFully = false

    /** Whether changes can be appended to the file it was opened from (not after a redaction). */
    val canSaveIncrementally: Boolean get() = !rewriteFully && file != null && runCatching { pdf.canBeSavedIncrementally() }.getOrDefault(false)

    /** Appends the changes to the file the document was opened from (fast: only what changed). */
    fun saveIncremental() {
        val f = file ?: throw PdfException(PdfException.Code.Io, "No file to save into.")
        pdf.save(f.absolutePath, "incremental")
    }

    // --- saving ---------------------------------------------------------------------------------

    /**
     * Writes the document to [target]. [final] writes a smaller, cleaned file (what Save does);
     * otherwise a quick snapshot. Extra MuPDF write options (encryption) go in [extra].
     */
    fun save(target: File, final: Boolean = true, extra: String? = null) {
        val options = buildString {
            append(if (final) SAVE_FINAL else SAVE_SNAPSHOT)
            // Nothing removed by a redaction may stay behind as an unused object (R1).
            if (!final && rewriteFully) append(",garbage")
            if (!extra.isNullOrEmpty()) append(',').append(extra)
        }
        target.parentFile?.mkdirs()
        pdf.save(target.absolutePath, options)
        rewriteFully = false
    }

    override fun close() {
        if (closed) return
        invalidate()
        closed = true
        PdfEngine.dispose(pdf)
    }
}

/** A handle that lets a render be abandoned half way (the page scrolled away). */
class RenderCancel {
    internal val cookie = Cookie()
    private var disposed = false

    @Synchronized
    fun cancel() {
        if (!disposed) cookie.abort()
    }

    /** Frees the cookie. Call on the engine thread, after the render it served has finished. */
    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        cookie.destroy()
    }
}

data class Size(val width: Float, val height: Float)

/** What the file's author allows, for whoever opened it (desktop decisions §23). */
data class Rights(
    val encrypted: Boolean,
    /** Everything is allowed: opened with the permissions password, or nothing is restricted. */
    val full: Boolean,
    val edit: Boolean,
    val assemble: Boolean,
    val annotate: Boolean,
    val forms: Boolean,
    val copy: Boolean,
    val print: Boolean,
)

/** Quick snapshot between edits. */
internal const val SAVE_SNAPSHOT = "compress"

/** What Save writes: smaller, and safe to open anywhere. */
internal const val SAVE_FINAL = "garbage=compact,compress,objstms"
