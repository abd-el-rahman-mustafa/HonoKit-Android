package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFGraftMap
import com.artifex.mupdf.fitz.PDFObject

/**
 * Copying pages with everything that belongs to them, and putting the catalogue back after
 * MuPDF's rearrangePages (desktop src/engine/pages.ts, review items P1, P2, P3;
 * decisions §50).
 */

/**
 * A new indirect object holding [o]. Never PDFDocument.addObject: in MuPDF 1.28.5's Java binding
 * it hands back the object it was given, not a reference, so the "new object" ends up written
 * inline wherever it is used (docs/testing.md).
 */
internal fun PDFDocument.makeIndirect(o: PDFObject): PDFObject = createObject().also { it.writeObject(o) }

private fun PDFObject.keys(): List<PDFObject> = if (isDictionary) iterator().asSequence().toList() else emptyList()

private fun PDFObject.items(): List<PDFObject> = if (isArray) (0 until size()).map { get(it) } else emptyList()

private fun nameOf(o: PDFObject): String = if (o.isName) o.asName() else ""

private fun subtypeOf(o: PDFObject): String = nameOf(o.get("Subtype"))

private fun refNum(o: PDFObject): Int? = if (o.isIndirect) o.asIndirect() else null

/** A copy of a direct value (arrays and dictionaries are not shared); indirect objects stay shared. */
private fun PDFDocument.clone(value: PDFObject, depth: Int = 0): PDFObject {
    if (value.isIndirect || depth > 32) return value
    if (value.isArray) {
        val out = newArray()
        for (v in value.items()) out.append(clone(v, depth + 1))
        return out
    }
    if (value.isDictionary) {
        val out = newDictionary()
        for (k in value.keys()) out.put(k, clone(value.get(k), depth + 1))
        return out
    }
    return value
}

/** A new stream object with the same (still encoded) data and a copy of the dictionary. */
private fun PDFDocument.copyStream(stream: PDFObject): PDFObject {
    val dict = newDictionary()
    for (k in stream.keys()) if (k.asName() != "Length") dict.put(k, clone(stream.get(k)))
    val raw = Buffer()
    try {
        raw.writeBytes(stream.readRawStream())
        return addRawStream(raw, dict)
    } finally {
        raw.destroy()
    }
}

/** An annotation's appearances (/AP) with streams of their own, so redrawing one copy leaves the other. */
private fun PDFDocument.copyAppearance(ap: PDFObject): PDFObject {
    if (!ap.isDictionary) return ap
    val out = newDictionary()
    for (k in ap.keys()) {
        val v = ap.get(k)
        when {
            v.isStream -> out.put(k, copyStream(v))
            v.isDictionary -> {
                val states = newDictionary()
                for (s in v.keys()) v.get(s).let { states.put(s, if (it.isStream) copyStream(it) else clone(it)) }
                out.put(k, states)
            }
            else -> out.put(k, clone(v))
        }
    }
    return out
}

/** The keys that belong to a form field rather than to its widget (PDF 32000 §12.7.3.1, §12.7.4). */
private val FIELD_KEYS = listOf("FT", "T", "TU", "TM", "Ff", "V", "DV", "Opt", "TI", "I", "MaxLen", "Lock", "SV", "RV", "DS")

/** The array at [key] of [owner]; created when missing. */
private fun PDFDocument.arrayAt(owner: PDFObject, key: String): PDFObject {
    if (!owner.get(key).isArray) owner.put(key, newArray())
    return owner.get(key)
}

/**
 * Copies of a page's annotations for another page of the same document, as Acrobat's Duplicate
 * does: each copy is a new object pointing (/P) at the new page, a note's pop-up comes along, and
 * a widget becomes a second widget of the same field (same name, same value). A widget that is its
 * own field is split into a field with two widgets.
 */
