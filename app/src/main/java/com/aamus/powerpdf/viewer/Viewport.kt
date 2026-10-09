package com.aamus.powerpdf.viewer

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the page view does with touches that are not plain scrolling and zooming. */
interface PageGestures {
    /** A one-finger drag starting here goes to the tool (pen, highlighter, a handle) instead of scrolling. */
    fun wantsDrag(page: Int?, point: Offset, view: Offset): Boolean = false
    fun onDragStart(page: Int, point: Offset, view: Offset) {}
    /** [point] is in the start page's points, even if the finger left that page. */
    fun onDrag(page: Int, point: Offset, view: Offset) {}
    fun onDragEnd() {}
    fun onDragCancel() {}
    fun onTap(page: Int?, point: Offset, view: Offset) {}
    fun onLongPress(page: Int?, point: Offset, view: Offset) {}
    /** Moving after a long press (extending a selection). */
    fun onLongPressDrag(page: Int?, point: Offset, view: Offset) {}
    fun onLongPressEnd() {}
    /** Double tap zooms unless this returns false (a tool that wants quick taps). */
    val doubleTapZooms: Boolean get() = true
}

/**
 * Night pages: lightness inverted, hue kept (a red mark stays red), white paper becomes a soft
 * near-black rather than pure black. Derived from YIQ: each channel c' = c − 2·Y + 255.
 */
val NightFilter: ColorFilter = run {
    val k = 0.9f
    val b = 20f
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.402f * k, -1.174f * k, -0.228f * k, 0f, 255f * k + b,
                -0.598f * k, -0.174f * k, -0.228f * k, 0f, 255f * k + b,
                -0.598f * k, -1.174f * k, 0.772f * k, 0f, 255f * k + b,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}

