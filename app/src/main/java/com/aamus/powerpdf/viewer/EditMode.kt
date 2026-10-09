package com.aamus.powerpdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.OnDebug
import com.aamus.powerpdf.R
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.FontKind
import com.aamus.powerpdf.engine.ParagraphAlign
import com.aamus.powerpdf.engine.ParagraphChange
import com.aamus.powerpdf.engine.ParagraphInfo
import com.aamus.powerpdf.engine.paragraphs
import com.aamus.powerpdf.engine.formatAllText
import com.aamus.powerpdf.engine.replaceParagraph
import com.aamus.powerpdf.engine.PictureInfo
import com.aamus.powerpdf.engine.deletePicture
import com.aamus.powerpdf.engine.movePicture
import com.aamus.powerpdf.engine.pictures
import com.aamus.powerpdf.engine.replacePicture
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.Swatch
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.theme.PlexArabic
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The paragraphs of each page, loaded for the pages on screen and again after every change. */
@Stable
class ParagraphCache(private val session: DocumentSession) {
    private val pages = mutableStateMapOf<Int, List<ParagraphInfo>>()
    private var version by mutableIntStateOf(-1)

    fun on(page: Int): List<ParagraphInfo>? = if (version == session.state.value.version) pages[page] else null

    suspend fun load(visible: IntRange) {
        val v = session.state.value.version
        if (v != version) {
            pages.clear()
            version = v
        }
        for (p in visible) {
            if (pages.containsKey(p)) continue
            val list = runCatching { session.engine { it.paragraphs(p) } }.getOrDefault(emptyList())
            if (session.state.value.version != v) return
            pages[p] = list
        }
    }

    fun at(page: Int, x: Float, y: Float): ParagraphInfo? {
        val list = on(page) ?: return null
        fun hit(p: ParagraphInfo, slack: Float) = p.lines.any { l -> x >= l.x0 - slack && x <= l.x1 + slack && y >= l.y0 - slack && y <= l.y1 + slack }
        return list.firstOrNull { hit(it, 1f) } ?: list.firstOrNull { hit(it, 5f) }
    }
}

/** A paragraph being retyped: its text and the look chosen for it. */
private data class ParaEdit(
    val info: ParagraphInfo,
    val text: String,
    val size: Float,
    val colour: Int,
    val bold: Boolean,
    val italic: Boolean,
    val kind: FontKind,
    val align: ParagraphAlign,
) {
    val changed: Boolean get() = text != info.text || size != info.size || colour != info.colour || bold != info.bold ||
        italic != info.italic || kind != info.kind || align != info.align

    companion object {
        fun of(p: ParagraphInfo) = ParaEdit(p, p.text, p.size, p.colour, p.bold, p.italic, p.kind, p.align)
    }
}

/** Where the column of a paragraph lies on its page (displayed points). */
private fun column(p: ParagraphInfo, pageWidth: Float): Pair<Float, Float> {
    var left = if (p.rtl) p.farEdge else minOf(p.firstStart, p.restStart)
    var right = if (p.rtl) maxOf(p.firstStart, p.restStart) else p.farEdge
    // A one-line paragraph may grow to the margin when retyped longer.
    if (p.lines.size == 1) {
        if (p.rtl) left = minOf(left, 36f) else right = maxOf(right, pageWidth - 36f)
    }
    return left to right
}

