package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Device
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StrokeState

/**
 * Filling in forms (desktop decisions §47): values are set through MuPDF, which keeps drawing
 * checkboxes, radio buttons and choices; text fields are drawn by us, because MuPDF's drawing
 * cannot shape Arabic: the field's frame (/MK colours, /BS width), its size (a size that cannot fit
 * counts as "fit"), colour and alignment (/Q, else the text's direction), wrapping when multi-line.
 * MuPDF rewrites /DA with its own fitted size, so the original is put back; NeedAppearances is
 * removed so readers show our drawing.
 */
enum class FieldKind { Text, Checkbox, Radio, Combo, List, Button, Signature }

data class FormField(
    val page: Int,
    /** Position among the page's widgets; with [page], names the widget. */
    val index: Int,
    val name: String,
    val kind: FieldKind,
    /** Where it is on the page as displayed. */
    val box: Box,
    val value: String,
    /** A checkbox or radio button that is on. */
    val checked: Boolean,
    val options: List<String>,
    val multiline: Boolean,
    val readOnly: Boolean,
    val required: Boolean,
    val maxLen: Int,
)

private fun kindOf(w: PDFWidget): FieldKind = when {
    w.isRadioButton -> FieldKind.Radio
    w.isCheckbox -> FieldKind.Checkbox
    w.isPushButton -> FieldKind.Button
    w.isComboBox -> FieldKind.Combo
    w.isListBox -> FieldKind.List
    w.isText -> FieldKind.Text
    w.fieldType == PDFWidget.TYPE_SIGNATURE -> FieldKind.Signature
    else -> FieldKind.Text
}

/** A checkbox or radio button is on when its appearance state is not Off. */
private fun isOn(w: PDFWidget): Boolean {
    val a = w.getObject().get("AS")
    return a.isName && a.asName() != "Off"
}

/** The form fields of one page, or of every page. */
fun PdfDoc.formFields(pageIndex: Int? = null): List<FormField> {
    val out = ArrayList<FormField>()
    val pages = pageIndex?.let { listOf(it) } ?: (0 until pageCount).toList()
    for (p in pages) {
        withPdfPage(p) { page ->
            page.widgetList().forEachIndexed { index, w ->
                val kind = kindOf(w)
                val flags = w.fieldFlags
                val b = w.bounds
                out.add(
                    FormField(
                        page = p,
                        index = index,
                        name = runCatching { w.name }.getOrNull().orEmpty(),
                        kind = kind,
                        box = Box(b.x0, b.y0, b.x1, b.y1),
                        value = if (kind == FieldKind.Checkbox || kind == FieldKind.Radio) "" else runCatching { w.value }.getOrNull().orEmpty(),
                        checked = (kind == FieldKind.Checkbox || kind == FieldKind.Radio) && isOn(w),
                        options = if (kind == FieldKind.Combo || kind == FieldKind.List) runCatching { w.options?.toList() }.getOrNull().orEmpty() else emptyList(),
                        multiline = kind == FieldKind.Text && w.isMultiline,
                        readOnly = (flags and PDFWidget.FIELD_IS_READ_ONLY) != 0,
                        required = (flags and PDFWidget.FIELD_IS_REQUIRED) != 0,
                        maxLen = if (kind == FieldKind.Text) w.maxLen else 0,
                    ),
                )
            }
        }
    }
    return out
}

/** Whether the document has fields to fill at all (the viewer offers "Fill in"). */
fun PdfDoc.hasFormFields(): Boolean = runCatching {
    val acro = pdf.trailer.get("Root").get("AcroForm")
    acro.isDictionary && acro.get("Fields").let { it.isArray && it.size() > 0 }
}.getOrDefault(false)

private fun daOf(w: PDFWidget): String = runCatching {
    val o = w.getObject().getInheritable("DA")
    if (o.isString) o.asString() else ""
}.getOrDefault("")

