package com.aamus.powerpdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ui.edgeFade
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.MarkInfo
import com.aamus.powerpdf.engine.MarkKind
import com.aamus.powerpdf.engine.Rgb
import com.aamus.powerpdf.engine.TextLogic
import com.aamus.powerpdf.engine.addInk
import com.aamus.powerpdf.engine.addNote
import com.aamus.powerpdf.engine.addShape
import com.aamus.powerpdf.engine.addStamp
import com.aamus.powerpdf.engine.addTextBox
import com.aamus.powerpdf.engine.moveMark
import com.aamus.powerpdf.engine.addTextMarkup
import com.aamus.powerpdf.engine.deleteMark
import com.aamus.powerpdf.engine.recolourMark
import com.aamus.powerpdf.engine.setMarkText
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.Swatch
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class CTool(val icon: Int, val label: Int, val shape: MarkKind? = null) {
    Note(R.drawable.ic_sticky_note_2, R.string.note),
    Highlight(R.drawable.ic_ink_highlighter, R.string.highlight),
    Underline(R.drawable.ic_format_underlined, R.string.underline),
    StrikeOut(R.drawable.ic_format_strikethrough, R.string.strikeout),
    Pen(R.drawable.ic_draw, R.string.pen),
    Rect(R.drawable.ic_shape_rect, R.string.shape_rect, MarkKind.Rect),
    Ellipse(R.drawable.ic_shape_ellipse, R.string.shape_ellipse, MarkKind.Ellipse),
    Line(R.drawable.ic_shape_line, R.string.shape_line, MarkKind.Line),
    Arrow(R.drawable.ic_shape_arrow, R.string.shape_arrow, MarkKind.Arrow),
    TextBox(R.drawable.ic_text_fields, R.string.text_box),
    Stamp(R.drawable.ic_stamp, R.string.stamp),
}

/** A line or arrow near a multiple of 45 degrees snaps to it (the end moves, the start stays). */
private fun snap45(from: Offset, to: Offset): Pair<Offset, Boolean> {
    val d = to - from
    val len = d.getDistance()
    if (len < 1f) return to to false
    val angle = Math.toDegrees(kotlin.math.atan2(d.y.toDouble(), d.x.toDouble()))
    val nearest = Math.round(angle / 45.0) * 45.0
    if (kotlin.math.abs(angle - nearest) > 6.0) return to to false
    val r = Math.toRadians(nearest)
    return Offset(from.x + (len * kotlin.math.cos(r)).toFloat(), from.y + (len * kotlin.math.sin(r)).toFloat()) to true
}

private class Stroke2(val page: Int, val points: MutableList<Offset>, val colour: Int, val width: Float)

