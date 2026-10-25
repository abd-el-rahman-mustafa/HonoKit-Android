package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFObject

/**
 * A text field with the Password flag must never have its value stored in the file
 * (PDF 32000-2 §12.7.5.3, Table 231, note to the Password flag: a processor is never to store the
 * value of such a field in the PDF file). Acrobat writes the typed value nowhere: a field with a
 * default value (/DV) is saved holding that default, a field without one is left empty, and the
 * masking characters are drawn from the saved value, so the file does not even say how long the
 * typed password was. /DV and /AA stay.
 *
 * [clearPasswordValues] does the same to the document about to be written, and every save goes
 * through it, so no path can carry a typed password into a file. The value may also sit on an
 * ancestor the field inherits from or on one of its widgets, so all three are cleared.
 * Desktop decisions §99.
 */

private const val PASSWORD_FLAG = 1 shl 13
private const val MAX_DEPTH = 64
private const val MAX_NODES = 20_000

private fun inheritedName(o: PDFObject, key: String): String? = runCatching {
    val v = o.getInheritable(key)
    if (v.isName) v.asName() else null
}.getOrNull()

private fun inheritedFlags(o: PDFObject, key: String): Int = runCatching {
    val v = o.getInheritable(key)
    if (v.isNumber) v.asInteger() else 0
}.getOrDefault(0)

/** A text field whose value is a password: /FT Tx and the Password flag, both inheritable. */
private fun isPasswordText(o: PDFObject): Boolean =
    inheritedName(o, "FT") == "Tx" && (inheritedFlags(o, "Ff") and PASSWORD_FLAG) != 0

/** The field's ancestors, nearest first, bounded so a looping /Parent chain ends. */
private fun ancestorsOf(field: PDFObject): List<PDFObject> {
    val out = ArrayList<PDFObject>()
    val seen = HashSet<Int>()
    var o = field.get("Parent")
    for (k in 0 until MAX_DEPTH) {
        if (!o.isDictionary) break
        val num = numOf(o)
        if (num >= 0 && !seen.add(num)) break
        out.add(o)
        o = o.get("Parent")
    }
    return out
}

/** Whether a /V is there at all, readable as text or not (a stream value counts). */
private fun hasOwnValue(o: PDFObject): Boolean = o.isDictionary && !o.get("V").isNull

/** The text a /V holds, as far as it can be read. */
private fun textOf(v: PDFObject): String = runCatching {
    when {
        v.isString -> v.asString()
        v.isName -> v.asName()
        else -> ""
    }
}.getOrDefault("")

/** Whether the field holds a value at all: its own /V, one inherited, or one on a widget. */
private fun holdsValue(field: PDFObject): Boolean {
    if (hasOwnValue(field)) return textOf(field.get("V")).isNotEmpty()
    for (a in ancestorsOf(field)) if (hasOwnValue(a)) return textOf(a.get("V")).isNotEmpty()
    for (w in widgetsOf(field)) if (numOf(w) != numOf(field) && hasOwnValue(w)) return textOf(w.get("V")).isNotEmpty()
    return false
}

/** The field's default value (/DV, inheritable) as text, or null when it has none. */
internal fun defaultFieldText(field: PDFObject): String? = runCatching {
    val dv = field.getInheritable("DV")
    if (dv.isNull) null else textOf(dv)
}.getOrNull()

/**
 * Every password text field of the form that holds a value, found the way the fields are listed:
 * the tree under /AcroForm /Fields, and the widgets of every page with the fields above them, so
 * a widget no /Fields entry reaches is covered too. [stopAtFirst] returns as soon as one is found.
 */
private fun PdfDoc.passwordFields(stopAtFirst: Boolean): List<PDFObject> {
    val acro = runCatching { pdf.trailer.get("Root").get("AcroForm") }.getOrNull() ?: return emptyList()
    if (!acro.isDictionary) return emptyList()
    val out = ArrayList<PDFObject>()
    val seen = HashSet<Int>()
    val taken = HashSet<Int>()
    // true when the search is over.
    fun take(node: PDFObject): Boolean {
        if (!isPasswordText(node)) return false
        val field = fieldOf(node)
        val num = numOf(field)
        if (num >= 0 && !taken.add(num)) return false
        if (!holdsValue(field)) return false
        out.add(field)
        return stopAtFirst
    }
    fun walk(node: PDFObject, depth: Int): Boolean {
        if (depth > MAX_DEPTH || seen.size > MAX_NODES || !node.isDictionary) return false
        val num = numOf(node)
        if (num >= 0 && !seen.add(num)) return false
        val kids = node.get("Kids")
        if (kids.isArray) for (i in 0 until kids.size()) if (walk(kids.get(i), depth + 1)) return true
        return take(node)
    }
    val fields = acro.get("Fields")
    if (fields.isArray) for (i in 0 until fields.size()) if (walk(fields.get(i), 0)) return out
    val pages = runCatching { pageCount }.getOrDefault(0)
    for (p in 0 until pages) {
        val annots = runCatching { pdf.findPage(p).get("Annots") }.getOrNull() ?: continue
        if (!annots.isArray) continue
        for (i in 0 until annots.size()) {
            val a = annots.get(i)
            if (!a.isDictionary) continue
            val subtype = a.get("Subtype")
            if (!subtype.isName || subtype.asName() != "Widget") continue
            if (take(a)) return out
        }
    }
    return out
}

/** Whether any password text field of the document holds a value that a save would store. */
internal fun PdfDoc.hasPasswordValues(): Boolean = passwordFields(true).isNotEmpty()

/** Whether every field under [node] that could inherit its value is a password text field. */
private fun onlyPasswordFieldsUnder(node: PDFObject, depth: Int = 0): Boolean {
    if (depth > MAX_DEPTH) return false
    val kids = node.get("Kids")
    if (!kids.isArray) return isPasswordText(node)
    var any = false
    for (i in 0 until kids.size()) {
        val k = kids.get(i)
        if (!k.isDictionary) continue
        val subtype = k.get("Subtype")
        // A widget of this very field, not a field of its own.
        if (subtype.isName && subtype.asName() == "Widget" && !k.get("T").isString) continue
        any = true
        if (!onlyPasswordFieldsUnder(k, depth + 1)) return false
    }
    return if (any) true else isPasswordText(node)
}

/**
 * Takes the value out of every password text field, as Acrobat does on save: the field is left
 * holding its default value (/DV) or nothing at all, and the same is done to its widgets and to
 * the ancestors only password fields inherit from. Where an ancestor's value has to stay because
 * an ordinary field inherits it too, the field is given an empty value of its own instead, so it
 * no longer inherits the password. Returns how many fields were changed.
 */
internal fun PdfDoc.clearPasswordValues(): Int {
    var changed = 0
    for (field in passwordFields(false)) {
        val def = defaultFieldText(field)
        var shadow = false
        for (w in widgetsOf(field)) {
            if (numOf(w) == numOf(field)) continue
            w.delete("V")
            w.delete("RV")
        }
        for (a in ancestorsOf(field)) {
            if (!hasOwnValue(a)) continue
            if (onlyPasswordFieldsUnder(a)) a.delete("V") else shadow = true
        }
        field.delete("RV")
        when {
            def != null -> field.put("V", pdf.newString(def))
            shadow -> field.put("V", pdf.newString(""))
            else -> field.delete("V")
        }
        changed++
    }
    return changed
}
