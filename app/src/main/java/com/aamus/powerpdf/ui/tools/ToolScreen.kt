package com.aamus.powerpdf.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import com.aamus.powerpdf.Screen
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.doc.Sessions
import com.aamus.powerpdf.engine.CompressLevel
import com.aamus.powerpdf.engine.PdfCompress
import com.aamus.powerpdf.engine.PdfEngine
import com.aamus.powerpdf.engine.PdfFiles
import com.aamus.powerpdf.engine.PdfSecurity
import com.aamus.powerpdf.engine.ProtectOptions
import com.aamus.powerpdf.engine.writePages
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.Group
import com.aamus.powerpdf.ui.GroupDivider
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.ListRow
import com.aamus.powerpdf.ui.LocalLauncher
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.RadioRow
import com.aamus.powerpdf.ui.SecondaryButton
import com.aamus.powerpdf.ui.Segmented
import com.aamus.powerpdf.ui.SwitchRow
import com.aamus.powerpdf.ui.ToolCatalog
import com.aamus.powerpdf.ui.ToolIcon
import com.aamus.powerpdf.ui.home.takeAccess
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ToolScreen(screen: Screen.Tool) {
    val session = screen.sessionId?.let { Sessions.get(it) }
    // A tool reads the document as it is: text still being typed in a form field goes in first (decisions §52).
    androidx.compose.runtime.LaunchedEffect(session) { session?.typing?.commit() }
    when (screen.tool) {
        ToolId.Merge -> MergeTool(screen.inputs, session)
        ToolId.Images -> ImagesTool(screen.inputs, camera = false)
        ToolId.Scan -> ImagesTool(screen.inputs, camera = true)
        ToolId.ScanId -> ImagesTool(screen.inputs, camera = true, kind = ScanKind.IdCard)
        ToolId.ScanBook -> ImagesTool(screen.inputs, camera = true, kind = ScanKind.Book)
        ToolId.Blank -> BlankTool()
        ToolId.Split -> session?.let { SplitTool(it) }
        ToolId.Compress -> session?.let { CompressTool(it) }
        ToolId.Protect -> session?.let { ProtectTool(it) }
        ToolId.Unlock -> session?.let { UnlockTool(it) }
        ToolId.ToImages -> session?.let { ToImagesTool(it) }
        ToolId.ToText -> session?.let { ToTextTool(it) }
        ToolId.Compare -> session?.let { CompareTool(it) }
        ToolId.Recognize -> session?.let { RecognizeTool(it) }
        ToolId.ImageToText -> ImageToTextTool(screen.inputs)
        ToolId.ImgCompress, ToolId.ImgResize, ToolId.ImgCrop, ToolId.ImgConvert, ToolId.ImgRotate, ToolId.ImgWatermark, ToolId.ImgInfo, ToolId.ImgUpscale, ToolId.ImgRemoveBg, ToolId.ImgBlur ->
            com.aamus.powerpdf.ui.tools.img.ImageBoard(screen)
        ToolId.ImgEditor -> com.aamus.powerpdf.ui.tools.img.PhotoEditor(screen.inputs)
        // A viewer tool has no screen of its own: never show an empty one.
        else -> {
            val model = LocalModel.current
            LaunchedEffect(Unit) { model.pop() }
        }
    }
}

