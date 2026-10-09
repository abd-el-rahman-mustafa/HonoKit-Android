package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFObject

/**
 * A new indirect object holding [o]. (PDFDocument.addObject hands back the object itself, not a
 * reference to it, so what it was given ends up written inline wherever it is used.)
 */
fun com.artifex.mupdf.fitz.PDFDocument.indirect(o: PDFObject): PDFObject = createObject().also { it.writeObject(o) }

/** Raw PDF objects written into test documents, as other programs write them. */
object Raw {
    fun nums(doc: PdfDoc, vararg v: Float): PDFObject = doc.pdf.newArray().also { a -> v.forEach { a.push(it) } }

    /** Adds [dict] to page 1's /Annots as an indirect object; returns the reference. */
    fun addAnnot(doc: PdfDoc, dict: PDFObject, page: Int = 0): PDFObject {
        val p = doc.pdf.findPage(page)
        var annots = p.get("Annots")
        if (!annots.isArray) {
            p.put("Annots", doc.pdf.newArray())
            annots = p.get("Annots")
        }
        val ref = doc.pdf.indirect(dict)
        ref.put("P", p)
        annots.push(ref)
        return ref
    }

    fun dict(doc: PdfDoc, vararg pairs: Pair<String, Any>): PDFObject {
        val d = doc.pdf.newDictionary()
        for ((k, v) in pairs) when (v) {
            is PDFObject -> d.put(k, v)
            is Int -> d.put(k, v)
            is Float -> d.put(k, v)
            is Boolean -> d.put(k, v)
            is String -> if (v.startsWith("/")) d.put(k, doc.pdf.newName(v.substring(1))) else d.put(k, doc.pdf.newString(v))
            else -> error("unsupported $v")
        }
        return d
    }

    fun stream(doc: PdfDoc, content: String, vararg pairs: Pair<String, Any>): PDFObject = doc.pdf.addStream(content, dict(doc, *pairs))

    fun action(doc: PdfDoc, kind: String, vararg pairs: Pair<String, Any>): PDFObject = dict(doc, "S" to "/$kind", *pairs)

    /** Every annotation dictionary of page 1. */
    fun annots(doc: PdfDoc, page: Int = 0): List<PDFObject> {
        val a = doc.pdf.findPage(page).get("Annots")
        return if (a.isArray) (0 until a.size()).map { a.get(it).resolve() } else emptyList()
    }

    fun subtypes(doc: PdfDoc, page: Int = 0): List<String> = annots(doc, page).map { it.get("Subtype").let { s -> if (s.isName) s.asName() else "" } }
}
