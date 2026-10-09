package com.aamus.powerpdf.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Pinch to zoom (up to [maxZoom]×) and drag the zoomed picture; double-tap zooms in at the finger
 * or back out. A tap at 1× is [onTap]; when zoomed, a tap goes back to 1× if [tapResets], else it
 * is [onTap] too (the image viewer shows and hides its bars). At 1× one finger is left to a
 * pager's swipe. [resetKey]: back to 1× whenever it changes. Shared by the scanner's review and
 * the image viewer (decisions §42).
 */
@Composable
fun Zoomable(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    tapResets: Boolean = true,
    maxZoom: Float = 5f,
    resetKey: Any? = null,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, k: Float): Offset {
        val mx = size.width * (k - 1) / 2
        val my = size.height * (k - 1) / 2
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
    LaunchedEffect(resetKey) { reset() }
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(tapResets) {
                detectTapGestures(
                    onTap = { if (scale > 1f && tapResets) reset() else onTap() },
                    onDoubleTap = { at ->
                        if (scale > 1f) reset() else {
                            scale = 2.5f
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((centre - at) * (scale - 1f), scale)
                        }
                    },
                )
            }
            .pointerInput(maxZoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val k = (scale * zoom).coerceIn(1f, maxZoom)
                            offset = if (k > 1f) clamp(offset + pan, k) else Offset.Zero
                            scale = k
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        ) { content() }
    }
}
