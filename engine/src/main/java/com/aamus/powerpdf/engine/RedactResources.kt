package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFObject

/**
 * Redaction gives a rewritten page its own pruned /Resources, but the dictionary the page drew
 * through before can still name the picture or form that was removed: one inherited from a /Pages
 * node, or one resource dictionary shared by several pages. The full rewrite keeps whatever is
 * still referenced, so the removed picture's samples would stay in the saved file.
 *
 * [xobjectsDrawn] is called on the redacted pages before the redaction, [unlinkRemoved] after it:
 * every page-tree XObject dictionary that still names an object a redacted page no longer draws
 * loses that entry, unless a page drawing through that dictionary still uses the name (the same
 * picture on another page). When a page's use of a name cannot be told, it fails closed.
 */
internal fun PdfDoc.xobjectsDrawn(pages: List<Int>): Map<Int, Set<Int>> =
    pages.associateWith { refsIn(pdf.findPage(it).getInheritable("Resources")) }

private fun refsIn(res: PDFObject): Set<Int> {
    if (!res.isDictionary) return emptySet()
    val xobj = res.get("XObject")
    if (!xobj.isDictionary) return emptySet()
    val out = HashSet<Int>()
    for (key in xobj) {
        val v = xobj.get(key)
        if (v.isIndirect) out.add(v.asIndirect())
    }
    return out
}

/** Removes what the redaction took off the redacted pages from the dictionaries they shared. */
internal fun PdfDoc.unlinkRemoved(before: Map<Int, Set<Int>>): Int {
    val kept = before.keys.flatMap { refsIn(pdf.findPage(it).getInheritable("Resources")) }.toSet()
    val removed = before.values.flatten().filter { it !in kept }.toSet()
    if (removed.isEmpty()) return 0
    var unlinked = 0
    for (group in xobjectGroups("XObject").values) {
        val names = ArrayList<String>()
        for (key in group.xobj) {
            val v = group.xobj.get(key)
            if (v.isIndirect && v.asIndirect() in removed) names.add(key.asName())
        }
        for (name in names) {
            if (group.pages.any { drawsName(it, group.xobj, name) }) continue
            group.xobj.delete(name)
            unlinked++
        }
    }
    return unlinked
}

private class XGroup(val xobj: PDFObject, val pages: MutableList<PDFObject> = ArrayList())

/**
 * Every XObject dictionary in the page tree, with the pages that draw through it. One held
 * directly in a node's /Resources is told apart by that node; one that is an object of its own (or
 * inside a /Resources that is) by its object number, so a dictionary shared by nodes is one group.
 */
private fun PdfDoc.xobjectGroups(kind: String): Map<String, XGroup> {
    val out = LinkedHashMap<String, XGroup>()
    val seen = HashSet<Int>()
    fun groupOf(node: PDFObject): String? {
        val res = node.get("Resources")
        if (!res.isDictionary) return null
        val xobj = res.get(kind)
        if (!xobj.isDictionary) return null
        val key = when {
            xobj.isIndirect -> "x${xobj.asIndirect()}"
            res.isIndirect -> "r${res.asIndirect()}"
            else -> "n${node.asIndirect()}"
        }
        out.getOrPut(key) { XGroup(xobj) }
        return key
    }
    fun visit(node: PDFObject, inherited: String?, depth: Int) {
        if (!node.isIndirect || depth > 64) throw PdfException(PdfException.Code.Corrupt, "The page tree could not be followed.")
        if (!seen.add(node.asIndirect())) throw PdfException(PdfException.Code.Corrupt, "The page tree could not be followed.")
        // A /Resources that is not a dictionary does not stop inheritance in readers either.
        val own = groupOf(node) ?: if (node.get("Resources").isDictionary) null else inherited
        val kids = node.get("Kids")
        val type = node.get("Type")
        if ((type.isName && type.asName() == "Pages") || kids.isArray) {
            for (i in 0 until kids.size()) visit(kids.get(i), own, depth + 1)
            return
        }
        if (own != null) out[own]!!.pages.add(node)
    }
    visit(pdf.trailer.get("Root").get("Pages"), null, 0)
    return out
}

/**
 * Removes marked-content property lists named [names] (their /ActualText or /Alt described text a
 * redaction removed) from every page-tree /Properties dictionary that no page drawing through it
 * still names them in. Returns how many entries went.
 */
internal fun PdfDoc.unlinkProperties(names: Set<String>): Int {
    if (names.isEmpty()) return 0
    var unlinked = 0
    for (group in xobjectGroups("Properties").values) {
        for (name in names) {
            if (group.xobj.get(name).isNull) continue
            if (group.pages.any { drawsName(it, group.xobj, name) }) continue
            group.xobj.delete(name)
            unlinked++
        }
    }
    return unlinked
}

/**
 * Whether [page] still uses [name] from [xobj]: as a name in its content, or in a form of that
 * dictionary without /Resources of its own (it draws through the page's). A content stream that
 * cannot be read fails the redaction rather than guessing.
 */
private fun drawsName(page: PDFObject, xobj: PDFObject, name: String): Boolean {
    val streams = ArrayList<PDFObject>()
    val contents = page.get("Contents")
    if (contents.isArray) for (i in 0 until contents.size()) streams.add(contents.get(i))
    else if (contents.isStream) streams.add(contents)
    for (key in xobj) {
        val v = xobj.get(key)
        val sub = v.get("Subtype")
        if (v.isStream && sub.isName && sub.asName() == "Form" && !v.get("Resources").isDictionary) streams.add(v)
    }
    for (s in streams) {
        val bytes = try {
            s.readStream()
        } catch (e: Exception) {
            throw PdfException(PdfException.Code.Corrupt, "A page could not be read, so the redaction could not be checked.")
        }
        if (name in ContentNames.of(String(bytes, Charsets.ISO_8859_1))) return true
    }
    return false
}

object ContentNames {
    private val NAME = Regex("""/([^\s/\[\]()<>{}%]*)""")
    private val ESCAPE = Regex("#([0-9a-fA-F]{2})")

    /** The names in a content stream, #xx escapes decoded. */
    fun of(text: String): Set<String> =
        NAME.findAll(text).map { m -> ESCAPE.replace(m.groupValues[1]) { it.groupValues[1].toInt(16).toChar().toString() } }.toSet()
}
