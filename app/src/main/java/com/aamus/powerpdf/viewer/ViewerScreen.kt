package com.aamus.powerpdf.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aamus.powerpdf.ui.SideBars
import com.aamus.powerpdf.ui.edgeFade
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import com.aamus.powerpdf.Screen
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.data.SaveMode
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.doc.Sessions
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalLauncher
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.ToolCatalog
import com.aamus.powerpdf.ui.theme.Power
import com.aamus.powerpdf.engine.addInk
import com.aamus.powerpdf.engine.deleteMark
import com.aamus.powerpdf.engine.marks
import com.aamus.powerpdf.engine.addPageNumbers
import com.aamus.powerpdf.engine.addText
import com.aamus.powerpdf.engine.addNote
import com.aamus.powerpdf.engine.addWatermark
import com.aamus.powerpdf.engine.addShape
import com.aamus.powerpdf.engine.addStamp
import com.aamus.powerpdf.engine.addTextBox
import com.aamus.powerpdf.engine.hasFormFields
import com.aamus.powerpdf.engine.XfaKind
import com.aamus.powerpdf.engine.xfaKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the viewer is doing: reading, or one of the tools that work on the open document. */
enum class Mode { Read, Comment, Sign, AddText, EditText, Erase, Forms, Redact, Organize, Watermark, PageNumbers }

@Composable
fun ViewerScreen(screen: Screen.Viewer) {
    val model = LocalModel.current
    val session = remember(screen.sessionId) { Sessions.get(screen.sessionId) }
    if (session == null) {
        LaunchedEffect(Unit) { model.pop() }
        return
    }
    Viewer(session, screen.startTool, screen.edit)
}

