package com.aamus.powerpdf.engine.img

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A crop as fractions (0…1) of the picture it is drawn on: left, top, right, bottom. */
data class CropBox(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) / 2
    val cy: Float get() = (t + b) / 2
    val isFull: Boolean get() = l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f

    companion object {
        val Full = CropBox(0f, 0f, 1f, 1f)
    }
}

/** What a finger holds while cropping: the inside (move), an edge, or a corner. */
enum class CropHandle { Move, Left, Top, Right, Bottom, TopLeft, TopRight, BottomLeft, BottomRight }

/**
 * The crop box you drag with a finger (decisions §40), shared by the Photo editor and Crop image.
 * Pure arithmetic in pixels of the picture as shown, so it is tested on the JVM (CropMathTest).
 */
object CropMath {
    /**
     * The box of shape [ratio] (width ÷ height, in pixels) on a [w] × [h] picture: [scale] of the
     * largest such box, centred on ([cx], [cy]) and kept inside the picture.
     */
    fun fit(ratio: Float, w: Int, h: Int, cx: Float = 0.5f, cy: Float = 0.5f, scale: Float = 1f): CropBox {
        var bw = w.toFloat()
        var bh = bw / ratio
        if (bh > h) { bh = h.toFloat(); bw = bh * ratio }
        bw *= scale.coerceIn(0.05f, 1f)
        bh *= scale.coerceIn(0.05f, 1f)
        val left = (cx * w - bw / 2).coerceIn(0f, w - bw)
        val top = (cy * h - bh / 2).coerceIn(0f, h - bh)
        return CropBox(left / w, top / h, (left + bw) / w, (top + bh) / h)
    }

    /** The same box after the picture turns a quarter turn, clockwise or not. */
    fun turn(b: CropBox, clockwise: Boolean): CropBox =
        if (clockwise) CropBox(1 - b.b, b.l, 1 - b.t, b.r) else CropBox(b.t, 1 - b.r, b.b, 1 - b.l)

    /** The same box after the picture is mirrored (left↔right) or turned upside down. */
    fun mirror(b: CropBox, horizontal: Boolean): CropBox =
        if (horizontal) CropBox(1 - b.r, b.t, 1 - b.l, b.b) else CropBox(b.l, 1 - b.b, b.r, 1 - b.t)

    /**
     * The handle under ([x], [y]) on a [w] × [h] picture (pixels), within [reach] pixels of a corner
     * or an edge; inside the box is Move; null elsewhere. Corners win over edges.
     */
    fun handleAt(box: CropBox, x: Float, y: Float, w: Float, h: Float, reach: Float): CropHandle? {
        val l = box.l * w
        val t = box.t * h
        val r = box.r * w
        val b = box.b * h
        val nearL = abs(x - l) <= reach
        val nearR = abs(x - r) <= reach
        val nearT = abs(y - t) <= reach
        val nearB = abs(y - b) <= reach
        val inX = x in (l - reach)..(r + reach)
        val inY = y in (t - reach)..(b + reach)
        return when {
            nearL && nearT -> CropHandle.TopLeft
            nearR && nearT -> CropHandle.TopRight
            nearL && nearB -> CropHandle.BottomLeft
            nearR && nearB -> CropHandle.BottomRight
            nearL && inY -> CropHandle.Left
            nearR && inY -> CropHandle.Right
            nearT && inX -> CropHandle.Top
            nearB && inX -> CropHandle.Bottom
            x in l..r && y in t..b -> CropHandle.Move
            else -> null
        }
    }

