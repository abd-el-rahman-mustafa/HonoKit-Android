package com.aamus.powerpdf.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aamus.powerpdf.R
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.Margins
import com.aamus.powerpdf.engine.cropPages
import com.aamus.powerpdf.engine.whiteMargins
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.SecondaryButton
import com.aamus.powerpdf.ui.Segmented
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PageScope { This, All, Odd, Even, Selected }

/** The pages a scope means, zero-based. Odd and even are as people count pages (1, 3, 5…). */
fun pagesOf(scope: PageScope, current: Int, count: Int, selected: Set<Int>): List<Int> = when (scope) {
    PageScope.This -> listOf(current)
    PageScope.All -> (0 until count).toList()
    PageScope.Odd -> (0 until count step 2).toList()
    PageScope.Even -> (1 until count step 2).toList()
    PageScope.Selected -> selected.sorted()
}

/** Which part of the crop box a finger holds. */
private enum class Grip { None, Move, L, T, R, B, TL, TR, BL, BR }

/**
 * Crop pages (desktop decisions §41): a box on the page, dragged by its corners and sides (a loupe
 * shows the edge under the finger), "Trim white margins" fits it to the content, and the crop applies
 * to this page, all, odd, even or the selected pages. The CropBox is set; nothing is deleted.
 */
@Composable
fun CropPagesDialog(session: DocumentSession, page: Int, selected: Set<Int>, onDismiss: () -> Unit) {
    val model = LocalModel.current
    // Outlives the mode: Done writes the last change while the mode closes.
    val scope = LocalModel.current.appScope
    val c = Power.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val size = session.state.value.pageSizes.getOrNull(page) ?: return
    val count = session.state.value.pageCount
    var margins by remember { mutableStateOf(Margins(size.width * 0.05f, size.height * 0.05f, size.width * 0.05f, size.height * 0.05f)) }
    var pageScope by remember { mutableStateOf(if (selected.size > 1) PageScope.Selected else PageScope.This) }
    var grip by remember { mutableStateOf(Grip.None) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val image by produceState<ImageBitmap?>(null, page) {
        value = runCatching { session.engine { it.renderWidth(page, 1100) }.asImageBitmap() }.getOrNull()
    }

    fun apply() {
        val list = pagesOf(pageScope, page, count, selected)
        val m = margins
        onDismiss()
        scope.launch {
            runCatching { session.change("crop") { it.cropPages(list, m) } }
                .onSuccess { model.say(model.plural(R.plurals.cropped_pages, list.size, list.size), action = model.str(R.string.undo)) { scope.launch { session.undo() } } }
                .onFailure { model.say(model.errorText(it)) }
        }
    }

    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            "croptrim" -> runCatching { session.engine { it.whiteMargins(page) } }.onSuccess { margins = it }
            "cropscope" -> pageScope = PageScope.entries.firstOrNull { it.name.equals(c.value, true) } ?: pageScope
            "cropapply" -> apply()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.surface).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_close, stringResource(R.string.cancel), onDismiss)
                Text(stringResource(R.string.crop_pages), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                androidx.compose.foundation.layout.Box(Modifier.padding(end = 8.dp)) { PrimaryButton(stringResource(R.string.apply), { apply() }) }
            }
            HorizontalDivider(color = c.line)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(c.sunken).padding(24.dp), contentAlignment = Alignment.Center) {
                val img = image
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val k = minOf(boxW / size.width, boxH / size.height)
                val w = size.width * k
                val h = size.height * k
                val handle = with(density) { 28.dp.toPx() }
                Canvas(
                    Modifier
                        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                        .pointerInput(k) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val m0 = margins
                                val r = Rect(m0.left * k, m0.top * k, w - m0.right * k, h - m0.bottom * k)
                                val p = down.position
                                fun near(x: Float, y: Float) = abs(p.x - x) < handle && abs(p.y - y) < handle
                                grip = when {
                                    near(r.left, r.top) -> Grip.TL
                                    near(r.right, r.top) -> Grip.TR
                                    near(r.left, r.bottom) -> Grip.BL
                                    near(r.right, r.bottom) -> Grip.BR
                                    abs(p.x - r.left) < handle && p.y in r.top..r.bottom -> Grip.L
                                    abs(p.x - r.right) < handle && p.y in r.top..r.bottom -> Grip.R
                                    abs(p.y - r.top) < handle && p.x in r.left..r.right -> Grip.T
                                    abs(p.y - r.bottom) < handle && p.x in r.left..r.right -> Grip.B
                                    r.contains(p) -> Grip.Move
                                    else -> Grip.None
                                }
                                if (grip == Grip.None) return@awaitEachGesture
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                down.consume()
                                val minSize = 36f
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    ch.consume()
                                    finger = ch.position
                                    val d = (ch.position - p) / k
                                    var (l, t, rr, b) = listOf(m0.left, m0.top, m0.right, m0.bottom)
                                    when (grip) {
                                        Grip.Move -> {
                                            val dx = d.x.coerceIn(-m0.left, m0.right)
                                            val dy = d.y.coerceIn(-m0.top, m0.bottom)
                                            l += dx; rr -= dx; t += dy; b -= dy
                                        }
                                        else -> {
                                            if (grip in setOf(Grip.L, Grip.TL, Grip.BL)) l = (m0.left + d.x).coerceIn(0f, maxOf(0f, size.width - rr - minSize))
                                            if (grip in setOf(Grip.R, Grip.TR, Grip.BR)) rr = (m0.right - d.x).coerceIn(0f, maxOf(0f, size.width - l - minSize))
                                            if (grip in setOf(Grip.T, Grip.TL, Grip.TR)) t = (m0.top + d.y).coerceIn(0f, maxOf(0f, size.height - b - minSize))
                                            if (grip in setOf(Grip.B, Grip.BL, Grip.BR)) b = (m0.bottom - d.y).coerceIn(0f, maxOf(0f, size.height - t - minSize))
                                        }
                                    }
                                    margins = Margins(l, t, rr, b)
                                }
                                grip = Grip.None
                                finger = null
                            }
                        },
                ) {
                    if (img != null) drawImage(img, dstSize = IntSize(w.roundToInt(), h.roundToInt()), dstOffset = IntOffset.Zero)
                    else drawRect(Color.White)
                    val m = margins
                    val r = Rect(m.left * k, m.top * k, w - m.right * k, h - m.bottom * k)
                    val outside = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, w, h))
                        addRect(r)
                    }
                    drawPath(outside, Color.Black.copy(alpha = 0.45f))
                    drawRect(Color(0xFF1473E6), r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                    val corner = 7.dp.toPx()
                    listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach {
                        drawCircle(Color.White, corner + 2.dp.toPx(), it)
                        drawCircle(Color(0xFF1473E6), corner, it)
                    }
                    listOf(Offset(r.center.x, r.top), Offset(r.center.x, r.bottom), Offset(r.left, r.center.y), Offset(r.right, r.center.y)).forEach {
                        drawRect(Color.White, it - Offset(9.dp.toPx(), 3.dp.toPx()), Size(18.dp.toPx(), 6.dp.toPx()))
                        drawRect(Color(0xFF1473E6), it - Offset(8.dp.toPx(), 2.dp.toPx()), Size(16.dp.toPx(), 4.dp.toPx()))
                    }
                    val f = finger
                    if (f != null && img != null && grip != Grip.Move) loupe(img, w, h, f) { drawRect(Color(0xFF1473E6), r.topLeft, r.size, style = Stroke(1.dp.toPx())) }
                }
            }
            // The size the pages will have.
            val mm = 25.4f / 72f
            val cw = ((size.width - margins.left - margins.right) * mm).roundToInt()
            val ch = ((size.height - margins.top - margins.bottom) * mm).roundToInt()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.crop_size_mm, cw, ch), style = MaterialTheme.typography.labelLarge, color = c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.trim_white_margins), {
                        scope.launch { runCatching { session.engine { it.whiteMargins(page) } }.onSuccess { margins = it } }
                    }, Modifier.weight(1f), icon = R.drawable.ic_auto_fix_high)
                    SecondaryButton(stringResource(R.string.crop_reset_box), { margins = Margins.NONE }, Modifier.weight(1f), icon = R.drawable.ic_fit_page_width)
                }
                val options = buildList {
                    add(PageScope.This to stringResource(R.string.scope_this, page + 1))
                    add(PageScope.All to stringResource(R.string.scope_all))
                    if (count > 1) {
                        add(PageScope.Odd to stringResource(R.string.scope_odd))
                        add(PageScope.Even to stringResource(R.string.scope_even))
                    }
                    if (selected.size > 1) add(PageScope.Selected to stringResource(R.string.scope_selected, selected.size))
                }
                Segmented(options, pageScope, { pageScope = it })
            }
        }
    }
}