@Composable
private fun Viewer(session: DocumentSession, startTool: ToolId?, startEditing: Boolean) {
    val model = LocalModel.current
    val context = LocalContext.current
    val launch = LocalLauncher.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val c = Power.colors

    val state by session.state.collectAsStateWithLifecycle()
    val name by session.name.collectAsStateWithLifecycle()
    val prefs by model.settings.flow.collectAsStateWithLifecycle()

    val startPage = remember { session.recentId?.let { model.recents.get(it)?.lastPage } ?: 0 }
    val viewport = remember(session) { ViewportState(startPage) }
    val renderer = remember(session) { PageRenderer(session, scope) }
    val vm = remember(session) { ViewerModel(session, scope) }
    DisposableEffect(session) { onDispose { renderer.dispose() } }

    var mode by remember { mutableStateOf(Mode.Read) }
    // Viewer first (decisions §42): a PDF opens in a clean reader with one Edit button; the
    // editing tools show only after it is tapped, and Back (or ×) puts them away again.
    var editing by remember { mutableStateOf(session.editing) }
    LaunchedEffect(editing) { session.editing = editing }
    var chrome by remember { mutableStateOf(true) }
    var night by remember { mutableStateOf(prefs.nightPages) }
    var menuOpen by remember { mutableStateOf(false) }
    var toolsSheet by remember { mutableStateOf(false) }
    var thumbsOpen by remember { mutableStateOf(false) }
    var goToOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var commentsOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var cropOpen by remember { mutableStateOf(false) }
    // Links on the pages and the "Open this link?" question (decisions §51).
    val links = remember(session) { LinksCache(session) }
    val follower = remember(session) { LinkFollower(model, context) }
    /** The link just tapped, outlined for a moment. */
    var linkFlash by remember { mutableStateOf<Pair<Int, com.aamus.powerpdf.engine.Box>?>(null) }
    LaunchedEffect(linkFlash) {
        if (linkFlash != null) {
            delay(450)
            linkFlash = null
        }
    }
    var leaving by remember { mutableStateOf(false) }
    // Back in a tool with unfinished work: apply it, or throw it away?
    var dropping by remember { mutableStateOf(false) }

    LaunchedEffect(state.pageSizes) { viewport.updatePages(state.pageSizes) }
    // A form to fill in is offered once when the document opens (Acrobat's message bar).
    LaunchedEffect(session.id) {
        if (session.formOffered) return@LaunchedEffect
        session.formOffered = true
        // A dynamic XFA form shows only a "please wait" page here: say why (desktop §80).
        if (runCatching { session.engine { it.xfaKind() } }.getOrNull() == XfaKind.Dynamic) {
            model.say(model.str(R.string.xfa_dynamic), long = true)
        } else if (startTool == null && runCatching { session.engine { it.hasFormFields() } }.getOrDefault(false)) {
            model.say(model.str(R.string.has_form), action = model.str(R.string.fill_in), long = true) { mode = Mode.Forms }
        }
    }
    LaunchedEffect(startTool) {
        // Only the first time: coming back from a tool screen recomposes the viewer.
        if (session.startToolUsed) return@LaunchedEffect
        session.startToolUsed = true
        // A tool picked on Home, or "Open with" › Edit: the editing tools stay out when the tool is done.
        if (startTool != null || startEditing) editing = true
        when (startTool) {
            ToolId.Comment -> mode = Mode.Comment
            ToolId.Sign -> mode = Mode.Sign
            ToolId.AddText -> mode = Mode.AddText
            ToolId.EditText -> mode = Mode.EditText
            ToolId.Crop -> cropOpen = true
            ToolId.Erase -> mode = Mode.Erase
            ToolId.Forms -> mode = Mode.Forms
            ToolId.Redact -> mode = Mode.Redact
            ToolId.Organize -> mode = Mode.Organize
            ToolId.Watermark -> mode = Mode.Watermark
            ToolId.PageNumbers -> mode = Mode.PageNumbers
            else -> {}
        }
    }
    // The page pill shows while scrolling, and fades 1.5 s after (unless the bars are up).
    var pillVisible by remember { mutableStateOf(true) }
    LaunchedEffect(viewport, chrome) {
        snapshotFlow { viewport.scrollY }.collectLatest {
            pillVisible = true
            if (!chrome) {
                delay(1500)
                pillVisible = false
            }
        }
    }
    // Remember where the person was.
    DisposableEffect(session) {
        onDispose { model.rememberPage(session, viewport.currentPage()) }
    }
    // Keep the screen on while reading, if asked.
    val hostView = LocalView.current
    DisposableEffect(prefs.keepScreenOn) {
        hostView.keepScreenOn = prefs.keepScreenOn
        onDispose { hostView.keepScreenOn = false }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(statusTop, navBottom, density) {
        with(density) {
            viewport.topInset = (statusTop + 56.dp).toPx()
            viewport.bottomInset = (navBottom + 76.dp).toPx()
        }
    }

    fun leave() {
        // Text still being typed in a form field counts as a change: committed first, so leaving asks to save.
        if (session.typing.has()) scope.launch {
            session.typing.commit()
            if (session.state.value.dirty) leaving = true else model.pop()
        } else if (state.dirty) leaving = true else model.pop()
    }
    val tool = rememberModeTool(mode, session, viewport, vm, onExit = { mode = Mode.Read })
    BackHandler(enabled = true) {
        when {
            vm.selection != null -> vm.clearSelection()
            vm.searchOpen -> vm.closeSearch()
            mode != Mode.Read -> if (tool?.pending?.invoke() == true) dropping = true else mode = Mode.Read
            editing -> editing = false
            else -> leave()
        }
    }

    // Read through derivedStateOf: the screen recomposes when the page changes, not on every scroll frame.
    val pageNow by remember(viewport) { derivedStateOf { if (viewport.pages.isNotEmpty()) viewport.currentPage().coerceIn(0, viewport.pages.size - 1) else 0 } }

    // The links of the pages on screen are read ahead, so a tap on one answers at once.
    LaunchedEffect(viewport, state.version) {
        snapshotFlow { viewport.layout.visiblePages(viewport.contentTop, viewport.contentTop + viewport.viewportHeight) }
            .collectLatest { range -> for (p in range) links.of(p) }
    }

    /** Follows a link on [page]: to a page of this document at once; a web or email link asks first. */
    fun followLink(page: Int, link: com.aamus.powerpdf.engine.PageLink) {
        linkFlash = page to link.box
        val target = link.page
        if (target != null) {
            android.util.Log.i("PowerPDF", "link: page ${page + 1} → page ${target + 1}" + (link.point?.let { " at ${it.x},${it.y}" } ?: ""))
            viewport.goTo(target, link.point?.let { Offset(it.x, it.y) })
        } else {
            link.uri?.let { follower.follow(it) }
        }
    }

    // --- gestures for reading (tools bring their own) --------------------------------------------
    val handleRadius = with(density) { 28.dp.toPx() }
    /** How far off a link a fingertip may land and still follow it. */
    val linkSlop = with(density) { 8.dp.toPx() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var draggingHandle by remember { mutableStateOf<Boolean?>(null) }
    val readGestures = remember(vm, viewport) {
        object : PageGestures {
            override fun wantsDrag(page: Int?, point: Offset, view: Offset): Boolean {
                val s = vm.selection ?: return false
                val l = viewport.layout
                val start = viewport.toView(l.toContent(s.page, Offset(s.sel.startHandle.first, s.sel.startHandle.second)))
                val end = viewport.toView(l.toContent(s.page, Offset(s.sel.endHandle.first, s.sel.endHandle.second)))
                // The knobs hang below the line ends, pointing outward.
                val k = with(density) { HANDLE_KNOB.toPx() }
                val startKnob = start + Offset(if (s.sel.rtlStart) k else -k, k)
                val endKnob = end + Offset(if (s.sel.rtlEnd) -k else k, k)
                val ds = (view - startKnob).getDistance()
                val de = (view - endKnob).getDistance()
                draggingHandle = when {
                    ds < handleRadius && ds <= de -> true
                    de < handleRadius -> false
                    else -> null
                }
                return draggingHandle != null
            }

            override fun onDrag(page: Int, point: Offset, view: Offset) {
                val s = vm.selection ?: return
                val which = draggingHandle ?: return
                // The finger is below the knob; aim at the line above it.
                val l = viewport.layout
                val r = l.pageRect(s.page)
                val c2 = viewport.toContent(view - Offset(0f, handleRadius * 1.2f))
                vm.moveSelectionEnd(which, Offset((c2.x - r.left) / l.scale, (c2.y - r.top) / l.scale))
            }

            override fun onDragEnd() {
                draggingHandle = null
            }

            override fun onTap(page: Int?, point: Offset, view: Offset) {
                focusManager.clearFocus()
                when {
                    vm.selection != null -> vm.clearSelection()
                    page == null -> chrome = !chrome
                    // A tap on a link follows it (as Acrobat's reader); elsewhere it shows or hides the bars.
                    else -> scope.launch {
                        val link = links.at(page, point.x, point.y, linkSlop / viewport.layout.scale)
                        if (link != null) followLink(page, link) else chrome = !chrome
                    }
                }
            }

            override fun onLongPress(page: Int?, point: Offset, view: Offset) {
                if (page == null) return
                scope.launch {
                    if (vm.selectWordAt(page, point)) {
                        hostView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                }
            }

            override fun onLongPressDrag(page: Int?, point: Offset, view: Offset) {
                val s = vm.selection ?: return
                val l = viewport.layout
                val r = l.pageRect(s.page)
                val cp = viewport.toContent(view)
                vm.extendTo(Offset((cp.x - r.left) / l.scale, (cp.y - r.top) / l.scale))
            }
        }
    }

    // A mode's hint and bottom bar, or the editing tools above the × button: snackbars go above them.
    DisposableEffect(mode, editing) {
        model.snackbarLift = when {
            mode == Mode.Organize -> null
            mode != Mode.Read -> 132
            editing -> 160
            else -> null
        }
        onDispose { model.snackbarLift = null }
    }

    // Debug builds: commands from the PC for testing and screenshots (docs/testing.md).
    val gesturesNow by androidx.compose.runtime.rememberUpdatedState(tool?.gestures ?: readGestures)
    LaunchedEffect(session) {
        com.aamus.powerpdf.DebugBus.events.collect { c ->
            when (c.cmd) {
                // "3": logs page 3's links (page from 1; the current page when empty).
                "links" -> {
                    val p = (c.value.trim().toIntOrNull() ?: (viewport.currentPage() + 1)) - 1
                    val list = links.of(p)
                    android.util.Log.i("PowerPDF", "links p${p + 1}: ${list.size}")
                    list.forEachIndexed { i, l ->
                        val b = l.box
                        val to = l.page?.let { "page ${it + 1}" + (l.point?.let { pt -> " at ${pt.x},${pt.y}" } ?: "") }
                            ?: (l.uri ?: "") + " → " + (com.aamus.powerpdf.engine.LinkRules.safeLink(l.uri ?: "")?.let { s -> "${s.kind} ${s.host}" } ?: "refused")
                        android.util.Log.i("PowerPDF", "link ${i + 1}: box ${b.x0},${b.y0},${b.x1},${b.y1} → $to")
                    }
                }
                // "page,x,y" (page from 1, page points): a finger's tap there, in whatever tool is open.
                "tapat" -> {
                    val v = c.value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (v.size == 3) {
                        val p = v[0].toInt() - 1
                        if (p in 0 until viewport.pages.size) {
                            viewport.reveal(p, v[1], v[2], v[1], v[2])
                            val view = viewport.toView(viewport.layout.toContent(p, Offset(v[1], v[2])))
                            gesturesNow.onTap(p, Offset(v[1], v[2]), view)
                        }
                    }
                }
                "bookmarks" -> bookmarksOpen = c.value != "off"
                "mode" -> mode = Mode.entries.firstOrNull { it.name.equals(c.value, ignoreCase = true) } ?: Mode.Read
                // The Edit button (on) or × (off) (decisions §42).
                "edit" -> editing = c.value != "off"
                "page" -> viewport.scrollToPage((c.value.toIntOrNull() ?: 1) - 1)
                "zoom" -> viewport.setZoom(c.value.toFloatOrNull() ?: 1f, Offset(viewport.viewportWidth / 2, viewport.viewportHeight / 2))
                "night" -> night = c.value != "off"
                "chrome" -> chrome = c.value != "off"
                "search" -> {
                    vm.searchOpen = true
                    vm.search(c.value, viewport.currentPage()) { hit -> viewport.reveal(hit.page, hit.box.x0, hit.box.y0, hit.box.x1, hit.box.y1) }
                }
                "select" -> {
                    // "page,x,y" in page points: selects the word there, as a long press does.
                    val p = c.value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (p.size == 3) vm.selectWordAt(p[0].toInt() - 1, Offset(p[1], p[2]))
                }
                "selectall" -> vm.selectAll(viewport.currentPage())
                "sheet" -> when (c.value) {
                    "tools" -> toolsSheet = true
                    "pages" -> thumbsOpen = true
                    "info" -> infoOpen = true
                    "goto" -> goToOpen = true
                    "menu" -> menuOpen = true
                    "bookmarks" -> bookmarksOpen = true
                    else -> {
                        toolsSheet = false
                        thumbsOpen = false
                        bookmarksOpen = false
                        infoOpen = false
                        goToOpen = false
                        menuOpen = false
                    }
                }
                "leave" -> leave()
                "undo" -> session.undo()
                "save" -> model.save(session)
                "closesearch" -> vm.closeSearch()
                "note" -> runCatching {
                    val p = c.value.split(',', limit = 4)
                    session.change("note") { it.addNote(p[0].toInt() - 1, p[1].toFloat(), p[2].toFloat(), p[3], com.aamus.powerpdf.engine.Rgb.of(0xFFFFD600.toInt()), authorName(model, prefs.author)) }
                }.onFailure { e -> android.util.Log.w("PowerPDF", "note", e) }
                // "page,x,y;x,y;…" (page from 1): a pen stroke through those points, as the pen stores it.
                "ink" -> runCatching {
                    val page = c.value.substringBefore(',').trim().toInt() - 1
                    val pts = c.value.substringAfter(',').split(';').map { p -> p.split(',').let { it[0].trim().toFloat() to it[1].trim().toFloat() } }
                    session.change("pen") { it.addInk(page, listOf(pts), com.aamus.powerpdf.engine.Rgb.of(0xFFD32F2F.toInt()), 3f, 1f, authorName(model, prefs.author)) }
                }.onFailure { e -> android.util.Log.w("PowerPDF", "ink", e) }
                // "page" logs the page's comments (with reply links); "page,n" deletes the n-th listed one.
                "marks" -> runCatching {
                    val page = c.value.substringBefore(',').trim().toInt() - 1
                    val list = session.engine { it.marks(page) }
                    list.forEachIndexed { n, m -> android.util.Log.i("PowerPDF", "mark p${page + 1} #$n ${m.kind} id=${m.id} replyTo=${m.replyTo} state=${m.state} text=${m.text.take(40)}") }
                    c.value.substringAfter(',', "").trim().toIntOrNull()?.let { n -> list.getOrNull(n)?.let { m -> session.change("delete mark") { it.deleteMark(page, m.index, m.box) } } }
                }.onFailure { e -> android.util.Log.w("PowerPDF", "marks", e) }
                "streams" -> android.util.Log.i("PowerPDF", "streams p${c.value}: " + session.engine { it.streamReport((c.value.toIntOrNull() ?: 1) - 1) })
                "text" -> {
                    // Logs the current page's lines as extracted (checks Arabic order).
                    val lines = session.engine { it.lines(viewport.currentPage()) }
                    lines.forEachIndexed { i, l -> android.util.Log.i("PowerPDF", "line $i: ${l.text}") }
                }
                "markup" -> vm.selection?.let { sel ->
                    val kind = MarkupKind.entries.firstOrNull { it.name.equals(c.value, ignoreCase = true) } ?: MarkupKind.Highlight
                    runCatching { addTextMarkup(session, model, sel, kind, prefs.author) }.onFailure { e -> android.util.Log.w("PowerPDF", "markup", e) }
                    vm.clearSelection()
                }
                "comments" -> commentsOpen = c.value != "off"
                "cropdialog" -> cropOpen = c.value != "off"
                // "page,x,y,text" (page from 1): a text box / a stamp with the date.
                "textbox", "stamp" -> runCatching {
                    val v = c.value.split(',', limit = 4)
                    val page = v[0].trim().toInt() - 1
                    val x = v[1].trim().toFloat()
                    val y = v[2].trim().toFloat()
                    session.change(c.cmd) {
                        if (c.cmd == "textbox") it.addTextBox(page, x, y, v[3], com.aamus.powerpdf.engine.Rgb.of(0xFF1F1F1F.toInt()), 12f, 300f, prefs.author)
                        else it.addStamp(page, x, y, v[3], "30 Sep 2026", com.aamus.powerpdf.engine.Rgb.of(0xFF2D9D78.toInt()), prefs.author)
                    }
                }.onFailure { e -> android.util.Log.w("PowerPDF", c.cmd, e) }
                // "page,Rect|Ellipse|Line|Arrow,x0,y0,x1,y1": a shape as the Comment tool draws it.
                "shape" -> runCatching {
                    val v = c.value.split(',')
                    val kind = com.aamus.powerpdf.engine.MarkKind.valueOf(v[1])
                    val n = v.drop(2).map { it.trim().toFloat() }
                    session.change("shape") { it.addShape(v[0].trim().toInt(), kind, n[0], n[1], n[2], n[3], com.aamus.powerpdf.engine.Rgb.of(0xFFE34850.toInt()), 2f, prefs.author) }
                }.onFailure { e -> android.util.Log.w("PowerPDF", "shape", e) }
                "watermark" -> runCatching {
                    session.change("watermark") {
                        it.addWatermark((0 until it.pageCount).toList(), c.value, com.aamus.powerpdf.engine.TextStyle(60f, com.aamus.powerpdf.engine.Rgb.of(0xFFE34850.toInt()), true, 0.3f), 45f, com.aamus.powerpdf.engine.VPos.Middle, com.aamus.powerpdf.engine.HAlign.Center)
                    }
                }.onSuccess { android.util.Log.i("PowerPDF", "watermark: done, version ${session.state.value.version}") }.onFailure { e -> android.util.Log.w("PowerPDF", "watermark", e) }
                "numbers" -> runCatching {
                    // value: template with {n} and {N}; Arabic-Indic digits when it contains Arabic.
                    session.change("numbers") {
                        it.addPageNumbers((0 until it.pageCount).toList(), c.value, 1, com.aamus.powerpdf.engine.TextStyle(11f, com.aamus.powerpdf.engine.Rgb.of(0xFF1F1F1F.toInt())), com.aamus.powerpdf.engine.VPos.Bottom, com.aamus.powerpdf.engine.HAlign.Center, 28f, com.aamus.powerpdf.engine.StampText.isRtl(c.value))
                    }
                }.onSuccess { android.util.Log.i("PowerPDF", "numbers: done, version ${session.state.value.version}") }.onFailure { e -> android.util.Log.w("PowerPDF", "numbers", e) }
                "addtext" -> runCatching {
                    // value: "page,x,y,text" (points; x is the start edge).
                    val parts = c.value.split(',', limit = 4)
                    val size = session.state.value.pageSizes[parts[0].toInt() - 1]
                    val x = parts[1].toFloat()
                    val rtl = com.aamus.powerpdf.engine.StampText.isRtl(parts[3])
                    session.change("add text") {
                        it.addText(parts[0].toInt() - 1, x, parts[2].toFloat(), if (rtl) x - 18f else size.width - x - 18f, parts[3].replace("|", "\n"), com.aamus.powerpdf.engine.TextStyle(16f, com.aamus.powerpdf.engine.Rgb.of(0xFF1473E6.toInt())))
                    }
                }.onSuccess { android.util.Log.i("PowerPDF", "addtext: done, version ${session.state.value.version}") }.onFailure { e -> android.util.Log.w("PowerPDF", "addtext", e) }
            }
        }
    }

    // Any press anywhere on the screen stops a page list that is still gliding: a tap on a bar
    // button (Edit, ×, the tools) is taken by the button, and the glide went on and crossed into
    // the next page just after it (3/26 → 4/26 on the owner's phone; UX review, phone-test issue 2).
    // Seen in the Initial pass and not consumed, so the button still gets its tap.
    Box(
        Modifier
            .fillMaxSize()
            .background(c.pageBackdrop)
            .pointerInput(viewport) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    scope.launch { viewport.stopFling() }
                }
            },
    ) {
        if (mode == Mode.Organize) {
            OrganizeScreen(session, onClose = { mode = Mode.Read }, onOpenPage = { p -> mode = Mode.Read; viewport.scrollToPage(p) })
            return@Box
        }

        PdfViewport(
            state = viewport,
            renderer = renderer,
            version = state.version,
            night = night,
            backdrop = c.pageBackdrop,
            gestures = tool?.gestures ?: readGestures,
        ) { layout ->
            drawHits(vm, viewport, layout, c.highlight, c.highlightCurrent)
            drawSelection(vm, viewport, layout, c.accent)
            linkFlash?.let { (p, b) -> drawLinkFlash(p, b, viewport, layout, c.accent) }
            tool?.overlay?.invoke(this, layout)
        }

        // Selection menu (Copy, Highlight, Underline, Strikethrough, Select all).
        vm.selection?.let { s ->
            SelectionMenu(
                s, viewport,
                onCopy = {
                    if (state.rights?.copy == false) model.say(model.str(R.string.copy_not_allowed))
                    else {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(name, s.sel.text))
                        if (android.os.Build.VERSION.SDK_INT < 33) model.say(model.str(R.string.text_copied))
                    }
                    vm.clearSelection()
                },
                onMarkup = { kind ->
                    scope.launch {
                        runCatching { addTextMarkup(session, model, s, kind, prefs.author) }
                            .onSuccess { model.say(model.str(R.string.mark_added), action = model.str(R.string.undo)) { scope.launch { session.undo() } } }
                            .onFailure { model.say(model.errorText(it)) }
                        vm.clearSelection()
                    }
                },
                onSelectAll = { scope.launch { vm.selectAll(s.page) } },
            )
        }

        // Top bar or search bar.
        AnimatedVisibility(
            visible = (chrome || vm.searchOpen) && tool?.hidesChrome != true,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Column(Modifier.fillMaxWidth().background(c.surface.copy(alpha = 0.97f)).statusBarsPadding().windowInsetsPadding(SideBars.only(WindowInsetsSides.Horizontal))) {
                if (vm.searchOpen) {
                    SearchBar(vm, onClose = { vm.closeSearch() }, onGo = { hit -> viewport.reveal(hit.page, hit.box.x0, hit.box.y0, hit.box.x1, hit.box.y1) }, fromPage = { viewport.currentPage() })
                } else {
                    TopBar(
                        name = name,
                        dirty = state.dirty,
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onBack = { leave() },
                        onUndo = { scope.launch { session.undo() } },
                        onRedo = { scope.launch { session.redo() } },
                        onSearch = { vm.searchOpen = true },
                        onShare = { model.share(session, launch) },
                        onMenu = { menuOpen = true },
                    )
                    Box(Modifier.align(Alignment.End)) {
                        ViewerMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            night = night,
                            canOverwrite = session.canOverwrite && prefs.saveMode == SaveMode.Overwrite,
                            onSave = { scope.launch { model.save(session) } },
                            onSaveAs = { scope.launch { model.save(session, askWhere = true) } },
                            onNight = { night = !night },
                            onPages = { thumbsOpen = true },
                            onBookmarks = { bookmarksOpen = true },
                            onInfo = { infoOpen = true },
                        )
                    }
                }
                HorizontalDivider(color = c.line)
            }
        }

        // Page pill.
        AnimatedVisibility(
            visible = pillVisible && state.pageCount > 0 && tool?.hidesChrome != true,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(SideBars.only(WindowInsetsSides.Horizontal)).padding(top = statusTop + (if (chrome || vm.searchOpen) 64.dp else 12.dp), start = 12.dp),
        ) {
            Text(
                stringResource(R.string.page_of, pageNow + 1, state.pageCount),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xCC2C2C2C))
                    .clickable { goToOpen = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Scrubber on the end edge.
        if (state.pageCount > 2 && tool?.hidesChrome != true) {
            // Above the Edit button, and above the editing tools while they show.
            Scrubber(viewport, visible = pillVisible || chrome, topPad = statusTop + 64.dp, bottomPad = navBottom + (if (editing && tool == null) 160.dp else 84.dp), onTap = { goToOpen = true })
        }

        // The mode's own bars; when reading, the Edit button, or the editing tools above × once
        // it is tapped (a speed dial: the pencil turns into ×; decisions §42).
        if (tool != null) {
            tool.chrome(this)
        } else {
            val bottomUp = chrome && !vm.searchOpen && vm.selection == null
            AnimatedVisibility(
                visible = bottomUp && editing,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                // FloatingTools adds the navigation bar and 12 dp itself: the pill sits 12 dp above the ×.
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(SideBars.only(WindowInsetsSides.Horizontal)).padding(bottom = 16.dp + 56.dp),
            ) {
                FloatingTools(
                    onComment = { mode = Mode.Comment },
                    onSign = { mode = Mode.Sign },
                    onText = { mode = Mode.AddText },
                    onEditText = { mode = Mode.EditText },
                    onAll = { toolsSheet = true },
                )
            }
            AnimatedVisibility(
                visible = bottomUp,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(SideBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).padding(end = 16.dp, bottom = 16.dp),
            ) {
                EditButton(editing = editing, onClick = { editing = !editing })
            }
        }
    }

    if (cropOpen) {
        CropPagesDialog(session, page = viewport.currentPage(), selected = emptySet(), onDismiss = { cropOpen = false })
    }
    if (commentsOpen) {
        CommentsSheet(session, onDismiss = { commentsOpen = false }, onOpen = { e ->
            commentsOpen = false
            viewport.reveal(e.page, e.mark.box.x0, e.mark.box.y0, e.mark.box.x1, e.mark.box.y1)
            vm.pendingMark = e
            mode = Mode.Comment
        })
    }
    if (toolsSheet) {
        AllToolsSheet(
            onDismiss = { toolsSheet = false },
            onTool = { t ->
                toolsSheet = false
                when (t) {
                    ToolId.Comment -> mode = Mode.Comment
                    ToolId.Sign -> mode = Mode.Sign
                    ToolId.AddText -> mode = Mode.AddText
                    ToolId.EditText -> mode = Mode.EditText
                    ToolId.Crop -> cropOpen = true
                    ToolId.Erase -> mode = Mode.Erase
                    ToolId.Forms -> mode = Mode.Forms
                    ToolId.Redact -> mode = Mode.Redact
                    ToolId.Organize -> mode = Mode.Organize
                    ToolId.Watermark -> mode = Mode.Watermark
                    ToolId.PageNumbers -> mode = Mode.PageNumbers
                    ToolId.Merge -> model.push(Screen.Tool(ToolId.Merge, sessionId = session.id))
                    ToolId.Images, ToolId.Scan, ToolId.ScanId, ToolId.ScanBook, ToolId.ImageToText -> model.push(Screen.Tool(t))
                    else -> model.push(Screen.Tool(t, sessionId = session.id))
                }
            },
        )
    }
    if (thumbsOpen) {
        ThumbnailSheet(session, current = pageNow, onDismiss = { thumbsOpen = false }, onPick = { p -> thumbsOpen = false; viewport.scrollToPage(p) }, onOrganize = { thumbsOpen = false; mode = Mode.Organize })
    }
    if (bookmarksOpen) {
        BookmarksSheet(
            session,
            current = pageNow,
            onDismiss = { bookmarksOpen = false },
            onPage = { e ->
                bookmarksOpen = false
                e.page?.let { p -> viewport.goTo(p, e.point?.let { Offset(it.x, it.y) }) }
            },
            onAddress = { a ->
                bookmarksOpen = false
                follower.follow(a)
            },
        )
    }
    follower.asking?.let { link -> LinkDialog(link) { open, always -> follower.answer(open, always) } }
    if (goToOpen) {
        GoToPageDialog(state.pageCount, onDismiss = { goToOpen = false }, onGo = { p -> goToOpen = false; viewport.scrollToPage(p) })
    }
    if (infoOpen) {
        InfoDialog(session, onDismiss = { infoOpen = false })
    }
    if (dropping) {
        val c = Power.colors
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dropping = false },
            title = { Text(stringResource(R.string.pending_title)) },
            text = { Text(stringResource(R.string.pending_body)) },
            confirmButton = { com.aamus.powerpdf.ui.DialogButton(stringResource(R.string.apply), { dropping = false; tool?.finish?.invoke() ?: run { mode = Mode.Read } }) },
            dismissButton = { com.aamus.powerpdf.ui.DialogButton(stringResource(R.string.discard_change), { dropping = false; tool?.discard?.invoke(); mode = Mode.Read }, danger = true) },
            containerColor = c.surface,
        )
    }
    if (leaving) {
        UnsavedDialog(
            name = name,
            session = session,
            copy = !(prefs.saveMode == SaveMode.Overwrite && session.canOverwrite),
            onDismiss = { leaving = false },
            onDiscard = { leaving = false; model.pop() },
            onSave = {
                leaving = false
                scope.launch { if (model.save(session)) model.pop() }
            },
        )
    }
}

