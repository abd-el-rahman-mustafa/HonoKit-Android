package com.aamus.powerpdf.ui.tools.img

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.engine.img.CropBox
import com.aamus.powerpdf.engine.img.CropMath
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The crop box you drag (decisions §40), as in Photos and Snapseed: the picture outside is dimmed,
 * corners and edges have handles, the inside moves the box, and thirds show while a finger is down.
 * Used by the Photo editor and by Crop image; the arithmetic is engine/img/CropMath.
 */

/** Where a picture of [w] × [h] sits when fitted, centred, in [size] less [pad] on every side. */
fun fittedRect(w: Int, h: Int, size: Size, pad: Float): Rect {
    val s = min((size.width - pad * 2) / w, (size.height - pad * 2) / h)
    val dw = w * s
    val dh = h * s
    return Rect(Offset((size.width - dw) / 2, (size.height - dh) / 2), Size(dw, dh))
}

/** Draws [box] over a picture drawn at [area]. */
fun DrawScope.drawCropBox(box: CropBox, area: Rect, active: Boolean) {
    val l = area.left + box.l * area.width
    val t = area.top + box.t * area.height
    val r = area.left + box.r * area.width
    val b = area.top + box.b * area.height
    val dim = Color.Black.copy(alpha = 0.55f)
    // Dim what will be cut away (four bands round the box).
    drawRect(dim, Offset(area.left, area.top), Size(area.width, t - area.top))
    drawRect(dim, Offset(area.left, b), Size(area.width, area.bottom - b))
    drawRect(dim, Offset(area.left, t), Size(l - area.left, b - t))
    drawRect(dim, Offset(r, t), Size(area.right - r, b - t))
    val white = Color.White
    drawRect(white, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
    if (active) {
        val grid = white.copy(alpha = 0.6f)
        for (i in 1..2) {
            val x = l + (r - l) * i / 3
            val y = t + (b - t) * i / 3
            drawLine(grid, Offset(x, t), Offset(x, b), 1.dp.toPx())
            drawLine(grid, Offset(l, y), Offset(r, y), 1.dp.toPx())
        }
    }
    // Corner brackets and edge bars, drawn just inside the box so they never leave the picture.
    val k = 4.dp.toPx()
    val len = min(22.dp.toPx(), min(r - l, b - t) / 2.5f)
    for ((cx, cy, sx, sy) in listOf(Corner(l, t, 1f, 1f), Corner(r, t, -1f, 1f), Corner(l, b, 1f, -1f), Corner(r, b, -1f, -1f))) {
        drawRect(white, Offset(if (sx > 0) cx else cx - len, if (sy > 0) cy else cy - k), Size(len, k))
        drawRect(white, Offset(if (sx > 0) cx else cx - k, if (sy > 0) cy else cy - len), Size(k, len))
    }
    val bar = min(18.dp.toPx(), min(r - l, b - t) / 4)
    drawRect(white, Offset((l + r) / 2 - bar / 2, t), Size(bar, k))
    drawRect(white, Offset((l + r) / 2 - bar / 2, b - k), Size(bar, k))
    drawRect(white, Offset(l, (t + b) / 2 - bar / 2), Size(k, bar))
    drawRect(white, Offset(r - k, (t + b) / 2 - bar / 2), Size(k, bar))
}

private data class Corner(val x: Float, val y: Float, val sx: Float, val sy: Float)

/**
 * Lets one finger drag the box shown at [area]: a corner, an edge, or the inside. The lambdas are
 * read at each touch, so the gesture never restarts while the box changes under it.
 */
fun Modifier.cropGestures(
    box: () -> CropBox,
    ratio: () -> Float?,
    area: () -> Rect,
    onActive: (Boolean) -> Unit,
    onChange: (CropBox, gesture: Long) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val a = area()
        if (a.width <= 0f) return@awaitEachGesture
        val start = box()
        val handle = CropMath.handleAt(start, down.position.x - a.left, down.position.y - a.top, a.width, a.height, 28.dp.toPx()) ?: return@awaitEachGesture
        down.consume()
        val gesture = System.nanoTime()
        onActive(true)
        try {
            drag(down.id) { change ->
                val d = change.position - down.position
                onChange(CropMath.drag(start, handle, d.x, d.y, a.width, a.height, ratio(), 48.dp.toPx()), gesture)
                change.consume()
            }
        } finally {
            onActive(false)
        }
    }
}

/** A picture with a crop box on it, fitted in this element (Crop image). */
@Composable
fun CropPicture(image: ImageBitmap, box: CropBox, ratio: Float?, onChange: (CropBox) -> Unit, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(box)
    val shape by rememberUpdatedState(ratio)
    val change by rememberUpdatedState(onChange)
    val pad = with(LocalDensity.current) { 20.dp.toPx() }
    BoxWithConstraints(modifier) {
        val area = fittedRect(image.width, image.height, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), pad)
        val shown by rememberUpdatedState(area)
        Canvas(Modifier.fillMaxSize().cropGestures({ current }, { shape }, { shown }, { active = it }, { b, _ -> change(b) })) {
            drawImage(
                image,
                dstOffset = IntOffset(area.left.roundToInt(), area.top.roundToInt()),
                dstSize = IntSize(area.width.roundToInt(), area.height.roundToInt()),
            )
            drawCropBox(box, area, active)
        }
    }
}