/** The frame every tool screen shares: a bar with Back and the tool's name, content, and one primary button. */
@Composable
fun ToolFrame(
    tool: ToolId,
    subtitle: String? = null,
    bottom: @Composable () -> Unit = {},
    /** What the bar's Back does (null: close the screen); a screen with work to lose asks first. */
    onBack: (() -> Unit)? = null,
    /** Icon actions at the end of the bar (the scanner's Undo and Show original). */
    actions: @Composable () -> Unit = {},
    /** Makes the tool's name a button that switches to another tool (the image tools). */
    onTitleClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val model = LocalModel.current
    val c = Power.colors
    val info = ToolCatalog.of(tool)
    // Sideways, a slimmer bar and button strip leave the content more of the little height.
    val compact = com.aamus.powerpdf.ui.isCompactHeight()
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Column(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(if (compact) 52.dp else 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_arrow_back, stringResource(R.string.back), { onBack?.invoke() ?: model.pop() })
                // The image tools' title is a switch to another image tool, the pictures kept (decisions §48).
                val switchLabel = stringResource(R.string.switch_tool)
                Row(
                    Modifier.weight(1f)
                        .then(
                            if (onTitleClick != null) Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable(onClickLabel = switchLabel, role = androidx.compose.ui.semantics.Role.Button, onClick = onTitleClick)
                                .padding(vertical = 4.dp)
                            else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolIcon(info, if (compact) 30 else 36)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(stringResource(info.title), style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(bidi(subtitle), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    if (onTitleClick != null) {
                        Spacer(Modifier.width(2.dp))
                        com.aamus.powerpdf.ui.Sym(R.drawable.ic_expand_more, null, tint = c.muted, size = 22.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                }
                actions()
            }
            HorizontalDivider(color = c.line)
        }
        Box(Modifier.weight(1f)) { content() }
        Column(Modifier.fillMaxWidth().background(c.surface)) {
            HorizontalDivider(color = c.line)
            Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 16.dp)) { bottom() }
        }
    }
}


// --- merge -------------------------------------------------------------------------------------

private data class MergeItem(val uri: Uri?, val session: DocumentSession?, val name: String, val pages: Int, val id: String = java.util.UUID.randomUUID().toString())

/**
 * A file's first page (96 px wide) and page count, read with Android's own PDF renderer (no
 * document opened in MuPDF just to show a list); null for a file it cannot read, such as a locked one.
 */
@Composable
private fun rememberMergeFace(item: MergeItem): Pair<androidx.compose.ui.graphics.ImageBitmap?, Int>? {
    val context = LocalContext.current
    val face by androidx.compose.runtime.produceState<Pair<androidx.compose.ui.graphics.ImageBitmap?, Int>?>(null, item.uri, item.session) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = item.uri?.let { context.contentResolver.openFileDescriptor(it, "r") }
                    ?: return@runCatching item.session?.let { null to it.state.value.pageCount }
                pfd.use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { r ->
                        val n = r.pageCount
                        val bmp = if (n > 0) r.openPage(0).use { p ->
                            val w = 96
                            val h = (w * p.height / maxOf(1, p.width)).coerceIn(1, 400)
                            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                                b.eraseColor(android.graphics.Color.WHITE)
                                p.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        } else null
                        bmp?.asImageBitmap() to n
                    }
                }
            }.getOrNull()
        }
    }
    return face
}