private fun PDFDocument.copyAnnotsWithin(annots: PDFObject, page: PDFObject): PDFObject {
    val out = newArray()
    val map = HashMap<Int, PDFObject>()
    val items = ArrayList<Pair<PDFObject, PDFObject>>()
    for (a in annots.items()) {
        if (!a.isDictionary) continue
        val copy = makeIndirect(newDictionary())
        refNum(a)?.let { map[it] = copy }
        items.add(a to copy)
        out.append(copy)
    }
    fun local(v: PDFObject): PDFObject? = refNum(v)?.let { map[it] }
    for ((a, copy) in items) {
        val widget = subtypeOf(a) == "Widget"
        for (key in a.keys()) {
            val k = key.asName()
            val v = a.get(key)
            if (k == "P" || k == "StructParent") continue
            if (k == "Popup" || k == "IRT" || (k == "Parent" && !widget)) {
                val to = local(v)
                if (to != null) copy.put(k, to) else if (k == "IRT") copy.put(k, v)
                continue
            }
            if (widget && k == "Parent") continue
            copy.put(k, if (k == "AP") copyAppearance(v) else clone(v))
        }
        copy.put("P", page)
        if (!widget) continue
        var field = a.get("Parent")
        if (!field.isDictionary && a.isIndirect && (a.get("FT").isName || a.get("T").isString)) {
            // A widget that is also its field: make the field a parent of both widgets.
            field = makeIndirect(newDictionary())
            for (k in FIELD_KEYS) {
                val v = a.get(k)
                if (v.isNull) continue
                field.put(k, v)
                a.delete(k)
                copy.delete(k)
            }
            for (k in listOf("DA", "Q", "DR")) if (!a.get(k).isNull) field.put(k, clone(a.get(k)))
            a.put("Parent", field)
            arrayAt(field, "Kids").append(a)
        }
        if (field.isDictionary) {
            copy.put("Parent", field)
            arrayAt(field, "Kids").append(copy)
        }
    }
    return out
}

/** Page keys inherited from the page tree (PDF 32000 §7.7.3.4): set on the copy itself. */
private val INHERITABLE = listOf("MediaBox", "CropBox", "Rotate")

/** A new page object showing the same page: own content streams, resources dictionary and annotations. */
private fun PDFDocument.copyPage(page: PDFObject): PDFObject {
    val dict = newDictionary()
    for (key in page.keys()) {
        val k = key.asName()
        // StructParents: the copy's content is not in the structure tree (it would point at the original's elements).
        if (k == "Parent" || k == "Annots" || k == "StructParents" || k == "Contents" || k == "Resources") continue
        dict.put(k, clone(page.get(key)))
    }
    for (k in INHERITABLE) {
        if (!dict.get(k).isNull) continue
        val v = page.getInheritable(k)
        if (!v.isNull) dict.put(k, clone(v))
    }
    // Resources: a dictionary of its own (and of its own sub-dictionaries), so a change that swaps
    // a picture or font on one page (redaction does) leaves the other. Fonts and pictures stay shared.
    val resources = page.getInheritable("Resources")
    val res = newDictionary()
    for (k in resources.keys()) {
        val v = resources.get(k)
        if (v.isDictionary && !v.isStream) {
            val sub = newDictionary()
            for (n in v.keys()) sub.put(n, clone(v.get(n)))
            res.put(k, sub)
        } else res.put(k, clone(v))
    }
    dict.put("Resources", res)
    val contents = page.get("Contents")
    if (contents.isArray) {
        val list = newArray()
        for (s in contents.items()) list.append(if (s.isStream) copyStream(s) else s)
        dict.put("Contents", list)
    } else if (contents.isStream) dict.put("Contents", copyStream(contents))
    val copy = makeIndirect(dict)
    val annots = page.get("Annots")
    if (annots.isArray && annots.size() > 0) copy.put("Annots", copyAnnotsWithin(annots, copy))
    return copy
}

/**
 * MuPDF's rearrangePages puts the same page object in the page tree once per listing, so a
 * duplicated page was one page shown twice: turning, redacting or marking up the copy changed the
 * original too (P1). Every repeat gets a page object of its own.
 */
internal fun PDFDocument.separateRepeats() {
    val seen = HashSet<Int>()
    for (i in 0 until countPages()) {
        val page = findPage(i)
        val num = page.asIndirect()
        if (seen.add(num)) continue
        val copy = copyPage(page)
        deletePage(i)
        insertPage(i, copy)
    }
}

