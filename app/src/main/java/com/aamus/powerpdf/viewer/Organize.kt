package com.aamus.powerpdf.viewer

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.deletePages
import com.aamus.powerpdf.engine.duplicatePages
import com.aamus.powerpdf.engine.insertBlank
import com.aamus.powerpdf.engine.insertFrom
import com.aamus.powerpdf.engine.movePages
import com.aamus.powerpdf.engine.rotatePages
import com.aamus.powerpdf.engine.writePages
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.SheetTitle
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.ToolCatalog
import com.aamus.powerpdf.ui.ToolRow
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.launch

/** Small page pictures, kept while the document is open (about 24 MB at most). */
class ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, b: Bitmap) {
        cache.put(key, b)
    }
}

private val thumbCaches = HashMap<String, ThumbCache>()
fun thumbsFor(session: DocumentSession): ThumbCache = thumbCaches.getOrPut(session.id) { ThumbCache() }

@Composable
fun PageThumb(session: DocumentSession, page: Int, version: Int, modifier: Modifier = Modifier, widthPx: Int = 260) {
    val c = Power.colors
    val cache = thumbsFor(session)
    val key = "$version:$page:$widthPx"
    // After a change (a new version) the old picture stays until the new one is drawn.
    val image by produceState<ImageBitmap?>(cache.get(key)?.asImageBitmap(), key) {
        cache.get(key)?.let {
            value = it.asImageBitmap()
            return@produceState
        }
        val b = runCatching { session.engine { it.renderWidth(page, widthPx) } }.getOrNull()
        if (b != null) {
            cache.put(key, b)
            value = b.asImageBitmap()
        }
    }
    val size = session.state.value.pageSizes.getOrNull(page)
    val ratio = if (size != null && size.height > 0) size.width / size.height else 0.707f
    Box(
        modifier
            .aspectRatio(ratio)
            .background(Color.White)
            .border(1.dp, c.line),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

/** "2–4, 7" from zero-based page indices. */
fun pageList(pages: Collection<Int>): String {
    val sorted = pages.toSortedSet().map { it + 1 }
    val out = ArrayList<String>()
    var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        out.add(if (j > i) "${sorted[i]}–${sorted[j]}" else "${sorted[i]}")
        i = j + 1
    }
    return out.joinToString(", ")
}

@Composable
fun OrganizeScreen(session: DocumentSession, onClose: () -> Unit, onOpenPage: (Int) -> Unit) {
    val model = LocalModel.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    val name by session.name.collectAsStateWithLifecycle()
    val c = Power.colors
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var insertMenu by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()

    // Drag to move: which page, where the finger is, where it would land.
    var dragging by remember { mutableStateOf<Int?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var dropBefore by remember { mutableStateOf<Int?>(null) }

    val count = state.pageCount
    // After a change the pages have new indices; keep the selection only if it still fits.
    if (selected.any { it >= count }) selected = emptySet()

    fun run(label: String, block: (com.aamus.powerpdf.engine.PdfDoc) -> Unit) {
        scope.launch { runCatching { session.change(label, block) }.onFailure { model.say(model.errorText(it)) } }
    }

    // Insert pages from another PDF: the file picked, then where they go.
    var insertSource by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickPdf = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) insertSource = uri
    }
    var moreMenu by remember { mutableStateOf(false) }
    var moveToOpen by remember { mutableStateOf(false) }
    var cropOpen by remember { mutableStateOf(false) }

    fun insertFrom(uri: android.net.Uri, at: Int) {
        scope.launch {
            val opened = model.openInput(uri) ?: return@launch
            val (other, file) = opened
            try {
                val n = com.aamus.powerpdf.engine.PdfEngine.run { other.pageCount }
                session.change("insert pages") { it.insertFrom(other, at) }
                selected = (at until at + n).toSet()
                model.say(model.plural(R.plurals.inserted_pages, n, n), action = model.str(R.string.undo)) { scope.launch { session.undo() } }
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { com.aamus.powerpdf.engine.PdfEngine.run { other.close() } }
                file.delete()
            }
        }
    }

    fun extract() {
        val list = selected.sorted()
        if (list.isEmpty()) return
        scope.launch {
            try {
                val out = Files.newWorkFile(model.getApplication())
                session.engine { it.writePages(list, out) }
                val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
                val saved = model.saveNewFile(out, model.str(R.string.extract_name, base) + ".pdf")
                out.delete()
                if (saved != null) model.showSaved(com.aamus.powerpdf.ToolId.Organize, listOf(saved.first), saved.second, again = false)
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            }
        }
    }

    com.aamus.powerpdf.OnDebug { cmd ->
        when (cmd.cmd) {
            "orgsel" -> selected = cmd.value.split(',').mapNotNull { it.trim().toIntOrNull()?.minus(1) }.filter { it in 0 until count }.toSet()
            "orgdo" -> when (cmd.value) {
                "rotate" -> run("rotate") { it.rotatePages(selected, 90) }
                "rotatel" -> run("rotate") { it.rotatePages(selected, -90) }
                "delete" -> confirmDelete = true
                "confirm" -> if (confirmDelete) {
                    confirmDelete = false
                    val sel = selected
                    selected = emptySet()
                    run("delete pages") { it.deletePages(sel) }
                }
                "dup" -> {
                    val sel = selected
                    selected = emptySet()
                    run("duplicate") { it.duplicatePages(sel) }
                }
                "extract" -> extract()
                "blank" -> run("insert") { it.insertBlank(count) }
                "move" -> {
                    // The selected pages to the front.
                    val sel = selected.sorted()
                    selected = emptySet()
                    run("move pages") { it.movePages(sel, 0) }
                }
            }
            // "path|at": a PDF's pages inserted before page index `at` (0 = start).
            "insertfile" -> insertFrom(android.net.Uri.fromFile(java.io.File(cmd.value.substringBefore('|'))), cmd.value.substringAfter('|', "$count").toIntOrNull() ?: count)
            "crop" -> cropOpen = true
            // "page" (from 1): the selected pages go before it.
            "moveto" -> {
                val before = (cmd.value.toIntOrNull() ?: 1) - 1
                val sel = selected.sorted()
                selected = emptySet()
                run("move pages") { it.movePages(sel, before.coerceIn(0, count)) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(com.aamus.powerpdf.ui.SideBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
        Column(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_close, stringResource(R.string.close), onClose)
                Text(
                    if (selected.isEmpty()) stringResource(R.string.organize_title) else androidx.compose.ui.res.pluralStringResource(R.plurals.selected_count, selected.size, selected.size),
                    style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1,
                )
                UndoRedo(session, scope)
                IconAction(
                    R.drawable.ic_select_all,
                    stringResource(if (selected.size == count) R.string.select_none else R.string.select_all_pages),
                    { selected = if (selected.size == count) emptySet() else (0 until count).toSet() },
                )
            }
            HorizontalDivider(color = c.line)
        }
        Box(Modifier.weight(1f)) {
            // Long-press a page and drag it: the gesture belongs to the grid, not the page, so it
            // survives the grid scrolling under the finger near its top and bottom edges (a page
            // can travel from 3 to 40), and is read before the grid's own scrolling.
            val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
            val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
            fun pageAt(at: Offset) = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                at.x >= item.offset.x && at.x <= item.offset.x + item.size.width &&
                    at.y >= item.offset.y && at.y <= item.offset.y + item.size.height
            }
            fun aim() {
                val over = pageAt(finger) ?: return
                // The page after this one is to its right, or to its left in Arabic.
                val mid = over.offset.x + over.size.width / 2
                val after = if (rtl) finger.x < mid else finger.x > mid
                dropBefore = if (after) over.index + 1 else over.index
            }
            LaunchedEffect(dragging != null) {
                while (dragging != null) {
                    val h = grid.layoutInfo.viewportSize.height
                    val speed = when {
                        finger.y < edge -> -(edge - finger.y) / edge * 24f
                        finger.y > h - edge -> (finger.y - (h - edge)) / edge * 24f
                        else -> 0f
                    }
                    if (speed != 0f) {
                        grid.scrollBy(speed)
                        aim()
                    }
                    kotlinx.coroutines.delay(16)
                }
            }
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 124.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().pointerInput(count, state.version, rtl) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val start = pageAt(down.position)?.index ?: return@awaitEachGesture
                        // A long press, timed here: the page's own tap handling takes the touch
                        // first, which the standard long-press wait counts as cancelled.
                        val slop = viewConfiguration.touchSlop
                        var cancelled = false
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = e.changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed || (ch.position - down.position).getDistance() > slop) {
                                    cancelled = true
                                    break
                                }
                            }
                        }
                        if (cancelled) return@awaitEachGesture
                        dragging = start
                        finger = down.position
                        aim()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // Ours now: the grid does not scroll and the page is not tapped.
                            change.consume()
                            if (!change.pressed) break
                            finger = change.position
                            aim()
                        }
                        val to = dropBefore
                        dragging = null
                        dropBefore = null
                        if (to != null) {
                            val moving = if (start in selected) selected.sorted() else listOf(start)
                            if (!(moving.size == 1 && (to == start || to == start + 1))) {
                                selected = emptySet()
                                run("move pages") { it.movePages(moving, to) }
                            }
                        }
                    }
                },
            ) {
                // Keyed by place, not by version: after a change each place keeps its picture
                // until the new one is drawn (thumbnails used to flash blank).
                items((0 until count).toList(), key = { "p$it" }) { p ->
                    val on = p in selected
                    val isDragged = dragging == p
                    // Named for screen readers: its number sits in its own button below (NAF before).
                    val pageLabel = stringResource(R.string.page_n, p + 1)
                    Column(
                        Modifier
                            // The page being moved stays faint in its place; a copy follows the finger.
                            .graphicsLayer { alpha = if (isDragged) 0.35f else 1f }
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selected = if (on) selected - p else selected + p }
                            .semantics { contentDescription = pageLabel; this.selected = on }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            if (dropBefore == p && dragging != null && dragging != p) {
                                Box(Modifier.align(Alignment.CenterStart).padding(end = 2.dp).width(3.dp).height(120.dp).background(c.accent))
                            }
                            PageThumb(
                                session, p, state.version,
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (on) Modifier.border(3.dp, c.accent, RoundedCornerShape(2.dp)) else Modifier),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (on) c.accent else Color.White.copy(alpha = 0.9f))
                                    .border(1.5.dp, if (on) c.accent else Color(0xFF9E9E9E), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Sym(R.drawable.ic_check, null, tint = Color.White, size = 16.dp) }
                        }
                        // The page number opens the page: a full-size target with an open icon
                        // (it was a tiny number).
                        Row(
                            Modifier
                                .padding(top = 2.dp)
                                .clip(CircleShape)
                                .clickable(onClickLabel = stringResource(R.string.open)) { onOpenPage(p) }
                                .heightIn(min = 40.dp)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${p + 1}", style = MaterialTheme.typography.labelLarge, color = if (on) c.accent else c.muted)
                            Spacer(Modifier.width(4.dp))
                            Sym(R.drawable.ic_open_in_new, null, tint = c.faint, size = 14.dp)
                        }
                    }
                }
            }
            // The page being moved, under the finger.
            dragging?.let { d ->
                val w = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.toPx() }
                Box(
                    Modifier
                        // Screen coordinates, as the finger's: not mirrored in Arabic.
                        .align(androidx.compose.ui.AbsoluteAlignment.TopLeft)
                        .absoluteOffset { androidx.compose.ui.unit.IntOffset((finger.x - w / 2).toInt(), (finger.y - w * 0.7f).toInt()) }
                        .width(96.dp)
                        .graphicsLayer {
                            shadowElevation = 16f
                            scaleX = 1.05f
                            scaleY = 1.05f
                        },
                ) { PageThumb(session, d, state.version, Modifier.fillMaxWidth()) }
            }
            if (selected.isEmpty()) {
                ModeHint(stringResource(R.string.select_pages_hint), Modifier.align(Alignment.BottomCenter))
            }
        }
        // Actions.
        Column(Modifier.fillMaxWidth().background(c.surface)) {
            HorizontalDivider(color = c.line)
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val any = selected.isNotEmpty()
                BarAction(R.drawable.ic_rotate_left, stringResource(R.string.rotate_left), any) { run("rotate") { it.rotatePages(selected, -90) } }
                BarAction(R.drawable.ic_rotate_right, stringResource(R.string.rotate_right), any) { run("rotate") { it.rotatePages(selected, 90) } }
                BarAction(R.drawable.ic_delete, stringResource(R.string.delete_pages), any) {
                    if (selected.size >= count) model.say(model.str(R.string.keep_one_page)) else confirmDelete = true
                }
                Box(Modifier.weight(1f)) {
                    Row { BarAction(R.drawable.ic_note_add, stringResource(R.string.insert), true) { insertMenu = true } }
                    DropdownMenu(insertMenu, { insertMenu = false }, containerColor = c.surface) {
                        val after = selected.maxOrNull()
                        if (after != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.insert_blank_after, after + 1)) }, onClick = {
                                insertMenu = false
                                run("insert") { it.insertBlank(after + 1) }
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.insert_blank_at_end)) }, onClick = {
                            insertMenu = false
                            run("insert") { it.insertBlank(count) }
                        })
                        HorizontalDivider(color = c.line)
                        DropdownMenuItem(text = { Text(stringResource(R.string.insert_from_pdf)) }, leadingIcon = { Sym(R.drawable.ic_file_open, null, tint = c.muted) }, onClick = {
                            insertMenu = false
                            pickPdf.launch(arrayOf("application/pdf"))
                        })
                    }
                }
                Box(Modifier.weight(1f)) {
                    Row { BarAction(R.drawable.ic_more_vert, stringResource(R.string.more), true) { moreMenu = true } }
                    DropdownMenu(moreMenu, { moreMenu = false }, containerColor = c.surface) {
                        @Composable
                        fun item(icon: Int, label: String, enabled: Boolean, action: () -> Unit) = DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { Sym(icon, null, tint = if (enabled) c.muted else c.faint) },
                            enabled = enabled,
                            onClick = { moreMenu = false; action() },
                        )
                        item(R.drawable.ic_file_open, stringResource(R.string.extract_pages), any) { extract() }
                        item(R.drawable.ic_library_add, stringResource(R.string.duplicate_pages), any) {
                            val sel = selected
                            selected = emptySet()
                            run("duplicate") { it.duplicatePages(sel) }
                        }
                        item(R.drawable.ic_move_item, stringResource(R.string.move_to), any) { moveToOpen = true }
                        item(R.drawable.ic_crop, stringResource(R.string.crop_pages), true) { cropOpen = true }
                    }
                }
            }
        }
    }

    insertSource?.let { uri ->
        InsertWhereDialog(selected, count, onDismiss = { insertSource = null }) { at ->
            insertSource = null
            insertFrom(uri, at)
        }
    }
    if (moveToOpen) {
        MoveToDialog(count, onDismiss = { moveToOpen = false }) { before ->
            moveToOpen = false
            val sel = selected.sorted()
            selected = emptySet()
            run("move pages") { it.movePages(sel, before) }
        }
    }
    if (cropOpen) {
        CropPagesDialog(session, page = selected.minOrNull() ?: 0, selected = selected, onDismiss = { cropOpen = false })
    }
    if (confirmDelete) {
        val list = pageList(selected)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_pages_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_pages_message,
                        if (selected.size == 1) stringResource(R.string.page_single, list) else stringResource(R.string.pages_list, list),
                    ),
                )
            },
            confirmButton = {
                DialogButton(stringResource(R.string.delete), {
                    confirmDelete = false
                    val s = selected
                    selected = emptySet()
                    run("delete pages") { it.deletePages(s) }
                }, danger = true)
            },
            dismissButton = { DialogButton(stringResource(R.string.cancel), { confirmDelete = false }) },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarAction(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Power.colors
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sym(icon, null, tint = if (enabled) c.ink else c.faint)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) c.muted else c.faint, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllToolsSheet(onDismiss: () -> Unit, onTool: (ToolId) -> Unit) {
    val c = Power.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        SheetTitle(stringResource(R.string.all_tools))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            // PDF tools only: the image tools have nothing to do with the open document (they
            // opened empty boards from here; decisions §42).
            ToolCatalog.pdfGroupIndices.forEach { gi ->
                val tools = ToolCatalog.groups[gi].second
                item {
                    Text(
                        stringResource(ToolCatalog.groupTitles[gi]),
                        style = MaterialTheme.typography.labelLarge,
                        color = c.muted,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(tools.size) { i -> ToolRow(tools[i]) { onTool(tools[i].id) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThumbnailSheet(session: DocumentSession, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit, onOrganize: () -> Unit) {
    val c = Power.colors
    val state by session.state.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = current)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        SheetTitle(stringResource(R.string.thumbnails)) {
            IconAction(R.drawable.ic_grid_view, stringResource(R.string.tool_organize), onOrganize, tint = ToolCatalog.organize.tint)
        }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items((0 until state.pageCount).toList(), key = { "t$it-${state.version}" }) { p ->
                val on = p == current
                val pageLabel = stringResource(R.string.page_n, p + 1)
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(p) }.semantics { contentDescription = pageLabel }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PageThumb(session, p, state.version, Modifier.fillMaxWidth().then(if (on) Modifier.border(3.dp, c.accent) else Modifier), widthPx = 220)
                    Spacer(Modifier.height(4.dp))
                    Text("${p + 1}", style = MaterialTheme.typography.labelMedium, color = if (on) c.accent else c.muted)
                }
            }
        }
    }
}


/** Where pages from another PDF go (research-ux-mobile.md 5.4): before or after a page, or at either end. */
@Composable
private fun InsertWhereDialog(selected: Set<Int>, count: Int, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    val c = Power.colors
    val first = selected.minOrNull()
    val last = selected.maxOrNull()
    val options = buildList {
        if (first != null) add(first to stringResource(R.string.insert_before, first + 1))
        if (last != null) add(last + 1 to stringResource(R.string.insert_after, last + 1))
        add(0 to stringResource(R.string.insert_start))
        add(count to stringResource(R.string.insert_end))
    }.distinctBy { it.first }
    var at by remember { mutableStateOf(options.firstOrNull { it.first == (last?.plus(1) ?: count) }?.first ?: count) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insert_where)) },
        text = {
            Column {
                options.forEach { (value, label) -> com.aamus.powerpdf.ui.RadioRow(label, at == value, onSelect = { at = value }) }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.insert), { onChoose(at) }) },
        dismissButton = { DialogButton(stringResource(R.string.cancel), onDismiss) },
        containerColor = c.surface,
    )
}

/** Moving pages without dragging them (research 5.3): before which page they go. */
@Composable
private fun MoveToDialog(count: Int, onDismiss: () -> Unit, onMove: (Int) -> Unit) {
    val c = Power.colors
    var text by remember { mutableStateOf("1") }
    val page = text.map { ch -> if (ch in '٠'..'٩') '0' + (ch - '٠') else ch }.joinToString("").trim().toIntOrNull()
    val ok = page != null && page in 1..count
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_title)) },
        text = {
            Column {
                com.aamus.powerpdf.ui.Field(text, { text = it }, stringResource(R.string.move_to_label), number = true)
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                com.aamus.powerpdf.ui.SecondaryButton(stringResource(R.string.move_to_end), { onMove(count) }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.move_to).removeSuffix("…"), { page?.let { onMove(it - 1) } }, enabled = ok) },
        dismissButton = { DialogButton(stringResource(R.string.cancel), onDismiss) },
        containerColor = c.surface,
    )
}