@Composable
private fun MergeTool(inputs: List<Uri>, current: DocumentSession?) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Power.colors
    val items = remember { mutableStateListOf<MergeItem>() }
    // More files come through the chooser (HonoKit's recent PDFs, then Browse phone; decisions §45).
    fun addFiles(uris: List<Uri>) {
        scope.launch {
            uris.forEach { u -> items.add(MergeItem(u, null, withContext(Dispatchers.IO) { Files.displayName(context, u) }, 0)) }
        }
    }
    val pick = { model.choose(com.aamus.powerpdf.data.Choosing.Kind.Pdf, multiple = true, tool = ToolId.Merge) { picked -> model.withUris(picked) { addFiles(it) } } }
    LaunchedEffect(Unit) {
        if (items.isEmpty()) {
            current?.let { items.add(MergeItem(null, it, it.name.value, it.state.value.pageCount)) }
            inputs.forEach { u -> items.add(MergeItem(u, null, withContext(Dispatchers.IO) { Files.displayName(context, u) }, 0)) }
        }
    }

    fun merge() {
        scope.launch {
            val opened = ArrayList<DocumentSession>()
            try {
                val docs = ArrayList<DocumentSession>()
                for (item in items) {
                    val s = item.session ?: model.loadSession(item.uri!!, persistable = true)?.also { opened.add(it) } ?: return@launch
                    docs.add(s)
                }
                model.busy = model.str(R.string.working)
                val out = Files.newWorkFile(context)
                PdfEngine.run { PdfFiles.merge(docs.map { it.doc }, out) }
                model.busy = null
                val name = Files.ensurePdfName(items.first().name.removeSuffix(".pdf").removeSuffix(".PDF") + " (" + model.str(R.string.combined_name) + ")")
                val saved = model.saveNewFile(out, name)
                out.delete()
                if (saved != null) {
                    model.pop()
                    model.showSaved(ToolId.Merge, listOf(saved.first), saved.second)
                }
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                model.busy = null
                opened.forEach { s -> Sessions.remove(s.id); runCatching { s.close() } }
            }
        }
    }

    com.aamus.powerpdf.OnDebug { c -> if (c.cmd == "go") merge() }

    // A list of files put together is work: Back asks before it is cleared.
    var leaveAsk by remember { mutableStateOf(false) }
    fun back() {
        if (items.size >= 2) leaveAsk = true else model.pop()
    }
    androidx.activity.compose.BackHandler(enabled = true) { back() }
    if (leaveAsk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { leaveAsk = false },
            title = { Text(stringResource(R.string.merge_leave_title)) },
            text = { Text(stringResource(R.string.merge_leave_body)) },
            confirmButton = { com.aamus.powerpdf.ui.DialogButton(stringResource(R.string.discard_change), { leaveAsk = false; model.pop() }, danger = true) },
            dismissButton = { com.aamus.powerpdf.ui.DialogButton(stringResource(R.string.cancel), { leaveAsk = false }) },
            containerColor = c.surface,
        )
    }

    ToolFrame(
        ToolId.Merge,
        onBack = { back() },
        bottom = {
            PrimaryButton(
                if (items.size >= 2) androidx.compose.ui.res.pluralStringResource(R.plurals.merge_button, items.size, items.size) else stringResource(R.string.merge_need_two),
                { merge() }, Modifier.fillMaxWidth(), enabled = items.size >= 2,
            )
        },
    ) {
        // Hold a file and drag it up or down (as the hint says; the arrows stay for one step and
        // for TalkBack). The rows swap as the finger passes their middle; near the top and bottom
        // the list scrolls. The gesture is the list's, read before its scrolling (as in Organize).
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        var dragging by remember { mutableStateOf<String?>(null) }
        var fingerY by remember { mutableStateOf(0f) }
        val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
        fun rowAt(y: Float) = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y <= it.offset + it.size && items.any { m -> m.id == it.key } }
        fun follow() {
            val id = dragging ?: return
            val over = rowAt(fingerY) ?: return
            val from = items.indexOfFirst { it.id == id }
            val to = items.indexOfFirst { it.id == over.key }
            if (from >= 0 && to >= 0 && from != to) items.add(to, items.removeAt(from))
        }
        LaunchedEffect(dragging != null) {
            while (dragging != null) {
                val h = list.layoutInfo.viewportSize.height
                val speed = when {
                    fingerY < edge -> -(edge - fingerY) / edge * 20f
                    fingerY > h - edge -> (fingerY - (h - edge)) / edge * 20f
                    else -> 0f
                }
                if (speed != 0f) {
                    list.scrollBy(speed)
                    follow()
                }
                kotlinx.coroutines.delay(16)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().pointerInput(items.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val start = rowAt(down.position.y)?.key as? String ?: return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    var cancelled = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ch = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed || (ch.position - down.position).getDistance() > slop) {
                                cancelled = true
                                break
                            }
                        }
                    }
                    if (cancelled) return@awaitEachGesture
                    dragging = start
                    fingerY = down.position.y
                    while (true) {
                        val ch = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                        fingerY = ch.position.y
                        follow()
                    }
                    dragging = null
                }
            },
            state = list,
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item { Hint(stringResource(R.string.merge_hint), Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                val held = dragging == item.id
                Row(
                    Modifier
                        .graphicsLayer {
                            if (held) {
                                scaleX = 1.03f
                                scaleY = 1.03f
                                shadowElevation = 12f
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(12.dp))
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.width(24.dp))
                    // The first page and the page count, so the order is chosen by sight.
                    val face = rememberMergeFace(item)
                    Box(
                        Modifier.padding(vertical = 8.dp).size(36.dp, 48.dp).clip(RoundedCornerShape(3.dp)).background(Color.White).border(1.dp, c.line, RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val img = face?.first
                        if (img != null) androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = Alignment.TopCenter)
                        else com.aamus.powerpdf.ui.Sym(R.drawable.ic_picture_as_pdf, null, tint = c.danger, size = 20.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        val n = face?.second ?: 0
                        if (n > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.doc_pages, n, n), style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    IconAction(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.move_up), { if (i > 0) items.add(i - 1, items.removeAt(i)) }, enabled = i > 0)
                    IconAction(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.move_down), { if (i < items.lastIndex) items.add(i + 1, items.removeAt(i)) }, enabled = i < items.lastIndex)
                    IconAction(R.drawable.ic_close, stringResource(R.string.remove), { items.removeAt(i) })
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    SecondaryButton(stringResource(R.string.add_files), { pick() }, icon = R.drawable.ic_add)
                }
            }
        }
    }
}

