package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject

/**
 * Redaction and the structure tree (tagged PDF). An element of the tree can spell out the content
 * it tags: /ActualText, /Alt and /E. MuPDF's redaction trims some of these and keeps others: an
 * element whose actual text is longer than the removed words, one that inherits its page from a
 * parent, and the parents around it kept the redacted words (desktop §97). And an element tagging
 * a comment points at it (/OBJR), so a comment removed from its page was still written to the file.
 *
 * The walk starts from the root's /K and also from the parent tree and the ID tree. When the tree
 * is too large to walk in full, every element in the file is cleaned instead (the safe side).
 */
internal object StructText {
    val KEYS = listOf("ActualText", "Alt", "E")

    /** The most references one walk visits; a real tree has far fewer. */
    const val LIMIT = 2_000_000

    /** The most ancestors followed up /P. */
    const val MAX_PARENTS = 256

    fun typeOf(o: PDFObject): String = o.get("Type").let { if (it.isName) it.asName() else "" }

    fun kidsOf(o: PDFObject): List<PDFObject> {
        val k = o.get("K")
        return when {
            k.isArray -> (0 until k.size()).map { k.get(it) }
            k.isNull -> emptyList()
            else -> listOf(k)
        }
    }

    fun root(pdf: PDFDocument): PDFObject? {
        val root = pdf.trailer.get("Root")
        val tree = if (root.isDictionary) root.get("StructTreeRoot") else null
        return tree?.takeIf { it.isDictionary }
    }

    /** The nearest /Pg up the /P chain: null when none, -1 when the lookup gives up. */
    private fun inheritedPage(elem: PDFObject): Int? {
        val seen = HashSet<Int>()
        var o = elem
        repeat(MAX_PARENTS) {
            if (!o.isDictionary || typeOf(o) == "StructTreeRoot") return null
            if (o.isIndirect && !seen.add(o.asIndirect())) return -1
            val pg = o.get("Pg")
            if (pg.isIndirect) return pg.asIndirect()
            o = o.get("P")
        }
        return -1
    }

    /**
     * Visits every element once with the page it is on (its own /Pg or the one it inherits).
     * False when the walk could not be finished: too many references, or an element whose page
     * could not be worked out.
     */
    fun walk(root: PDFObject, visit: (PDFObject, Int?) -> Unit): Boolean {
        val visited = HashSet<Int>()
        var count = 0
        val queue = ArrayDeque<Pair<PDFObject, Int?>>()
        fun run(): Boolean {
            while (queue.isNotEmpty()) {
                val (elem, inherited) = queue.removeLast()
                if (++count > LIMIT) return false
                if (!elem.isDictionary) continue
                if (elem.isIndirect && !visited.add(elem.asIndirect())) continue
                val type = typeOf(elem)
                if (type == "MCR" || type == "OBJR" || type == "StructTreeRoot") continue
                val pg = elem.get("Pg")
                val page = if (pg.isIndirect) pg.asIndirect() else inherited
                visit(elem, page)
                for (kid in kidsOf(elem)) if (kid.isDictionary) queue.addLast(kid to page)
            }
            return true
        }
        for (kid in kidsOf(root)) if (kid.isDictionary) queue.addLast(kid to null)
        if (!run()) return false
        // Elements only the parent tree or the ID tree name; their page comes from their parents.
        val more = ArrayList<PDFObject>()
        for ((_, value) in numberTree(root.get("ParentTree"))) {
            if (value.isArray) for (i in 0 until value.size()) more.add(value.get(i)) else more.add(value)
        }
        for ((_, value) in nameTree(root.get("IDTree"))) more.add(value)
        for (elem in more) {
            if (!elem.isDictionary || (elem.isIndirect && elem.asIndirect() in visited)) continue
            val page = inheritedPage(elem)
            if (page == -1) return false
            queue.addLast(elem to page)
            if (!run()) return false
        }
        return true
    }

