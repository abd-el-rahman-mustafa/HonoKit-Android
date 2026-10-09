package com.aamus.powerpdf.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.aamus.powerpdf.engine.Size
import kotlinx.coroutines.coroutineScope

/** Zoom and scroll of the page view. Zoom 1 = the widest page fits the width. */
@Stable
class ViewportState(initialPage: Int = 0) {
    var zoom by mutableFloatStateOf(1f)
        private set
    var scrollX by mutableFloatStateOf(0f)
        private set
    var scrollY by mutableFloatStateOf(0f)
        private set
    var viewportWidth by mutableFloatStateOf(0f)
        private set
    var viewportHeight by mutableFloatStateOf(0f)
        private set
    var pages by mutableStateOf<List<Size>>(emptyList())
        private set
    /** Space kept above the first page and below the last one (the bars float over it). */
    var topInset by mutableFloatStateOf(0f)
    var bottomInset by mutableFloatStateOf(0f)

    /** False while a finger is down or a fling runs; the renderer waits for true to draw sharp tiles. */
    var settled by mutableStateOf(true)
        internal set

    private var pendingPage: Int = initialPage
    private val fling = Animatable(Offset.Zero, Offset.VectorConverter)

    val layout: PageLayout by derivedStateOf {
        PageLayout(pages, viewportWidth, viewportHeight - topInset - bottomInset, zoom, GAP, PAD)
    }

    /** Content y at the top of the visible area (content starts below [topInset]). */
    val contentTop: Float get() = scrollY - topInset

    fun setViewport(width: Float, height: Float) {
        if (width == viewportWidth && height == viewportHeight) return
        if (width == viewportWidth && viewportWidth > 0) {
            // Only the height changed (the keyboard, a bar): keep the view where it is.
            viewportHeight = height
            clamp()
            return
        }
        val first = viewportWidth <= 0
        val page = if (pages.isNotEmpty() && !first) currentPage() else pendingPage
        val frac = if (pages.isNotEmpty() && !first) pageFraction() else 0f
        viewportWidth = width
        viewportHeight = height
        if (pages.isNotEmpty()) {
            scrollToPage(page)
            if (frac > 0f) {
                val r = layout.pageRect(page.coerceIn(0, pages.lastIndex))
                scrollY = (r.top + frac * r.height - PAD).coerceIn(0f, maxScrollY)
            }
        }
    }

    fun updatePages(list: List<Size>) {
        if (list == pages) return
        val keep = if (pages.isNotEmpty() && viewportWidth > 0) currentPage() else pendingPage
        val relY = if (pages.isNotEmpty() && viewportWidth > 0) pageFraction() else 0f
        pages = list
        if (viewportWidth > 0 && list.isNotEmpty()) {
            val p = keep.coerceIn(0, list.lastIndex)
            val r = layout.pageRect(p)
            scrollY = (r.top + relY * r.height - PAD).coerceIn(0f, maxScrollY)
            clamp()
        }
    }

    val maxScrollY: Float get() = maxOf(0f, layout.contentHeight + topInset + bottomInset - viewportHeight)
    val maxScrollX: Float get() = layout.maxScrollX

    private fun clamp() {
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
    }

    /** Converts a point on screen to content pixels. */
    fun toContent(view: Offset) = Offset(view.x + scrollX, view.y + scrollY - topInset)

    /** Converts content pixels to a point on screen. */
    fun toView(content: Offset) = Offset(content.x - scrollX, content.y - scrollY + topInset)

    fun toView(content: Rect) = Rect(content.left - scrollX, content.top - scrollY + topInset, content.right - scrollX, content.bottom - scrollY + topInset)

    fun scrollBy(dx: Float, dy: Float): Offset {
        val ox = scrollX
        val oy = scrollY
        scrollX = (scrollX + dx).coerceIn(0f, maxScrollX)
        scrollY = (scrollY + dy).coerceIn(0f, maxScrollY)
        return Offset(scrollX - ox, scrollY - oy)
    }