// --- split -------------------------------------------------------------------------------------

private enum class SplitMode { Every, Each, Ranges }

@Composable
private fun SplitTool(session: DocumentSession) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    val name by session.name.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(SplitMode.Every) }
    var every by remember { mutableStateOf("1") }
    var ranges by remember { mutableStateOf("") }
    val count = state.pageCount
    val groups: List<IntRange>? = when (mode) {
        SplitMode.Each -> (0 until count).map { it..it }
        SplitMode.Every -> every.toIntOrNull()?.takeIf { it >= 1 }?.let { n -> (0 until count step n).map { it..minOf(count - 1, it + n - 1) } }
        SplitMode.Ranges -> PdfFiles.parseRanges(ranges, count)
    }

    fun split() {
        val list = groups ?: return
        scope.launch {
            try {
                model.busy = model.str(R.string.working)
                val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
                val made = ArrayList<Uri>()
                list.forEachIndexed { i, r ->
                    val out = Files.newWorkFile(context)
                    session.engine { it.writePages(r.toList(), out) }
                    val saved = model.saveNewFile(out, "$base (${model.str(R.string.part_name, i + 1)}).pdf")
                    out.delete()
                    if (saved == null) return@launch
                    made.add(saved.first)
                }
                model.busy = null
                model.pop()
                model.showSaved(ToolId.Split, made, place = null, title = model.plural(R.plurals.split_done, list.size, list.size, model.str(R.string.downloads_place)))
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                model.busy = null
            }
        }
    }

    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            "splitmode" -> mode = SplitMode.entries.firstOrNull { it.name.equals(c.value, true) } ?: mode
            "splitvalue" -> if (mode == SplitMode.Ranges) ranges = c.value else every = c.value
            "go" -> split()
        }
    }

    ToolFrame(
        ToolId.Split, subtitle = name,
        bottom = {
            PrimaryButton(
                if (groups != null && groups.isNotEmpty()) androidx.compose.ui.res.pluralStringResource(R.plurals.split_button, groups.size, groups.size) else stringResource(R.string.split_title),
                { split() }, Modifier.fillMaxWidth(), enabled = groups != null && groups.size >= 1 && !(groups.size == 1 && groups[0] == 0 until count),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Segmented(
                listOf(SplitMode.Every to stringResource(R.string.split_every), SplitMode.Each to stringResource(R.string.split_each), SplitMode.Ranges to stringResource(R.string.split_ranges)),
                mode, { mode = it },
            )
            when (mode) {
                SplitMode.Every -> Field(every, { every = it.filter(Char::isDigit).take(4) }, stringResource(R.string.split_every_n), number = true)
                SplitMode.Ranges -> Field(
                    ranges, { ranges = it }, stringResource(R.string.split_ranges),
                    placeholder = stringResource(R.string.split_ranges_hint),
                    error = if (ranges.isNotBlank() && groups == null) stringResource(R.string.split_bad_range, ranges, count) else null,
                )
                SplitMode.Each -> {}
            }
            if (groups != null) Hint(androidx.compose.ui.res.pluralStringResource(R.plurals.split_result, groups.size, groups.size))
        }
    }
}

// --- compress ----------------------------------------------------------------------------------