internal val DA_SIZE = Regex("""([\d.]+)\s+Tf""")
internal val DA_RGB = Regex("""([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+rg""")
internal val DA_GREY = Regex("""([\d.]+)\s+g\b""")

private fun colourOf(o: PDFObject?): Rgb? {
    if (o == null || !o.isArray || o.size() == 0) return null
    val v = (0 until o.size()).map { o.get(it).asFloat() }
    return when (v.size) {
        1 -> Rgb(v[0], v[0], v[0])
        3 -> Rgb(v[0], v[1], v[2])
        4 -> Rgb(1 - minOf(1f, v[0] + v[3]), 1 - minOf(1f, v[1] + v[3]), 1 - minOf(1f, v[2] + v[3]))
        else -> null
    }
}

private fun rectPath(x0: Float, y0: Float, x1: Float, y1: Float) = Path().apply {
    moveTo(x0, y0)
    lineTo(x1, y0)
    lineTo(x1, y1)
    lineTo(x0, y1)
    closePath()
}

/** Draws one page of [w]×[h] points (device space, y down) and returns it as a form XObject of this document. */
internal fun PdfDoc.drawForm(w: Float, h: Float, paint: (Device) -> Unit): PDFObject {
    val buffer = Buffer()
    val writer = DocumentWriter(buffer, "pdf", "")
    try {
        val dev = writer.beginPage(Rect(0f, 0f, w, h))
        try {
            paint(dev)
            writer.endPage()
        } finally {
            // Our hold on the page's device goes now, while the writer is alive (see stamp()).
            dev.destroy()
        }
        writer.close()
    } finally {
        writer.destroy()
    }
    val overlay = PdfEngine.openedAsPdf(Document.openDocument(buffer.asByteArray(), "application/pdf"))
        ?: throw PdfException(PdfException.Code.Unsupported, "The field could not be drawn.")
    try {
        runCatching { overlay.subsetFonts() }
        val source = overlay.findPage(0)
        val form = pdf.newDictionary()
        form.put("Type", pdf.newName("XObject"))
        form.put("Subtype", pdf.newName("Form"))
        form.put("BBox", Rect(0f, 0f, w, h))
        val graft = pdf.newPDFGraftMap()
        try {
            form.put("Resources", graft.graftObject(source.get("Resources")))
        } finally {
            graft.destroy()
        }
        val content = contentOf(source)
        try {
            return pdf.addStream(content, form)
        } finally {
            content.destroy()
            source.destroy()
        }
    } finally {
        PdfEngine.dispose(overlay)
        buffer.destroy()
    }
}

private const val FF_MULTILINE = 1 shl 12
private const val FF_PASSWORD = 1 shl 13

/** A field flag word (/Ff), inherited from the field. */
private fun fieldFlags(o: PDFObject): Int = runCatching {
    val ff = o.getInheritable("Ff")
    if (ff.isNumber) ff.asInteger() else 0
}.getOrDefault(0)

private fun daOfObject(o: PDFObject): String = runCatching {
    val v = o.getInheritable("DA")
    if (v.isString) v.asString() else ""
}.getOrDefault("")

/** The field a widget belongs to: itself when it carries the name (/T), or its parent. */
internal fun fieldOf(widget: PDFObject): PDFObject {
    val parent = widget.get("Parent")
    return if (widget.get("T").isString || !parent.isDictionary) widget else parent
}

/** The field's fully qualified name: its /T after its ancestors', joined with dots. */
internal fun fullNameOf(field: PDFObject): String {
    val parts = ArrayList<String>()
    var o = field
    for (k in 0 until 32) {
        if (!o.isDictionary) break
        val t = o.get("T")
        if (t.isString) parts.add(0, t.asString())
        o = o.get("Parent")
    }
    return parts.joinToString(".")
}

/**
 * Other field dictionaries of the form with [field]'s fully qualified name and type [ft]: one
 * field by the standard, written as several by some programs. Bounded, so a looping tree ends.
 */
