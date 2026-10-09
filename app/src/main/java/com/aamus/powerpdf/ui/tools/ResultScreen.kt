package com.aamus.powerpdf.ui.tools

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.SavedItem
import com.aamus.powerpdf.Screen
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.ListRow
import com.aamus.powerpdf.ui.LocalLauncher
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.SecondaryButton
import com.aamus.powerpdf.ui.SectionTitle
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.ToolCatalog
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt

/** The tools a made PDF can go on to (decisions §45): the ones people chain most. */
private val PDF_NEXT = listOf(ToolId.Compress, ToolId.Sign, ToolId.Protect, ToolId.PageNumbers, ToolId.Watermark, ToolId.Split, ToolId.Merge, ToolId.ToImages)

/** The image tools a picture can go on to (as the image tools' result screen had them). */
val IMAGE_NEXT = listOf(ToolId.ImgCompress, ToolId.ImgResize, ToolId.ImgCrop, ToolId.ImgConvert, ToolId.ImgWatermark, ToolId.ImgUpscale, ToolId.ImgRemoveBg, ToolId.ImgRotate, ToolId.Images)

/**
 * One way out of every tool (decisions §45; UX review finding 10): the image tools' result screen,
 * for PDF tools too. The file(s), what changed, where they went; Open, Share, Rename; and
 * "Continue with another tool", which hands the result straight to the next tool. Back returns to
 * where the tool was started.
 */