@Composable
private fun CompressTool(session: DocumentSession) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name by session.name.collectAsStateWithLifecycle()
    var level by remember { mutableStateOf(CompressLevel.Medium) }
    val arabic = context.resources.configuration.locales[0].language == "ar"
    // The level that made nothing smaller, shown as a card on this screen until another level is
    // picked (decisions §48; a snackbar alone was easy to miss on the phone).
    var noGain by remember { mutableStateOf<CompressLevel?>(null) }

    fun compress() {
        scope.launch {
            try {
                model.busy = model.str(R.string.working)
                // Work on a copy: the open document stays as it is.
                val snapshot = session.writeOut()
                val out = Files.newWorkFile(context)
                val r = PdfEngine.run {
                    val copy = PdfEngine.open(snapshot, session.doc.password)
                    try {
                        PdfCompress.compress(copy, snapshot, out, level)
                    } finally {
                        copy.close()
                    }
                }
                snapshot.delete()
                model.busy = null
                if (!r.changed) {
                    out.delete()
                    noGain = level
                    return@launch
                }
                val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
                val saved = model.saveNewFile(out, "$base (${model.str(R.string.compressed_name)}).pdf")
                out.delete()
                if (saved != null) {
                    val pct = ((1 - r.after.toDouble() / r.before) * 100).toInt()
                    model.pop()
                    model.showSaved(ToolId.Compress, listOf(saved.first), saved.second, title = model.str(R.string.compress_result, Files.formatSize(r.before, arabic), Files.formatSize(r.after, arabic), "$pct"))
                }
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                model.busy = null
            }
        }
    }

    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            "level" -> level = CompressLevel.entries.firstOrNull { it.name.equals(c.value, true) } ?: level
            "go" -> compress()
        }
    }

    ToolFrame(ToolId.Compress, subtitle = name, bottom = {
        PrimaryButton(stringResource(R.string.compress_button), { compress() }, Modifier.fillMaxWidth())
    }) {
        // The size it starts from, before anything runs (UX review finding 13). The engine only
        // knows the size after the run, so no estimate is shown.
        val now by androidx.compose.runtime.produceState(-1L, session) {
            value = withContext(Dispatchers.IO) { session.source?.let { Files.size(context, it) }?.takeIf { it > 0 } ?: session.work.length() }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(noGain) { if (noGain != null) scroll.animateScrollTo(0) }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 16.dp)) {
            if (now > 0) Text(
                stringResource(R.string.size_now, Files.formatSize(now, arabic)),
                style = MaterialTheme.typography.titleSmall, color = Power.colors.ink,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
            )
            val tried = noGain
            if (tried != null && tried == level) {
                val names = mapOf(CompressLevel.Low to R.string.compress_low, CompressLevel.Medium to R.string.compress_medium, CompressLevel.High to R.string.compress_high)
                val stronger = when (tried) { CompressLevel.Low -> CompressLevel.Medium; CompressLevel.Medium -> CompressLevel.High; else -> null }
                NothingChangedCard(
                    title = stringResource(R.string.compress_no_gain_title),
                    body = stringResource(R.string.compress_no_gain_body, stringResource(names.getValue(tried)), if (now > 0) Files.formatSize(now, arabic) else "") +
                        (if (stronger == null) " " + stringResource(R.string.compress_no_gain_strongest) else ""),
                    action = stronger?.let { stringResource(R.string.compress_try_stronger, stringResource(names.getValue(it))) },
                    onAction = { stronger?.let { level = it; compress() } },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            // The same order as Compress image: Light, Recommended, Strong (decisions §46).
            Group {
                RadioRow(stringResource(R.string.compress_low), level == CompressLevel.Low, { level = CompressLevel.Low }, stringResource(R.string.compress_low_desc))
                GroupDivider()
                RadioRow(stringResource(R.string.compress_medium), level == CompressLevel.Medium, { level = CompressLevel.Medium }, stringResource(R.string.compress_medium_desc))
                GroupDivider()
                RadioRow(stringResource(R.string.compress_high), level == CompressLevel.High, { level = CompressLevel.High }, stringResource(R.string.compress_high_desc))
            }
            Hint(stringResource(R.string.compress_hint), Modifier.padding(20.dp))
        }
    }
}

/**
 * "Nothing changed" said where it cannot be missed (decisions §48): a card on the tool screen with
 * what was tried and, when there is one, the next thing to try. Read out when it appears.
 */
@Composable
fun NothingChangedCard(title: String, body: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = Power.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(c.warn.copy(alpha = if (c.isDark) 0.16f else 0.10f))
            .border(1.dp, c.warn.copy(alpha = 0.45f), shape)
            .semantics(mergeDescendants = false) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.aamus.powerpdf.ui.Sym(R.drawable.ic_info, null, tint = c.warn, size = 24.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(bidi(body), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton(action, onAction, Modifier.fillMaxWidth())
        }
    }
}

// --- protect / unlock ------------------------------------------------------------------------