// --- The catalogue after rearranging (P3) ------------------------------------------------------

/** The entries of a number tree (/Nums, /Kids), flattened and sorted by key. */
private fun numberTree(tree: PDFObject, depth: Int = 0): List<Pair<Int, PDFObject>> {
    val out = ArrayList<Pair<Int, PDFObject>>()
    if (!tree.isDictionary || depth > 16) return out
    val nums = tree.get("Nums")
    if (nums.isArray) {
        var i = 0
        while (i + 1 < nums.size()) {
            if (nums.get(i).isNumber) out.add(nums.get(i).asInteger() to nums.get(i + 1))
            i += 2
        }
    }
    for (kid in tree.get("Kids").items()) out.addAll(numberTree(kid, depth + 1))
    return out.sortedBy { it.first }
}

/** The entries of a name tree (/Names, /Kids), flattened. */
private fun nameTree(tree: PDFObject, depth: Int = 0): List<Pair<PDFObject, PDFObject>> {
    val out = ArrayList<Pair<PDFObject, PDFObject>>()
    if (!tree.isDictionary || depth > 16) return out
    val names = tree.get("Names")
    if (names.isArray) {
        var i = 0
        while (i + 1 < names.size()) {
            out.add(names.get(i) to names.get(i + 1))
            i += 2
        }
    }
    for (kid in tree.get("Kids").items()) out.addAll(nameTree(kid, depth + 1))
    return out
}

/** The page a destination (explicit array, or a dictionary with /D) points at, as an object number. */
private fun destPage(dest: PDFObject): Int? {
    val d = if (dest.isDictionary) dest.get("D") else dest
    if (!d.isArray || d.size() == 0) return null
    return refNum(d.get(0))
}

/**
 * Page labels after rearranging: each page keeps the label it had ("ii" stays "ii" wherever it
 * goes); a new range starts wherever a page does not follow its old neighbour.
 */
private fun PDFDocument.relabel(labels: PDFObject, order: List<Int>): PDFObject? {
    val ranges = numberTree(labels)
    if (ranges.isEmpty()) return null
    fun rangeOf(page: Int): Int {
        var found = -1
        for (r in ranges.indices) if (ranges[r].first <= page) found = r
        return found
    }
    val nums = newArray()
    order.forEachIndexed { j, page ->
        val r = rangeOf(page)
        if (j > 0 && order[j - 1] == page - 1 && rangeOf(page - 1) == r) return@forEachIndexed
        val style = newDictionary()
        var start = page + 1
        if (r >= 0) {
            val (first, dict) = ranges[r]
            if (dict.isDictionary) {
                for (k in listOf("S", "P")) if (!dict.get(k).isNull) style.put(k, dict.get(k))
                start = (if (dict.get("St").isNumber) dict.get("St").asInteger() else 1) + (page - first)
            } else start = 1 + (page - first)
        } else style.put("S", newName("D"))
        if (start != 1) style.put("St", start)
        nums.push(j)
        nums.append(style)
    }
    val tree = newDictionary()
    tree.put("Nums", nums)
    return tree
}

/**
 * The structure tree (tagged PDF) after pages were removed: elements whose content lay only on
 * removed pages go, with their entries in the parent tree and the ID tree. False when nothing is left.
 */
