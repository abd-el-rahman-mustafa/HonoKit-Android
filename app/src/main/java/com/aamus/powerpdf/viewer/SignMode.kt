package com.aamus.powerpdf.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.PowerPdfApp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ui.edgeFade
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.addPicture
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** A signature being placed: which one, on which page, in which box (page points). */
private data class Placement(val page: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val w get() = x1 - x0
    val h get() = y1 - y0
}

@Composable
fun rememberSignTool(session: DocumentSession, viewport: ViewportState, onExit: () -> Unit): ModeTool {
    val model = LocalModel.current
    // Outlives the mode: Done writes the last change while the mode closes.
    val scope = LocalModel.current.appScope
    val density = LocalDensity.current
    val store = PowerPdfApp.instance.signatures
    val saved by store.flow.collectAsStateWithLifecycle()

    var chosen by remember { mutableStateOf<File?>(saved.firstOrNull()) }
    var oneOff by remember { mutableStateOf<Bitmap?>(null) }
    var padOpen by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf<Placement?>(null) }
    val image by produceState<Bitmap?>(null, chosen, oneOff) {
        value = oneOff ?: chosen?.let { f -> withContext(Dispatchers.IO) { store.load(f) } }
    }
    val imageBitmap = remember(image) { image?.asImageBitmap() }

    // What a drag on the placement does: move it, or resize from the corner.
    var dragMode by remember { mutableStateOf(0) } // 0 none, 1 move, 2 resize
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var startBox by remember { mutableStateOf<Placement?>(null) }

    fun place() {
        val p = placing ?: return
        val bmp = image ?: return
        placing = null
        scope.launch {
            val png = withContext(Dispatchers.Default) {
                ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
            runCatching { session.change("signature") { it.addPicture(p.page, png, p.x0, p.y0, p.x1, p.y1) } }
                .onFailure { model.say(model.errorText(it)) }
        }
    }

    /**
     * No separate Place step (decisions §47; UX review finding 15): a new signature lands in the
     * middle of the page on screen at once, with its handles; drag it where it goes. Done (or a
     * tap elsewhere on the page) sets it down.
     */
    fun landCentered(bmp: Bitmap) {
        val sizes = session.state.value.pageSizes
        if (sizes.isEmpty()) return
        val page = viewport.currentPage().coerceIn(0, sizes.lastIndex)
        val size = sizes[page]
        val l = viewport.layout
        val r = l.pageRect(page)
        val cp = viewport.toContent(Offset(viewport.viewportWidth / 2, viewport.viewportHeight / 2))
        val px = ((cp.x - r.left) / l.scale).takeIf { it in 0f..size.width } ?: (size.width / 2)
        val py = ((cp.y - r.top) / l.scale).takeIf { it in 0f..size.height } ?: (size.height / 2)
        val w = minOf(size.width * 0.35f, 190f)
        val h = w * bmp.height / bmp.width
        val x0 = (px - w / 2).coerceIn(0f, size.width - w)
        val y0 = (py - h / 2).coerceIn(0f, size.height - h)
        placing = Placement(page, x0, y0, x0 + w, y0 + h)
    }
    // A saved signature picked from the strip lands once its picture has been read.
    var landWhenReady by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(image, landWhenReady) {
        val b = image
        if (landWhenReady && b != null) {
            landWhenReady = false
            if (placing == null) landCentered(b)
        }
    }

    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            "sigland" -> image?.let { landCentered(it) }
            "sigtest" -> {
                // A drawn-looking test signature, saved like a real one.
                val b = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF1A3D8F.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 8f
                    strokeCap = Paint.Cap.ROUND
                }
                val path = android.graphics.Path().apply {
                    moveTo(30f, 140f)
                    cubicTo(90f, 20f, 150f, 20f, 170f, 120f)
                    cubicTo(190f, 190f, 250f, 60f, 300f, 100f)
                    cubicTo(350f, 140f, 380f, 40f, 440f, 90f)
                    quadTo(500f, 130f, 570f, 70f)
                }
                canvas.drawPath(path, paint)
                chosen = store.add(b)
            }
            "signat" -> {
                val v = c.value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                val bmp = image
                if (v.size == 3 && bmp != null) {
                    val w = 170f
                    val h = w * bmp.height / bmp.width
                    placing = Placement(v[0].toInt() - 1, v[1], v[2], v[1] + w, v[2] + h)
                }
            }
            // "text|Script|Classic|Plain": a typed signature, saved like a drawn one.
            "sigtype" -> {
                val text = c.value.substringBefore('|')
                val style = SignStyle.entries.firstOrNull { it.name.equals(c.value.substringAfter('|', "Script"), true) } ?: SignStyle.Script
                renderTyped(PowerPdfApp.instance, text, style, 0xFF1A3D8F.toInt())?.let { b -> chosen = store.add(b) }
                android.util.Log.i("PowerPDF", "sigtype: ${chosen?.name}")
            }
            // A path to a photo of a signature on paper.
            "sigphoto" -> {
                val made = withContext(Dispatchers.Default) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(c.value)?.let { inkFromPhoto(it, 0x111111) } }.getOrNull()
                }
                android.util.Log.i("PowerPDF", "sigphoto: ${made?.width}x${made?.height}")
                made?.let { b -> chosen = store.add(b) }
            }
            "signok" -> place()
            "pad" -> padOpen = c.value != "off"
        }
    }

    val gestures = remember(session) {
        object : PageGestures {
            override val doubleTapZooms: Boolean get() = false

            override fun wantsDrag(page: Int?, point: Offset, view: Offset): Boolean {
                val p = placing ?: return false
                if (page != p.page) return false
                val handle = with(density) { 28.dp.toPx() } / viewport.layout.scale
                dragMode = when {
                    kotlin.math.abs(point.x - p.x1) < handle && kotlin.math.abs(point.y - p.y1) < handle -> 2
                    point.x in p.x0..p.x1 && point.y in p.y0..p.y1 -> 1
                    else -> 0
                }
                return dragMode != 0
            }

            override fun onDragStart(page: Int, point: Offset, view: Offset) {
                dragStart = point
                startBox = placing
            }

            override fun onDrag(page: Int, point: Offset, view: Offset) {
                val b = startBox ?: return
                val d = point - dragStart
                val size = session.state.value.pageSizes.getOrNull(b.page) ?: return
                placing = if (dragMode == 1) {
                    val dx = d.x.coerceIn(-b.x0, size.width - b.x1)
                    val dy = d.y.coerceIn(-b.y0, size.height - b.y1)
                    b.copy(x0 = b.x0 + dx, y0 = b.y0 + dy, x1 = b.x1 + dx, y1 = b.y1 + dy)
                } else {
                    // Keep the proportions; at least 24 pt wide.
                    val ratio = b.h / b.w
                    val w = (b.w + d.x).coerceIn(24f, size.width - b.x0)
                    b.copy(x1 = b.x0 + w, y1 = b.y0 + w * ratio)
                }
            }

            override fun onDragEnd() {
                dragMode = 0
            }

            override fun onTap(page: Int?, point: Offset, view: Offset) {
                // A signature waiting on the page: a tap on it keeps it; a tap elsewhere sets it down.
                placing?.let { p ->
                    if (page != p.page || point.x !in p.x0..p.x1 || point.y !in p.y0..p.y1) place()
                    return
                }
                if (page == null) return
                val bmp = image
                if (bmp == null) {
                    padOpen = true
                    return
                }
                val size = session.state.value.pageSizes.getOrNull(page) ?: return
                val w = minOf(size.width * 0.35f, 190f)
                val h = w * bmp.height / bmp.width
                val x0 = (point.x - w / 2).coerceIn(0f, size.width - w)
                val y0 = (point.y - h / 2).coerceIn(0f, size.height - h)
                placing = Placement(page, x0, y0, x0 + w, y0 + h)
            }
        }
    }

    val overlay: DrawScope.(PageLayout) -> Unit = { layout ->
        val p = placing
        val img = imageBitmap
        if (p != null && img != null) {
            val r = layout.viewRect(viewport, p.page, p.x0, p.y0, p.x1, p.y1)
            drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()), dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()))
            drawRect(Color(0xFF1473E6), r.topLeft, r.size, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            drawCircle(Color.White, 9.dp.toPx(), r.bottomRight)
            drawCircle(Color(0xFF1473E6), 9.dp.toPx(), r.bottomRight, style = Stroke(2.dp.toPx()))
        }
    }

    val chrome: @Composable BoxScope.() -> Unit = {
        val c = Power.colors
        Column(Modifier.align(Alignment.TopCenter)) {
            ModeTopBar(stringResource(R.string.tool_sign), onDone = {
                place()
                onExit()
            }) { UndoRedo(session, scope) }
        }
        placing?.let { p ->
            val r = viewport.layout.viewRect(viewport, p.page, p.x0, p.y0, p.x1, p.y1)
            val y = (r.bottom + with(density) { 16.dp.toPx() })
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, y.roundToInt()) }
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(c.surface)
                        .border(1.dp, c.line, CircleShape)
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Only a way to take it off again: Done (or a tap elsewhere) sets it down.
                    IconAction(R.drawable.ic_delete, stringResource(R.string.sign_remove), { placing = null })
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            ModeHint(stringResource(if (placing != null) R.string.sign_adjust_hint else R.string.sign_place_hint))
            ModeBottom {
                Row(
                    rememberScrollState().let { s -> Modifier.fillMaxWidth().edgeFade(s).horizontalScroll(s) }.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .size(width = 120.dp, height = 64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, c.line, RoundedCornerShape(12.dp))
                            .clickable { padOpen = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Sym(R.drawable.ic_add, null, tint = c.accent)
                        Text(stringResource(R.string.create_signature), style = MaterialTheme.typography.labelSmall, color = c.accent, textAlign = TextAlign.Center, maxLines = 2)
                    }
                    oneOff?.let { b -> SignatureCard(b.asImageBitmap(), selected = true, onClick = {}, onDelete = { oneOff = null }) }
                    saved.forEach { f ->
                        val bmp by produceState<ImageBitmap?>(null, f) { value = withContext(Dispatchers.IO) { store.load(f)?.asImageBitmap() } }
                        SignatureCard(bmp, selected = oneOff == null && chosen == f, onClick = { oneOff = null; chosen = f; if (placing == null) landWhenReady = true }, onDelete = {
                            store.remove(f)
                            if (chosen == f) chosen = null
                        })
                    }
                }
            }
        }
    }

    if (padOpen) {
        SignaturePad(onDismiss = { padOpen = false }) { bmp, keep ->
            padOpen = false
            if (keep) {
                chosen = store.add(bmp)
                oneOff = null
            } else oneOff = bmp
            // The new signature lands on the page at once (no tap, no Place).
            if (placing == null) landCentered(bmp)
        }
    }

    return remember(gestures, overlay, chrome) {
        ModeTool(gestures, overlay, chrome, pending = { placing != null }, finish = {
            place()
            onExit()
        })
    }
}