@Composable
private fun ProtectTool(session: DocumentSession) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name by session.name.collectAsStateWithLifecycle()
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var print by remember { mutableStateOf(true) }
    var copy by remember { mutableStateOf(true) }
    var edit by remember { mutableStateOf(true) }
    var comment by remember { mutableStateOf(true) }
    val problem = when {
        pw.contains(',') -> stringResource(R.string.password_comma)
        again.isNotEmpty() && again != pw -> stringResource(R.string.passwords_differ)
        else -> null
    }
    val ok = pw.isNotEmpty() && pw == again && problem == null

    fun protect() {
        scope.launch {
            try {
                model.busy = model.str(R.string.working)
                val out = Files.newWorkFile(context)
                session.engine { PdfSecurity.protect(it, out, ProtectOptions(pw, null, print, copy, edit, comment)) }
                model.busy = null
                val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
                val saved = model.saveNewFile(out, "$base (${model.str(R.string.protected_name)}).pdf")
                out.delete()
                if (saved != null) {
                    model.pop()
                    model.showSaved(ToolId.Protect, listOf(saved.first), saved.second)
                }
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                model.busy = null
            }
        }
    }

    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            "pw" -> {
                pw = c.value
                again = c.value
            }
            "go" -> protect()
        }
    }

    ToolFrame(ToolId.Protect, subtitle = name, bottom = {
        PrimaryButton(stringResource(R.string.protect_button), { protect() }, Modifier.fillMaxWidth(), enabled = ok)
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(pw, { pw = it }, stringResource(R.string.open_password), password = true, error = if (pw.contains(',')) problem else null)
                Field(again, { again = it }, stringResource(R.string.confirm_password), password = true, error = if (!pw.contains(',')) problem else null)
                Hint(stringResource(R.string.protect_hint))
            }
            com.aamus.powerpdf.ui.SectionTitle(stringResource(R.string.restrictions))
            Group {
                SwitchRow(stringResource(R.string.allow_print), print, { print = it })
                GroupDivider()
                SwitchRow(stringResource(R.string.allow_copy), copy, { copy = it })
                GroupDivider()
                SwitchRow(stringResource(R.string.allow_edit), edit, { edit = it })
                GroupDivider()
                SwitchRow(stringResource(R.string.allow_comment), comment, { comment = it })
            }
        }
    }
}

@Composable
private fun UnlockTool(session: DocumentSession) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name by session.name.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val encrypted = state.encrypted
    val needsOwner = encrypted && state.rights?.full == false
    var owner by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun unlock() {
        scope.launch {
            try {
                model.busy = model.str(R.string.working)
                val out = Files.newWorkFile(context)
                if (needsOwner) {
                    // Open a copy with the permissions password, which lifts the restrictions.
                    val snapshot = session.writeOut()
                    val ok = PdfEngine.run {
                        val copy = runCatching { PdfEngine.open(snapshot, owner) }.getOrNull() ?: return@run false
                        try {
                            if (!copy.rights().full) false else {
                                PdfSecurity.unlock(copy, out)
                                true
                            }
                        } finally {
                            copy.close()
                        }
                    }
                    snapshot.delete()
                    if (!ok) {
                        wrong = true
                        out.delete()
                        return@launch
                    }
                } else {
                    session.engine { PdfSecurity.unlock(it, out) }
                }
                model.busy = null
                val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
                val saved = model.saveNewFile(out, "$base (${model.str(R.string.unlocked_name)}).pdf")
                out.delete()
                if (saved != null) {
                    model.pop()
                    model.showSaved(ToolId.Unlock, listOf(saved.first), saved.second)
                }
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                model.busy = null
            }
        }
    }

    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            "pw" -> owner = c.value
            "go" -> unlock()
        }
    }

    ToolFrame(ToolId.Unlock, subtitle = name, bottom = {
        PrimaryButton(stringResource(R.string.unlock_button), { unlock() }, Modifier.fillMaxWidth(), enabled = encrypted && (!needsOwner || owner.isNotEmpty()))
    }) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !encrypted -> Text(stringResource(R.string.not_protected), style = MaterialTheme.typography.bodyLarge, color = Power.colors.ink)
                needsOwner -> {
                    Text(stringResource(R.string.unlock_needs_owner), style = MaterialTheme.typography.bodyLarge, color = Power.colors.ink)
                    Field(owner, { owner = it; wrong = false }, stringResource(R.string.password), password = true, error = if (wrong) stringResource(R.string.wrong_password) else null)
                }
                else -> Text(stringResource(R.string.unlock_hint), style = MaterialTheme.typography.bodyLarge, color = Power.colors.ink)
            }
        }
    }
}

