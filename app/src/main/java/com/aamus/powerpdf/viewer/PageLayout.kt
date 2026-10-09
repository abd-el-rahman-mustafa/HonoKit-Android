package com.aamus.powerpdf.viewer

import com.aamus.powerpdf.engine.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Where each page sits when the pages are stacked top to bottom, at a given zoom.
 *
 * Units: "content pixels" — the whole stack drawn at the current zoom, before scrolling.
 * Page points (PDF units, top-left origin, as displayed) become content pixels by
 * `pageRect.left + x * scale`. Zoom 1 fits the widest page to the screen width.
 */
class PageLayout(
    val pages: List<Size>,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val zoom: Float,
    private val gap: Float,
    private val pad: Float,
) {
    val maxPageWidth: Float = pages.maxOfOrNull { it.width } ?: 612f
    val fitScale: Float = if (viewportWidth <= 0f) 1f else (viewportWidth - 2 * pad) / maxPageWidth
    /** Pixels per point. */
    val scale: Float = fitScale * zoom
    val contentWidth: Float = maxOf(viewportWidth, maxPageWidth * scale + 2 * pad)
    private val tops = FloatArray(pages.size)
    val contentHeight: Float

    init {
        var y = pad
        pages.forEachIndexed { i, p ->
            tops[i] = y
            y += p.height * scale + gap
        }
        contentHeight = maxOf(viewportHeight, y - gap + pad)
    }

    fun pageRect(i: Int): Rect {
        val p = pages[i]
        val w = p.width * scale
        val left = (contentWidth - w) / 2
        return Rect(left, tops[i], left + w, tops[i] + p.height * scale)
    }

    val maxScrollX: Float get() = maxOf(0f, contentWidth - viewportWidth)
    val maxScrollY: Float get() = maxOf(0f, contentHeight - viewportHeight)

    /** Pages that touch the band [top, bottom] of content pixels. */
    fun visiblePages(top: Float, bottom: Float): IntRange {
        if (pages.isEmpty()) return IntRange.EMPTY
        var first = -1
        var last = -1
        for (i in pages.indices) {
            val r = pageRect(i)
            if (r.bottom >= top && r.top <= bottom) {
                if (first < 0) first = i
                last = i
            } else if (first >= 0) break
        }
        return if (first < 0) IntRange.EMPTY else first..last
    }

    /** The page at a content point, and the point in page points; null between pages. */
    fun hit(content: Offset, slop: Float = 0f): Pair<Int, Offset>? {
        for (i in pages.indices) {
            val r = pageRect(i)
            if (content.y < r.top - slop) return null
            if (content.y <= r.bottom + slop && content.x >= r.left - slop && content.x <= r.right + slop) {
                return i to Offset((content.x - r.left) / scale, (content.y - r.top) / scale)
            }
        }
        return null
    }

    /** The page nearest a content point (the one under it, or the closest by height). */
    fun nearestPage(contentY: Float): Int {
        if (pages.isEmpty()) return 0
        for (i in pages.indices) {
            val r = pageRect(i)
            if (contentY <= r.bottom + gap / 2) return i
        }
        return pages.lastIndex
    }

    fun toContent(page: Int, point: Offset): Offset {
        val r = pageRect(page)
        return Offset(r.left + point.x * scale, r.top + point.y * scale)
    }

    fun toContent(page: Int, x0: Float, y0: Float, x1: Float, y1: Float): Rect {
        val r = pageRect(page)
        return Rect(r.left + x0 * scale, r.top + y0 * scale, r.left + x1 * scale, r.top + y1 * scale)
    }

    fun withZoom(z: Float) = PageLayout(pages, viewportWidth, viewportHeight, z, gap, pad)
}