// --- bars ------------------------------------------------------------------------------------

@Composable
private fun TopBar(
    name: String,
    dirty: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSearch: () -> Unit,
    onShare: () -> Unit,
    onMenu: () -> Unit,
) {
    val c = Power.colors
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(R.drawable.ic_arrow_back, stringResource(R.string.back), onBack)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (dirty) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.unsaved), style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
                }
            }
        }
        // Undo and Redo in the bar while there is something to undo (they were in ⋮; UX review
        // finding 16), as the tool modes and the photo editor have them.
        if (canUndo) IconAction(R.drawable.ic_undo, stringResource(R.string.undo), onUndo)
        if (canRedo) IconAction(R.drawable.ic_redo, stringResource(R.string.redo), onRedo)
        IconAction(R.drawable.ic_search, stringResource(R.string.search), onSearch)
        IconAction(R.drawable.ic_share, stringResource(R.string.share), onShare)
        IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), onMenu)
    }
}

/**
 * The reader's ⋮ (decisions §47; UX review finding 16): Save (as Settings says: a copy, or over
 * the file) and Save as…, then the views that have no other door — Pages (which leads on to
 * Organize), Bookmarks (decisions §51), night pages and the document's details. Undo/Redo moved to the bar; "Save a copy"
 * is what Save does by default; Go to page is the page pill; the comments list is in Comment.
 */
