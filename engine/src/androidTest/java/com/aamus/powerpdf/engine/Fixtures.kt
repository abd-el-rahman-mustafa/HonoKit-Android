package com.aamus.powerpdf.engine

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect
import java.io.File

/**
 * Test PDFs made in code (never shipped: the repository holds no PDFs) and helpers to open, save,
 * reopen and render them. Instrumented tests run MuPDF itself on the emulator (docs/testing.md).
 */
object Fixtures {
    const val SIZE = 500f

    val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val dir: File by lazy { File(context.cacheDir, "engine-tests").apply { mkdirs() } }

    fun init() {
        StampFonts.init(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    /** A page's shape: /Rotate and where its MediaBox starts. */
    data class Shape(val rotate: Int, val ox: Float = 0f, val oy: Float = 0f) {
        override fun toString() = "/Rotate $rotate, origin $ox,$oy"
    }

    /** Square pages: turned, they show at the same size as an unturned one. */
    val SHAPES = listOf(
        Shape(90), Shape(180), Shape(270),
        Shape(0, -40f, 70f), Shape(90, 30f, -60f), Shape(270, 55f, 25f),
    )

    fun temp(name: String = "test"): File = File.createTempFile("hk-$name", ".pdf", dir)

    /**
     * A one-page PDF of [SIZE] points square in [shape]. [content] is raw page content in PDF
     * space (Helvetica is /F1); [build] may add objects (fields, annotations) to the page.
     */
    fun pdf(shape: Shape = Shape(0), content: String = "", build: (PDFDocument, PDFObject) -> Unit = { _, _ -> }): File {
        val pdf = PDFDocument()
        try {
            return write(pdf, shape, content, build)
        } finally {
            // The wrappers made while writing are released first, on this thread (decisions §23).
            PdfEngine.dispose(pdf)
        }
    }

    private fun write(pdf: PDFDocument, shape: Shape, content: String, build: (PDFDocument, PDFObject) -> Unit): File {
        run {
            val font = pdf.newDictionary()
            font.put("Type", pdf.newName("Font"))
            font.put("Subtype", pdf.newName("Type1"))
            font.put("BaseFont", pdf.newName("Helvetica"))
            font.put("Encoding", pdf.newName("WinAnsiEncoding"))
            val fonts = pdf.newDictionary()
            fonts.put("F1", pdf.indirect(font))
            val res = pdf.newDictionary()
            res.put("Font", fonts)
            val page = pdf.addPage(Rect(shape.ox, shape.oy, shape.ox + SIZE, shape.oy + SIZE), shape.rotate, res, content)
            pdf.insertPage(-1, page)
            build(pdf, pdf.findPage(0))
            val f = temp()
            pdf.save(f.absolutePath, "")
            return f
        }
    }

    /**
     * A PDF of [count] plain pages of [SIZE] points square, page i drawing [content] (i) (Helvetica
     * is /F1; the pages share one resources dictionary). [build] may add to the document.
     */
    fun pages(count: Int, content: (Int) -> String, build: (PDFDocument) -> Unit = {}): File {
        val pdf = PDFDocument()
        try {
            return writePages(pdf, count, content, build)
        } finally {
            PdfEngine.dispose(pdf)
        }
    }

    private fun writePages(pdf: PDFDocument, count: Int, content: (Int) -> String, build: (PDFDocument) -> Unit): File {
        val font = pdf.newDictionary()
        font.put("Type", pdf.newName("Font"))
        font.put("Subtype", pdf.newName("Type1"))
        font.put("BaseFont", pdf.newName("Helvetica"))
        font.put("Encoding", pdf.newName("WinAnsiEncoding"))
        val fonts = pdf.newDictionary()
        fonts.put("F1", pdf.indirect(font))
        val res = pdf.newDictionary()
        res.put("Font", fonts)
        val shared = pdf.indirect(res)
        for (i in 0 until count) {
            val page = pdf.addPage(Rect(0f, 0f, SIZE, SIZE), 0, shared, content(i))
            pdf.insertPage(-1, page)
        }
        build(pdf)
        val f = temp()
        pdf.save(f.absolutePath, "")
        return f
    }

    fun open(file: File, password: String? = null): PdfDoc = PdfEngine.open(file, password)

    /** Saves as the session's snapshot does, closes, and opens what was written. */
    fun reopen(doc: PdfDoc): PdfDoc {
        val f = temp("reopen")
        doc.save(f, final = false)
        val pw = doc.password
        doc.close()
        return open(f, pw)
    }

    /** Page 1 as shown, grey (0–255), one pixel per point. */
    class Grey(val px: IntArray, val width: Int, val height: Int) {
        /** Pixels clearly darker than paper. */
        val inked: Int get() = px.count { it < 200 }

        /** The inked area, or null. */
        fun inkBox(): IntArray? {
            var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
            for (y in 0 until height) for (x in 0 until width) if (px[y * width + x] < 200) {
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
        }

        fun inkIn(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            var n = 0
            for (y in maxOf(0, y0) until minOf(height, y1)) for (x in maxOf(0, x0) until minOf(width, x1)) if (px[y * width + x] < 200) n++
            return n
        }
    }

    fun render(doc: PdfDoc, page: Int = 0): Grey {
        val size = doc.pageSize(page)
        val bmp = doc.renderWidth(page, size.width.toInt())
        try {
            val w = bmp.width
            val h = bmp.height
            val argb = IntArray(w * h)
            bmp.getPixels(argb, 0, w, 0, 0, w, h)
            return Grey(IntArray(w * h) { i -> val c = argb[i]; (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000 }, w, h)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Writes a rendering as a PNG for a person to look at (debugging a failing comparison):
     * /sdcard/Android/data/com.aamus.powerpdf.engine.test/files/<name>.png, pulled with adb.
     */
    fun dump(g: Grey, name: String) {
        val bmp = Bitmap.createBitmap(g.width, g.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(IntArray(g.px.size) { val v = g.px[it]; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, 0, g.width, 0, 0, g.width, g.height)
        val out = File(context.getExternalFilesDir(null), "$name.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    /**
     * The share of inked pixels that differ between two renderings (desktop test/rotated.test.ts):
     * a pixel counts as different only when no pixel within one step of it in the other picture
     * is close in grey, as anti-aliasing of a turned drawing differs a little at its edges.
     */
    fun difference(a: Grey, b: Grey): Float {
        if (a.width != b.width || a.height != b.height) return 1f
        val w = a.width
        val h = a.height
        fun near(p: IntArray, x: Int, y: Int, v: Int): Boolean {
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && kotlin.math.abs(p[yy * w + xx] - v) < 90) return true
            }
            return false
        }
        var ink = 0
        var off = 0
        for (y in 0 until h) for (x in 0 until w) {
            val va = a.px[y * w + x]
            val vb = b.px[y * w + x]
            if (va >= 200 && vb >= 200) continue
            ink++
            if (!near(b.px, x, y, va) || !near(a.px, x, y, vb)) off++
        }
        return if (ink == 0) 0f else off.toFloat() / ink
    }

    /** Every stream in the file, decoded, as Latin-1 text (to look for words that must be gone). */
    fun allStreams(file: File): String {
        val pdf = PdfEngine.openedAsPdf(com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath))!!
        try {
            val out = StringBuilder()
            for (n in 1 until pdf.countObjects()) {
                runCatching {
                    val o = pdf.newIndirect(n, 0)
                    if (o.isStream) out.append(String(o.readStream(), Charsets.ISO_8859_1)).append('\n')
                    else out.append(o.resolve().toString(false, false)).append('\n')
                }
            }
            return out.toString()
        } finally {
            // Wrappers of its objects are released first, on this thread (decisions §23).
            PdfEngine.dispose(pdf)
        }
    }

    /**
     * The text of page 1 as shown, comments and fields included: a copy with them baked into the
     * page (structured text leaves annotations out).
     */
    fun shownText(doc: PdfDoc): String {
        val copy = temp("baked")
        doc.save(copy, final = false)
        val baked = open(copy, doc.password)
        try {
            baked.pdf.bake(true, true)
            baked.invalidate()
            return baked.pageText(0)
        } finally {
            baked.close()
        }
    }

    /** A small PNG whose top edge is dark: shows which way up it is drawn. */
    fun arrowPng(): ByteArray {
        val w = 80
        val h = 50
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val dark = y < 10 || (x < 10 && y < 35)
            bmp.setPixel(x, y, if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