@Composable
fun rememberCommentTool(session: DocumentSession, viewport: ViewportState, vm: ViewerModel, onExit: () -> Unit): ModeTool {
    val model = LocalModel.current
    // Outlives the mode: Done writes the last change while the mode closes.
    val scope = LocalModel.current.appScope
    val density = LocalDensity.current
    val prefs by model.settings.flow.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val marks = remember(session) { MarksCache(session) }

    var tool by remember { mutableStateOf<CTool?>(CTool.Highlight) }
    val colours = remember {
        mutableStateMapOf(
            CTool.Note to 0xFFFFD600.toInt(),
            CTool.Highlight to defaultColour(MarkupKind.Highlight),
            CTool.Underline to defaultColour(MarkupKind.Underline),
            CTool.StrikeOut to defaultColour(MarkupKind.StrikeOut),
            CTool.Pen to 0xFF1473E6.toInt(),
            CTool.Rect to 0xFFE34850.toInt(),
            CTool.Ellipse to 0xFFE34850.toInt(),
            CTool.Line to 0xFFE34850.toInt(),
            CTool.Arrow to 0xFFE34850.toInt(),
            CTool.TextBox to 0xFF1F1F1F.toInt(),
            CTool.Stamp to 0xFF2D9D78.toInt(),
        )
    }
    var penWidth by remember { mutableFloatStateOf(2f) }
    var shapeWidth by remember { mutableFloatStateOf(2f) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // A shape being drawn, and a selected mark being moved.
    var shapePage by remember { mutableStateOf(-1) }
    var shapeFrom by remember { mutableStateOf(Offset.Zero) }
    var shapeTo by remember { mutableStateOf<Offset?>(null) }
    var snapped by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var moveFrom by remember { mutableStateOf(Offset.Zero) }
    var moveBy by remember { mutableStateOf(Offset.Zero) }
    var styleOpen by remember { mutableStateOf(false) }

    // Text markup being dragged.
    var dragPage by remember { mutableStateOf(-1) }
    var dragFrom by remember { mutableStateOf(Offset.Zero) }
    var preview by remember { mutableStateOf<TextLogic.Selection?>(null) }

    // Pen strokes: the one being drawn, those waiting to be written, those written but not yet redrawn.
    val strokes = remember { mutableStateListOf<Stroke2>() }
    val written = remember { mutableStateListOf<Stroke2>() }
    var strokeTick by remember { mutableStateOf(0) }
    var commitJob by remember { mutableStateOf<Job?>(null) }
    var shownUntil by remember { mutableStateOf(Int.MAX_VALUE) }

    var selected by remember { mutableStateOf<Pair<Int, MarkInfo>?>(null) }
    var listOpen by remember { mutableStateOf(false) }
    // Where a text box or stamp goes, while its dialog is open.
    var textBoxAt by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    var stampAt by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    // A mark picked in the comments list before this mode opened.
    LaunchedEffect(vm.pendingMark) {
        vm.pendingMark?.let { e ->
            selected = e.page to e.mark
            vm.pendingMark = null
        }
    }
    var noteAt by remember { mutableStateOf<Triple<Int, Offset, MarkInfo?>?>(null) }

    fun allowed() = session.state.value.rights?.annotate != false
    val author = authorName(model, prefs.author)

    // Load marks and text for the pages on screen.
    LaunchedEffect(session) {
        snapshotFlow { viewport.layout.visiblePages(viewport.contentTop, viewport.contentTop + viewport.viewportHeight) to state.version }
            .collect { (visible, _) ->
                marks.load(visible)
                for (p in visible) vm.linesOf(p)
            }
    }
    // Written strokes stay drawn until the page shows them.
    LaunchedEffect(state.version) {
        if (state.version >= shownUntil) {
            delay(400)
            written.clear()
            shownUntil = Int.MAX_VALUE
        }
    }

    fun commitStrokes() {
        commitJob?.cancel()
        val pending = strokes.toList()
        if (pending.isEmpty()) return
        strokes.clear()
        written.addAll(pending)
        scope.launch {
            try {
                for ((page, list) in pending.groupBy { it.page }) {
                    val first = list.first()
                    session.change("pen") {
                        it.addInk(page, list.map { s -> s.points.map { p -> p.x to p.y } }, Rgb.of(first.colour), first.width, 1f, author)
                    }
                }
                shownUntil = session.state.value.version
            } catch (e: Throwable) {
                written.clear()
                model.say(model.errorText(e))
            }
        }
    }

    fun scheduleCommit() {
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(1400)
            commitStrokes()
        }
    }

    fun toPage(page: Int, view: Offset): Offset {
        val l = viewport.layout
        val r = l.pageRect(page)
        val c = viewport.toContent(view)
        return Offset((c.x - r.left) / l.scale, (c.y - r.top) / l.scale)
    }

    val gestures = remember(session) {
        object : PageGestures {
            override val doubleTapZooms: Boolean get() = tool != CTool.Note

            fun slop() = with(density) { 12.dp.toPx() } / viewport.layout.scale

            fun onSelected(page: Int, point: Offset): Boolean {
                val (p, m) = selected ?: return false
                // Highlights and underlines belong to their text: they are not moved.
                if (!m.movable) return false
                val sl = slop()
                return p == page && point.x in m.box.x0 - sl..m.box.x1 + sl && point.y in m.box.y0 - sl..m.box.y1 + sl
            }

            override fun wantsDrag(page: Int?, point: Offset, view: Offset): Boolean {
                if (page == null || !allowed()) return false
                // A drag that starts on the selected mark moves it.
                if (onSelected(page, point)) return true
                return tool.let { it == CTool.Highlight || it == CTool.Underline || it == CTool.StrikeOut || it == CTool.Pen || it?.shape != null }
            }

            override fun onDragStart(page: Int, point: Offset, view: Offset) {
                if (onSelected(page, point)) {
                    moving = true
                    moveFrom = point
                    moveBy = Offset.Zero
                    return
                }
                selected = null
                when {
                    tool == CTool.Pen -> {
                        commitJob?.cancel()
                        strokes.add(Stroke2(page, mutableListOf(point), colours[CTool.Pen]!!, penWidth))
                        strokeTick++
                    }
                    tool?.shape != null -> {
                        shapePage = page
                        shapeFrom = point
                        shapeTo = point
                        snapped = false
                    }
                    else -> {
                        dragPage = page
                        dragFrom = point
                        preview = null
                    }
                }
            }

            override fun onDrag(page: Int, point: Offset, view: Offset) {
                if (moving) {
                    moveBy = point - moveFrom
                    return
                }
                when {
                    tool == CTool.Pen -> {
                        strokes.lastOrNull()?.points?.add(point)
                        strokeTick++
                    }
                    tool?.shape != null -> {
                        val straight = tool == CTool.Line || tool == CTool.Arrow
                        val (end, isSnapped) = if (straight) snap45(shapeFrom, point) else point to false
                        if (isSnapped && !snapped) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        snapped = isSnapped
                        shapeTo = end
                    }
                    else -> {
                        val lines = vm.cachedLines(dragPage) ?: return
                        preview = TextLogic.selectText(lines, dragFrom.x to dragFrom.y, point.x to point.y)
                    }
                }
            }

            override fun onDragEnd() {
                if (moving) {
                    moving = false
                    val (page, m) = selected ?: return
                    val d = moveBy
                    moveBy = Offset.Zero
                    if (d.getDistance() < 1f) return
                    scope.launch {
                        runCatching { session.change("move mark") { it.moveMark(page, m.index, m.box, d.x, d.y) } }
                            .onSuccess { selected = null }
                            .onFailure { e -> model.say(model.errorText(e)) }
                    }
                    return
                }
                val shape = tool?.shape
                when {
                    tool == CTool.Pen -> scheduleCommit()
                    shape != null -> {
                        val page = shapePage
                        val from = shapeFrom
                        val to = shapeTo
                        val t = tool!!
                        shapePage = -1
                        shapeTo = null
                        if (to == null) return
                        // A tap-sized drag draws nothing (the finger only meant to scroll or select).
                        val straight = shape == MarkKind.Line || shape == MarkKind.Arrow
                        val big = if (straight) (to - from).getDistance() >= 6f else kotlin.math.abs(to.x - from.x) >= 4f && kotlin.math.abs(to.y - from.y) >= 4f
                        if (!big) return
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        scope.launch {
                            runCatching { session.change("shape") { it.addShape(page, shape, from.x, from.y, to.x, to.y, Rgb.of(colours[t]!!), shapeWidth, author) } }
                                .onFailure { model.say(model.errorText(it)) }
                        }
                    }
                    else -> {
                        val sel = preview
                        val page = dragPage
                        preview = null
                        dragPage = -1
                        val t = tool ?: return
                        if (sel == null || sel.boxes.isEmpty()) return
                        val kind = when (t) {
                            CTool.Underline -> MarkKind.Underline
                            CTool.StrikeOut -> MarkKind.StrikeOut
                            else -> MarkKind.Highlight
                        }
                        scope.launch {
                            runCatching { session.change("markup") { it.addTextMarkup(page, kind, sel.boxes, Rgb.of(colours[t]!!), author) } }
                                .onFailure { model.say(model.errorText(it)) }
                        }
                    }
                }
            }

            override fun onDragCancel() {
                preview = null
                dragPage = -1
                shapePage = -1
                shapeTo = null
                moving = false
                moveBy = Offset.Zero
                if (tool == CTool.Pen && strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
            }

            override fun onTap(page: Int?, point: Offset, view: Offset) {
                if (page == null) {
                    selected = null
                    return
                }
                if (tool == CTool.Note || tool == CTool.TextBox || tool == CTool.Stamp) {
                    // A tap on an existing mark selects it; elsewhere it places a new one.
                    val slopHit = with(density) { 12.dp.toPx() } / viewport.layout.scale
                    val hitMark = marks.at(page, point.x, point.y, slopHit)
                    if (hitMark != null && tool != CTool.Note) {
                        selected = page to hitMark
                        return
                    }
                    if (!allowed()) {
                        model.say(model.str(R.string.annotate_not_allowed))
                        return
                    }
                    when (tool) {
                        CTool.TextBox -> textBoxAt = page to point
                        CTool.Stamp -> stampAt = page to point
                        else -> noteAt = Triple(page, point, null)
                    }
                    return
                }
                val slop = with(density) { 12.dp.toPx() } / viewport.layout.scale
                val hit = marks.at(page, point.x, point.y, slop)
                selected = hit?.let { page to it }
                if (hit?.kind == MarkKind.Note) noteAt = Triple(page, point, hit)
            }

            override fun onLongPress(page: Int?, point: Offset, view: Offset) {
                onTap(page, point, view)
            }
        }
    }

    val overlay: DrawScope.(PageLayout) -> Unit = { layout ->
        @Suppress("UNUSED_VARIABLE") val t = strokeTick
        preview?.let { sel ->
            val colour = Color(colours[tool] ?: 0xFFFFD600.toInt())
            sel.boxes.forEach { b ->
                val r = layout.viewRect(viewport, dragPage, b.x0, b.y0, b.x1, b.y1)
                when (tool) {
                    CTool.Underline -> drawRect(colour, r.bottomLeft - Offset(0f, 2.dp.toPx()), androidx.compose.ui.geometry.Size(r.width, 2.dp.toPx()))
                    CTool.StrikeOut -> drawRect(colour, Offset(r.left, r.center.y - 1.dp.toPx()), androidx.compose.ui.geometry.Size(r.width, 2.dp.toPx()))
                    else -> drawRect(colour.copy(alpha = 0.4f), r.topLeft, r.size)
                }
            }
        }
        for (s in written + strokes) {
            if (s.points.isEmpty()) continue
            val path = Path()
            // The same smooth curve the stroke is stored as (Smooth.stroke, decisions §52).
            com.aamus.powerpdf.engine.Smooth.stroke(s.points.map { it.x to it.y }).forEachIndexed { i, p ->
                val v = viewport.toView(layout.toContent(s.page, Offset(p.first, p.second)))
                if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y)
            }
            if (s.points.size == 1) {
                val v = viewport.toView(layout.toContent(s.page, s.points[0]))
                drawCircle(Color(s.colour), s.width * layout.scale / 2, v)
            } else {
                drawPath(path, Color(s.colour), style = Stroke(s.width * layout.scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        shapeTo?.let { to ->
            val t = tool ?: return@let
            val colour = Color(colours[t] ?: 0xFFE34850.toInt())
            val a = viewport.toView(layout.toContent(shapePage, shapeFrom))
            val b = viewport.toView(layout.toContent(shapePage, to))
            val stroke = Stroke(maxOf(1.dp.toPx(), shapeWidth * layout.scale))
            val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
            val size = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
            when (t.shape) {
                MarkKind.Rect -> drawRect(colour, tl, size, style = stroke)
                MarkKind.Ellipse -> drawOval(colour, tl, size, style = stroke)
                else -> {
                    drawLine(colour, a, b, stroke.width, cap = StrokeCap.Round)
                    if (t.shape == MarkKind.Arrow && (b - a).getDistance() > 1f) {
                        val ang = kotlin.math.atan2(b.y - a.y, b.x - a.x)
                        val head = maxOf(10.dp.toPx(), stroke.width * 4)
                        for (side in listOf(-1, 1)) {
                            val th = ang + Math.PI.toFloat() + side * 0.5f
                            drawLine(colour, b, Offset(b.x + head * kotlin.math.cos(th), b.y + head * kotlin.math.sin(th)), stroke.width, cap = StrokeCap.Round)
                        }
                    }
                }
            }
        }
        selected?.let { (page, m) ->
            val shift = moveBy
            val r = layout.viewRect(viewport, page, m.box.x0 + shift.x, m.box.y0 + shift.y, m.box.x1 + shift.x, m.box.y1 + shift.y)
            val pad = 4.dp.toPx()
            drawRect(
                Power.run { Color(0xFF1473E6) },
                topLeft = r.topLeft - Offset(pad, pad),
                size = androidx.compose.ui.geometry.Size(r.width + 2 * pad, r.height + 2 * pad),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
        }
    }

    val chrome: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        val c = Power.colors
        Column(Modifier.align(Alignment.TopCenter)) {
            ModeTopBar(
                stringResource(R.string.tool_comment),
                onDone = {
                    commitStrokes()
                    onExit()
                },
            ) {
                IconAction(R.drawable.ic_list, stringResource(R.string.comments_list), { listOpen = true })
                UndoRedo(session, scope)
            }
        }

        if (listOpen) {
            CommentsSheet(session, onDismiss = { listOpen = false }, onOpen = { e ->
                listOpen = false
                viewport.reveal(e.page, e.mark.box.x0, e.mark.box.y0, e.mark.box.x1, e.mark.box.y1)
                selected = e.page to e.mark
            })
        }

        // The selected mark's small menu.
        selected?.let { (page, m) ->
            val l = viewport.layout
            val r = l.viewRect(viewport, page, m.box.x0, m.box.y0, m.box.x1, m.box.y1)
            val y = (r.top - with(density) { 60.dp.toPx() }).coerceAtLeast(viewport.topInset + 8f)
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, y.roundToInt()) }
                        .shadow(6.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MarkColours.take(6).forEach { col ->
                        Swatch(Color(col), selected = m.colour.toArgb() == col, onClick = {
                            scope.launch {
                                runCatching { session.change("recolour") { it.recolourMark(page, m.index, m.box, Rgb.of(col)) } }.onFailure { e -> model.say(model.errorText(e)) }
                                selected = null
                            }
                        }, size = 22.dp)
                    }
                    if (m.kind == MarkKind.Note) IconAction(R.drawable.ic_edit, stringResource(R.string.edit_note), { noteAt = Triple(page, Offset(m.box.x0, m.box.y0), m) })
                    IconAction(R.drawable.ic_delete, stringResource(R.string.delete_mark), {
                        scope.launch {
                            runCatching { session.change("delete mark") { it.deleteMark(page, m.index, m.box) } }.onFailure { e -> model.say(model.errorText(e)) }
                            selected = null
                        }
                    }, tint = c.danger)
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter)) {
            val hint = when {
                !allowed() -> stringResource(R.string.annotate_not_allowed)
                tool == CTool.Pen -> stringResource(R.string.comment_hint_pen)
                tool == CTool.Note -> stringResource(R.string.comment_hint_note)
                tool == CTool.TextBox -> stringResource(R.string.comment_hint_text_box)
                tool == CTool.Stamp -> stringResource(R.string.comment_hint_stamp)
                tool?.shape != null -> stringResource(R.string.comment_hint_shape)
                tool != null -> stringResource(R.string.comment_hint_markup)
                else -> null
            }
            val movable = selected?.second?.movable == true
            if (movable && allowed()) ModeHint(stringResource(R.string.move_mark_hint)) else if (hint != null && selected == null) ModeHint(hint)
            ModeBottom {
                Column {
                    if (styleOpen && tool != null) {
                        StyleRow(
                            colour = colours[tool]!!,
                            onColour = { colours[tool!!] = it },
                            width = if (tool == CTool.Pen) penWidth else if (tool?.shape != null) shapeWidth else null,
                            onWidth = { if (tool == CTool.Pen) penWidth = it else shapeWidth = it },
                        )
                    }
                    // Whole labels; with large text the row scrolls, and its edges fade where tools are hidden.
                    val toolsScroll = rememberScrollState()
                    Row(
                        Modifier.fillMaxWidth().edgeFade(toolsScroll).horizontalScroll(toolsScroll).padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CTool.entries.forEach { t ->
                            ToolChip(t.icon, stringResource(t.label), active = tool == t, dot = colours[t]!!) {
                                if (tool == CTool.Pen && t != CTool.Pen) commitStrokes()
                                if (tool == t) styleOpen = !styleOpen else {
                                    tool = t
                                    styleOpen = false
                                }
                                selected = null
                            }
                        }
                    }
                }
            }
        }
    }

    noteAt?.let { (page, at, existing) ->
        NoteDialog(
            initial = existing?.text ?: "",
            editing = existing != null,
            onDismiss = { noteAt = null },
            onPost = { text ->
                noteAt = null
                scope.launch {
                    runCatching {
                        if (existing != null) session.change("note") { it.setMarkText(page, existing.index, existing.box, text) }
                        else if (text.isNotBlank()) session.change("note") { it.addNote(page, at.x - 10f, at.y - 10f, text, Rgb.of(colours[CTool.Note]!!), author) }
                    }.onFailure { model.say(model.errorText(it)) }
                    selected = null
                }
            },
        )
    }

    textBoxAt?.let { (page, at) ->
        TextBoxDialog(colours[CTool.TextBox]!!, onDismiss = { textBoxAt = null }) { text, size ->
            textBoxAt = null
            val pageW = session.state.value.pageSizes.getOrNull(page)?.width ?: 600f
            val rtl = com.aamus.powerpdf.engine.StampText.isRtl(text)
            val room = maxOf(60f, if (rtl) at.x - 18f else pageW - at.x - 18f)
            scope.launch {
                runCatching { session.change("text box") { it.addTextBox(page, at.x, at.y, text, Rgb.of(colours[CTool.TextBox]!!), size, room, author) } }
                    .onFailure { model.say(model.errorText(it)) }
            }
        }
    }
    stampAt?.let { (page, at) ->
        StampSheet(onDismiss = { stampAt = null }) { title, withDate, colour ->
            stampAt = null
            colours[CTool.Stamp] = colour
            val date = if (withDate) java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.Builder().setLocale(java.util.Locale.getDefault()).setUnicodeLocaleKeyword("nu", "latn").build()).format(java.util.Date()) else null
            val sub = listOfNotNull(author.takeIf { withDate }, date).joinToString(" · ").ifBlank { null }
            scope.launch {
                runCatching { session.change("stamp") { it.addStamp(page, at.x, at.y, title, sub, Rgb.of(colour), author) } }
                    .onFailure { model.say(model.errorText(it)) }
            }
        }
    }

    return remember(gestures, overlay, chrome) {
        ModeTool(gestures, overlay, chrome, pending = { strokes.isNotEmpty() }, finish = {
            commitStrokes()
            onExit()
        })
    }
}

/** Text for a text box, and its size. */
@Composable
private fun TextBoxDialog(colour: Int, onDismiss: () -> Unit, onPlace: (String, Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableFloatStateOf(12f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_box)) },
        text = {
            Column {
                Field(
                    text, { text = it }, stringResource(R.string.text_box),
                    placeholder = stringResource(R.string.type_here),
                    singleLine = false, minLines = 3,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Default,
                )
                Spacer(Modifier.height(10.dp))
                com.aamus.powerpdf.ui.Segmented(listOf(10f to "10", 12f to "12", 14f to "14", 18f to "18", 24f to "24"), size, { size = it })
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.place), { onPlace(text.trim(), size) }, enabled = text.isNotBlank()) },
        dismissButton = { DialogButton(stringResource(R.string.cancel), onDismiss) },
        containerColor = Power.colors.surface,
    )
}

