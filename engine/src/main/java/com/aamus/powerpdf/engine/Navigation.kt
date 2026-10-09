package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.LinkDestination
import com.artifex.mupdf.fitz.OutlineIterator

/**
 * Moving around a document: its bookmarks (the outline) and the links on its pages, read through
 * MuPDF (desktop src/engine/navigation.ts; decisions §51). Read only: nothing here changes the file.
 * Coordinates are page points as displayed (top-left origin, rotation applied), as text and marks.
 */

/** A point on a page, in points as displayed. */
data class PagePoint(val x: Float, val y: Float)

/** A link on a page: to a page of this document ([page], maybe [point]), or to an address ([uri]). */
data class PageLink(
    val box: Box,
    val page: Int? = null,
    val point: PagePoint? = null,
    /** A web address, an email or anything else outside the document: opened only through [LinkRules]. */
    val uri: String? = null,
)

/** One bookmark. */
data class OutlineEntry(
    val title: String,
    /** The page it goes to (from 0), or null when it goes nowhere in this document. */
    val page: Int?,
    val point: PagePoint?,
    /** An address outside the document (shown with a globe; opened only after asking). */
    val uri: String?,
    /** Open (expanded) in the file. */
    val open: Boolean,
    val children: List<OutlineEntry>,
)

/** More than any real book has; a bound against looping or hostile outlines. */
private const val MAX_ENTRIES = 20000
private const val MAX_DEPTH = 64

private fun external(uri: String) = !uri.startsWith("#")

/** Where an internal address goes: the page and, for XYZ/FitH/FitR… destinations, a point. */
private fun PdfDoc.destination(uri: String, count: Int): Pair<Int, PagePoint?>? = try {
    val d: LinkDestination = pdf.resolveLinkDestination(uri)
    val page = pdf.pageNumberFromLocation(d)
    if (page !in 0 until count) {
        null
    } else {
        val hasPoint = when (d.type) {
            LinkDestination.LINK_DEST_XYZ, LinkDestination.LINK_DEST_FIT_H, LinkDestination.LINK_DEST_FIT_BH,
            LinkDestination.LINK_DEST_FIT_R, LinkDestination.LINK_DEST_FIT_V, LinkDestination.LINK_DEST_FIT_BV -> true
            else -> false
        }
        // MuPDF gives the point on the page as displayed; a missing coordinate is NaN.
        val x = d.x.takeIf { it.isFinite() }
        val y = d.y.takeIf { it.isFinite() }
        page to (if (hasPoint && (x != null || y != null)) PagePoint(x ?: 0f, y ?: 0f) else null)
    }
} catch (_: Throwable) {
    null
}

/** The links on page [index]: to pages of this document, or to addresses outside it. */
fun PdfDoc.pageLinks(index: Int): List<PageLink> {
    val count = pageCount
    if (index !in 0 until count) return emptyList()
    return withPage(index) { page ->
        val links: Array<Link> = runCatching { page.links }.getOrNull() ?: emptyArray()
        try {
            val out = ArrayList<PageLink>()
            for (link in links) {
                val b = runCatching { link.bounds }.getOrNull() ?: continue
                if (!(b.x1 > b.x0 && b.y1 > b.y0)) continue
                val box = Box(b.x0, b.y0, b.x1, b.y1)
                val uri = runCatching { link.uri }.getOrNull().orEmpty()
                if (uri.isEmpty()) continue
                if (runCatching { link.isExternal }.getOrDefault(false) || external(uri)) {
                    out.add(PageLink(box, uri = uri))
                    continue
                }
                val d = destination(uri, count) ?: continue
                out.add(PageLink(box, page = d.first, point = d.second))
            }
            out
        } finally {
            links.forEach { runCatching { it.destroy() } }
        }
    }
}

/** The document's bookmarks, nested; empty when it has none. */
fun PdfDoc.outline(): List<OutlineEntry> {
    val count = pageCount
    var seen = 0
    fun entry(rawTitle: String?, uri: String?, open: Boolean, children: List<OutlineEntry>): OutlineEntry {
        val title = (rawTitle ?: "").replace(Regex("\\s+"), " ").trim()
        val u = uri.orEmpty()
        if (u.isNotEmpty() && external(u)) return OutlineEntry(title, null, null, u, open, children)
        val d = if (u.isNotEmpty()) destination(u, count) else null
        return OutlineEntry(title, d?.first, d?.second, null, open, children)
    }

    // The tree as MuPDF loads it (cycles and broken entries already dealt with), every bookmark closed.
    val items = runCatching { pdf.loadOutline() }.getOrNull() ?: return emptyList()
    fun walkTree(list: Array<com.artifex.mupdf.fitz.Outline>, depth: Int): List<OutlineEntry> {
        val out = ArrayList<OutlineEntry>()
        for (o in list) {
            if (++seen > MAX_ENTRIES) break
            val kids = if (depth < MAX_DEPTH && !o.down.isNullOrEmpty()) walkTree(o.down, depth + 1) else emptyList()
            out.add(entry(o.title, o.uri, false, kids))
        }
        return out
    }
    val tree = walkTree(items, 0)
    if (tree.isEmpty()) return tree

    // The same tree through the outline iterator, which also gives each bookmark's open state; used
    // only when it agrees with the loaded tree entry for entry.
    val withOpen = runCatching { iterated(::entry) }.getOrNull()
    return if (withOpen != null && sameShape(withOpen, tree)) withOpen else tree
}

private fun PdfDoc.iterated(entry: (String?, String?, Boolean, List<OutlineEntry>) -> OutlineEntry): List<OutlineEntry> {
    val it: OutlineIterator = pdf.outlineIterator() ?: return emptyList()
    var seen = 0
    try {
        fun walk(depth: Int): List<OutlineEntry> {
            val out = ArrayList<OutlineEntry>()
            while (seen < MAX_ENTRIES) {
                val item = it.item() ?: break
                seen++
                var children = emptyList<OutlineEntry>()
                when (it.down()) {
                    OutlineIterator.ITERATOR_AT_ITEM -> {
                        if (depth < MAX_DEPTH) children = walk(depth + 1)
                        if (it.up() == OutlineIterator.ITERATOR_DID_NOT_MOVE) error("outline: could not go back up")
                    }
                    OutlineIterator.ITERATOR_AT_EMPTY -> it.up()
                }
                out.add(entry(item.title, item.uri, item.is_open, children))
                if (it.next() != OutlineIterator.ITERATOR_AT_ITEM) break
            }
            return out
        }
        return walk(0)
    } finally {
        runCatching { it.destroy() }
    }
}

private fun sameShape(a: List<OutlineEntry>, b: List<OutlineEntry>): Boolean =
    a.size == b.size && a.indices.all { i -> a[i].title == b[i].title && a[i].page == b[i].page && a[i].uri == b[i].uri && sameShape(a[i].children, b[i].children) }