    /** Zooms by [factor] keeping the point under [focus] (screen pixels) where it is. */
    fun zoomBy(factor: Float, focus: Offset) {
        val old = zoom
        val next = (old * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == old) return
        val content = toContent(focus)
        val oldLayout = layout
        zoom = next
        val newLayout = layout
        // Horizontal: content is centred when narrower than the screen, so map through page coordinates.
        val hit = oldLayout.hit(content, slop = 10_000f)
        if (hit != null) {
            val (page, pt) = hit
            val c = newLayout.toContent(page, pt)
            scrollX = c.x - focus.x
            scrollY = c.y - (focus.y - topInset)
        } else {
            val k = next / old
            scrollX = content.x * k - focus.x
            scrollY = content.y * k - (focus.y - topInset)
        }
        clamp()
    }

    fun setZoom(z: Float, focus: Offset) = zoomBy(z / zoom, focus)

    fun currentPage(): Int {
        if (pages.isEmpty()) return 0
        val l = layout
        // The page covering the middle of the upper third of the screen.
        return l.nearestPage(scrollY + (viewportHeight - topInset - bottomInset) / 3)
    }

    private fun pageFraction(): Float {
        val p = currentPage()
        val r = layout.pageRect(p)
        return ((scrollY + PAD - r.top) / r.height).coerceIn(0f, 1f)
    }

    fun scrollToPage(page: Int) {
        if (pages.isEmpty() || viewportWidth <= 0) {
            pendingPage = page
            return
        }
        val r = layout.pageRect(page.coerceIn(0, pages.lastIndex))
        scrollY = (r.top - PAD).coerceIn(0f, maxScrollY)
        clamp()
    }

    /**
     * A link's or bookmark's destination (decisions §51): page [page] with [point] (page points)
     * at the top of the view, as Acrobat's XYZ; without a point, the page's top.
     */
    fun goTo(page: Int, point: Offset?) {
        if (point == null || pages.isEmpty() || viewportWidth <= 0) {
            scrollToPage(page)
            return
        }
        val p = page.coerceIn(0, pages.lastIndex)
        val c = layout.toContent(p, Offset(point.x, point.y))
        scrollY = (c.y - PAD).coerceIn(0f, maxScrollY)
        if (c.x < scrollX || c.x > scrollX + viewportWidth * 0.8f) scrollX = (c.x - PAD).coerceIn(0f, maxScrollX)
    }

    /**
     * Scrolls so a box on a page (in page points) is in view, with some room around it.
     * [coveredBelow] pixels at the bottom are hidden (the keyboard and a tool bar above it).
     */
    fun reveal(page: Int, x0: Float, y0: Float, x1: Float, y1: Float, coveredBelow: Float = 0f) {
        if (pages.isEmpty() || viewportWidth <= 0) {
            pendingPage = page
            return
        }
        val l = layout
        val box = l.toContent(page, x0, y0, x1, y1)
        val visH = maxOf(viewportHeight * 0.25f, viewportHeight - topInset - maxOf(bottomInset, coveredBelow))
        if (box.top < scrollY + visH * 0.15f || box.bottom > scrollY + visH * 0.85f) {
            scrollY = (box.center.y - visH * 0.4f).coerceIn(0f, maxScrollY)
        }
        if (box.left < scrollX || box.right > scrollX + viewportWidth) {
            scrollX = (box.center.x - viewportWidth / 2).coerceIn(0f, maxScrollX)
        }
    }

    suspend fun flingBy(velocity: Offset, decay: DecayAnimationSpec<Offset>) {
        settled = false
        try {
            fling.snapTo(Offset(scrollX, scrollY))
            fling.animateDecay(Offset(-velocity.x, -velocity.y), decay) {
                scrollX = value.x.coerceIn(0f, maxScrollX)
                scrollY = value.y.coerceIn(0f, maxScrollY)
            }
        } finally {
            settled = true
        }
    }

    suspend fun stopFling() {
        fling.stop()
    }

    /** Animated zoom to [target] around [focus] (double tap). */
    suspend fun animateZoomTo(target: Float, focus: Offset) = coroutineScope {
        settled = false
        val anim = Animatable(zoom)
        try {
            anim.animateTo(target.coerceIn(MIN_ZOOM, MAX_ZOOM), tween(220, easing = FastOutSlowInEasing)) {
                setZoom(value, focus)
            }
        } finally {
            settled = true
        }
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 10f
        const val GAP = 12f
        const val PAD = 12f
    }
}