/**
 * A magnifier above the finger (research-ux-mobile.md 4.10): the page around the finger at twice
 * the size, with the crop edge in it. Drawn by hand: the platform magnifier needs API 28.
 */
fun DrawScope.loupe(img: ImageBitmap, w: Float, h: Float, at: Offset, marks: DrawScope.() -> Unit) {
    val radius = 48.dp.toPx()
    val zoom = 2f
    // Above the finger, or below it near the top.
    val centre = Offset(at.x.coerceIn(radius, maxOf(radius, size.width - radius)), if (at.y - radius * 2 > 0) at.y - radius * 1.6f else at.y + radius * 1.6f)
    val circle = Path().apply { addOval(Rect(centre, radius)) }
    drawCircle(Color.Black.copy(alpha = 0.25f), radius + 3.dp.toPx(), centre + Offset(0f, 2.dp.toPx()))
    clipPath(circle) {
        drawRect(Color.White, centre - Offset(radius, radius), Size(radius * 2, radius * 2))
        withTransform({
            translate(centre.x - at.x * zoom, centre.y - at.y * zoom)
            scale(zoom, zoom, Offset.Zero)
        }) {
            drawImage(img, dstSize = IntSize(w.roundToInt(), h.roundToInt()), dstOffset = IntOffset.Zero)
            marks()
        }
    }
    drawCircle(Color.White, radius, centre, style = Stroke(3.dp.toPx()))
    drawCircle(Color(0xFF1473E6), 3.dp.toPx(), centre)
}