    /** Every structure element in the file, found by reading each object rather than through the tree. */
    fun everyElement(pdf: PDFDocument, visit: (PDFObject) -> Unit) {
        for (n in 1 until pdf.countObjects()) {
            val o = runCatching { pdf.newIndirect(n, 0) }.getOrNull() ?: continue
            if (!o.isDictionary) continue
            val type = typeOf(o)
            if (type == "StructElem" || (type == "" && o.get("S").isName && o.get("P").isDictionary && !o.get("Subtype").isName)) visit(o)
        }
    }

    fun clearKeys(elem: PDFObject) {
        for (k in KEYS) if (!elem.get(k).isNull) elem.delete(k)
    }
}

/**
 * Clears /ActualText, /Alt and /E from the elements tagging [mcids] of page [index] (marked content
 * a redaction changed or removed) and from the elements around them. Content tagged inside a form
 * XObject (an MCR with /Stm) is not matched here.
 */
internal fun PdfDoc.clearTagText(index: Int, mcids: Set<Int>) {
    if (mcids.isEmpty()) return
    val root = StructText.root(pdf) ?: return
    val pageObj = pdf.findPage(index)
    if (!pageObj.isIndirect) return
    val pageNum = pageObj.asIndirect()
    val owners = ArrayList<PDFObject>()
    val complete = StructText.walk(root) { elem, page ->
        for (kid in StructText.kidsOf(elem)) {
            var mcid: Int? = null
            var on = page
            if (kid.isInteger) mcid = kid.asInteger()
            else if (kid.isDictionary && StructText.typeOf(kid) == "MCR" && kid.get("Stm").isNull && kid.get("MCID").isInteger) {
                mcid = kid.get("MCID").asInteger()
                if (kid.get("Pg").isIndirect) on = kid.get("Pg").asIndirect()
            }
            if (mcid != null && on == pageNum && mcid in mcids) {
                owners.add(elem)
                break
            }
        }
    }
    if (!complete) {
        StructText.everyElement(pdf, StructText::clearKeys)
        return
    }
    for (owner in owners) {
        // The element and every element around it, up to the root.
        val seen = HashSet<Int>()
        var o = owner
        for (i in 0 until StructText.MAX_PARENTS) {
            if (!o.isDictionary || StructText.typeOf(o) == "StructTreeRoot") break
            if (o.isIndirect && !seen.add(o.asIndirect())) break
            StructText.clearKeys(o)
            o = o.get("P")
        }
    }
}

/**
 * Drops the tree's references (/OBJR) to annotations no page lists any more, so a comment or field
 * removed by redaction is not written to the file through its tag.
 */
internal fun PdfDoc.dropRemovedAnnotRefs() {
    val root = StructText.root(pdf) ?: return
    val live = HashSet<Int>()
    for (p in 0 until pdf.countPages()) {
        val annots = pdf.findPage(p).get("Annots")
        if (annots.isArray) for (i in 0 until annots.size()) if (annots.get(i).isIndirect) live.add(annots.get(i).asIndirect())
    }
    fun gone(kid: PDFObject): Boolean {
        if (!kid.isDictionary || StructText.typeOf(kid) != "OBJR") return false
        val obj = kid.get("Obj")
        if (!obj.isIndirect || !obj.isDictionary) return false
        // Only annotations: an OBJR can also tag a picture or a form XObject, which stay.
        val annot = StructText.typeOf(obj) == "Annot" || (obj.get("Subtype").isName && obj.get("Rect").isArray && !obj.get("Type").isName)
        return annot && obj.asIndirect() !in live
    }
    fun clean(elem: PDFObject) {
        val k = elem.get("K")
        if (k.isArray) {
            val kids = (0 until k.size()).map { k.get(it) }
            if (kids.none(::gone)) return
            val list = pdf.newArray()
            for (kid in kids) if (!gone(kid)) list.append(kid)
            elem.put("K", list)
        } else if (gone(k)) elem.delete("K")
    }
    if (!StructText.walk(root) { elem, _ -> clean(elem) }) StructText.everyElement(pdf, ::clean)
}