private fun PDFDocument.pruneStructure(tree: PDFObject, gone: Set<Int>): Boolean {
    val kept = HashSet<Int>()
    val visited = HashSet<Int>()
    fun onGone(pg: Int?) = pg != null && pg in gone
    fun pageOf(o: PDFObject, inherited: Int?): Int? = o.get("Pg").let { if (it.isIndirect) it.asIndirect() else inherited }
    lateinit var element: (PDFObject, Int?, Int) -> Boolean
    // Filters an element's (or the root's) /K; true when something is left, null when it has none.
    fun filterKids(owner: PDFObject, pg: Int?, depth: Int): Boolean? {
        val k = owner.get("K")
        if (k.isNull) return null
        fun keepItem(item: PDFObject): Boolean {
            if (item.isNumber) return !onGone(pg)
            if (!item.isDictionary) return true
            val type = nameOf(item.get("Type"))
            if (type == "MCR" || type == "OBJR") return !onGone(pageOf(item, pg))
            return element(item, pg, depth + 1)
        }
        if (k.isArray) {
            if (k.size() == 0) return null
            val list = newArray()
            for (item in k.items()) if (keepItem(item)) list.append(item)
            owner.put("K", list)
            return list.size() > 0
        }
        if (keepItem(k)) return true
        owner.delete("K")
        return false
    }
    element = fun(elem: PDFObject, inherited: Int?, depth: Int): Boolean {
        if (depth > 256) return true
        val num = refNum(elem)
        if (num != null) {
            if (!visited.add(num)) return num in kept
        }
        val pg = pageOf(elem, inherited)
        val left = filterKids(elem, pg, depth)
        val keep = left ?: !onGone(pg)
        if (!keep) return false
        if (elem.get("Pg").isIndirect && elem.get("Pg").asIndirect() in gone) elem.delete("Pg")
        if (num != null) kept.add(num)
        return true
    }
    val any = filterKids(tree, null, 0)
    fun isKept(v: PDFObject) = !v.isIndirect || v.asIndirect() in kept
    val parents = numberTree(tree.get("ParentTree"))
    if (parents.isNotEmpty()) {
        val nums = newArray()
        for ((key, value) in parents) {
            if (value.isArray) {
                val list = newArray()
                var live = false
                for (v in value.items()) {
                    val ok = !v.isNull && isKept(v)
                    live = live || ok
                    list.append(if (ok) v else newNull())
                }
                if (!live) continue
                nums.push(key)
                nums.append(list)
            } else if (isKept(value)) {
                nums.push(key)
                nums.append(value)
            }
        }
        val parentTree = newDictionary()
        parentTree.put("Nums", nums)
        tree.put("ParentTree", parentTree)
    }
    val ids = nameTree(tree.get("IDTree"))
    if (ids.isNotEmpty()) {
        val names = newArray()
        for ((key, value) in ids) {
            if (!isKept(value)) continue
            names.append(key)
            names.append(value)
        }
        val idTree = newDictionary()
        idTree.put("Names", names)
        tree.put("IDTree", idTree)
    }
    return any != false
}

/** True when a destination or GoTo action leads to a page that was removed. */
private fun onRemovedPage(target: PDFObject, gone: Set<Int>): Boolean {
    if (gone.isEmpty() || (!target.isDictionary && !target.isArray)) return false
    val dest = if (target.isDictionary && target.get("S").isName) {
        if (target.get("S").asName() == "GoTo") target.get("D") else return false
    } else target
    val page = destPage(dest) ?: return false
    return page in gone
}

/** The catalogue's entries and page objects before a rearrangement. */
internal class CatalogSnapshot(val entries: List<Pair<String, PDFObject>>, val pages: List<Int>)

internal fun PDFDocument.snapshotCatalog(): CatalogSnapshot {
    val root = trailer.get("Root")
    return CatalogSnapshot(root.keys().map { it.asName() to root.get(it) }, (0 until countPages()).map { findPage(it).asIndirect() })
}

/**
 * MuPDF's rearrangePages rebuilds the catalogue with little more than the page tree: page labels,
 * the structure tree (tagged PDF), the document's language, viewer preferences, mark info, open
 * action and so on were lost on every keep, delete, move, reorder, extract or duplicate (P3). Put
 * them back, adjusted to the new page order; what points at a removed page is dropped.
 */