internal fun PdfDoc.twinsOf(field: PDFObject, ft: String): List<PDFObject> {
    val name = fullNameOf(field)
    val self = numOf(field)
    if (name.isEmpty() || self < 0) return emptyList()
    val acro = runCatching { pdf.trailer.get("Root").get("AcroForm") }.getOrNull() ?: return emptyList()
    val fields = if (acro.isDictionary) acro.get("Fields") else return emptyList()
    if (!fields.isArray) return emptyList()
    val twins = ArrayList<PDFObject>()
    val seen = HashSet<Int>()
    fun walk(node: PDFObject, depth: Int) {
        if (depth > 32 || seen.size > 20_000 || !node.isDictionary) return
        val num = numOf(node)
        if (num >= 0 && !seen.add(num)) return
        val type = node.getInheritable("FT")
        if (num >= 0 && num != self && node.get("T").isString && type.isName && type.asName() == ft && fullNameOf(node) == name) twins.add(node)
        val kids = node.get("Kids")
        if (kids.isArray) for (i in 0 until kids.size()) walk(kids.get(i), depth + 1)
    }
    for (i in 0 until fields.size()) walk(fields.get(i), 0)
    return twins
}

/** Every widget of [field] (itself when field and widget are one dictionary). */
internal fun widgetsOf(field: PDFObject, depth: Int = 0): List<PDFObject> {
    val kids = field.get("Kids")
    if (!kids.isArray || depth > 32) return listOf(field)
    val out = ArrayList<PDFObject>()
    for (i in 0 until kids.size()) {
        val k = kids.get(i)
        val subtype = k.get("Subtype")
        if (subtype.isName && subtype.asName() == "Widget") out.add(k)
        else if (k.get("Kids").isArray) out.addAll(widgetsOf(k, depth + 1))
    }
    return out
}

/**
 * A text field's value drawn as its appearance (one widget): shaped, aligned as the field says
 * (or by its direction), wrapped if multi-line; a password field shows bullets (F1). A widget
 * turned by /MK /R (fields on a turned page, as Acrobat makes them) is drawn upright in its own
 * box and turned into /Rect by the appearance's /Matrix (desktop §78).
 */
