package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Point

/** Form fields written by hand into a test PDF, as another program (Acrobat) would make them. */
object FormFixtures {
    data class Spec(
        val name: String,
        /** Tx, Btn (checkbox), Ch (drop-down). */
        val type: String,
        /** Where it shows on the page (displayed points). */
        val box: Box,
        val multiline: Boolean = false,
        val password: Boolean = false,
        val options: List<String> = emptyList(),
        /** Extra boxes: further widgets of the same field (the same value shown twice). */
        val more: List<Box> = emptyList(),
        /** A blue frame (/MK /BC). */
        val border: Boolean = true,
        /** An appearance of its own (/AP), as Acrobat writes; without, MuPDF draws one in memory. */
        val appearance: Boolean = true,
    )

    /** The page-space /Rect of a displayed box. */
    fun rectOf(doc: PdfDoc, box: Box): PDFObject {
        val m = doc.displayToUser(0)
        val pts = listOf(Point(box.x0, box.y0), Point(box.x1, box.y0), Point(box.x0, box.y1), Point(box.x1, box.y1)).map { it.transform(m) }
        val arr = doc.pdf.newArray()
        arr.push(pts.minOf { it.x }); arr.push(pts.minOf { it.y }); arr.push(pts.maxOf { it.x }); arr.push(pts.maxOf { it.y })
        return arr
    }

    private fun widget(doc: PdfDoc, box: Box, turn: Int, page: PDFObject, border: Boolean = true, look: String? = null): PDFObject {
        val pdf = doc.pdf
        val w = pdf.newDictionary()
        w.put("Type", pdf.newName("Annot"))
        w.put("Subtype", pdf.newName("Widget"))
        w.put("Rect", rectOf(doc, box))
        w.put("F", 4)
        w.put("P", page)
        val mk = pdf.newDictionary()
        val bc = pdf.newArray(); bc.push(0f); bc.push(0f); bc.push(1f)
        if (border) mk.put("BC", bc)
        if (turn != 0) mk.put("R", turn)
        w.put("MK", mk)
        if (look != null) {
            val sideways = turn % 180 != 0
            val bw = if (sideways) box.y1 - box.y0 else box.x1 - box.x0
            val bh = if (sideways) box.x1 - box.x0 else box.y1 - box.y0
            fun stream(content: String): PDFObject {
                val d = doc.pdf.newDictionary()
                d.put("Type", doc.pdf.newName("XObject")); d.put("Subtype", doc.pdf.newName("Form"))
                d.put("BBox", com.artifex.mupdf.fitz.Rect(0f, 0f, bw, bh))
                return doc.pdf.addStream(content, d)
            }
            val n = if (look == "Btn") doc.pdf.newDictionary().also {
                it.put("Yes", stream("q 0 g 5 5 ${bw - 10} ${bh - 10} re f Q"))
                it.put("Off", stream(""))
            } else stream("")
            val ap = doc.pdf.newDictionary()
            ap.put("N", n)
            w.put("AP", ap)
        }
        return w
    }

    /** Adds [specs] as fields of page 1; [turn] is written as each widget's /MK /R. */
    fun addFields(doc: PdfDoc, specs: List<Spec>, turn: Int = 0) {
        val pdf = doc.pdf
        val root = pdf.trailer.get("Root")
        var acro = root.get("AcroForm")
        if (!acro.isDictionary) {
            acro = pdf.newDictionary()
            acro.put("Fields", pdf.newArray())
            val helv = pdf.newDictionary()
            helv.put("Type", pdf.newName("Font")); helv.put("Subtype", pdf.newName("Type1")); helv.put("BaseFont", pdf.newName("Helvetica"))
            val fonts = pdf.newDictionary(); fonts.put("Helv", pdf.indirect(helv))
            val dr = pdf.newDictionary(); dr.put("Font", fonts)
            acro.put("DR", dr)
            acro.put("DA", pdf.newString("/Helv 0 Tf 0 g"))
            root.put("AcroForm", pdf.indirect(acro))
            acro = root.get("AcroForm")
        }
        val page = pdf.findPage(0)
        var annots = page.get("Annots")
        if (!annots.isArray) {
            page.put("Annots", pdf.newArray())
            annots = page.get("Annots")
        }
        for (s in specs) {
            val field: PDFObject
            if (s.more.isEmpty()) {
                field = pdf.indirect(widget(doc, s.box, turn, page, s.border, s.type.takeIf { s.appearance }))
                annots.push(field)
            } else {
                field = pdf.indirect(pdf.newDictionary())
                val kids = pdf.newArray()
                for (b in listOf(s.box) + s.more) {
                    val w = widget(doc, b, turn, page, s.border, s.type.takeIf { s.appearance })
                    w.put("Parent", field)
                    val ref = pdf.indirect(w)
                    kids.push(ref)
                    annots.push(ref)
                }
                field.put("Kids", kids)
            }
            field.put("T", pdf.newString(s.name))
            field.put("FT", pdf.newName(s.type))
            field.put("DA", pdf.newString("/Helv 0 Tf 0 g"))
            var ff = 0
            if (s.multiline) ff = ff or (1 shl 12)
            if (s.password) ff = ff or (1 shl 13)
            if (s.type == "Ch") ff = ff or (1 shl 17)
            if (ff != 0) field.put("Ff", ff)
            if (s.type == "Ch") {
                val opt = pdf.newArray()
                s.options.forEach { opt.push(pdf.newString(it)) }
                field.put("Opt", opt)
            }
            if (s.type == "Btn") {
                field.put("V", pdf.newName("Off"))
                field.put("AS", pdf.newName("Off"))
                val mk = field.get("MK")
                if (mk.isDictionary) mk.put("CA", pdf.newString("4"))
            }
            acro.get("Fields").push(field)
        }
    }
}