@Composable
private fun ViewerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    night: Boolean,
    canOverwrite: Boolean,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onNight: () -> Unit,
    onPages: () -> Unit,
    onBookmarks: () -> Unit,
    onInfo: () -> Unit,
) {
    val c = Power.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = c.surface, shape = RoundedCornerShape(12.dp)) {
        @Composable
        fun item(icon: Int, text: String, action: () -> Unit) = DropdownMenuItem(
            text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { Sym(icon, null, tint = c.muted) },
            onClick = { onDismiss(); action() },
            modifier = Modifier.widthIn(min = 220.dp),
        )
        item(R.drawable.ic_save, if (canOverwrite) stringResource(R.string.save_over) else stringResource(R.string.save), onSave)
        item(R.drawable.ic_save_as, stringResource(R.string.save_as), onSaveAs)
        HorizontalDivider(color = c.line)
        item(R.drawable.ic_grid_view, stringResource(R.string.thumbnails), onPages)
        item(R.drawable.ic_bookmark, stringResource(R.string.bookmarks), onBookmarks)
        item(if (night) R.drawable.ic_light_mode else R.drawable.ic_dark_mode, stringResource(if (night) R.string.day_mode else R.string.night_mode), onNight)
        item(R.drawable.ic_info, stringResource(R.string.document_info), onInfo)
    }
}