private fun PdfDoc.drawTextField(widget: PDFObject, text: String, da: String) {
    val rect = widget.get("Rect")
    if (!rect.isArray || rect.size() < 4) return
    val r = (0 until 4).map { rect.get(it).asFloat() }
    val flags = fieldFlags(widget)
    val value = if (flags and FF_PASSWORD != 0) "•".repeat(text.codePointCount(0, text.length)) else text
    val mk = widget.get("MK")
    val turn = if (mk.isDictionary && mk.get("R").isNumber) mk.get("R").asInteger() else 0
    val sideways = kotlin.math.abs(Math.round(turn / 90f)) % 2 == 1
    val width = kotlin.math.abs(if (sideways) r[3] - r[1] else r[2] - r[0])
    val height = kotlin.math.abs(if (sideways) r[2] - r[0] else r[3] - r[1])
    if (width < 2 || height < 2) return
    val asked = DA_SIZE.find(da)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    val colour = DA_RGB.find(da)?.let { m -> Rgb(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat()) }
        ?: DA_GREY.find(da)?.let { m -> m.groupValues[1].toFloat().let { g -> Rgb(g, g, g) } }
        ?: Rgb(0f, 0f, 0f)
    val background = if (mk.isDictionary) colourOf(mk.get("BG")) else null
    val border = if (mk.isDictionary) colourOf(mk.get("BC")) else null
    val bs = widget.get("BS")
    val borderWidth = if (bs.isDictionary && bs.get("W").isNumber) bs.get("W").asFloat() else 1f
    val multiline = flags and FF_MULTILINE != 0
    // A size the field cannot hold (some tools write one fitted to the empty box) counts as "fit".
    val fits = asked > 0 && (if (multiline) asked * 2.4f <= height else asked <= height * 0.9f)
    var size = if (fits) asked else if (multiline) minOf(11f, height / 2.6f) else minOf(12f, maxOf(6f, height * 0.62f))
    val pad = 2f + if (border != null) borderWidth else 0f
    val q = widget.getInheritable("Q")
    val rtl = StampText.isRtl(value)
    val align = when (if (q.isNumber) q.asInteger() else if (rtl) 2 else 0) {
        1 -> HAlign.Center
        2 -> HAlign.Right
        else -> HAlign.Left
    }
    val room = width - 2 * pad
    var block = layoutText(value, TextStyle(size, colour, align = align, lineHeight = 1.2f), if (multiline) room else 100_000f)
    // A single line too long for the field shrinks to fit, as Acrobat's auto size does.
    if (!multiline && !fits && block.width > room && block.width > 0) {
        size = maxOf(4f, size * room / block.width)
        block.destroy()
        block = layoutText(value, TextStyle(size, colour, align = align, lineHeight = 1.2f), 100_000f)
    }
    try {
        val xobject = drawForm(width, height) { dev ->
            if (background != null) dev.fillPath(rectPath(0f, 0f, width, height), false, Matrix(), ColorSpace.DeviceRGB, background.toArray(), 1f, 0)
            if (border != null && borderWidth > 0) {
                val half = borderWidth / 2
                dev.strokePath(rectPath(half, half, width - half, height - half), StrokeState(StrokeState.LINE_CAP_BUTT, StrokeState.LINE_JOIN_MITER, borderWidth, 10f), Matrix(), ColorSpace.DeviceRGB, border.toArray(), 1f, 0)
            }
            if (!block.isEmpty) {
                val x = when (align) {
                    HAlign.Left -> pad
                    HAlign.Right -> width - pad - block.width
                    HAlign.Center -> (width - block.width) / 2
                }
                val y = if (multiline) pad else (height - block.height) / 2
                block.draw(dev, Matrix.Translate(x, y))
            }
        }
        val m = Upright.turnMatrix(turn)
        if (!Upright.isIdentity(m)) xobject.put("Matrix", Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        val ap = pdf.newDictionary()
        ap.put("N", xobject)
        widget.put("AP", ap)
    } finally {
        block.destroy()
    }
}

/**
 * Draws a checkbox's or radio button's two looks (on and Off) when its /AP does not hold them:
 * the frame and background from /MK, and a tick (a dot for a radio button) in the /DA colour when
 * on, turned by /MK /R like a text field. A box with its own looks keeps them.
 */
private fun PdfDoc.drawCheckIfMissing(widget: PDFObject, radio: Boolean) {
    val n = widget.get("AP").get("N")
    val states = if (n.isDictionary) n.iterator().asSequence().map { it.asName() }.toList() else emptyList()
    val state = widget.get("AS").let { if (it.isName && it.asName() != "Off") it.asName() else null }
    val onName = states.firstOrNull { it != "Off" } ?: state ?: "Yes"
    if (n.isDictionary && n.get(onName).isStream && n.get("Off").isStream) return
    val rect = widget.get("Rect")
    if (!rect.isArray || rect.size() < 4) return
    val r = (0 until 4).map { rect.get(it).asFloat() }
    val mk = widget.get("MK")
    val turn = if (mk.isDictionary && mk.get("R").isNumber) mk.get("R").asInteger() else 0
    val sideways = kotlin.math.abs(Math.round(turn / 90f)) % 2 == 1
    val width = kotlin.math.abs(if (sideways) r[3] - r[1] else r[2] - r[0])
    val height = kotlin.math.abs(if (sideways) r[2] - r[0] else r[3] - r[1])
    if (width < 2 || height < 2) return
    val da = daOfObject(widget)
    val colour = DA_RGB.find(da)?.let { m -> Rgb(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat()) }
        ?: DA_GREY.find(da)?.let { m -> m.groupValues[1].toFloat().let { g -> Rgb(g, g, g) } }
        ?: Rgb(0f, 0f, 0f)
    val background = if (mk.isDictionary) colourOf(mk.get("BG")) else null
    val border = if (mk.isDictionary) colourOf(mk.get("BC")) else null
    val bs = widget.get("BS")
    val borderWidth = if (bs.isDictionary && bs.get("W").isNumber) bs.get("W").asFloat() else 1f
    val m = Upright.turnMatrix(turn)
    fun look(on: Boolean): PDFObject {
        val xo = drawForm(width, height) { dev ->
            if (background != null) dev.fillPath(rectPath(0f, 0f, width, height), false, Matrix(), ColorSpace.DeviceRGB, background.toArray(), 1f, 0)
            if (border != null && borderWidth > 0) {
                val half = borderWidth / 2
                dev.strokePath(rectPath(half, half, width - half, height - half), StrokeState(StrokeState.LINE_CAP_BUTT, StrokeState.LINE_JOIN_MITER, borderWidth, 10f), Matrix(), ColorSpace.DeviceRGB, border.toArray(), 1f, 0)
            }
            if (on) {
                val s = minOf(width, height)
                val cx = width / 2
                val cy = height / 2
                if (radio) {
                    val k = s * 0.22f
                    val c = k * 0.5523f
                    val dot = Path().apply {
                        moveTo(cx + k, cy)
                        curveTo(cx + k, cy + c, cx + c, cy + k, cx, cy + k)
                        curveTo(cx - c, cy + k, cx - k, cy + c, cx - k, cy)
                        curveTo(cx - k, cy - c, cx - c, cy - k, cx, cy - k)
                        curveTo(cx + c, cy - k, cx + k, cy - c, cx + k, cy)
                        closePath()
                    }
                    dev.fillPath(dot, false, Matrix(), ColorSpace.DeviceRGB, colour.toArray(), 1f, 0)
                } else {
                    val tick = Path().apply {
                        moveTo(cx - s * 0.28f, cy + s * 0.02f)
                        lineTo(cx - s * 0.08f, cy + s * 0.24f)
                        lineTo(cx + s * 0.3f, cy - s * 0.24f)
                    }
                    dev.strokePath(tick, StrokeState(StrokeState.LINE_CAP_ROUND, StrokeState.LINE_JOIN_ROUND, maxOf(1f, s * 0.12f), 10f), Matrix(), ColorSpace.DeviceRGB, colour.toArray(), 1f, 0)
                }
            }
        }
        if (!Upright.isIdentity(m)) xo.put("Matrix", Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        return xo
    }
    val normal = pdf.newDictionary()
    normal.put(onName, look(true))
    normal.put("Off", look(false))
    val ap = pdf.newDictionary()
    ap.put("N", normal)
    widget.put("AP", ap)
    // The state must name one of the looks.
    val asName = widget.get("AS")
    if (!asName.isName || (asName.asName() != "Off" && asName.asName() != onName)) widget.put("AS", pdf.newName("Off"))
}

/** Needs the author's "fill in forms" right (desktop §23). */
private fun PdfDoc.requireForms() {
    if (!rights().forms) throw PdfException(PdfException.Code.Restricted, "The author does not allow filling in this form.")
}

/** A form written (also) in XFA, Adobe's XML forms (desktop forms.ts xfaKind). */
enum class XfaKind {
    /** XFA beside ordinary fields: Acrobat builds the form from the XFA, so it is dropped when the fields change. */
    Hybrid,

    /** XFA only (no fields, or /NeedsRendering): the pages hold a "please wait" placeholder; it cannot be shown or filled here. */
    Dynamic,
}

fun PdfDoc.xfaKind(): XfaKind? = runCatching {
    val root = pdf.trailer.get("Root")
    val acro = root.get("AcroForm")
    if (!acro.isDictionary) return@runCatching null
    val xfa = acro.get("XFA")
    if (!xfa.isArray && !xfa.isStream) return@runCatching null
    val fields = acro.get("Fields")
    val needsRendering = root.get("NeedsRendering")
    if ((needsRendering.isBoolean && needsRendering.asBoolean()) || !fields.isArray || fields.size() == 0) XfaKind.Dynamic else XfaKind.Hybrid
}.getOrNull()

/**
 * Removes the XFA form (and /NeedsRendering), leaving the AcroForm fields, as Acrobat does when it
 * saves a change to a hybrid form: otherwise Acrobat shows the old values from the XFA data. A
 * signed form keeps its catalogue as signed. True if there was one.
 */
internal fun PdfDoc.dropXfa(): Boolean {
    val root = pdf.trailer.get("Root")
    if (!root.isDictionary || isSigned()) return false
    val acro = root.get("AcroForm")
    val had = acro.isDictionary && !acro.get("XFA").isNull
    if (had) acro.delete("XFA")
    if (!root.get("NeedsRendering").isNull) root.delete("NeedsRendering")
    return had
}

/**
 * Sets one field: [text] for text fields and choices, [checked] for checkboxes and radio buttons.
 * Every widget of a text field (the same value shown twice) gets our drawing (F2). A dynamic XFA
 * form is refused (it has nothing to fill here); a hybrid one loses its XFA, as in Acrobat.
 */
fun PdfDoc.setField(pageIndex: Int, index: Int, text: String? = null, checked: Boolean? = null) {
    requireForms()
    if (xfaKind() == XfaKind.Dynamic) throw PdfException(PdfException.Code.DynamicXfa, "This is a dynamic XFA form (Adobe LiveCycle). It can only be shown and filled in Adobe Acrobat or Adobe Reader.")
    withPdfPage(pageIndex) { page ->
        val w = page.widgetList().getOrNull(index) ?: throw PdfException(PdfException.Code.BadArguments, "That form field is no longer there.")
        if ((w.fieldFlags and PDFWidget.FIELD_IS_READ_ONLY) != 0) throw PdfException(PdfException.Code.Restricted, "This field is read-only.")
        when (kindOf(w)) {
            FieldKind.Checkbox, FieldKind.Radio -> {
                val want = checked ?: !isOn(w)
                if (isOn(w) != want) w.toggle()
                w.update()
                // A box with no look of its own: MuPDF's drawing of it is kept in memory only and
                // the saved file had none, so the check did not show in other readers.
                val self = annotEntry(page, w.getObject())
                if (self != null) for (wd in widgetsOf(fieldOf(self)).filter { numOf(it) > 0 }) drawCheckIfMissing(wd, kindOf(w) == FieldKind.Radio)
            }
            FieldKind.Combo, FieldKind.List -> {
                w.setChoiceValue(text ?: "")
                w.update()
            }
            FieldKind.Text -> {
                val max = w.maxLen
                val value = (text ?: "").let { if (max > 0) it.take(max) else it }
                // The page's own reference to the widget, so its object number is known.
                val self = annotEntry(page, w.getObject()) ?: w.getObject()
                val selfNum = numOf(self)
                // This widget first, then the field's other widgets (F2), then the widgets of
                // fields with the same fully qualified name: one field by the standard
                // (PDF 32000 §12.7.3.1), written as several by some programs; each gets the
                // value, or Acrobat shows whichever /V it reads first and the typed value seems lost.
                val field = fieldOf(self)
                val twins = twinsOf(field, "Tx")
                for (t in twins) {
                    t.put("V", pdf.newString(value))
                    t.delete("RV")
                }
                val widgets = listOf(self) + widgetsOf(field).filter { numOf(it) > 0 && numOf(it) != selfNum } + twins.flatMap { widgetsOf(it) }.filter { numOf(it) > 0 }
                val password = fieldFlags(self) and FF_PASSWORD != 0
                // MuPDF writes its own fitted size into /DA; each widget's setting is kept.
                val das = widgets.map { daOfObject(it) }
                w.setTextValue(value)
                w.update()
                // After MuPDF's own drawing, so it does not draw over ours. Comb fields (one
                // character per box) keep MuPDF's drawing, which spaces the characters, unless it
                // is a password, which must show bullets (F1).
                // A password field is drawn from the value the file will hold — its default, or
                // nothing — never from what was typed, so the saved look does not say how long
                // the password was (desktop §99). The value itself is taken out on every save.
                val shown = if (password) defaultFieldText(field) ?: "" else value
                if (!w.isComb || password) {
                    widgets.forEachIndexed { i, wd ->
                        drawTextField(wd, shown, das[i])
                        if (das[i].isNotEmpty() && wd.get("DA").isString) wd.put("DA", pdf.newString(das[i]))
                    }
                }
                // Rich text (/RV) would be shown by readers instead of the new value.
                var o: PDFObject = self
                for (k in 0 until 32) {
                    if (!o.isDictionary) break
                    o.delete("RV")
                    o = o.get("Parent")
                }
            }
            else -> throw PdfException(PdfException.Code.Unsupported, "This kind of field cannot be filled in here.")
        }
    }
    // Readers should show our drawing, not make their own.
    runCatching {
        val acro = pdf.trailer.get("Root").get("AcroForm")
        if (acro.isDictionary) acro.delete("NeedAppearances")
    }
    // A hybrid form's XFA would bring the old values back in Acrobat. Taking the packet out only
    // unlinks it: an appended save, or a plain snapshot, keeps what nothing points at, so the old
    // values would still be in the file for anyone to read back (desktop §100). The next save
    // writes the whole file again with its unused objects collected.
    if (dropXfa()) rewriteFully = true
    invalidate()
}

/**
 * Takes [node] (a field or widget) out of the field tree: out of its parent's /Kids, or out of
 * /AcroForm /Fields (and /CO) at the top. A parent left with no kids goes too.
 */
private fun PdfDoc.unlinkField(node: PDFObject, depth: Int = 0) {
    val n = numOf(node)
    fun drop(array: PDFObject) {
        if (!array.isArray || n <= 0) return
        for (i in array.size() - 1 downTo 0) if (numOf(array.get(i)) == n) array.delete(i)
    }
    val parent = node.get("Parent")
    if (parent.isDictionary && depth < 32) {
        val kids = parent.get("Kids")
        drop(kids)
        if (!kids.isArray || kids.size() == 0) unlinkField(parent, depth + 1)
    } else {
        val acro = pdf.trailer.get("Root").get("AcroForm")
        if (acro.isDictionary) drop(acro.get("Fields"))
    }
    val acro = pdf.trailer.get("Root").get("AcroForm")
    if (acro.isDictionary) drop(acro.get("CO"))
}

/**
 * Removes whole fields — every widget of each, from every page, and the field from the form —
 * given any of their widgets (redaction, R2: a field's value is shared by all its widgets, so one
 * widget under a mark takes the field). Returns the widgets' object numbers.
 */
internal fun PdfDoc.removeFieldsOf(widgets: List<PDFObject>): Set<Int> {
    val removed = HashSet<Int>()
    val fields = LinkedHashMap<Int, PDFObject>()
    for (w in widgets) {
        val f = fieldOf(w)
        fields[if (numOf(f) > 0) numOf(f) else -fields.size - 1] = f
    }
    for (f in fields.values) {
        for (w in widgetsOf(f)) if (numOf(w) > 0) removed.add(numOf(w))
        unlinkField(f)
    }
    if (removed.isEmpty()) return removed
    for (p in 0 until pageCount) withPdfPage(p) { page ->
        for (a in page.annots()) if (runCatching { annotNumber(page, a) }.getOrDefault(-1) in removed) runCatching { page.deleteAnnotation(a) }
        val annots = page.getObject().get("Annots")
        if (annots.isArray) for (i in annots.size() - 1 downTo 0) if (numOf(annots.get(i)) in removed) annots.delete(i)
    }
    return removed
}
