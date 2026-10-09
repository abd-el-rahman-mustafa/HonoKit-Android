package com.aamus.powerpdf.viewer

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.RenderCancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Draws pages into bitmaps off the main thread, the way MuPDF's own viewer does: each page has a
 * "base" bitmap of the whole page at a moderate resolution, and when zoomed in further, a sharp
 * "detail" bitmap of just the part on screen. While a gesture runs, what exists is stretched; the
 * sharp version follows when the fingers stop.
 */
class PageRenderer(private val session: DocumentSession, scope: CoroutineScope) {

    class Tile(val page: Int, val scale: Float, val version: Int, val left: Int, val top: Int, val bitmap: Bitmap) {
        val image: ImageBitmap = bitmap.asImageBitmap()
    }

    /** Bumped when a new bitmap is ready: the canvas reads it, so it redraws. */
    var tick by mutableIntStateOf(0)
        private set

    private val bases = HashMap<Int, Tile>()
    private val details = HashMap<Int, Tile>()

    private data class Wanted(
        val visible: IntRange,
        val scale: Float,
        val fitScale: Float,
        val version: Int,
        /** Visible part of each page, in that page's pixels at [scale]: left, top, right, bottom. */
        val windows: Map<Int, IntArray>,
        val settled: Boolean,
        val pageCount: Int,
    )

    private val wanted = MutableStateFlow<Wanted?>(null)
    private val job: Job = scope.launch {
        wanted.collectLatest { w -> if (w != null) work(w) }
    }

    fun base(page: Int): Tile? = bases[page]
    fun detail(page: Int): Tile? = details[page]

    /**
     * Tells the renderer what is on screen. Cheap; call on every frame of a gesture.
     * [settled] is false while fingers are down or a fling runs.
     */
    fun request(layout: PageLayout, scrollX: Float, top: Float, height: Float, version: Int, settled: Boolean) {
        val visible = layout.visiblePages(top, top + height)
        val windows = HashMap<Int, IntArray>()
        for (i in visible) {
            val r = layout.pageRect(i)
            val left = maxOf(0f, scrollX - r.left)
            val wTop = maxOf(0f, top - r.top)
            val right = minOf(r.width, scrollX + layout.viewportWidth - r.left)
            val bottom = minOf(r.height, top + height - r.top)
            if (right > left && bottom > wTop) windows[i] = intArrayOf(left.toInt(), wTop.toInt(), right.toInt() + 1, bottom.toInt() + 1)
        }
        wanted.value = Wanted(visible, layout.scale, layout.fitScale, version, windows, settled, layout.pages.size)
    }

    /** The resolution a page's base bitmap is drawn at: the zoom rounded to half octaves, capped. */
    private fun baseScale(w: Wanted, page: Int): Float {
        val size = session.state.value.pageSizes.getOrNull(page) ?: return w.fitScale
        val zoom = w.scale / w.fitScale
        val stepped = w.fitScale * 2f.pow((log2(zoom.coerceAtLeast(0.25f)) * 2).roundToInt() / 2f)
        val cap = kotlin.math.sqrt(MAX_BASE_PIXELS / (size.width * size.height))
        return minOf(stepped, cap, w.scale.coerceAtLeast(w.fitScale * 0.5f) * 1.5f)
    }

    private suspend fun work(w: Wanted) {
        // While a gesture runs, only fill pages that have nothing at all.
        val order = w.visible.sortedBy { kotlin.math.abs(it - (w.visible.first + w.visible.last) / 2) }
        for (i in order) {
            val want = baseScale(w, i)
            val have = bases[i]
            val stale = have == null || have.version != w.version
            val coarse = have != null && (have.scale < want * 0.8f || have.scale > want * 1.6f)
            if (stale || (w.settled && coarse)) renderBase(i, want, w.version)
        }
        if (!w.settled) return
        delay(60)
        // Sharp part of each visible page whenever its base is not drawn at the zoom shown: the
        // part on screen is drawn at exactly the screen's pixels (decisions §52).
        for (i in order) {
            val base = bases[i] ?: continue
            val window = w.windows[i]
            if (window == null || !TileScale.needsDetail(w.scale, base.scale)) {
                details.remove(i)?.let { recycleLater(it) }
                continue
            }
            val d = details[i]
            if (d != null && d.version == w.version && d.scale == w.scale &&
                d.left <= window[0] && d.top <= window[1] &&
                d.left + d.bitmap.width >= window[2] && d.top + d.bitmap.height >= window[3]
            ) continue
            renderDetail(i, w.scale, window, w.version)
        }
        // Neighbours, so scrolling on shows a page at once.
        for (i in listOf(w.visible.last + 1, w.visible.first - 1, w.visible.last + 2)) {
            if (i < 0 || i >= w.pageCount) continue
            val have = bases[i]
            val want = baseScale(w, i)
            if (have == null || have.version != w.version || have.scale < want * 0.8f) renderBase(i, want, w.version)
        }
        // Forget what is far away.
        val keep = (w.visible.first - 3)..(w.visible.last + 3)
        bases.keys.filter { it !in keep }.forEach { k -> bases.remove(k)?.let { recycleLater(it) } }
        details.keys.filter { it !in w.visible }.forEach { k -> details.remove(k)?.let { recycleLater(it) } }
    }