@Composable
private fun SearchBar(vm: ViewerModel, onClose: () -> Unit, onGo: (Hit) -> Unit, fromPage: () -> Int) {
    val c = Power.colors
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf(vm.query) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Search as the person types, after a short pause.
    LaunchedEffect(text) {
        delay(350)
        if (text != vm.query) vm.search(text, fromPage(), onGo)
    }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(R.drawable.ic_arrow_back, stringResource(R.string.close), onClose)
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(CircleShape)
                .background(c.sunken)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text(stringResource(R.string.search_hint), color = c.faint, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(text, fromPage(), onGo) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Spacer(Modifier.width(8.dp))
        val label = when {
            vm.searching && vm.hits.isEmpty() -> null
            vm.query.isBlank() -> ""
            vm.hits.isEmpty() && vm.searchDone -> stringResource(if (vm.noText) R.string.search_none else R.string.search_none)
            else -> stringResource(R.string.search_results, vm.hitIndex + 1, vm.hits.size)
        }
        if (label == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
        else Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1)
        IconAction(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.previous), { vm.step(-1)?.let(onGo) }, enabled = vm.hits.isNotEmpty())
        IconAction(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.next), { vm.step(1)?.let(onGo) }, enabled = vm.hits.isNotEmpty())
    }
    if (vm.searchDone && vm.noText && vm.query.isNotBlank()) {
        Text(
            stringResource(R.string.search_no_text),
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
            modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

/**
 * The reader's one way into editing (Acrobat's pencil at the bottom end, research §2.3): a
 * labelled "Edit" button; while the editing tools show, a round × that puts them away.
 */
@Composable
private fun EditButton(editing: Boolean, onClick: () -> Unit) {
    val c = Power.colors
    // Named on the button itself: uiautomator found the extended FAB without a name (NAF) and
    // TalkBack said only "Button" (UX review finding 2).
    val editLabel = stringResource(R.string.edit)
    val closeLabel = stringResource(R.string.close_tools)
    androidx.compose.animation.AnimatedContent(
        targetState = editing,
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.85f)) },
        label = "edit button",
    ) { open ->
        if (open) {
            androidx.compose.material3.FloatingActionButton(
                onClick = onClick,
                containerColor = c.surface,
                contentColor = c.ink,
                shape = CircleShape,
                modifier = Modifier.border(1.dp, c.line, CircleShape).semantics { contentDescription = closeLabel },
            ) {
                Sym(R.drawable.ic_close, null)
            }
        } else {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = onClick,
                icon = { Sym(R.drawable.ic_edit, null, size = 22.dp) },
                text = { Text(stringResource(R.string.edit), style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                containerColor = c.accent,
                contentColor = c.accentInk,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.semantics { contentDescription = editLabel },
            )
        }
    }
}