/** Ready stamps in both languages, or one's own words; the date under it if wanted. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StampSheet(onDismiss: () -> Unit, onPick: (String, Boolean, Int) -> Unit) {
    val c = Power.colors
    val green = 0xFF2D9D78.toInt()
    val red = 0xFFE34850.toInt()
    val blue = 0xFF1473E6.toInt()
    val violet = 0xFF7E57E6.toInt()
    val orange = 0xFFE68619.toInt()
    val presets = listOf(
        "Approved" to green, "معتمد" to green,
        "Not approved" to red, "غير معتمد" to red,
        "Draft" to blue, "مسودة" to blue,
        "Final" to green, "نهائي" to green,
        "Confidential" to red, "سري" to red,
        "Received" to blue, "تم الاستلام" to blue,
        "Reviewed" to violet, "تمت المراجعة" to violet,
        "For comment" to orange, "للتعليق" to orange,
    )
    var withDate by remember { mutableStateOf(true) }
    var custom by remember { mutableStateOf("") }
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        com.aamus.powerpdf.ui.SheetTitle(stringResource(R.string.stamp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            presets.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (word, colour) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(2.dp, Color(colour), RoundedCornerShape(10.dp))
                                .clickable { onPick(word, withDate, colour) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(word, color = Color(colour), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(custom, { custom = it }, stringResource(R.string.stamp_custom), Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                DialogButton(stringResource(R.string.place), { onPick(custom.trim(), withDate, blue) }, enabled = custom.isNotBlank())
            }
            com.aamus.powerpdf.ui.SwitchRow(stringResource(R.string.stamp_with_date), withDate, { withDate = it })
        }
    }
}

@Composable
private fun ToolChip(icon: Int, label: String, active: Boolean, dot: Int, onClick: () -> Unit) {
    val c = Power.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) c.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Sym(icon, null, tint = if (active) c.accent else c.ink)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(4.dp, 2.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Color(dot))
                    .border(1.dp, c.surface, CircleShape)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) c.accent else c.muted, maxLines = 1)
    }
}

/** Colours (and pen thickness) for the active tool. */
@Composable
fun StyleRow(colour: Int, onColour: (Int) -> Unit, width: Float?, onWidth: (Float) -> Unit) {
    val c = Power.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            MarkColours.forEach { col -> Swatch(Color(col), selected = col == colour, onClick = { onColour(col) }, size = 26.dp) }
        }
        if (width != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.thickness), style = MaterialTheme.typography.labelMedium, color = c.muted)
                Spacer(Modifier.width(12.dp))
                listOf(1f, 2f, 4f, 8f).forEach { w ->
                    val on = w == width
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (on) c.accentSoft else Color.Transparent)
                            .border(1.dp, if (on) c.accent else c.line, CircleShape)
                            .clickable { onWidth(w) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size((w * 2 + 2).dp).clip(CircleShape).background(Color(colour)))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteDialog(initial: String, editing: Boolean, onDismiss: () -> Unit, onPost: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing) R.string.edit_note else R.string.note)) },
        text = {
            Field(
                text, { text = it }, stringResource(R.string.note),
                placeholder = stringResource(R.string.note_placeholder),
                singleLine = false, minLines = 3,
                imeAction = androidx.compose.ui.text.input.ImeAction.Default,
            )
        },
        confirmButton = { DialogButton(stringResource(if (editing) R.string.save else R.string.post), { onPost(text) }, enabled = text.isNotBlank() || editing) },
        dismissButton = { DialogButton(stringResource(R.string.cancel), onDismiss) },
        containerColor = Power.colors.surface,
    )
}