    private suspend fun renderBase(page: Int, scale: Float, version: Int) {
        val size = session.state.value.pageSizes.getOrNull(page) ?: return
        val w = maxOf(1, (size.width * scale).roundToInt())
        val h = maxOf(1, (size.height * scale).roundToInt())
        val bitmap = withContext(Dispatchers.Default) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        if (!draw(page, scale, bitmap, 0, 0)) {
            bitmap.recycle()
            return
        }
        bases.put(page, Tile(page, scale, version, 0, 0, bitmap))?.let { recycleLater(it) }
        tick++
    }

    private suspend fun renderDetail(page: Int, scale: Float, window: IntArray, version: Int) {
        // A margin around what is visible, so small pans stay sharp.
        val margin = 256
        val left = maxOf(0, window[0] - margin)
        val top = maxOf(0, window[1] - margin)
        val size = session.state.value.pageSizes.getOrNull(page) ?: return
        val right = minOf((size.width * scale).toInt(), window[2] + margin)
        val bottom = minOf((size.height * scale).toInt(), window[3] + margin)
        if (right <= left || bottom <= top) return
        val bitmap = withContext(Dispatchers.Default) { Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888) }
        if (!draw(page, scale, bitmap, left, top)) {
            bitmap.recycle()
            return
        }
        details.put(page, Tile(page, scale, version, left, top, bitmap))?.let { recycleLater(it) }
        tick++
    }

    /** Draws on the engine thread; a cancelled request aborts MuPDF half way. */
    private suspend fun draw(page: Int, scale: Float, bitmap: Bitmap, left: Int, top: Int): Boolean {
        val cancel = RenderCancel()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel.cancel() }
            com.aamus.powerpdf.engine.PdfEngine.dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
                val ok = if (!cont.isActive) false else runCatching {
                    if (page < session.doc.pageCount) {
                        session.doc.render(page, scale, bitmap, left, top, cancel)
                        true
                    } else false
                }.getOrDefault(false)
                // The cookie is freed here, on the engine thread, once MuPDF is done with it.
                cancel.dispose()
                if (cont.isActive) cont.resume(ok)
            }
        }
    }

    /** Bitmaps may still be on screen for a frame; free them a little later. */
    private fun recycleLater(tile: Tile) {
        retired.add(tile)
        if (retired.size > 6) retired.removeAt(0).bitmap.recycle()
    }

    private val retired = ArrayList<Tile>()

    fun dispose() {
        job.cancel()
        bases.clear()
        details.clear()
        retired.clear()
    }

    companion object {
        /** About 10 MB per page bitmap. */
        const val MAX_BASE_PIXELS = 2_600_000f
    }
}

/**
 * When a page's part on screen gets a picture of its own (decisions §52). The base
 * picture of a page is drawn at the zoom rounded to half octaves (1, 1.41, 2…), so at any zoom in
 * between it was shown stretched or shrunk once the fingers stopped: slightly soft (zoomed in up to
 * 8 % past a step, and every zoom just below a step, which shrank a larger picture). Now the part
 * on screen is drawn at exactly the zoom shown, one picture pixel per screen pixel, whenever the
 * base differs from it by more than rounding.
 */
internal object TileScale {
    fun needsDetail(scale: Float, baseScale: Float): Boolean = kotlin.math.abs(scale - baseScale) > baseScale * 0.002f
}