/**
 * Acrobat mobile's bottom toolbar, as a floating pill: the four most used tools and "All tools",
 * each with a short name under its icon (UX review finding 14; an M3 Expressive floating
 * toolbar). Wider than the screen (large text, a narrow phone), it scrolls sideways.
 * Edit text took Organize's place (decisions §47): it was three taps deep; Organize is one tap
 * away in Pages (⋮) and All tools, and is a full screen of its own anyway.
 */
@Composable
private fun FloatingTools(onComment: () -> Unit, onSign: () -> Unit, onText: () -> Unit, onEditText: () -> Unit, onAll: () -> Unit) {
    val c = Power.colors
    val shape = RoundedCornerShape(28.dp)
    val scroll = rememberScrollState()
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .edgeFade(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillTool(ToolCatalog.comment.icon, stringResource(R.string.tool_comment), ToolCatalog.comment.tint, onComment)
        PillTool(ToolCatalog.sign.icon, stringResource(R.string.pill_sign), ToolCatalog.sign.tint, onSign)
        PillTool(ToolCatalog.addText.icon, stringResource(R.string.tool_add_text), ToolCatalog.addText.tint, onText)
        PillTool(ToolCatalog.editText.icon, stringResource(R.string.pill_edit_text), ToolCatalog.editText.tint, onEditText)
        Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 32.dp).background(c.line))
        PillTool(R.drawable.ic_apps, stringResource(R.string.all_tools), c.ink, onAll)
    }
}