@Composable
fun PdfViewport(
    state: ViewportState,
    renderer: PageRenderer,
    version: Int,
    night: Boolean,
    backdrop: Color,
    gestures: PageGestures,
    modifier: Modifier = Modifier,
    /** Draws marks over the pages (search hits, selection, ink being drawn), in screen pixels. */
    overlay: DrawScope.(layout: PageLayout) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val g by rememberUpdatedState(gestures)
    val currentVersion by rememberUpdatedState(version)

    // Tell the renderer what is on screen whenever it changes.
    LaunchedEffect(state, renderer) {
        snapshotFlow { Quad(state.layout, state.scrollX, state.contentTop, state.settled) }
            .collect { (layout, sx, top, settled) ->
                if (layout.pages.isNotEmpty() && layout.viewportWidth > 0) {
                    renderer.request(layout, sx, top, state.viewportHeight, currentVersion, settled)
                }
            }
    }
    LaunchedEffect(version) {
        val l = state.layout
        if (l.pages.isNotEmpty() && l.viewportWidth > 0) renderer.request(l, state.scrollX, state.contentTop, state.viewportHeight, version, true)
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { state.setViewport(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(state) {
                val decay: DecayAnimationSpec<Offset> = splineBasedDecay(this)
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scope.launch { state.stopFling() }
                    state.settled = false
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    fun hitAt(view: Offset): Pair<Int, Offset>? = state.layout.hit(state.toContent(view))
                    fun pagePoint(page: Int, view: Offset): Offset {
                        val l = state.layout
                        val r = l.pageRect(page)
                        val c = state.toContent(view)
                        return Offset((c.x - r.left) / l.scale, (c.y - r.top) / l.scale)
                    }

                    val startHit = hitAt(down.position)
                    val toolDrag = g.wantsDrag(startHit?.first, startHit?.second ?: Offset.Zero, down.position)
                    var dragging = false
                    var panning = false
                    var pinching = false
                    var longPressed = false
                    var last = down.position
                    val pointerId = down.id

                    // Phase 1: until the finger moves, lifts, a second finger lands, or it is held long.
                    val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                pinching = true
                                return@withTimeoutOrNull true
                            }
                            val ch = event.changes.firstOrNull { it.id == pointerId } ?: return@withTimeoutOrNull true
                            if (!ch.pressed) return@withTimeoutOrNull false // lifted: a tap
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            if ((ch.position - down.position).getDistance() > slop) {
                                if (toolDrag && startHit != null) {
                                    dragging = true
                                    g.onDragStart(startHit.first, startHit.second, down.position)
                                    g.onDrag(startHit.first, pagePoint(startHit.first, ch.position), ch.position)
                                } else {
                                    panning = true
                                    val d = ch.position - down.position
                                    state.scrollBy(-d.x, -d.y)
                                }
                                last = ch.position
                                ch.consume()
                                return@withTimeoutOrNull true
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") true
                    }

                    if (decided == null) {
                        // Held without moving: a long press, unless a tool drag wants the hold.
                        if (toolDrag && startHit != null) {
                            dragging = true
                            g.onDragStart(startHit.first, startHit.second, down.position)
                        } else {
                            longPressed = true
                            g.onLongPress(startHit?.first, startHit?.second ?: Offset.Zero, down.position)
                        }
                    } else if (decided == false) {
                        // A tap: wait briefly for a second one.
                        val tapHit = startHit
                        if (g.doubleTapZooms) {
                            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                                awaitFirstDown(requireUnconsumed = false)
                            }
                            if (second != null) {
                                // Wait for it to lift (or turn into something else).
                                var lifted = false
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == second.id } ?: break
                                    if (!c.pressed) {
                                        lifted = true
                                        break
                                    }
                                    if ((c.position - second.position).getDistance() > slop * 2) break
                                }
                                if (lifted) {
                                    val target = if (state.zoom < 1.6f) 2.5f else 1f
                                    scope.launch { state.animateZoomTo(target, second.position) }
                                }
                                state.settled = true
                                return@awaitEachGesture
                            }
                        }
                        g.onTap(tapHit?.first, tapHit?.second ?: Offset.Zero, down.position)
                        state.settled = true
                        return@awaitEachGesture
                    }

                    // Phase 2: follow the gesture to its end.
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (dragging) {
                                g.onDragCancel()
                                dragging = false
                            }
                            if (longPressed) {
                                g.onLongPressEnd()
                                longPressed = false
                            }
                            pinching = true
                            panning = false
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoom != 1f && centroid != Offset.Unspecified) state.zoomBy(zoom, centroid)
                            if (pan != Offset.Zero) state.scrollBy(-pan.x, -pan.y)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            continue
                        }
                        val ch = pressed.first()
                        if (pinching) {
                            // One finger left after a pinch: keep panning with it.
                            val d = ch.position - ch.previousPosition
                            state.scrollBy(-d.x, -d.y)
                            ch.consume()
                            continue
                        }
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        when {
                            dragging && startHit != null -> {
                                g.onDrag(startHit.first, pagePoint(startHit.first, ch.position), ch.position)
                                autoScroll(state, ch.position)
                            }
                            longPressed -> {
                                val h = hitAt(ch.position)
                                g.onLongPressDrag(h?.first, h?.second ?: Offset.Zero, ch.position)
                                autoScroll(state, ch.position)
                            }
                            else -> {
                                if (!panning && (ch.position - down.position).getDistance() > slop) panning = true
                                if (panning) {
                                    val d = ch.position - last
                                    state.scrollBy(-d.x, -d.y)
                                }
                            }
                        }
                        last = ch.position
                        ch.consume()
                    }

                    when {
                        dragging -> {
                            g.onDragEnd()
                            state.settled = true
                        }
                        longPressed -> {
                            g.onLongPressEnd()
                            state.settled = true
                        }
                        panning && !pinching -> {
                            val v = tracker.calculateVelocity()
                            val velocity = Offset(v.x, v.y)
                            if (velocity.getDistance() > 300f) scope.launch { state.flingBy(velocity, decay) } else state.settled = true
                        }
                        else -> state.settled = true
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val tick = renderer.tick
            val layout = state.layout
            if (layout.pages.isEmpty()) return@Canvas
            val top = state.contentTop
            val visible = layout.visiblePages(top, top + size.height)
            val filter = if (night) NightFilter else null
            val paper = if (night) Color(0xFF141414) else Color.White
            for (i in visible) {
                val r = state.toView(layout.pageRect(i))
                // Shadow, then paper.
                drawRect(Color.Black.copy(alpha = if (backdrop.luminanceIsDark()) 0.5f else 0.10f), topLeft = r.topLeft + Offset(0f, 2f), size = r.size)
                drawRect(paper, topLeft = r.topLeft, size = r.size)
                val base = renderer.base(i)
                if (base != null) {
                    drawImage(
                        base.image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(base.bitmap.width, base.bitmap.height),
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                        colorFilter = filter,
                    )
                }
                val detail = renderer.detail(i)
                if (detail != null && abs(detail.scale - layout.scale) < 0.001f) {
                    drawImage(
                        detail.image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(detail.bitmap.width, detail.bitmap.height),
                        dstOffset = IntOffset((r.left + detail.left).roundToInt(), (r.top + detail.top).roundToInt()),
                        dstSize = IntSize(detail.bitmap.width, detail.bitmap.height),
                        filterQuality = FilterQuality.None,
                        colorFilter = filter,
                    )
                }
            }
            overlay(layout)
        }
    }
}

/** While dragging near the top or bottom edge, scroll so the drag can continue. */
private fun autoScroll(state: ViewportState, view: Offset) {
    val edge = 64f
    val h = state.viewportHeight
    when {
        view.y < state.topInset + edge -> state.scrollBy(0f, -12f)
        view.y > h - state.bottomInset - edge -> state.scrollBy(0f, 12f)
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

private data class Quad(val layout: PageLayout, val sx: Float, val top: Float, val settled: Boolean)

/** A rectangle of page points on screen. */
fun PageLayout.viewRect(state: ViewportState, page: Int, x0: Float, y0: Float, x1: Float, y1: Float): Rect =
    state.toView(toContent(page, x0, y0, x1, y1))