@Composable
fun rememberEditTool(session: DocumentSession, viewport: ViewportState, onExit: () -> Unit): ModeTool {
    val model = LocalModel.current
    // Outlives the mode: Done writes the last change while the mode closes.
    val scope = LocalModel.current.appScope
    val density = LocalDensity.current
    val state by session.state.collectAsStateWithLifecycle()
    val paras = remember(session) { ParagraphCache(session) }
    var edit by remember { mutableStateOf<ParaEdit?>(null) }
    var formatAllOpen by remember { mutableStateOf(false) }
    // Pictures (desktop §29): the selected one, and a drag moving or resizing it.
    val pictureCache = remember(session) { mutableStateMapOf<Int, List<PictureInfo>>() }
    var picturesVersion by remember { mutableIntStateOf(-1) }
    var picture by remember { mutableStateOf<Pair<Int, PictureInfo>?>(null) }
    var pictureDrag by remember { mutableStateOf(0) } // 0 none, 1 move, 2 resize
    var pictureFrom by remember { mutableStateOf(Offset.Zero) }
    var pictureBox by remember { mutableStateOf<com.aamus.powerpdf.engine.Box?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickReplacement = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        val sel = picture ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        picture = null
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bmp = com.aamus.powerpdf.ui.tools.decodeUpright(context, uri, 0, 2400) ?: return@runCatching null
                    java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }.getOrNull()
            }
            if (bytes == null) {
                model.say(model.str(R.string.error_read))
                return@launch
            }
            runCatching { session.change("replace picture") { it.replacePicture(sel.first, sel.second.box, bytes) } }
                .onFailure { model.say(model.errorText(it)) }
        }
    }

    LaunchedEffect(session) {
        snapshotFlow { viewport.layout.visiblePages(viewport.contentTop, viewport.contentTop + viewport.viewportHeight) to state.version }
            .collect { (visible, v) ->
                paras.load(visible)
                if (v != picturesVersion) {
                    pictureCache.clear()
                    picturesVersion = v
                }
                for (p in visible) if (!pictureCache.containsKey(p)) {
                    pictureCache[p] = runCatching { session.engine { it.pictures(p) } }.getOrDefault(emptyList())
                }
            }
    }

    fun apply(e: ParaEdit, text: String) {
        val p = e.info
        val point = p.lines.first().let { Offset((it.x0 + it.x1) / 2, (it.y0 + it.y1) / 2) }
        val change = ParagraphChange(
            size = e.size.takeIf { it != p.size },
            colour = e.colour.takeIf { it != p.colour },
            bold = e.bold.takeIf { it != p.bold },
            italic = e.italic.takeIf { it != p.italic },
            kind = e.kind.takeIf { it != p.kind },
            align = e.align.takeIf { it != p.align },
        )
        scope.launch {
            runCatching { session.change("edit text") { it.replaceParagraph(p.page, point.x, point.y, text, change) } }
                .onSuccess { r ->
                    val message = when {
                        text.isBlank() -> R.string.text_deleted
                        r.grew -> R.string.text_grew
                        else -> R.string.text_changed
                    }
                    model.say(model.str(message), action = model.str(R.string.undo), long = r.grew) { scope.launch { session.undo() } }
                }
                .onFailure { model.say(model.errorText(it)) }
        }
    }

    /**
     * Applies the paragraph being edited. Refused while it still holds characters the file has no
     * text for (U+FFFD): retyped, they would be lost. Returns whether the edit is closed.
     */
    fun commit(): Boolean {
        val e = edit ?: return true
        if (e.changed && e.text.contains('�')) {
            model.say(model.str(R.string.edit_type_marks), action = model.str(R.string.discard_change), long = true) { edit = null }
            return false
        }
        edit = null
        if (e.changed) apply(e, e.text.trim())
        return true
    }

    fun delete() {
        val e = edit ?: return
        edit = null
        apply(e, "")
    }

    fun begin(page: Int, x: Float, y: Float) {
        if (session.state.value.rights?.edit == false) {
            model.say(model.str(R.string.error_restricted))
            return
        }
        val found = paras.at(page, x, y)
        val pic = pictureCache[page]?.filter { x in it.box.x0..it.box.x1 && y in it.box.y0..it.box.y1 }?.minByOrNull { it.box.width * it.box.height }
        // Mostly characters without text (a font with no letter map): nothing sensible to retype.
        val unreadable = found != null && found.unreadable * 10 > found.text.count { !it.isWhitespace() } * 3
        when {
            unreadable -> model.say(model.str(R.string.edit_unreadable), long = true)
            found != null -> edit = ParaEdit.of(found)
            pic != null -> picture = page to pic
            paras.on(page)?.isEmpty() == true -> model.say(model.str(R.string.edit_no_text_page))
            else -> model.say(model.str(R.string.edit_no_text_here))
        }
    }

    val gestures = remember(session) {
        object : PageGestures {
            override val doubleTapZooms: Boolean get() = false

            fun handle() = with(density) { 28.dp.toPx() } / viewport.layout.scale

            override fun wantsDrag(page: Int?, point: Offset, view: Offset): Boolean {
                val (p, pic) = picture ?: return false
                if (page != p) return false
                val b = pic.box
                pictureDrag = when {
                    kotlin.math.abs(point.x - b.x1) < handle() && kotlin.math.abs(point.y - b.y1) < handle() -> 2
                    point.x in b.x0..b.x1 && point.y in b.y0..b.y1 -> 1
                    else -> 0
                }
                return pictureDrag != 0
            }

            override fun onDragStart(page: Int, point: Offset, view: Offset) {
                pictureFrom = point
                pictureBox = picture?.second?.box
            }

            override fun onDrag(page: Int, point: Offset, view: Offset) {
                val b = picture?.second?.box ?: return
                val d = point - pictureFrom
                pictureBox = if (pictureDrag == 1) com.aamus.powerpdf.engine.Box(b.x0 + d.x, b.y0 + d.y, b.x1 + d.x, b.y1 + d.y)
                else {
                    // Corner resize keeps the picture's proportions.
                    val w = (b.width + d.x).coerceAtLeast(12f)
                    com.aamus.powerpdf.engine.Box(b.x0, b.y0, b.x0 + w, b.y0 + w * b.height / b.width)
                }
            }

            override fun onDragEnd() {
                val (p, pic) = picture ?: return
                val to = pictureBox ?: return
                pictureBox = null
                pictureDrag = 0
                if (kotlin.math.abs(to.x0 - pic.box.x0) < 0.5f && kotlin.math.abs(to.y0 - pic.box.y0) < 0.5f && kotlin.math.abs(to.width - pic.box.width) < 0.5f) return
                picture = null
                scope.launch {
                    runCatching { session.change("move picture") { it.movePicture(p, pic.box, to) } }
                        .onFailure { model.say(model.errorText(it)) }
                }
            }

            override fun onDragCancel() {
                pictureBox = null
                pictureDrag = 0
            }

            override fun onTap(page: Int?, point: Offset, view: Offset) {
                if (picture != null) {
                    picture = null
                    return
                }
                val e = edit
                if (e != null) {
                    // A tap inside the paragraph being edited stays in it; elsewhere it finishes.
                    val inside = page == e.info.page && e.info.box.let { point.x in it.x0 - 4..it.x1 + 4 && point.y in it.y0 - 4..it.y1 + 4 }
                    if (!inside) commit()
                    return
                }
                if (page != null) begin(page, point.x, point.y)
            }
        }
    }

    OnDebug { c ->
        when (c.cmd) {
            // "page,x,y": start editing the paragraph there.
            "edittap" -> {
                val v = c.value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (v.size == 3) {
                    paras.load(v[0].toInt()..v[0].toInt())
                    begin(v[0].toInt(), v[1], v[2])
                }
            }
            "edittext" -> {
                edit = edit?.copy(text = c.value.replace("\\n", "\n"))
                android.util.Log.i("PowerPDF", "edit text set: ${edit?.text?.take(40)} (editing: ${edit != null})")
            }
            "editsize" -> edit = edit?.let { e -> e.copy(size = c.value.toFloatOrNull() ?: e.size) }
            "editbold" -> edit = edit?.copy(bold = c.value == "1")
            "editalign" -> edit = edit?.copy(align = ParagraphAlign.entries.firstOrNull { it.name.equals(c.value, true) } ?: ParagraphAlign.Start)
            "editkind" -> edit = edit?.copy(kind = FontKind.entries.firstOrNull { it.name.equals(c.value, true) } ?: FontKind.Sans)
            "editdone" -> commit()
            "formatallopen" -> formatAllOpen = c.value != "off"
            // "page": log the page's pictures; "page,move|delete,dx,dy": change its first picture.
            "pics" -> {
                val p = (c.value.substringBefore(',').toIntOrNull() ?: 1) - 1
                val list = session.engine { it.pictures(p) }
                list.forEach { android.util.Log.i("PowerPDF", "picture p${p + 1} ${it.box} ${it.width}x${it.height}") }
                val v = c.value.split(',')
                val first = list.firstOrNull()
                if (v.size >= 2 && first != null) runCatching {
                    session.change("picture") { d ->
                        if (v[1] == "delete") d.deletePicture(p, first.box)
                        else {
                            val dx = v.getOrNull(2)?.toFloatOrNull() ?: 20f
                            val dy = v.getOrNull(3)?.toFloatOrNull() ?: 20f
                            d.movePicture(p, first.box, com.aamus.powerpdf.engine.Box(first.box.x0 + dx, first.box.y0 + dy, first.box.x1 + dx, first.box.y1 + dy))
                        }
                    }
                }.onFailure { e -> android.util.Log.w("PowerPDF", "pics", e) }
            }
            // "kind|scale", e.g. "Serif|1.1": Format all text on every page.
            "formatall" -> {
                val kind = FontKind.entries.firstOrNull { it.name.equals(c.value.substringBefore('|'), true) }
                val scale = c.value.substringAfter('|', "").toFloatOrNull()
                runCatching { session.change("format all") { it.formatAllText((0 until it.pageCount).toList(), com.aamus.powerpdf.engine.FormatAll(kind = kind, scale = scale)) } }
                    .onSuccess { r -> android.util.Log.i("PowerPDF", "format all: ${r.paragraphs} paragraphs, ${r.mixed} mixed") }
                    .onFailure { e -> android.util.Log.w("PowerPDF", "format all", e) }
            }
            "editdelete" -> delete()
            "paras" -> {
                val p = c.value.toIntOrNull() ?: 0
                val list = session.engine { it.paragraphs(p) }
                list.forEach { android.util.Log.i("PowerPDF", "para p$p ${it.box} rtl=${it.rtl} size=${it.size} bold=${it.bold} kind=${it.kind} align=${it.align} mixed=${it.mixed} lines=${it.lines.size} unreadable=${it.unreadable} guessed=${it.guessed}: ${it.text.take(400)}") }
            }
        }
    }

    val overlay: DrawScope.(PageLayout) -> Unit = { layout ->
        val accent = Color(0xFF1473E6)
        val visible = layout.visiblePages(viewport.contentTop, viewport.contentTop + viewport.viewportHeight)
        val e = edit
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        for (page in visible) {
            for (p in paras.on(page) ?: continue) {
                if (e != null && e.info.page == page && e.info.box == p.box) continue
                val r = layout.viewRect(viewport, page, p.box.x0 - 2, p.box.y0 - 2, p.box.x1 + 2, p.box.y1 + 2)
                drawRect(accent.copy(alpha = 0.55f), r.topLeft, r.size, style = Stroke(1.dp.toPx(), pathEffect = dash))
            }
        }
        val green = Color(0xFF2D9D78)
        for (page in visible) for (pic in pictureCache[page] ?: emptyList()) {
            val sel = picture?.let { it.first == page && it.second.box == pic.box } == true
            val b = if (sel) pictureBox ?: pic.box else pic.box
            val r = layout.viewRect(viewport, page, b.x0, b.y0, b.x1, b.y1)
            drawRect(green.copy(alpha = if (sel) 1f else 0.6f), r.topLeft, r.size, style = Stroke(if (sel) 2.dp.toPx() else 1.dp.toPx(), pathEffect = if (sel) null else dash))
            if (sel) {
                drawCircle(Color.White, 10.dp.toPx(), r.bottomRight)
                drawCircle(green, 8.dp.toPx(), r.bottomRight)
            }
        }
        if (e != null) {
            // The old words are hidden while they are retyped (the page is white under text).
            for (l in e.info.lines) {
                val r = layout.viewRect(viewport, e.info.page, l.x0 - 1, l.y0 - 1, l.x1 + 1, l.y1 + 1)
                drawRect(Color.White, r.topLeft, r.size)
            }
        }
    }

    val chrome: @Composable BoxScope.() -> Unit = {
        val c = Power.colors
        Column(Modifier.align(Alignment.TopCenter)) {
            ModeTopBar(stringResource(R.string.tool_edit_text), onDone = {
                if (commit()) onExit()
            }) {
                IconAction(R.drawable.ic_format_size, stringResource(R.string.format_all), { if (commit()) formatAllOpen = true })
                UndoRedo(session, scope)
            }
        }

        if (formatAllOpen) {
            val current = viewport.currentPage()
            FormatAllDialog(current, onDismiss = { formatAllOpen = false }) { change, allPages ->
                formatAllOpen = false
                val pages = if (allPages) (0 until session.state.value.pageCount).toList() else listOf(current)
                scope.launch {
                    model.busy = model.str(R.string.working)
                    runCatching { session.change("format all") { d -> d.formatAllText(pages, change) { p -> model.busy = model.str(R.string.page_progress, p + 1, session.state.value.pageCount) } } }
                        .onSuccess { r ->
                            var message = model.plural(R.plurals.formatted_paragraphs, r.paragraphs, r.paragraphs)
                            if (r.skipped > 0) message += ". " + model.plural(R.plurals.format_all_skipped, r.skipped, r.skipped)
                            model.say(message, action = model.str(R.string.undo), long = true) { scope.launch { session.undo() } }
                        }
                        .onFailure { model.say(model.errorText(it)) }
                    model.busy = null
                }
            }
        }

        picture?.let { (p, pic) ->
            val r = viewport.layout.viewRect(viewport, p, pic.box.x0, pic.box.y0, pic.box.x1, pic.box.y1)
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
                    IconAction(R.drawable.ic_photo_library, stringResource(R.string.replace_picture), {
                        pickReplacement.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    IconAction(R.drawable.ic_delete, stringResource(R.string.delete_picture), {
                        picture = null
                        scope.launch {
                            runCatching { session.change("delete picture") { it.deletePicture(p, pic.box) } }
                                .onSuccess { hole -> model.say(model.str(if (hole) R.string.picture_deleted_hole else R.string.picture_deleted), action = model.str(R.string.undo)) { scope.launch { session.undo() } } }
                                .onFailure { model.say(model.errorText(it)) }
                        }
                    }, tint = c.danger)
                    IconAction(R.drawable.ic_close, stringResource(R.string.close), { picture = null })
                }
            }
        }

        edit?.let { e ->
            val focus = remember { FocusRequester() }
            val l = viewport.layout
            val p = e.info
            val pageWidth = session.state.value.pageSizes.getOrNull(p.page)?.width ?: 600f
            val (colLeft, colRight) = column(p, pageWidth)
            val top = p.box.y0
            val at = viewport.toView(l.toContent(p.page, Offset(colLeft, top)))
            val widthPx = (colRight - colLeft) * l.scale
            val fontPx = e.size * l.scale
            val fontSp = with(density) { (fontPx / fontScale / this.density).sp }
            val lineSp = with(density) { (p.pitch * (e.size / p.size) * l.scale / fontScale / this.density).sp }
            val family = when {
                p.rtl -> PlexArabic
                e.kind == FontKind.Serif -> FontFamily.Serif
                e.kind == FontKind.Mono -> FontFamily.Monospace
                else -> FontFamily.SansSerif
            }
            val align = when (e.align) {
                ParagraphAlign.Start -> TextAlign.Start
                ParagraphAlign.End -> TextAlign.End
                ParagraphAlign.Centre -> TextAlign.Center
                ParagraphAlign.Justify -> TextAlign.Justify
            }
            // Kept above the keyboard and the format bar while typing.
            val covered = WindowInsets.ime.getBottom(density) + with(density) { 72.dp.toPx() }
            LaunchedEffect(p.page, p.box, covered) {
                viewport.reveal(p.page, p.box.x0, p.box.y0, p.box.x1, p.box.y1, covered)
            }
            LaunchedEffect(p.page, p.box) { focus.requestFocus() }
            Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                BasicTextField(
                    value = e.text,
                    onValueChange = { edit = e.copy(text = it) },
                    textStyle = TextStyle(
                        fontSize = fontSp,
                        lineHeight = lineSp,
                        color = Color(e.colour),
                        fontFamily = family,
                        fontWeight = if (e.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (e.italic) FontStyle.Italic else FontStyle.Normal,
                        // The paragraph's own direction, whatever the interface's.
                        textDirection = if (p.rtl) TextDirection.Rtl else TextDirection.Ltr,
                        textAlign = align,
                    ),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier
                        // The frame sits just outside the column, so the first letter stays clear of it.
                        .absoluteOffset { IntOffset((at.x - 5.dp.toPx()).roundToInt(), (at.y - 4.dp.toPx()).roundToInt()) }
                        .width(with(density) { widthPx.toDp() } + 10.dp)
                        .background(Color.White, RoundedCornerShape(3.dp))
                        .border(1.5.dp, c.accent, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 4.dp)
                        .focusRequester(focus),
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).imePadding()) {
            val e = edit
            when {
                e == null -> ModeHint(stringResource(R.string.edit_text_hint))
                e.text.contains('�') -> ModeHint(stringResource(R.string.edit_unreadable_some), warning = true)
                e.info.guessed > 0 -> ModeHint(stringResource(R.string.edit_guessed), warning = true)
                e.info.mixed -> ModeHint(stringResource(R.string.edit_mixed))
            }
            ModeBottom {
                if (e == null) {
                    Text(
                        stringResource(R.string.edit_matched_font),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconAction(R.drawable.ic_zoom_out, stringResource(R.string.font_size), { edit = e.copy(size = (e.size - 1).coerceAtLeast(4f)) })
                        Text(stringResource(R.string.font_size_value, e.size.roundToInt()), style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.widthIn(min = 44.dp), textAlign = TextAlign.Center)
                        IconAction(R.drawable.ic_zoom_in, stringResource(R.string.font_size), { edit = e.copy(size = (e.size + 1).coerceAtMost(144f)) })
                        IconAction(R.drawable.ic_format_bold, stringResource(R.string.bold), { edit = e.copy(bold = !e.bold) }, selected = e.bold)
                        IconAction(R.drawable.ic_format_italic, stringResource(R.string.italic), { edit = e.copy(italic = !e.italic) }, selected = e.italic)
                        val kinds = listOf(FontKind.Sans to R.string.typeface_sans, FontKind.Serif to R.string.typeface_serif, FontKind.Mono to R.string.typeface_mono)
                        val kindLabel = stringResource(kinds.first { it.first == e.kind }.second)
                        Text(
                            kindLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = c.ink,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { edit = e.copy(kind = kinds[(kinds.indexOfFirst { it.first == e.kind } + 1) % kinds.size].first) }
                                .border(1.dp, c.line, CircleShape)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        val alignIcon = when (e.align) {
                            ParagraphAlign.Start -> R.drawable.ic_format_align_left
                            ParagraphAlign.Centre -> R.drawable.ic_format_align_center
                            ParagraphAlign.End -> R.drawable.ic_format_align_right
                            ParagraphAlign.Justify -> R.drawable.ic_format_align_justify
                        }
                        IconAction(alignIcon, stringResource(R.string.alignment), {
                            edit = e.copy(align = ParagraphAlign.entries[(e.align.ordinal + 1) % ParagraphAlign.entries.size])
                        })
                        Spacer(Modifier.width(4.dp))
                        (listOf(e.info.colour) + listOf(0xFF1F1F1F.toInt(), 0xFF1473E6.toInt(), 0xFFE34850.toInt(), 0xFF2D9D78.toInt()).filter { it != e.info.colour }).forEach { col ->
                            Swatch(Color(col), selected = e.colour == col, onClick = { edit = e.copy(colour = col) }, size = 24.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                        IconAction(R.drawable.ic_delete, stringResource(R.string.delete_text), { delete() })
                        IconAction(R.drawable.ic_close, stringResource(R.string.cancel), { edit = null })
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(c.accent)
                                .clickable { commit() }
                                .heightIn(min = 40.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Sym(R.drawable.ic_check, null, tint = Color.White, size = 18.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.done), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    return remember(gestures, overlay, chrome) {
        ModeTool(gestures, overlay, chrome, pending = { edit?.changed == true }, finish = { if (commit()) onExit() })
    }
}