/** One tool in the pill: its icon above its name, one 56 dp touch target, read as one button. */
@Composable
private fun PillTool(icon: Int, label: String, tint: Color, onClick: () -> Unit) {
    val c = Power.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .widthIn(min = 56.dp)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Sym(icon, null, tint = tint, size = 22.dp)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.ink, maxLines = 1)
    }
}

/** A draggable thumb on the end edge for moving through long documents quickly. */
@Composable
private fun Scrubber(viewport: ViewportState, visible: Boolean, topPad: androidx.compose.ui.unit.Dp, bottomPad: androidx.compose.ui.unit.Dp, onTap: () -> Unit) {
    val c = Power.colors
    var dragging by remember { mutableStateOf(false) }
    AnimatedVisibility(visible || dragging, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        // Clear of the navigation buttons and a cut-out at the side in landscape (the thumb sat on
        // the 3-button bar's ■; UX review finding 4).
        val scrubLabel = stringResource(R.string.scrubber)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(SideBars.only(WindowInsetsSides.Horizontal)).padding(top = topPad, bottom = bottomPad)) {
            val trackPx = with(LocalDensity.current) { (maxHeight - 40.dp).toPx() }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        // Read in the layout phase: scrolling moves the thumb without recomposing.
                        val frac = if (viewport.maxScrollY > 0) viewport.scrollY / viewport.maxScrollY else 0f
                        IntOffset(0, (frac * trackPx).roundToInt())
                    }
                    .padding(end = 4.dp)
                    .size(width = 28.dp, height = 40.dp)
                    .shadow(4.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(14.dp))
                    .clickable(onClick = onTap)
                    .semantics { contentDescription = scrubLabel }
                    .pointerInputScrub(trackPx, viewport, onDragging = { dragging = it }),
                contentAlignment = Alignment.Center,
            ) {
                Sym(R.drawable.ic_swap_vert, null, tint = c.muted, size = 18.dp)
            }
        }
    }
}

private fun Modifier.pointerInputScrub(trackPx: Float, viewport: ViewportState, onDragging: (Boolean) -> Unit): Modifier =
    pointerInput(trackPx) {
        detectVerticalDragGestures(
            onDragStart = { onDragging(true); viewport.settled = false },
            onDragEnd = { onDragging(false); viewport.settled = true },
            onDragCancel = { onDragging(false); viewport.settled = true },
        ) { change, dy ->
            change.consume()
            if (trackPx > 0) viewport.scrollBy(0f, dy / trackPx * viewport.maxScrollY)
        }
    }

// --- overlays --------------------------------------------------------------------------------

private fun DrawScope.drawHits(vm: ViewerModel, viewport: ViewportState, layout: PageLayout, hit: Color, current: Color) {
    if (!vm.searchOpen || vm.hits.isEmpty()) return
    val top = viewport.contentTop
    val visible = layout.visiblePages(top, top + size.height)
    vm.hits.forEachIndexed { i, h ->
        if (h.page !in visible) return@forEachIndexed
        val r = layout.viewRect(viewport, h.page, h.box.x0, h.box.y0, h.box.x1, h.box.y1)
        drawRoundRect(if (i == vm.hitIndex) current else hit, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(3f, 3f))
    }
}

private fun DrawScope.drawSelection(vm: ViewerModel, viewport: ViewportState, layout: PageLayout, accent: Color) {
    val s = vm.selection ?: return
    s.sel.boxes.forEach { b ->
        val r = layout.viewRect(viewport, s.page, b.x0, b.y0, b.x1, b.y1)
        drawRect(accent.copy(alpha = 0.28f), topLeft = r.topLeft, size = r.size)
    }
    // Handles: a stem at each end and a teardrop below it pointing outward, as Android's own.
    val first = s.sel.boxes.firstOrNull() ?: return
    val last = s.sel.boxes.last()
    val r = HANDLE_KNOB.toPx()
    fun handle(x: Float, y: Float, lineHeight: Float, outward: Float) {
        val p = viewport.toView(layout.toContent(s.page, Offset(x, y)))
        drawLine(accent, Offset(p.x, p.y - lineHeight), Offset(p.x, p.y), strokeWidth = 2.dp.toPx())
        drawCircle(accent, r, Offset(p.x + outward * r, p.y + r))
        drawRect(accent, topLeft = Offset(if (outward < 0) p.x - r else p.x, p.y), size = androidx.compose.ui.geometry.Size(r, r))
    }
    handle(s.sel.startHandle.first, s.sel.startHandle.second, (first.y1 - first.y0) * layout.scale, if (s.sel.rtlStart) 1f else -1f)
    handle(s.sel.endHandle.first, s.sel.endHandle.second, (last.y1 - last.y0) * layout.scale, if (s.sel.rtlEnd) -1f else 1f)
}