@Composable
private fun SignatureCard(image: ImageBitmap?, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val c = Power.colors
    Box {
        Box(
            Modifier
                .size(width = 140.dp, height = 64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) Image(image, stringResource(R.string.signature), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(6.dp, (-6).dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(c.surface)
                .border(1.dp, c.line, CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Sym(R.drawable.ic_close, stringResource(R.string.delete_signature), tint = c.muted, size = 14.dp) }
    }
}

/**
 * Making a signature, as Acrobat's and Foxit's phone apps do: draw it with a finger, type it in a
 * handwriting style, or take it from a photo of it on paper (research-ux-mobile.md 3.1–3.4). The
 * result is a transparent PNG trimmed to the ink.
 */
@Composable
fun SignaturePad(onDismiss: () -> Unit, onDone: (Bitmap, Boolean) -> Unit) {
    val c = Power.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val inks = listOf(Color(0xFF111111), Color(0xFF1A3D8F), Color(0xFFC62828))
    var ink by remember { mutableStateOf(inks[0]) }
    var keep by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var typed by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(SignStyle.Script) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var photoColours by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val width = with(density) { 3.dp.toPx() }
    val ready = when (tab) {
        0 -> strokes.isNotEmpty()
        1 -> typed.isNotBlank()
        else -> photo != null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_close, stringResource(R.string.close), onDismiss)
                Text(stringResource(R.string.create_signature), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                Box(Modifier.padding(end = 8.dp)) {
                    PrimaryButton(stringResource(R.string.done), enabled = ready, onClick = {
                        val made = when (tab) {
                            0 -> renderSignature(strokes, ink, width)
                            1 -> renderTyped(context, typed.trim(), style, ink.toArgb())
                            else -> photo
                        }
                        if (made != null) onDone(made, keep)
                    })
                }
            }
            HorizontalDivider(color = c.line)
            com.aamus.powerpdf.ui.Segmented(
                listOf(0 to stringResource(R.string.sign_tab_draw), 1 to stringResource(R.string.sign_tab_type), 2 to stringResource(R.string.sign_tab_photo)),
                tab, { tab = it },
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> DrawPad(strokes, current, ink, width)
                    1 -> TypeSignature(typed, { typed = it }, style, { style = it }, ink)
                    else -> PhotoSignature(photo, { photo = it }, if (photoColours) null else ink.toArgb())
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                inks.forEach { col ->
                    com.aamus.powerpdf.ui.Swatch(col, selected = col == ink && !(tab == 2 && photoColours), onClick = { ink = col; photoColours = false }, size = 28.dp)
                }
                if (tab == 2) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.sign_photo_colours),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (photoColours) c.accent else c.ink,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (photoColours) c.accentSoft else Color.Transparent)
                            .border(1.dp, if (photoColours) c.accent else c.line, CircleShape)
                            .clickable { photoColours = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (tab == 0) com.aamus.powerpdf.ui.SecondaryButton(stringResource(R.string.clear), { strokes.clear() }, enabled = strokes.isNotEmpty())
            }
            Row(
                Modifier.fillMaxWidth().clickable { keep = !keep }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(keep, { keep = it }, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                Text(stringResource(R.string.save_signature), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The Draw tab: a white pad with a baseline; one finger draws. */
@Composable
private fun DrawPad(strokes: MutableList<List<Offset>>, current: MutableList<Offset>, ink: Color, width: Float) {
    val c = Power.colors
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    current.clear()
                    current.add(down.position)
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        current.add(ch.position)
                        ch.consume()
                    }
                    strokes.add(current.toList())
                    current.clear()
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val base = this.size.height * 0.68f
            drawLine(Color(0xFFBDBDBD), Offset(32f, base), Offset(this.size.width - 32f, base), strokeWidth = 1.dp.toPx())
            for (s in strokes + listOf(current.toList())) drawInk(s, ink, width)
        }
        if (strokes.isEmpty() && current.isEmpty()) {
            Text(
                stringResource(R.string.sign_draw_hint),
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun DrawScope.drawInk(points: List<Offset>, colour: Color, width: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(colour, width / 2, points[0])
        return
    }
    val path = smoothPath(points)
    drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Quadratic curves through the midpoints: smooth ink without wobble. */
private fun smoothPath(points: List<Offset>): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size - 1) {
        val mid = (points[i] + points[i + 1]) / 2f
        path.quadraticTo(points[i].x, points[i].y, mid.x, mid.y)
    }
    path.lineTo(points.last().x, points.last().y)
    return path
}

/** Draws the strokes onto a transparent bitmap trimmed to the ink (with a small margin). */
private fun renderSignature(strokes: List<List<Offset>>, colour: Color, width: Float): Bitmap {
    val all = strokes.flatten()
    val pad = width * 2
    val bounds = Rect(all.minOf { it.x } - pad, all.minOf { it.y } - pad, all.maxOf { it.x } + pad, all.maxOf { it.y } + pad)
    val w = maxOf(8, bounds.width.roundToInt())
    val h = maxOf(8, bounds.height.roundToInt())
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (s in strokes) {
        val pts = s.map { it - bounds.topLeft }
        if (pts.size == 1) {
            canvas.drawCircle(pts[0].x, pts[0].y, width / 2, paint.apply { style = Paint.Style.FILL })
            paint.style = Paint.Style.STROKE
            continue
        }
        val path = android.graphics.Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2
            val my = (pts[i].y + pts[i + 1].y) / 2
            path.quadTo(pts[i].x, pts[i].y, mx, my)
        }
        path.lineTo(pts.last().x, pts.last().y)
        canvas.drawPath(path, paint)
    }
    return bmp
}