@Composable
fun ResultScreen(screen: Screen.Result) {
    val model = LocalModel.current
    val context = LocalContext.current
    val launch = LocalLauncher.current
    val scope = rememberCoroutineScope()
    val c = Power.colors
    // Rename changes a file's name (and for a document, its Uri) on this screen.
    val files = remember(screen) { mutableStateListOf<SavedItem>().apply { addAll(screen.files) } }
    var renaming by remember { mutableStateOf<Int?>(null) }
    val ok = files.filter { it.error == null }
    val pictures = screen.mime.startsWith("image/")
    val pdf = screen.mime == Files.PDF
    val names by produceState(emptyMap<Uri, String>(), files.toList()) {
        value = withContext(Dispatchers.IO) { files.associate { it.uri to (it.name ?: Files.displayName(context, it.uri)) } }
    }
    fun nameOf(f: SavedItem) = names[f.uri] ?: f.name ?: ""

    fun open(f: SavedItem) {
        when {
            // Pictures in the image viewer, beside the other results (decisions §42).
            pictures -> {
                val pics = ok.map { it.uri }
                model.openImages(pics, pics.indexOf(f.uri).coerceAtLeast(0))
            }
            pdf -> model.openSaved(f.uri, screen.mime)
            else -> launch(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, screen.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    val next = when {
        pdf && ok.size == 1 -> PDF_NEXT
        // Several parts (Split): put them back together, or make each smaller is one file at a time.
        pdf && ok.size > 1 -> listOf(ToolId.Merge)
        pictures && ok.isNotEmpty() -> IMAGE_NEXT
        else -> emptyList()
    }.filter { it != screen.tool }

    // Pictures: the one shown large, with Before / After (decisions §48).
    var shown by remember(screen) { mutableStateOf(0) }
    var showBefore by remember(screen) { mutableStateOf(false) }
    val preview = if (pictures) files.getOrNull(shown)?.takeIf { it.error == null } ?: ok.firstOrNull() else null
    val adjustable = screen.under == com.aamus.powerpdf.ResultUnder.Board
    // With the board under it, Back leaves both: back to where the tool was started (§45).
    androidx.activity.compose.BackHandler(enabled = adjustable) { model.leaveResult() }

    com.aamus.powerpdf.OnDebug { cmd ->
        when (cmd.cmd) {
            // Debug builds (docs/testing.md): Before/After, which picture is shown, Adjust settings.
            "compare" -> showBefore = cmd.value != "off"
            "resultpick" -> cmd.value.toIntOrNull()?.let { if (it in files.indices) { shown = it; showBefore = false } }
            "adjustsettings" -> if (adjustable) model.pop()
        }
    }

    val info = ToolCatalog.of(screen.tool)
    ToolFrame(
        screen.tool,
        onBack = { model.leaveResult() },
        subtitle = when {
            ok.size == 1 && pictures -> model.str(R.string.img_done_one)
            ok.size > 1 && pictures -> model.str(R.string.img_done_title, "${ok.size}")
            else -> null
        },
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ok.size == 1 || pictures) {
                    SecondaryButton(stringResource(R.string.open), { (preview ?: ok.firstOrNull())?.let { open(it) } }, Modifier.weight(1f), enabled = ok.isNotEmpty(), icon = R.drawable.ic_open_in_new)
                }
                PrimaryButton(stringResource(R.string.share), {
                    // Named copies are shared (a Downloads Uri reached the share sheet as its number).
                    model.shareSaved(ok.map { it.uri }, screen.mime, launch)
                }, Modifier.weight(1f), enabled = ok.isNotEmpty(), icon = R.drawable.ic_share)
            }
        },
    ) {
        // A readable column on a wide screen (sideways, tablets), centred.
        // Sideways, a picture result sits beside the list (the height is short): it stays in view.
        val side = preview != null && com.aamus.powerpdf.ui.isCompactHeight()
        Row(Modifier.fillMaxSize()) {
        if (side && preview != null) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp)) {
                PicturePreview(preview, showBefore = showBefore, onToggle = { showBefore = !showBefore }, onOpen = { open(preview) }, fill = true)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth(), contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                // Saved, what changed, and where it went.
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(c.ok.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Sym(R.drawable.ic_check, null, tint = c.ok, size = 24.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bidi(screen.title ?: stringResource(R.string.saved)), style = MaterialTheme.typography.titleMedium, color = c.ink)
                        screen.place?.let { p ->
                            // The folder only: the file's own name is in its row below (and may be renamed).
                            val first = screen.files.firstOrNull()?.let { it.name ?: names[it.uri] }
                            val folder = if (first != null && p.endsWith(" › $first")) p.removeSuffix(" › $first") else p
                            Text(bidi(stringResource(R.string.saved_to, folder)), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        }
                    }
                }
            }
            if (preview != null && !side) {
                item(key = "preview") {
                    PicturePreview(
                        preview,
                        showBefore = showBefore,
                        onToggle = { showBefore = !showBefore },
                        onOpen = { open(preview) },
                    )
                }
            }
            screen.note?.let { note ->
                item(key = "note") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Sym(R.drawable.ic_info, null, tint = c.muted, size = 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(bidi(note), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    }
                }
            }
            if (adjustable) {
                // Try again with other settings: the board comes back with the same pictures and
                // options; a new run replaces these files (decisions §48).
                item(key = "adjust") {
                    SecondaryButton(stringResource(R.string.result_adjust), { model.pop() }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), icon = R.drawable.ic_tune)
                }
            }
            itemsIndexed(files, key = { i, f -> "$i:${f.uri}" }) { i, f ->
                // Several pictures: a tap shows one above (a second tap opens it).
                val choose = pictures && ok.size > 1
                FileRow(
                    f, nameOf(f), screen.mime,
                    onOpen = { if (choose && preview?.uri != f.uri) { shown = i; showBefore = false } else open(f) },
                    onRename = { renaming = i },
                    selected = choose && preview?.uri == f.uri,
                )
            }
            if (next.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.img_continue), Modifier.padding(top = 8.dp)) }
                next.forEach { t ->
                    item(key = "next-$t") {
                        val n = ToolCatalog.of(t)
                        ListRow(stringResource(n.title), icon = n.icon, iconTint = n.tint, onClick = { model.continueWith(t, ok.map { it.uri }) }, trailing = {
                            Sym(R.drawable.ic_chevron_right, null, tint = c.faint, size = 20.dp)
                        })
                    }
                }
            }
            if (screen.again) {
                item {
                    ListRow(stringResource(R.string.img_start_over), icon = info.icon, iconTint = info.tint, onClick = {
                        model.leaveResult()
                        model.startTool(screen.tool)
                    })
                }
            }
        }
        }
        }
    }

    renaming?.let { i ->
        val f = files.getOrNull(i) ?: return@let
        val full = nameOf(f)
        val ext = full.substringAfterLast('.', "").takeIf { full.contains('.') }
        var text by remember(i) { mutableStateOf(if (ext != null) full.substringBeforeLast('.') else full) }
        val clean = text.trim().replace('/', '_').replace('\\', '_')
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.result_rename)) },
            text = { Field(text, { text = it }, stringResource(R.string.result_name), onDone = {}) },
            confirmButton = {
                DialogButton(stringResource(R.string.result_rename), {
                    renaming = null
                    val target = if (ext != null) "$clean.$ext" else clean
                    scope.launch {
                        val now = withContext(Dispatchers.IO) { Files.rename(context, f.uri, target) }
                        if (now == null) model.say(model.str(R.string.result_rename_failed))
                        else {
                            val shown = withContext(Dispatchers.IO) { Files.displayName(context, now) }
                            files[i] = f.copy(uri = now, name = shown)
                            model.savedVersion++
                        }
                    }
                }, enabled = clean.isNotEmpty() && (if (ext != null) "$clean.$ext" else clean) != full)
            },
            dismissButton = { DialogButton(stringResource(R.string.cancel), { renaming = null }) },
            containerColor = c.surface,
        )
    }
}

