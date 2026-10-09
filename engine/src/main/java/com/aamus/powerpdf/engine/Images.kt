package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import java.io.File

/** Page size for pictures made into a PDF. */
enum class PaperSize(val width: Float, val height: Float) {
    /** The page takes the picture's own proportions (at 150 dpi, never smaller than A6). */
    Fit(0f, 0f),
    A4(595.28f, 841.89f),
    Letter(612f, 792f),
}

/**
 * One picture, already turned upright, cropped and encoded (JPEG or PNG). [paper], when given,
 * is this page's own size (a scanned page snapped to A4 or Letter), whatever the document's is.
 */
class PictureInput(
    val bytes: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val paper: PaperSize? = null,
    /**
     * [bytes] are ink-or-paper pixels packed one bit each ([Bilevel.pack]), not an encoded picture:
     * a black-and-white scan stored as such is a small part of a colour JPEG of the same page.
     */
    val bilevel: Boolean = false,
    /** This page's margin in points instead of the document's (a page laid out already, as an ID card sheet). */
    val margin: Float? = null,
)

/** Black-and-white pages as 1-bit pictures (docs/research-scan.md M4). */
object Bilevel {
    /**
     * Packs [argb] ([w] × [h]) one bit a pixel, rows starting on a byte, the leftmost pixel in the
     * highest bit; 1 is paper (white) and 0 ink, as PDF's DeviceGray reads one-bit samples.
     */
    fun pack(argb: IntArray, w: Int, h: Int): ByteArray {
        val row = (w + 7) / 8
        val out = ByteArray(row * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = argb[y * w + x]
            val lum = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
            if (lum >= 128) out[y * row + x / 8] = (out[y * row + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
        }
        return out
    }

    fun deflate(bytes: ByteArray): ByteArray {
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        d.setInput(bytes)
        d.finish()
        val out = java.io.ByteArrayOutputStream(bytes.size / 8 + 64)
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}

object PdfImages {
    /**
     * Makes a PDF with one picture per page. On A4/Letter the page turns to landscape for a wide
     * picture, and the picture is fitted inside [margin] points, centred.
     */
    fun imagesToPdf(pictures: List<PictureInput>, paper: PaperSize, margin: Float, target: File, progress: (Int) -> Unit = {}) {
        if (pictures.isEmpty()) throw PdfException(PdfException.Code.BadArguments, "No pictures.")
        val out = PDFDocument()
        try {
            pictures.forEachIndexed { i, p ->
                progress(i)
                val image = if (p.bilevel) null else Image(p.bytes)
                try {
                    val ref = if (image != null) out.addImage(image) else {
                        // One bit a pixel, Flate-compressed by us (MuPDF stores it as given).
                        val dict = out.newDictionary()
                        dict.put("Type", out.newName("XObject"))
                        dict.put("Subtype", out.newName("Image"))
                        dict.put("Width", p.pixelWidth)
                        dict.put("Height", p.pixelHeight)
                        dict.put("ColorSpace", out.newName("DeviceGray"))
                        dict.put("BitsPerComponent", 1)
                        dict.put("Filter", out.newName("FlateDecode"))
                        val data = com.artifex.mupdf.fitz.Buffer()
                        try {
                            data.writeBytes(Bilevel.deflate(p.bytes))
                            out.addRawStream(data, dict)
                        } finally {
                            data.destroy()
                        }
                    }
                    val wide = p.pixelWidth > p.pixelHeight
                    val mg = p.margin ?: margin
                    val (pw, ph) = when (val size = p.paper ?: paper) {
                        PaperSize.Fit -> {
                            // 150 dpi: a phone photo becomes about A4; small pictures are not made tiny.
                            val w = p.pixelWidth * 72f / 150f
                            val h = p.pixelHeight * 72f / 150f
                            val minSide = 297.6f // A6 short side
                            val k = maxOf(1f, minSide / minOf(w, h))
                            (w * k + 2 * mg) to (h * k + 2 * mg)
                        }
                        else -> if (wide) size.height to size.width else size.width to size.height
                    }
                    val boxW = pw - 2 * mg
                    val boxH = ph - 2 * mg
                    val k = minOf(boxW / p.pixelWidth, boxH / p.pixelHeight)
                    val iw = p.pixelWidth * k
                    val ih = p.pixelHeight * k
                    val x = (pw - iw) / 2
                    val y = (ph - ih) / 2
                    val resources = out.newDictionary()
                    val xobjects = out.newDictionary()
                    xobjects.put("Im0", ref)
                    resources.put("XObject", xobjects)
                    val content = String.format(java.util.Locale.US, "q %.3f 0 0 %.3f %.3f %.3f cm /Im0 Do Q", iw, ih, x, y)
                    val page = out.addPage(Rect(0f, 0f, pw, ph), 0, resources, content)
                    out.insertPage(-1, page)
                } finally {
                    image?.destroy()
                }
            }
            target.parentFile?.mkdirs()
            out.save(target.absolutePath, SAVE_FINAL)
        } finally {
            PdfEngine.dispose(out)
        }
    }
}