    /**
     * [start] after its [handle] moved by ([dx], [dy]) pixels on a [w] × [h] picture. With a
     * [ratio] the box keeps that shape (corners follow the finger's larger move; an edge grows the
     * other side about the middle). The box stays inside the picture and at least [min] pixels.
     */
    fun drag(start: CropBox, handle: CropHandle, dx: Float, dy: Float, w: Float, h: Float, ratio: Float?, min: Float): CropBox {
        val l0 = start.l * w
        val t0 = start.t * h
        val r0 = start.r * w
        val b0 = start.b * h
        val minW = min(min, w)
        val minH = min(min, h)
        fun box(l: Float, t: Float, r: Float, b: Float) = CropBox(l / w, t / h, r / w, b / h)

        if (handle == CropHandle.Move) {
            val mx = dx.coerceIn(-l0, w - r0)
            val my = dy.coerceIn(-t0, h - b0)
            return box(l0 + mx, t0 + my, r0 + mx, b0 + my)
        }

        if (ratio == null) {
            var l = l0
            var t = t0
            var r = r0
            var b = b0
            if (handle in setOf(CropHandle.Left, CropHandle.TopLeft, CropHandle.BottomLeft)) l = (l0 + dx).coerceIn(0f, r0 - minW)
            if (handle in setOf(CropHandle.Right, CropHandle.TopRight, CropHandle.BottomRight)) r = (r0 + dx).coerceIn(l0 + minW, w)
            if (handle in setOf(CropHandle.Top, CropHandle.TopLeft, CropHandle.TopRight)) t = (t0 + dy).coerceIn(0f, b0 - minH)
            if (handle in setOf(CropHandle.Bottom, CropHandle.BottomLeft, CropHandle.BottomRight)) b = (b0 + dy).coerceIn(t0 + minH, h)
            return box(l, t, r, b)
        }

        // The smallest box of this shape that is still at least `min` both ways.
        val leastW = max(minW, minH * ratio)
        when (handle) {
            CropHandle.TopLeft, CropHandle.TopRight, CropHandle.BottomLeft, CropHandle.BottomRight -> {
                // The opposite corner stays put; the box grows towards the finger.
                val left = handle == CropHandle.TopLeft || handle == CropHandle.BottomLeft
                val top = handle == CropHandle.TopLeft || handle == CropHandle.TopRight
                val ax = if (left) r0 else l0
                val ay = if (top) b0 else t0
                val fx = (if (left) l0 else r0) + dx
                val fy = (if (top) t0 else b0) + dy
                val roomW = if (left) ax else w - ax
                val roomH = if (top) ay else h - ay
                var bw = max(abs(fx - ax), abs(fy - ay) * ratio)
                bw = bw.coerceAtMost(min(roomW, roomH * ratio)).coerceAtLeast(min(leastW, min(roomW, roomH * ratio)))
                val bh = bw / ratio
                val l = if (left) ax - bw else ax
                val t = if (top) ay - bh else ay
                return box(l, t, l + bw, t + bh)
            }
            CropHandle.Left, CropHandle.Right -> {
                // That edge follows the finger; the height follows the shape, about the middle.
                val left = handle == CropHandle.Left
                val ax = if (left) r0 else l0
                val roomW = if (left) ax else w - ax
                var bw = if (left) ax - (l0 + dx) else (r0 + dx) - ax
                bw = bw.coerceAtMost(min(roomW, h * ratio)).coerceAtLeast(min(leastW, min(roomW, h * ratio)))
                val bh = bw / ratio
                val t = ((t0 + b0) / 2 - bh / 2).coerceIn(0f, h - bh)
                val l = if (left) ax - bw else ax
                return box(l, t, l + bw, t + bh)
            }
            else -> {
                val top = handle == CropHandle.Top
                val ay = if (top) b0 else t0
                val roomH = if (top) ay else h - ay
                var bh = if (top) ay - (t0 + dy) else (b0 + dy) - ay
                bh = bh.coerceAtMost(min(roomH, w / ratio)).coerceAtLeast(min(leastW / ratio, min(roomH, w / ratio)))
                val bw = bh * ratio
                val l = ((l0 + r0) / 2 - bw / 2).coerceIn(0f, w - bw)
                val t = if (top) ay - bh else ay
                return box(l, t, l + bw, t + bh)
            }
        }
    }
}
