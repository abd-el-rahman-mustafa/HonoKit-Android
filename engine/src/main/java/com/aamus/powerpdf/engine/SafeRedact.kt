package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect

/** Pictures under the boxes: left, their covered pixels blanked, or removed whole. */
enum class RedactImages(internal val mupdf: Int) {
    Keep(PDFPage.REDACT_IMAGE_NONE),
    Pixels(PDFPage.REDACT_IMAGE_PIXELS),
    Remove(PDFPage.REDACT_IMAGE_REMOVE),
}

/** Drawings under the boxes: left, removed when covered, or removed when touched at all. */
enum class RedactLineArt(internal val mupdf: Int) {
    Keep(PDFPage.REDACT_LINE_ART_NONE),
    Covered(PDFPage.REDACT_LINE_ART_REMOVE_IF_COVERED),
    Touched(PDFPage.REDACT_LINE_ART_REMOVE_IF_TOUCHED),
}

/** A Subtype MuPDF does not know, given for a moment to an annotation it must not touch. */
private const val HELD = "HonoHeld"

/**
 * Removes what lies under [boxes] (displayed points) on one page with MuPDF's redaction, and
 * nothing else (desktop saferedact.ts, review item E1). For editing text, Erase area, Format all
 * and removing a picture — never for the person's own Redact tool (that is [applyRedactions]).
 *
 * MuPDF's PDFPage.applyRedactions applies EVERY Redact annotation on the page — the marks the
 * person has drawn but not applied yet too — and deletes Link and FreeText annotations under the
 * area. An edit must change none of them. So the page's /Annots is swapped for an empty array
 * while the removal runs (only the redactions made here are on the page), and the page's own
 * /Annots — the same object, every annotation in the same order — is put back after. A page MuPDF
 * already holds open keeps the annotation list it loaded, whatever /Annots says now: for that
 * case each annotation in that list is given a type MuPDF does not know while the removal runs,
 * and its own type back after.
 *
 * Text is removed unless [keepText]; [blackBoxes] paints MuPDF's black boxes where content went.
 * Call [invalidate] (or let the session's change do it) afterwards.
 */
fun PdfDoc.safeRedact(
    pageIndex: Int,
    boxes: List<Box>,
    images: RedactImages = RedactImages.Keep,
    lineArt: RedactLineArt = RedactLineArt.Keep,
    keepText: Boolean = false,
    blackBoxes: Boolean = false,
) {
    if (boxes.isEmpty()) return
    val pageObj = pdf.findPage(pageIndex)
    val own: PDFObject = pageObj.get("Annots")
    val hadAnnots = !own.isNull
    pageObj.put("Annots", pdf.newArray())
    val renamed = ArrayList<Pair<PDFObject, PDFObject>>()
    try {
        withPdfPage(pageIndex) { page ->
            for (a in page.annots()) {
                val obj = a.getObject()
                renamed.add(obj to obj.get("Subtype"))
                obj.put("Subtype", pdf.newName(HELD))
            }
            for (b in boxes) {
                val mark = page.newAnnot(PDFAnnotation.TYPE_REDACT)
                mark.rect = Rect(b.x0, b.y0, b.x1, b.y1)
            }
            page.applyRedactions(blackBoxes, images.mupdf, lineArt.mupdf, if (keepText) PDFPage.REDACT_TEXT_NONE else PDFPage.REDACT_TEXT_REMOVE)
        }
    } finally {
        for ((obj, subtype) in renamed) {
            if (subtype.isNull) obj.delete("Subtype") else obj.put("Subtype", subtype)
        }
        // Our own Redact annotations go with the temporary array (MuPDF deletes them once
        // applied; after a failure they would otherwise stay on the page as marks).
        if (hadAnnots) pageObj.put("Annots", own) else pageObj.delete("Annots")
        invalidate(listOf(pageIndex))
    }
}