internal fun PDFDocument.restoreCatalog(before: CatalogSnapshot, order: List<Int>) {
    val root = trailer.get("Root")
    val now = (0 until countPages()).map { findPage(it).asIndirect() }.toSet()
    val gone = before.pages.filter { it !in now }.toSet()
    // MuPDF keeps these itself (pruned to the pages left), or they are handled elsewhere.
    val handled = setOf("Type", "Pages", "Outlines", "AcroForm")
    for ((key, value) in before.entries) {
        if (key in handled || !root.get(key).isNull) continue
        when (key) {
            "PageLabels" -> relabel(value, order)?.let { root.put(key, it) }
            "StructTreeRoot" -> if (gone.isEmpty() || pruneStructure(value, gone)) root.put(key, value)
            "OpenAction" -> if (!onRemovedPage(value, gone)) root.put(key, value)
            "Dests" -> {
                val dests = newDictionary()
                for (name in value.keys()) value.get(name).let { if (!onRemovedPage(it, gone)) dests.put(name, it) }
                root.put(key, dests)
            }
            "Names" -> {
                val names = newDictionary()
                for (name in value.keys()) {
                    val tree = value.get(name)
                    if (name.asName() != "Dests") {
                        names.put(name, tree)
                        continue
                    }
                    val list = newArray()
                    for ((k, d) in nameTree(tree)) {
                        if (onRemovedPage(d, gone)) continue
                        list.append(k)
                        list.append(d)
                    }
                    val dests = newDictionary()
                    dests.put("Names", list)
                    names.put("Dests", dests)
                }
                root.put(key, names)
            }
            // Article threads run from page to page; kept only when no page went.
            "Threads" -> if (gone.isEmpty()) root.put(key, value)
            else -> root.put(key, value)
        }
    }
    // MuPDF kept /Names (its named destinations) but not the rest of it: embedded files, scripts…
    val old = before.entries.firstOrNull { it.first == "Names" }?.second
    val names = root.get("Names")
    if (old != null && old.isDictionary && names.isDictionary) {
        for (name in old.keys()) if (name.asName() != "Dests" && names.get(name).isNull) names.put(name, old.get(name))
    }
}

// --- Pages from another document, with their annotations (P2) ----------------------------------

/** True when [value] leads to a page or the page tree: grafting it would copy the other document's pages along. */
private fun refersToPage(value: PDFObject, visited: HashSet<Int> = HashSet(), depth: Int = 0): Boolean {
    if (depth > 8) return false
    refNum(value)?.let { if (!visited.add(it)) return false }
    if (value.isDictionary) {
        val type = nameOf(value.get("Type"))
        if (type == "Page" || type == "Pages") return true
        for (k in value.keys()) {
            val v = value.get(k)
            if ((v.isIndirect || v.isArray || v.isDictionary) && refersToPage(v, visited, depth + 1)) return true
        }
        return false
    }
    if (value.isArray) for (v in value.items()) if (refersToPage(v, visited, depth + 1)) return true
    return false
}

/**
 * Copies pages [list] of [donor] into this document at [at], as Acrobat's Insert and Combine do,
 * with their annotations. MuPDF's graftPage copies what a page
 * shows but none of its annotations, so notes, links and filled-in fields were lost. Here:
 *  - annotations are copied without /P and /StructParent (which would drag the other document's
 *    pages and structure along), then /P is set to the new page;
 *  - a link to a copied page points at the copy (named destinations are resolved first); a link
 *    to a page that was not copied is dropped, and a GoTo elsewhere is removed;
 *  - fields come along with their widgets and are added to /AcroForm /Fields; a field whose name
 *    is taken gets "_2", "_3"… so the two forms do not share values by accident;
 *  - the same page copied twice gets two independent sets of annotations.
 */
