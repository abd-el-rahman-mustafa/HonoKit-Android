package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredTextWalker
import java.security.MessageDigest

/**
 * Pictures on a page: move, resize, replace, delete (desktop decisions §29). Taking a picture off
 * means disconnecting exactly that picture object: MuPDF's image redaction punches a white hole in
 * a picture behind it (a scan, a coloured page) or deletes that one too. The object is found among
 * the page's resources — and inside forms, where stamps and overlays keep theirs — by its size and
 * a hash of its pixels, and swapped for an empty form in page-local copies (shared resources and
 * forms are copied, never changed, so other pages keep theirs). Moving draws the same image object
 * again in its new place, so its transparency (a signature's) is kept. Only an inline image, which
 * has no object, falls back to pixel redaction.
 */
data class PictureInfo(val box: Box, val width: Int, val height: Int)

/** A picture as drawn: its box, its pixels and where its unit square goes on the displayed page. */
private class Found(val box: Box, val image: Image, val transform: Matrix? = null)

private fun PdfDoc.findPictures(pageIndex: Int): List<Found> {
    val out = ArrayList<Found>()
    withPage(pageIndex) { page ->
        val stext = page.toStructuredText("preserve-images")
        try {
            stext.walk(object : StructuredTextWalker {
                override fun onImageBlock(bbox: Rect, transform: Matrix?, image: com.artifex.mupdf.fitz.Image?) {
                    if (image != null) out.add(Found(Box(bbox.x0, bbox.y0, bbox.x1, bbox.y1), image, transform?.let { Matrix(it.a, it.b, it.c, it.d, it.e, it.f) }))
                }
                override fun beginTextBlock(bbox: Rect?, flags: Int) {}
                override fun endTextBlock() {}
                override fun beginLine(bbox: Rect?, wmode: Int, dir: com.artifex.mupdf.fitz.Point?) {}
                override fun endLine() {}
                override fun onChar(c: Int, origin: com.artifex.mupdf.fitz.Point?, font: com.artifex.mupdf.fitz.Font?, size: Float, q: com.artifex.mupdf.fitz.Quad?, argb: Int, flags: Int, bidi: Int) {}
                override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                override fun endStruct() {}
                override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
            })
        } finally {
            stext.destroy()
        }
    }
    return out
}

/** The page's pictures that can be edited: not those covering most of it (a scan, a background). */
fun PdfDoc.pictures(pageIndex: Int): List<PictureInfo> {
    val size = pageSize(pageIndex)
    val area = size.width * size.height
    return findPictures(pageIndex).mapNotNull { p ->
        val a = p.box.width * p.box.height
        val info = if (a < area * 0.55f && p.box.width >= 4 && p.box.height >= 4) PictureInfo(p.box, p.image.width, p.image.height) else null
        p.image.destroy()
        info
    }
}

private fun close(a: Box, b: Box) = kotlin.math.abs(a.x0 - b.x0) < 1.5f && kotlin.math.abs(a.y0 - b.y0) < 1.5f && kotlin.math.abs(a.x1 - b.x1) < 1.5f && kotlin.math.abs(a.y1 - b.y1) < 1.5f

private fun overlapShare(a: Box, b: Box): Float {
    val w = maxOf(0f, minOf(a.x1, b.x1) - maxOf(a.x0, b.x0))
    val h = maxOf(0f, minOf(a.y1, b.y1) - maxOf(a.y0, b.y0))
    return w * h / maxOf(1e-6f, a.width * a.height)
}

private fun fingerprint(image: Image): String {
    val pixmap = image.toPixmap()
    try {
        val digest = MessageDigest.getInstance("SHA-1").digest(pixmap.samples)
        return "${image.width}x${image.height}:" + digest.joinToString("") { "%02x".format(it) }
    } finally {
        pixmap.destroy()
    }
}

private fun PdfDoc.copyDict(d: PDFObject): PDFObject {
    val out = pdf.newDictionary()
    if (d.isDictionary) for (key in d) out.put(key, d.get(key))
    return out
}

private fun PdfDoc.emptyForm(): PDFObject {
    val d = pdf.newDictionary()
    d.put("Type", pdf.newName("XObject"))
    d.put("Subtype", pdf.newName("Form"))
    d.put("BBox", Rect(0f, 0f, 0f, 0f))
    return pdf.addStream("", d)
}

/**
 * Looks for the picture among [resources] and, inside forms, theirs. Returns new resources with the
 * picture swapped for an empty form, and the picture's own object, or null.
 */
