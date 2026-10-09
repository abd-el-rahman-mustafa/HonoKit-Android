package com.aamus.powerpdf.doc

import android.content.Context
import android.graphics.Bitmap
import com.aamus.powerpdf.engine.PdfDoc
import com.aamus.powerpdf.engine.PdfEngine
import com.aamus.powerpdf.engine.RecognisedLine
import com.aamus.powerpdf.engine.addTextLayer
import com.aamus.powerpdf.ocr.OcrEngine
import com.aamus.powerpdf.ocr.OcrImage
import com.aamus.powerpdf.ocr.OcrLogic
import com.aamus.powerpdf.ocr.Reading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recognize text (decisions §29), as the desktop's recogniseText: pages that are pictures get
 * their lines found and read; the result goes into an invisible text layer (engine/TextLayer.kt),
 * so the page looks the same but can be searched, selected and copied. The page is drawn on the
 * engine thread; the models run on a worker, so the viewer keeps drawing meanwhile.
 */
object Recognize {
    /** A page counts as having text when it has more than this many letters or digits. */
    const val HAS_TEXT = 30

    /** Pages are read at this resolution (scans are usually 200–300 dpi; desktop S5). */
    const val DPI = 200

    /** The longest side read, so a poster-sized page does not take all the memory. */
    private const val MAX_SIDE = 2600

    fun hasText(doc: PdfDoc, page: Int): Boolean =
        doc.lines(page).sumOf { line -> line.text.count { it.isLetterOrDigit() } } > HAS_TEXT

    /** A page drawn for reading: its pixels, and pixels per point. */
    class PagePicture(val image: OcrImage, val scale: Float)

    /** Draws page [index] for reading (engine thread). */
    fun draw(doc: PdfDoc, index: Int): PagePicture {
        val size = doc.pageSize(index)
        var widthPx = (size.width * DPI / 72f).toInt()
        val longest = maxOf(size.width, size.height) * DPI / 72f
        if (longest > MAX_SIDE) widthPx = (widthPx * MAX_SIDE / longest).toInt()
        val bitmap = doc.renderWidth(index, widthPx.coerceAtLeast(16), Bitmap.Config.ARGB_8888)
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = OcrImage(px, bitmap.width, bitmap.height)
        bitmap.recycle()
        return PagePicture(image, image.width / size.width)
    }

    /**
     * One line, the way the desktop reads it with only the Arabic recogniser (which also reads
     * Latin and digits): a Latin line keeps its spaces, minus the ones the model invents inside a
     * number and after a capital that starts a word ("T otal").
     */
    fun cleanLatin(r: Reading): String {
        if (OcrLogic.ARABIC.containsMatchIn(r.text)) return r.text
        return r.text.replace(Regex("(\\d) (?=\\d)"), "$1").replace(Regex("(^|\\s)([B-HJ-Z]) (?=[a-z])"), "$1$2")
    }

    /**
     * The lines of [picture], in points of the page as displayed. [onLine] after each line read
     * (for progress); throws OcrException.Cancelled when [ocr] is cancelled.
     */
    fun readPage(ocr: OcrEngine, picture: PagePicture, onLine: (Int, Int) -> Unit = { _, _ -> }): List<RecognisedLine> {
        val boxes = ocr.detectLines(picture.image).filter { it.h >= 8f && it.w >= 12f }
        val out = ArrayList<RecognisedLine>()
        for ((i, box) in boxes.withIndex()) {
            val r = ocr.read(picture.image.crop(box.x, box.y, box.w, box.h))
            onLine(i + 1, boxes.size)
            val text = cleanLatin(r).replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty() || r.confidence < 0.5f) continue
            val k = picture.scale
            out.add(RecognisedLine(text, box.x / k, box.y / k, box.w / k, box.h / k))
        }
        return out
    }

    /**
     * Lines as plain text in reading order: lines that share a row (overlap by half their height)
     * are one row, joined right to left when the row is Arabic, else left to right; rows top down.
     */
    fun toText(lines: List<RecognisedLine>): String {
        val rows = ArrayList<MutableList<RecognisedLine>>()
        for (l in lines.sortedBy { it.y }) {
            val row = rows.lastOrNull()
            val r0 = row?.minOf { it.y } ?: 0f
            val r1 = row?.maxOf { it.y + it.h } ?: 0f
            val overlap = minOf(r1, l.y + l.h) - maxOf(r0, l.y)
            if (row != null && overlap > l.h * 0.5f) row.add(l) else rows.add(mutableListOf(l))
        }
        return rows.joinToString("\n") { row ->
            val arabic = row.count { OcrLogic.ARABIC.containsMatchIn(it.text) } * 2 >= row.size
            (if (arabic) row.sortedByDescending { it.x } else row.sortedBy { it.x }).joinToString(" ") { it.text }
        }
    }

    /** What a run found. */
    class Result(val pages: Int, val lines: Int, val skipped: Int)

    /**
     * Reads [pages] of [doc] (each drawn through [onEngine], which runs a block on the engine
     * thread) and returns each page's lines; pages that already have text are skipped unless
     * [redo]. [progress]: page number (1-based) of how many, and line of lines on that page.
     */
    suspend fun read(
        context: Context,
        onEngine: suspend (block: (PdfDoc) -> Any?) -> Any?,
        pages: List<Int>,
        redo: Boolean,
        ocrSlot: (OcrEngine?) -> Unit,
        progress: (page: Int, of: Int, line: Int, lines: Int) -> Unit,
    ): Pair<Map<Int, List<RecognisedLine>>, Int> {
        @Suppress("UNCHECKED_CAST")
        val todo = onEngine { doc -> pages.filter { redo || !hasText(doc, it) } } as List<Int>
        val skipped = pages.size - todo.size
        if (todo.isEmpty()) return emptyMap<Int, List<RecognisedLine>>() to skipped
        val found = LinkedHashMap<Int, List<RecognisedLine>>()
        withContext(Dispatchers.Default) {
            val ocr = OcrEngine.open(context, threads())
            ocrSlot(ocr)
            try {
                for ((n, page) in todo.withIndex()) {
                    progress(n + 1, todo.size, 0, 0)
                    val picture = onEngine { doc -> draw(doc, page) } as PagePicture
                    found[page] = readPage(ocr, picture) { line, lines -> progress(n + 1, todo.size, line, lines) }
                }
            } finally {
                ocrSlot(null)
                ocr.close()
            }
        }
        return found to skipped
    }

    /** The models' threads: the phone's cores, at most 4 (the big ones; desktop S5). */
    fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Reads every page of the PDF file [file] that has no text and adds the layer; saves to [target]. */
    suspend fun makeSearchable(context: Context, file: java.io.File, target: java.io.File, progress: (page: Int, of: Int) -> Unit): Result {
        val doc = PdfEngine.run { PdfEngine.open(file) }
        try {
            val count = PdfEngine.run { doc.pageCount }
            val (found, skipped) = read(context, { block -> PdfEngine.run { block(doc) } }, (0 until count).toList(), redo = false, ocrSlot = {}) { p, of, _, _ -> progress(p, of) }
            PdfEngine.run {
                if (found.isNotEmpty()) doc.addTextLayer(found)
                doc.save(target)
            }
            return Result(found.size, found.values.sumOf { it.size }, skipped)
        } finally {
            PdfEngine.run { doc.close() }
        }
    }
}