/** Radius of a selection handle's knob. */
private val HANDLE_KNOB = 9.dp

@Composable
private fun SelectionMenu(s: TextSelection, viewport: ViewportState, onCopy: () -> Unit, onMarkup: (MarkupKind) -> Unit, onSelectAll: () -> Unit) {
    val c = Power.colors
    val density = LocalDensity.current
    val l = viewport.layout
    val first = s.sel.boxes.firstOrNull() ?: return
    val last = s.sel.boxes.last()
    val top = l.viewRect(viewport, s.page, first.x0, first.y0, first.x1, first.y1).top
    val bottom = l.viewRect(viewport, s.page, last.x0, last.y0, last.x1, last.y1).bottom
    var menuH by remember { mutableStateOf(0) }
    val above = top - with(density) { 64.dp.toPx() } > viewport.topInset
    val y = if (above) top - menuH - with(density) { 12.dp.toPx() } else bottom + with(density) { 36.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, y.roundToInt()) }
                .onSizeChanged { menuH = it.height }
                .shadow(6.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MenuChip(stringResource(R.string.copy), onCopy)
            IconAction(R.drawable.ic_ink_highlighter, stringResource(R.string.highlight), { onMarkup(MarkupKind.Highlight) }, tint = Color(0xFFE6A700))
            IconAction(R.drawable.ic_format_underlined, stringResource(R.string.underline), { onMarkup(MarkupKind.Underline) })
            IconAction(R.drawable.ic_format_strikethrough, stringResource(R.string.strikeout), { onMarkup(MarkupKind.StrikeOut) })
            IconAction(R.drawable.ic_select_all, stringResource(R.string.select_all), onSelectAll)
        }
    }
}

@Composable
private fun MenuChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Power.colors.ink, maxLines = 1)
    }
}

// --- dialogs ---------------------------------------------------------------------------------

@Composable
private fun GoToPageDialog(count: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.trim().toIntOrNull()
    val ok = n != null && n in 1..count
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_page)) },
        text = { Field(text, { text = it.filter(Char::isDigit).take(6) }, stringResource(R.string.go_to_page_hint, count), number = true, onDone = { if (ok) onGo(n!! - 1) }) },
        confirmButton = { DialogButton(stringResource(R.string.go), { onGo(n!! - 1) }, enabled = ok) },
        dismissButton = { DialogButton(stringResource(R.string.cancel), onDismiss) },
        containerColor = Power.colors.surface,
    )
}

@Composable
private fun UnsavedDialog(name: String, session: DocumentSession, copy: Boolean, onDismiss: () -> Unit, onDiscard: () -> Unit, onSave: () -> Unit) {
    val c = Power.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unsaved_title, com.aamus.powerpdf.ui.bidi(name))) },
        text = {
            Column {
                Text(
                    when {
                        session.source == null -> stringResource(R.string.unsaved_new_note)
                        copy -> stringResource(R.string.unsaved_copy_note, stringResource(R.string.downloads_place))
                        else -> stringResource(R.string.unsaved_over_note)
                    },
                    color = c.muted,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.unsaved_lost), color = c.muted)
            }
        },
        confirmButton = { DialogButton(stringResource(if (copy || session.source == null) R.string.save_copy else R.string.save), onSave) },
        dismissButton = {
            Row {
                DialogButton(stringResource(R.string.dont_save), onDiscard, danger = true)
                DialogButton(stringResource(R.string.cancel), onDismiss)
            }
        },
        containerColor = c.surface,
    )
}

@Composable
private fun InfoDialog(session: DocumentSession, onDismiss: () -> Unit) {
    val c = Power.colors
    val state by session.state.collectAsStateWithLifecycle()
    val name by session.name.collectAsStateWithLifecycle()
    val arabic = LocalContext.current.resources.configuration.locales[0].language == "ar"
    var title by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) { title = session.engine { it.title() } }
    val first = state.pageSizes.firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.document_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @Composable
                fun row(label: String, value: String) {
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
                row(stringResource(R.string.info_name), name)
                title?.let { row(stringResource(R.string.info_title), it) }
                row(stringResource(R.string.info_pages), state.pageCount.toString())
                if (first != null) row(stringResource(R.string.info_size), pageSizeLabel(first.width, first.height))
                row(stringResource(R.string.info_file_size), Files.formatSize(session.source?.let { Files.size(LocalContext.current, it) }?.takeIf { it > 0 } ?: session.work.length(), arabic))
                row(
                    stringResource(R.string.info_protection),
                    when {
                        session.doc.needsOpenPassword -> stringResource(R.string.info_protected)
                        state.rights?.full == false -> stringResource(R.string.info_restricted)
                        else -> stringResource(R.string.info_none)
                    },
                )
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.close), onDismiss) },
        containerColor = c.surface,
    )
}

/** "A4 · 210 × 297 mm" for known sizes, else millimetres. */
fun pageSizeLabel(wPt: Float, hPt: Float): String {
    val w = (wPt / 72f * 25.4f).roundToInt()
    val h = (hPt / 72f * 25.4f).roundToInt()
    val (a, b) = if (w <= h) w to h else h to w
    val named = when {
        kotlin.math.abs(a - 210) <= 2 && kotlin.math.abs(b - 297) <= 2 -> "A4"
        kotlin.math.abs(a - 148) <= 2 && kotlin.math.abs(b - 210) <= 2 -> "A5"
        kotlin.math.abs(a - 297) <= 2 && kotlin.math.abs(b - 420) <= 2 -> "A3"
        kotlin.math.abs(a - 216) <= 2 && kotlin.math.abs(b - 279) <= 2 -> "Letter"
        kotlin.math.abs(a - 216) <= 2 && kotlin.math.abs(b - 356) <= 2 -> "Legal"
        else -> null
    }
    val mm = "$w × $h mm"
    return if (named != null) "$named · $mm" else mm
}