/** One saved file: its picture or first page, name, size (or pixels), the gain, and Rename. */
@Composable
private fun FileRow(f: SavedItem, name: String, mime: String, onOpen: () -> Unit, onRename: () -> Unit, selected: Boolean = false) {
    val c = Power.colors
    val context = LocalContext.current
    val arabic = context.resources.configuration.locales[0].language == "ar"
    val thumb by produceState<ImageBitmap?>(null, f.uri) {
        value = if (f.error != null) null else withContext(Dispatchers.IO) { faceOf(context, f.uri, mime) }
    }
    val size by produceState(-1L, f.uri) { value = withContext(Dispatchers.IO) { Files.size(context, f.uri) } }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.accentSoft else Color.Transparent)
            .clickable(enabled = f.error == null, onClick = onOpen)
            .heightIn(min = 72.dp)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp, 56.dp).clip(RoundedCornerShape(6.dp)).background(if (mime == Files.PDF) Color.White else c.surface2).border(1.dp, c.line, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            when {
                t != null -> Image(t, null, Modifier.fillMaxSize(), contentScale = if (mime == Files.PDF) ContentScale.Crop else ContentScale.Fit, alignment = if (mime == Files.PDF) Alignment.TopCenter else Alignment.Center)
                f.error != null -> Sym(R.drawable.ic_error, null, tint = c.danger)
                mime == Files.PDF -> Sym(R.drawable.ic_picture_as_pdf, null, tint = c.danger.copy(alpha = 0.7f), size = 22.dp)
                mime.startsWith("image/") -> Sym(R.drawable.ic_image, null, tint = c.muted, size = 22.dp)
                else -> Sym(R.drawable.ic_description, null, tint = c.muted, size = 22.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(bidi(name), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val line = when {
                f.error != null -> stringResource(R.string.img_failed)
                f.detail != null -> f.detail
                size > 0 -> Files.formatSize(size, arabic)
                else -> null
            }
            if (line != null) Text(bidi(line), style = MaterialTheme.typography.bodySmall, color = if (f.error != null) c.danger else c.muted, maxLines = 2)
            if (f.badge != null) Text(f.badge, style = MaterialTheme.typography.labelMedium, color = c.ok)
        }
        if (f.error == null) IconAction(R.drawable.ic_edit, stringResource(R.string.result_rename), onRename)
    }
}

/**
 * A picture result shown large (decisions §48; the desktop owner found results were only a file
 * row): the picture fitted on a checkerboard when it has transparency; Before / After with the
 * compare button (a toggle, as everywhere in HonoKit, §40) or by touching and holding; a tap opens
 * it in the image viewer. Under it, the pixels, format and size before and after.
 */
@Composable
private fun PicturePreview(item: SavedItem, showBefore: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, fill: Boolean = false) {
    val c = Power.colors
    val context = LocalContext.current
    val arabic = context.resources.configuration.locales[0].language == "ar"
    val after by produceState<android.graphics.Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { runCatching { com.aamus.powerpdf.engine.img.ImageKit.decode(context.contentResolver, item.uri, 1440) }.getOrNull() }
    }
    val before by produceState<android.graphics.Bitmap?>(null, item.source) {
        value = item.source?.let { s -> withContext(Dispatchers.IO) { runCatching { com.aamus.powerpdf.engine.img.ImageKit.decode(context.contentResolver, s, 1440) }.getOrNull() } }
    }
    val facts by produceState<Pair<com.aamus.powerpdf.engine.img.ImageFacts?, com.aamus.powerpdf.engine.img.ImageFacts?>>(null to null, item.uri, item.source) {
        value = withContext(Dispatchers.IO) {
            fun of(u: Uri?) = u?.let { runCatching { com.aamus.powerpdf.engine.img.ImageKit.facts(context.contentResolver, it, Files.size(context, it)) }.getOrNull() }
            of(item.source) to of(item.uri)
        }
    }
    var holding by remember(item.uri) { mutableStateOf(false) }
    val original = (showBefore || holding) && before != null
    val bmp = if (original) before else after
    val image = remember(bmp) { bmp?.asImageBitmap() }
    val checker = bmp?.hasAlpha() == true
    val compact = com.aamus.powerpdf.ui.isCompactHeight()
    val beforeLabel = stringResource(R.string.result_before)
    val afterLabel = stringResource(R.string.result_after)
    val openLabel = stringResource(R.string.open)
    val described = stringResource(R.string.result_picture, if (original) beforeLabel else afterLabel)
    Column(Modifier.fillMaxWidth().then(if (fill) Modifier.fillMaxHeight() else Modifier).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f) else Modifier.height(if (compact) 220.dp else 340.dp)).clip(RoundedCornerShape(12.dp)).background(c.sunken),
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                Text("…", color = c.faint)
            } else {
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = described; onClick(openLabel) { onOpen(); true } }
                        .pointerInput(item.uri) {
                            detectTapGestures(
                                onTap = { onOpen() },
                                onLongPress = { holding = true },
                                onPress = { tryAwaitRelease(); holding = false },
                            )
                        },
                ) {
                    val pad = 8.dp.toPx()
                    val area = com.aamus.powerpdf.ui.tools.img.fittedRect(image.width, image.height, size, pad)
                    if (checker) {
                        // A checkerboard shows what is transparent (Remove background).
                        val cell = 10.dp.toPx()
                        drawRect(Color.White, area.topLeft, area.size)
                        clipRect(area.left, area.top, area.right, area.bottom) {
                            var y = area.top
                            var row = 0
                            while (y < area.bottom) {
                                var x = area.left + if (row % 2 == 0) 0f else cell
                                while (x < area.right) {
                                    drawRect(Color(0xFFDDDDDD), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
                                    x += cell * 2
                                }
                                y += cell
                                row++
                            }
                        }
                    }
                    drawImage(
                        image,
                        dstOffset = androidx.compose.ui.unit.IntOffset(area.left.roundToInt(), area.top.roundToInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(area.width.roundToInt().coerceAtLeast(1), area.height.roundToInt().coerceAtLeast(1)),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    )
                }
            }
            // Which one is on screen, always said.
            if (before != null) Text(
                if (original) beforeLabel else afterLabel,
                style = MaterialTheme.typography.labelLarge, color = if (original) c.accentInk else c.ink,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (original) c.accent else c.surface.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 5.dp),
            )
            if (before != null) {
                val compare = stringResource(R.string.result_compare)
                val state = stringResource(if (original) R.string.result_showing_before else R.string.result_showing_after)
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).size(48.dp).clip(CircleShape)
                        .background(if (showBefore) c.accent else c.surface.copy(alpha = 0.92f))
                        .border(1.dp, if (showBefore) c.accent else c.line, CircleShape)
                        .clickable(onClick = onToggle)
                        .semantics { contentDescription = compare; stateDescription = state; role = Role.Switch },
                    contentAlignment = Alignment.Center,
                ) { Sym(R.drawable.ic_visibility, null, tint = if (showBefore) c.accentInk else c.ink, size = 22.dp) }
            }
        }
        Text(
            stringResource(if (before != null) R.string.result_hold_hint else R.string.result_tap_hint),
            style = MaterialTheme.typography.bodySmall, color = c.faint,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
        // Before and after: pixels, format, size.
        val (fb, fa) = facts
        if (fa != null) {
            fun line(f: com.aamus.powerpdf.engine.img.ImageFacts) =
                "${Char(0x2066)}${f.width} × ${f.height}${Char(0x2069)} · ${f.format.label}" + (if (f.bytes > 0) " · " + Files.formatSize(f.bytes, arabic) else "")
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fb != null) {
                    Column(Modifier.weight(1f)) {
                        Text(beforeLabel, style = MaterialTheme.typography.labelMedium, color = c.muted)
                        Text(bidi(line(fb)), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(afterLabel, style = MaterialTheme.typography.labelMedium, color = c.muted)
                    Text(bidi(line(fa)), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    if (item.badge != null) Text(item.badge, style = MaterialTheme.typography.labelMedium, color = c.ok)
                }
            }
        }
    }
}

/** A small picture of a result: a picture itself, or a PDF's first page (Android's own renderer). */
private fun faceOf(context: android.content.Context, uri: Uri, mime: String): ImageBitmap? = runCatching {
    when {
        mime.startsWith("image/") -> com.aamus.powerpdf.engine.img.ImageKit.decode(context.contentResolver, uri, 240).asImageBitmap()
        mime == Files.PDF -> context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r ->
                if (r.pageCount == 0) null else r.openPage(0).use { p ->
                    val w = 120
                    val h = (w * p.height / maxOf(1, p.width)).coerceIn(1, 480)
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(android.graphics.Color.WHITE)
                        p.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }.asImageBitmap()
                }
            }
        }
        else -> null
    }
}.getOrNull()