internal fun PDFDocument.importPages(donor: PDFDocument, list: List<Int>, at: Int) {
    val doc = this
    val map = newPDFGraftMap()
    try {
        val pageMap = HashMap<Int, PDFObject>()
        val placed = ArrayList<PDFObject>()
        list.forEachIndexed { k, p ->
            map.graftPage(at + k, donor, p)
            val page = findPage(at + k)
            page.delete("StructParents")
            placed.add(page)
            pageMap.putIfAbsent(donor.findPage(p).asIndirect(), page)
        }

        val named = HashMap<String, PDFObject>()
        val donorRoot = donor.trailer.get("Root")
        for ((k, d) in nameTree(donorRoot.get("Names").get("Dests"))) {
            val name = if (k.isString) k.asString() else if (k.isName) k.asName() else continue
            named[name] = d
        }
        val oldDests = donorRoot.get("Dests")
        for (k in oldDests.keys()) named.putIfAbsent(k.asName(), oldDests.get(k))

        fun mapDest(dest: PDFObject, depth: Int = 0): PDFObject? {
            if (depth > 4) return null
            if (dest.isName) return named[dest.asName()]?.let { mapDest(it, depth + 1) }
            if (dest.isString) return named[dest.asString()]?.let { mapDest(it, depth + 1) }
            if (dest.isDictionary) return mapDest(dest.get("D"), depth + 1)
            if (!dest.isArray || dest.size() == 0) return null
            val page = refNum(dest.get(0))?.let { pageMap[it] } ?: return null
            val out = newArray()
            out.append(page)
            for (i in 1 until dest.size()) out.append(map.graftObject(dest.get(i)))
            return out
        }
        fun mapAction(action: PDFObject): PDFObject? {
            if (!action.isDictionary) return null
            if (nameOf(action.get("S")) == "GoTo") {
                val dest = mapDest(action.get("D")) ?: return null
                val out = newDictionary()
                out.put("S", newName("GoTo"))
                out.put("D", dest)
                return out
            }
            // URI, Named…: copied as they are, unless they lead to the other document's pages.
            return if (refersToPage(action)) null else map.graftObject(action)
        }
        fun copyValue(key: String, value: PDFObject): PDFObject? = when (key) {
            "Dest" -> mapDest(value)
            "A" -> mapAction(value)
            "AA" -> if (!value.isDictionary) null else {
                val out = newDictionary()
                var any = false
                for (trigger in value.keys()) mapAction(value.get(trigger))?.let { out.put(trigger, it); any = true }
                if (any) out else null
            }
            else -> if (refersToPage(value)) null else map.graftObject(value)
        }

        val fields = HashMap<Int, PDFObject>()
        val top = ArrayList<PDFObject>()
        fun importField(src: PDFObject, depth: Int = 0): PDFObject {
            val num = refNum(src) ?: -1
            fields[num]?.let { return it }
            val field = makeIndirect(newDictionary())
            if (num >= 0) fields[num] = field
            for (key in src.keys()) {
                val k = key.asName()
                if (k == "Kids" || k == "Parent" || k == "P") continue
                copyValue(k, src.get(key))?.let { field.put(k, it) }
            }
            field.put("Kids", newArray())
            val parent = src.get("Parent")
            if (parent.isDictionary && depth < 32) {
                val up = importField(parent, depth + 1)
                field.put("Parent", up)
                arrayAt(up, "Kids").append(field)
            } else top.add(field)
            return field
        }

        val copied = HashSet<Int>()
        /** Each donor annotation's first copy, whatever page it went to. */
        val anyPage = HashMap<Int, PDFObject>()
        /** Replies and review states whose comment is on another page: settled once every page is in. */
        class Reply(val copy: PDFObject, val parent: Int, val page: PDFObject, val state: Boolean)
        val replies = ArrayList<Reply>()
        list.forEachIndexed { k, p ->
            val source = donor.findPage(p)
            val annots = source.get("Annots")
            if (!annots.isArray || annots.size() == 0) return@forEachIndexed
            val again = !copied.add(source.asIndirect())
            val page = placed[k]
            val local = HashMap<Int, PDFObject>()
            val items = ArrayList<Pair<PDFObject, PDFObject>>()
            for (a in annots.items()) {
                if (!a.isDictionary) continue
                // A link that leads to a page that was not copied leads nowhere: leave it out.
                if (subtypeOf(a) == "Link" && ((!a.get("Dest").isNull && mapDest(a.get("Dest")) == null) || (!a.get("A").isNull && mapAction(a.get("A")) == null))) continue
                val copy = makeIndirect(newDictionary())
                refNum(a)?.let {
                    local[it] = copy
                    anyPage.putIfAbsent(it, copy)
                }
                items.add(a to copy)
            }
            val out = newArray()
            for ((a, copy) in items) {
                val widget = subtypeOf(a) == "Widget"
                for (key in a.keys()) {
                    val kk = key.asName()
                    if (kk == "P" || kk == "StructParent" || (widget && kk == "Parent")) continue
                    val v = a.get(key)
                    if (kk == "Popup" || kk == "IRT" || kk == "Parent") {
                        val to = refNum(v)?.let { local[it] }
                        if (to != null) copy.put(kk, to)
                        else if (kk == "IRT") refNum(v)?.let { replies.add(Reply(copy, it, page, !a.get("State").isNull)) }
                        continue
                    }
                    val value = copyValue(kk, v) ?: continue
                    // The same page twice: the second set of annotations gets appearance streams of its own.
                    copy.put(kk, if (again && kk == "AP") doc.copyAppearance(value) else value)
                }
                copy.put("P", page)
                if (widget) {
                    val parent = a.get("Parent")
                    if (parent.isDictionary) {
                        val field = importField(parent)
                        copy.put("Parent", field)
                        arrayAt(field, "Kids").append(copy)
                    } else if (a.get("T").isString || a.get("FT").isName) top.add(copy)
                }
                out.append(copy)
            }
            if (out.size() > 0) page.put("Annots", out) else page.delete("Annots")
        }
        // A reply or review state on another page than its comment keeps it
        // (decisions §52). A review state whose comment was not brought along says nothing: it is
        // left out, with its pop-up. A reply without its comment stays, as a comment of its own.
        for (r in replies) {
            val parent = anyPage[r.parent]
            if (parent != null) {
                r.copy.put("IRT", parent)
                continue
            }
            if (!r.state) continue
            val annots = r.page.get("Annots")
            if (!annots.isArray) continue
            val drop = setOfNotNull(refNum(r.copy), refNum(r.copy.get("Popup")))
            for (i in annots.size() - 1 downTo 0) if (refNum(annots.get(i)) in drop) annots.delete(i)
            if (annots.size() == 0) r.page.delete("Annots")
        }
        registerFields(donor, top, map)
    } finally {
        map.destroy()
    }
}