private fun PdfDoc.withoutPicture(resources: PDFObject, wanted: String, image: Image, depth: Int): Pair<PDFObject, PDFObject>? {
    val xobjects = resources.get("XObject")
    if (!xobjects.isDictionary) return null
    var key: PDFObject? = null
    var replacement: PDFObject? = null
    var original: PDFObject? = null
    for (k in xobjects) {
        if (replacement != null) break
        val value = xobjects.get(k)
        runCatching {
            if (!value.isDictionary) return@runCatching
            when (value.get("Subtype").asName()) {
                "Image" -> {
                    if (value.get("Width").asInteger() != image.width || value.get("Height").asInteger() != image.height) return@runCatching
                    val candidate = pdf.loadImage(value)
                    try {
                        if (fingerprint(candidate) == wanted) {
                            key = k
                            replacement = emptyForm()
                            original = value
                        }
                    } finally {
                        candidate.destroy()
                    }
                }
                "Form" -> {
                    if (depth >= 4) return@runCatching
                    val inner = value.get("Resources")
                    if (!inner.isDictionary) return@runCatching
                    val changed = withoutPicture(inner, wanted, image, depth + 1) ?: return@runCatching
                    val dict = copyDict(value)
                    dict.put("Resources", changed.first)
                    val raw = com.artifex.mupdf.fitz.Buffer()
                    raw.writeBytes(value.readRawStream())
                    key = k
                    replacement = pdf.addRawStream(raw, dict)
                    original = changed.second
                }
            }
        }
    }
    val k = key ?: return null
    val ownXObjects = copyDict(xobjects)
    ownXObjects.put(k, replacement!!)
    val ownResources = copyDict(resources)
    ownResources.put("XObject", ownXObjects)
    return ownResources to original!!
}

/** Takes the picture at [box] off the page. Returns its object (null for an inline image) and whether a blank may show. */
private fun PdfDoc.removePicture(pageIndex: Int, box: Box): Triple<Found, PDFObject?, Boolean> {
    val all = findPictures(pageIndex)
    val found = all.firstOrNull { close(it.box, box) } ?: all.firstOrNull { overlapShare(it.box, box) > 0.8f }
        ?: run {
            all.forEach { it.image.destroy() }
            throw PdfException(PdfException.Code.BadArguments, "There is no picture there.")
        }
    try {
        val page = pdf.findPage(pageIndex)
        val resources = page.getInheritable("Resources")
        val result = if (resources.isDictionary) withoutPicture(resources, fingerprint(found.image), found.image, 0) else null
        if (result != null) {
            page.put("Resources", result.first)
            invalidate(listOf(pageIndex))
            return Triple(found, result.second, false)
        }
        // An inline image: its pixels are blanked instead.
        val behind = all.any { it !== found && overlapShare(found.box, it.box) > 0f }
        // Only under the picture: pending Redact marks and links stay (E1).
        safeRedact(pageIndex, listOf(found.box), images = RedactImages.Pixels, keepText = true)
        return Triple(found, null, behind)
    } finally {
        all.filter { it !== found }.forEach { it.image.destroy() }
    }
}

/** Deletes the picture at [box]. Returns true if a picture behind it may show a blank area. */
fun PdfDoc.deletePicture(pageIndex: Int, box: Box): Boolean {
    requireEdit()
    val (found, _, hole) = removePicture(pageIndex, box)
    found.image.destroy()
    return hole
}

/** Moves (and resizes) the picture at [box] into [to]. */
fun PdfDoc.movePicture(pageIndex: Int, box: Box, to: Box) {
    requireEdit()
    if (to.width < 2 || to.height < 2) throw PdfException(PdfException.Code.BadArguments, "The picture is too small.")
    val (found, obj, _) = removePicture(pageIndex, box)
    try {
        // The same image object (a JPEG stays the bytes it was); inline: drawn again from its
        // pixels (MuPDF keeps its mask when adding it).
        val ref = obj ?: pdf.addImage(found.image)
        val t = found.transform
        if (t == null) placeImageObject(pageIndex, ref, to)
        else {
            // As it was drawn, turned or mirrored (E2): its own matrix, carried from the old box to
            // the new one, instead of an upright picture stretched into the box.
            val from = found.box
            val sx = to.width / maxOf(1e-3f, from.width)
            val sy = to.height / maxOf(1e-3f, from.height)
            val carry = Matrix(Matrix(Matrix.Translate(-from.x0, -from.y0), Matrix.Scale(sx, sy)), Matrix.Translate(to.x0, to.y0))
            // MuPDF draws a PDF image's unit square upside down ([1 0 0 -1 0 1] before the CTM).
            placeImageObject(pageIndex, ref, Matrix(Matrix(1f, 0f, 0f, -1f, 0f, 1f), Matrix(t, carry)))
        }
    } finally {
        found.image.destroy()
    }
}

/** Replaces the picture at [box] with [bytes], fitted inside the old box with its own proportions. */
fun PdfDoc.replacePicture(pageIndex: Int, box: Box, bytes: ByteArray) {
    requireEdit()
    val image = try {
        Image(bytes)
    } catch (e: Throwable) {
        throw PdfException(PdfException.Code.Unsupported, "That picture could not be read.")
    }
    try {
        val (found, _, _) = removePicture(pageIndex, box)
        val old = found.box
        found.image.destroy()
        val s = minOf(old.width / image.width, old.height / image.height)
        val w = image.width * s
        val h = image.height * s
        val x = old.x0 + (old.width - w) / 2
        val y = old.y0 + (old.height - h) / 2
        placeImageObject(pageIndex, pdf.addImage(image), Box(x, y, x + w, y + h))
    } finally {
        image.destroy()
    }
}