/** Adds imported top-level fields to /AcroForm /Fields, renaming a field whose name is taken. */
private fun PDFDocument.registerFields(donor: PDFDocument, top: List<PDFObject>, map: PDFGraftMap) {
    if (top.isEmpty()) return
    val root = trailer.get("Root")
    if (!root.get("AcroForm").isDictionary) root.put("AcroForm", makeIndirect(newDictionary()))
    val form = root.get("AcroForm")
    val list = arrayAt(form, "Fields")
    val names = HashSet<String>()
    for (f in list.items()) f.get("T").let { if (it.isString) names.add(it.asString()) }
    for (field in top) {
        val t = field.get("T")
        if (t.isString) {
            var name = t.asString()
            if (name in names) {
                var n = 2
                while ("${name}_$n" in names) n++
                name = "${name}_$n"
                field.put("T", newString(name))
            }
            names.add(name)
        }
        list.append(field)
    }
    // The other form's defaults (fonts for its fields' text, alignment), where this one has none.
    val theirs = donor.trailer.get("Root").get("AcroForm")
    if (!theirs.isDictionary) return
    for (key in listOf("DA", "Q", "NeedAppearances", "SigFlags")) {
        if (form.get(key).isNull && !theirs.get(key).isNull) form.put(key, map.graftObject(theirs.get(key)))
    }
    val dr = theirs.get("DR")
    if (!dr.isDictionary) return
    if (!form.get("DR").isDictionary) {
        form.put("DR", map.graftObject(dr))
        return
    }
    val ours = form.get("DR")
    for (kind in dr.keys()) {
        val group = dr.get(kind)
        if (!group.isDictionary) continue
        if (!ours.get(kind).isDictionary) {
            ours.put(kind, map.graftObject(group))
            continue
        }
        val into = ours.get(kind)
        for (name in group.keys()) if (into.get(name).isNull) into.put(name, map.graftObject(group.get(name)))
    }
}
