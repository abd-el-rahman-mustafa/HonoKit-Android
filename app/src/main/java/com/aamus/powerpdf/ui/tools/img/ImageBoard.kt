package com.aamus.powerpdf.ui.tools.img

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.Screen
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.engine.img.HideArea
import com.aamus.powerpdf.engine.img.HideStyle
import com.aamus.powerpdf.engine.img.ImageExtras
import com.aamus.powerpdf.engine.img.ImageFacts
import com.aamus.powerpdf.engine.img.ImageKit
import com.aamus.powerpdf.engine.img.ImgFormat
import com.aamus.powerpdf.ocr.ImageAi
import com.aamus.powerpdf.ocr.OcrException
import com.aamus.powerpdf.ui.Group
import com.aamus.powerpdf.ui.GroupDivider
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.ListRow
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.RadioRow
import com.aamus.powerpdf.ui.SecondaryButton
import com.aamus.powerpdf.ui.SectionTitle
import com.aamus.powerpdf.ui.Segmented
import com.aamus.powerpdf.ui.SwitchRow
import com.aamus.powerpdf.ui.Swatch
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.ToolCatalog
import com.aamus.powerpdf.ui.ToolIcon
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.home.takeAccess
import com.aamus.powerpdf.ui.theme.Power
import com.aamus.powerpdf.ui.tools.ToolFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke

/**
 * Every image tool's screen on the phone (decisions §34), as the desktop's board: the pictures as
 * cards (tap one to preview it), the tool's options, one button, then what was saved — with
 * Share and "Continue with another tool".
 */

private class Card(val key: Int, val uri: Uri, val name: String) {
    var facts by mutableStateOf<ImageFacts?>(null)
    var thumb by mutableStateOf<Bitmap?>(null)
    var failed by mutableStateOf(false)
    /** Blur faces: the areas found (null while not searched yet). */
    var hide by mutableStateOf<List<HideArea>?>(null)
    /** Remove background: the picture without its background, made once for the preview. */
    var cutout by mutableStateOf<Bitmap?>(null)
    /** The AI model failed on this picture while previewing (said on screen, never hidden; decisions §48). */
    var aiError by mutableStateOf<String?>(null)
    var result by mutableStateOf<ImgResult?>(null)
    /** The file this board last saved for the picture: a run with other settings replaces it. */
    var saved: Uri? = null
}

/**
 * A board's pictures and options, kept by the app while its result screen is on top, so "Adjust
 * settings" comes back to the same pictures and settings (decisions §48).
 */
private class BoardState(tool: ToolId) {
    val cards = mutableStateListOf<Card>()
    var opts by mutableStateOf(remembered[tool] ?: ImgOptions(convertTo = ImgFormat.JPG, markText = ""))
    var selected by mutableStateOf<Int?>(null)
    var drawArea by mutableStateOf(false)
    var started = false
    /** Blur faces: what the pictures were last searched for (faces, plates). */
    var searchedFor: Pair<Boolean, Boolean>? = null
}

private var counter = 0

/** Sizes such as 1200 × 1543 read left to right in Arabic too (otherwise the two numbers swap places). */
private fun ltr(text: String): String = "${Char(0x2066)}$text${Char(0x2069)}"
/** Each tool's last options, kept while the app runs. */
private val remembered = HashMap<ToolId, ImgOptions>()

/** Tools whose result is about the file size: the result says how much smaller (or larger). */
private val SIZE_TOOLS = setOf(ToolId.ImgCompress, ToolId.ImgConvert, ToolId.ImgResize)
private val AI_TOOLS = setOf(ToolId.ImgUpscale, ToolId.ImgRemoveBg, ToolId.ImgBlur)
private val PREVIEW_TOOLS = setOf(ToolId.ImgCrop, ToolId.ImgRotate, ToolId.ImgWatermark, ToolId.ImgBlur, ToolId.ImgRemoveBg)

private fun suffixOf(tool: ToolId): Int = when (tool) {
    ToolId.ImgCompress -> R.string.img_suffix_compress
    ToolId.ImgResize -> R.string.img_suffix_resize
    ToolId.ImgCrop -> R.string.img_suffix_crop
    ToolId.ImgConvert -> R.string.img_suffix_convert
    ToolId.ImgRotate -> R.string.img_suffix_rotate
    ToolId.ImgWatermark -> R.string.img_suffix_watermark
    ToolId.ImgInfo -> R.string.img_suffix_info
    ToolId.ImgUpscale -> R.string.img_suffix_upscale
    ToolId.ImgRemoveBg -> R.string.img_suffix_removebg
    else -> R.string.img_suffix_blur
}

@Composable
fun ImageBoard(screen: Screen.Tool) {
    val tool = screen.tool
    val inputs = screen.inputs
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Power.colors
    val st = remember(screen) { model.keep(screen) { BoardState(tool) } }
    val cards = st.cards
    val opts = st.opts
    fun set(o: ImgOptions) { st.opts = o; remembered[tool] = o }
    var selected by st::selected
    // Blur faces: while on, a drag on the preview draws a new area to hide.
    var drawArea by st::drawArea
    var running by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    val sample = stringResource(R.string.img_mark_sample)
    val suffix = stringResource(suffixOf(tool))
    val info = ToolCatalog.of(tool)

    // One model runtime for the screen, opened when first needed and closed when it is left.
    val aiHolder = remember { arrayOfNulls<ImageAi>(1) }
    DisposableEffect(Unit) { onDispose { aiHolder[0]?.close() } }
    val ai: (() -> ImageAi)? = if (tool in AI_TOOLS) ({ aiHolder[0] ?: ImageAi.open(context).also { aiHolder[0] = it } }) else null

    val loading = remember { Semaphore(2) }
    fun add(uris: List<Uri>) {
        val fresh = uris.map { Card(counter++, it, Files.displayName(context, it)) }
        cards.addAll(fresh)
        if (selected == null) selected = fresh.firstOrNull()?.key
        fresh.forEach { card ->
            scope.launch {
                loading.withPermit {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            card.facts = ImageKit.facts(context.contentResolver, card.uri, Files.size(context, card.uri)) ?: error("unreadable")
                            card.thumb = ImageKit.decode(context.contentResolver, card.uri, 720)
                        }.onFailure { card.failed = true }
                    }
                }
            }
        }
    }
    val logo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) set(opts.copy(markPicture = uri)) }
    LaunchedEffect(Unit) {
        // Once per board: coming back from the result screen keeps the pictures as they were.
        if (st.started) return@LaunchedEffect
        st.started = true
        if (inputs.isNotEmpty()) add(inputs)
    }

    // Blur faces: each picture searched once its preview is ready (and again when what to look
    // for changes; not when the board comes back from its result with the areas set by hand).
    LaunchedEffect(tool, opts.findFaces, opts.findPlates) {
        if (tool != ToolId.ImgBlur) return@LaunchedEffect
        val wanted = opts.findFaces to opts.findPlates
        if (st.searchedFor != null && st.searchedFor != wanted) cards.forEach { it.hide = null; it.aiError = null }
        st.searchedFor = wanted
    }
    val pendingSearch = cards.firstOrNull { tool == ToolId.ImgBlur && it.thumb != null && it.hide == null }
    LaunchedEffect(pendingSearch?.key, opts.findFaces, opts.findPlates) {
        val card = pendingSearch ?: return@LaunchedEffect
        val found = withContext(Dispatchers.Default) {
            runCatching {
                val b = ImageKit.decode(context.contentResolver, card.uri, 1600)
                val a = ai!!()
                (if (opts.findFaces) a.faces(b) else emptyList()) + (if (opts.findPlates) a.plates(b) else emptyList())
            }
        }
        // A search that failed says so (it showed "0 faces" before): areas can still be drawn by hand.
        card.aiError = found.exceptionOrNull()?.let { e -> if (e is OcrException.Unsupported) model.str(R.string.img_ai_64bit) else e.message ?: e.javaClass.simpleName }
        card.hide = found.getOrDefault(emptyList()).map { HideArea(it.x, it.y, it.w, it.h, it.face) }
    }

    val usable = cards.filter { it.facts != null && !it.failed }
    val chosen = cards.firstOrNull { it.key == selected } ?: usable.firstOrNull()

    fun durationText(seconds: Long): String =
        if (seconds < 60) model.str(R.string.dur_seconds, "${seconds.coerceAtLeast(1)}") else model.str(R.string.dur_minutes, "${seconds / 60}", "${seconds % 60}")

    /**
     * The results go to the one result screen every tool ends on (decisions §45): each picture
     * with its pixels, format and size, how it changed, and the picture it came from (Before /
     * After). The board stays under it, so "Adjust settings" comes back here (decisions §48).
     */
    fun finish(seconds: Long, replaced: Int) {
        val arabic = context.resources.configuration.locales[0].language == "ar"
        val made = cards.mapNotNull { card -> card.result?.let { card to it } }
        val items = made.map { (card, r) ->
            val f = card.facts
            val before = f?.bytes ?: 0
            com.aamus.powerpdf.SavedItem(
                uri = r.uri,
                name = if (r.error == null) r.name else card.name,
                detail = if (r.error == null) ltr("${r.width} × ${r.height} · ${r.format.label}") + " · " + Files.formatSize(r.bytes, arabic) else r.error,
                badge = when {
                    r.error != null -> null
                    r.unchanged -> model.str(R.string.img_kept)
                    // The change that matters for the tool: the scale for Upscale, the file size for
                    // the tools that are about size (a blur or a watermark re-saves, its size is noise).
                    tool == ToolId.ImgUpscale -> model.str(R.string.img_scaled, "${opts.upscale}")
                    tool !in SIZE_TOOLS -> null
                    before > 0 && r.bytes * 100 < before * 99 -> model.str(R.string.img_saved_less, "${(before - r.bytes) * 100 / before}")
                    before > 0 && r.bytes * 100 > before * 101 && tool != ToolId.ImgCompress -> model.str(R.string.img_bigger, "${(r.bytes - before) * 100 / before}")
                    else -> null
                },
                error = r.error,
                source = card.uri,
            )
        }
        val formats = made.filter { it.second.error == null }.map { it.second.format }.distinct()
        // How it was made, plainly: the AI tools name the model that ran on the phone and the time.
        val ok = made.count { it.second.error == null }
        val took = durationText(seconds)
        val method = when {
            ok == 0 -> null
            tool == ToolId.ImgUpscale -> model.str(R.string.img_method_upscale, "${opts.upscale}", took)
            tool == ToolId.ImgRemoveBg -> model.str(R.string.img_method_removebg, took)
            tool == ToolId.ImgBlur -> {
                val done = made.filter { it.second.error == null }.flatMap { it.first.hide ?: emptyList() }
                model.str(R.string.img_method_blur, "${done.count { it.face }}", "${done.count { !it.face && !it.drawn }}", "${done.count { it.on }}")
            }
            else -> null
        }
        val note = listOfNotNull(method, if (replaced > 0) model.str(R.string.img_replaced) else null).joinToString(" ").ifEmpty { null }
        model.showResult(tool, items, place = "Pictures › HonoKit › ${ImageJobsFolder.of(tool)}", mime = formats.singleOrNull()?.mime ?: "image/*", note = note, under = com.aamus.powerpdf.ResultUnder.Board)
    }

    fun runAll() {
        running = true
        scope.launch {
            try {
                val started = System.nanoTime()
                var replaced = 0
                val stamp = if (tool == ToolId.ImgWatermark) withContext(Dispatchers.IO) { ImageJobs.stamp(context, opts, sample) } else null
                for ((i, card) in usable.withIndex()) {
                    progressText = model.str(R.string.img_working, "${i + 1}", "${usable.size}")
                    progress = i.toFloat() / usable.size
                    // A run with other settings replaces this board's earlier file for the picture
                    // (decisions §48): no orphans in Pictures › HonoKit.
                    val earlier = card.saved
                    var r = withContext(Dispatchers.Default) {
                        runCatching {
                            ImageJobs.run(context, tool, opts, card.uri, card.name, card.facts!!, suffix, ai, card.hide ?: emptyList(), stamp) { f -> progress = (i + f) / usable.size }
                        }.getOrElse { e ->
                            android.util.Log.w("PowerPDF", "image tool $tool failed", e)
                            val text = when (e) {
                                is OcrException.Unsupported -> model.str(R.string.img_ai_64bit)
                                is OutOfMemoryError -> model.str(R.string.img_too_big)
                                else -> e.message ?: e.toString()
                            }
                            ImgResult(card.uri, card.name, 0, 0, 0, ImgFormat.JPG, error = text)
                        }
                    }
                    if (r.error == null && earlier != null && earlier != r.uri) {
                        val made = r
                        val gone = withContext(Dispatchers.IO) { Files.deleteSaved(context, earlier) }
                        if (gone) {
                            replaced++
                            // The new file was saved beside the old one ("… (1).png"): it takes the plain name back.
                            val now = withContext(Dispatchers.IO) { Files.displayName(context, made.uri) }
                            if (now != made.name) withContext(Dispatchers.IO) { Files.rename(context, made.uri, made.name) }?.let { r = made.copy(uri = it) }
                        }
                    }
                    card.result = r
                    // A failed run keeps the earlier file as the one a later run replaces.
                    if (r.error == null) card.saved = r.uri
                }
                finish((System.nanoTime() - started) / 1_000_000_000, replaced)
            } finally {
                running = false
            }
        }
    }

    com.aamus.powerpdf.OnDebug { cmd ->
        when (cmd.cmd) {
            // Debug builds (docs/testing.md): run the tool; switch to another image tool; its sheet.
            "imggo" -> if (!running && usable.isNotEmpty()) runAll()
            "switchtool" -> ToolId.entries.firstOrNull { it.name.equals(cmd.value, true) }?.let { t -> model.replaceTop(Screen.Tool(t, inputs = cards.map { it.uri })) }
            "toolsheet" -> switching = cmd.value != "off"
            "upscaleby" -> cmd.value.toIntOrNull()?.takeIf { it == 2 || it == 4 }?.let { set(opts.copy(upscale = it)) }
        }
    }

    if (switching) {
        com.aamus.powerpdf.ui.image.ImageToolsSheet(onDismiss = { switching = false }, current = tool, title = stringResource(R.string.switch_tool)) { t ->
            switching = false
            // The same pictures in the other tool; Back still returns to where this one was opened.
            model.replaceTop(Screen.Tool(t, inputs = cards.map { it.uri }))
        }
    }

    ToolFrame(
        tool,
        subtitle = if (cards.size > 1) model.str(R.string.img_count, cards.size) else null,
        onTitleClick = if (running) null else ({ switching = true }),
        bottom = {
            if (running) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progressText, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f))
                        Text(bidi("${(progress * 100).roundToInt()}%"), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    }
                    // Said while it runs: the AI tools use a model on this phone, nothing is sent anywhere.
                    if (tool in AI_TOOLS) Text(stringResource(R.string.img_ai_using), style = MaterialTheme.typography.bodySmall, color = c.faint)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            } else {
                PrimaryButton(
                    if (tool == ToolId.ImgInfo) (if (usable.size == 1) model.str(R.string.img_info_remove_one) else model.str(R.string.img_info_remove, usable.size)) else stringResource(info.title),
                    { runAll() },
                    Modifier.fillMaxWidth(),
                    enabled = usable.isNotEmpty() && (tool != ToolId.ImgBlur || usable.all { it.hide != null }),
                    icon = info.icon,
                )
            }
        },
    ) {
        val list = rememberLazyListState()
        // The preview arrives after the cards: bring the list back to its top so it is seen.
        val hasPreview = tool in PREVIEW_TOOLS && chosen != null
        LaunchedEffect(hasPreview) { if (hasPreview) list.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            // The preview: the selected picture as it will come out.
            // Always the first row (empty until a picture is added), so the list stays at its top when the preview arrives.
            if (tool in PREVIEW_TOOLS) {
                item("preview") { if (chosen != null) Preview(tool, opts, ::set, chosen, ai, sample, drawArea) }
            }
            item("cards") {
                if (cards.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ToolIcon(info, 64)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.img_empty), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    }
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(cards, key = { it.key }) { card ->
                            CardView(tool, opts, card, card.key == chosen?.key, { selected = card.key }) {
                                // Removed at once, with Undo in the message (as in Photos and Files).
                                val at = cards.indexOf(card)
                                cards.remove(card)
                                if (selected == card.key) selected = cards.firstOrNull()?.key
                                model.say(model.str(R.string.img_removed), action = model.str(R.string.undo)) {
                                    cards.add(at.coerceIn(0, cards.size), card)
                                    selected = card.key
                                }
                            }
                        }
                    }
                }
            }
            item("add") {
                // One way to add pictures (decisions §45): the chooser, with HonoKit's recent
                // pictures, Photos and Browse files (it was "Add pictures" and "Open" side by side).
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center) {
                    SecondaryButton(stringResource(R.string.img_add), {
                        model.choose(com.aamus.powerpdf.data.Choosing.Kind.Image, multiple = true, tool = tool) { picked -> model.withUris(picked) { add(it) } }
                    }, Modifier.fillMaxWidth(), icon = R.drawable.ic_add_photo_alternate, enabled = !running)
                }
            }
            item("options") {
                Column(Modifier.padding(top = 8.dp)) { Options(tool, opts, ::set, chosen, onLogo = { logo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, drawArea = drawArea, setDrawArea = { drawArea = it }, totalBytes = usable.sumOf { it.facts?.bytes ?: 0L }, pictures = usable.size) }
            }
        }
    }
}

@Composable
private fun CardView(tool: ToolId, o: ImgOptions, card: Card, selected: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val c = Power.colors
    val f = card.facts
    Column(
        Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, RoundedCornerShape(12.dp))
            .background(c.surface)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp).background(c.surface2), contentAlignment = Alignment.Center) {
            val t = card.thumb
            when {
                t != null -> Image(t.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
                card.failed -> Sym(R.drawable.ic_error, null, tint = c.danger)
                else -> Text("…", color = c.faint)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp).clip(RoundedCornerShape(14.dp)).background(c.surface.copy(alpha = 0.9f)).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Sym(R.drawable.ic_close, stringResource(R.string.img_remove), tint = c.muted, size = 16.dp)
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(bidi(card.name), style = MaterialTheme.typography.labelMedium, color = if (card.failed) c.danger else c.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                if (f != null) ltr("${f.width} × ${f.height} · ${f.format.label}") else if (card.failed) stringResource(R.string.img_cannot_read) else " ",
                style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 2,
            )
            val line = when {
                f == null -> null
                tool == ToolId.ImgResize -> resized(f.width, f.height, o).let { (w, h) -> ltr("→ $w × $h") }
                tool == ToolId.ImgUpscale -> ltr("→ ${f.width * o.upscale} × ${f.height * o.upscale}")
                tool == ToolId.ImgConvert -> ltr("→ .${o.convertTo.ext}")
                tool == ToolId.ImgCrop -> cropFor(o, f.width, f.height).let { b -> ltr("→ ${(b.w * f.width).roundToInt()} × ${(b.h * f.height).roundToInt()}") }
                tool == ToolId.ImgBlur && card.aiError != null -> stringResource(R.string.img_blur_not_searched)
                tool == ToolId.ImgBlur -> card.hide?.let { h ->
                    // Areas drawn by hand are counted as such, not as number plates.
                    val drawn = h.count { it.drawn && it.on }
                    stringResource(R.string.img_blur_found, h.count { it.face && it.on }, h.count { !it.face && !it.drawn && it.on }) + (if (drawn > 0) stringResource(R.string.img_blur_drawn, "$drawn") else "")
                } ?: stringResource(R.string.img_blur_finding)
                else -> null
            }
            if (line != null) Text(bidi(line), style = MaterialTheme.typography.labelSmall, color = if (card.aiError != null) c.danger else c.accent, maxLines = 1)
        }
    }
}

/**
 * The selected picture as it will come out, and the place to adjust it by hand (decisions §40):
 * Crop drags its box; Watermark drags and pinches the mark; Blur faces taps an area off or on and
 * draws new ones; Remove background runs the model once per picture and repaints the colour live.
 * The eye switches between the result and the original.
 */
@Composable
private fun Preview(tool: ToolId, o: ImgOptions, set: (ImgOptions) -> Unit, card: Card, ai: (() -> ImageAi)?, sample: String, drawArea: Boolean) {
    val c = Power.colors
    val thumb = card.thumb
    var original by remember(card.key, tool) { mutableStateOf(false) }
    // A fixed height, the whole picture fitted inside: a tall poster is not cut, and the list
    // below does not move when the preview arrives. Tools you work on by hand get more room.
    val tall = tool == ToolId.ImgCrop || tool == ToolId.ImgWatermark || tool == ToolId.ImgBlur
    Box(
        Modifier.fillMaxWidth().padding(16.dp).height(if (tall) 380.dp else 300.dp).clip(RoundedCornerShape(12.dp)).background(c.sunken),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb == null) {
            Text("…", color = c.faint)
            return@Box
        }
        val image = remember(thumb) { thumb.asImageBitmap() }
        when {
            tool == ToolId.ImgCrop -> CropPicture(image, cropFor(o, thumb.width, thumb.height), o.ratio, { b -> set(withCrop(o, b, thumb.width, thumb.height)) }, Modifier.fillMaxSize())
            original -> FittedPicture(image)
            tool == ToolId.ImgWatermark -> WatermarkPreview(image, o, set, sample)
            tool == ToolId.ImgBlur -> BlurPreview(card, image, o, drawArea)
            tool == ToolId.ImgRemoveBg -> CutoutPreview(card, image, o, ai)
            else -> RenderedPreview(tool, o, card, ai)
        }
        if (tool != ToolId.ImgCrop) {
            val compare = stringResource(R.string.ed_compare)
            val on = stringResource(R.string.ed_compare_on)
            val off = stringResource(R.string.ed_compare_off)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).size(44.dp).clip(CircleShape)
                    .background(if (original) c.accent else c.surface.copy(alpha = 0.92f))
                    .border(1.dp, if (original) c.accent else c.line, CircleShape)
                    .clickable { original = !original }
                    .semantics { contentDescription = compare; stateDescription = if (original) on else off; role = Role.Switch },
                contentAlignment = Alignment.Center,
            ) { Sym(R.drawable.ic_visibility, null, tint = if (original) c.accentInk else c.ink, size = 22.dp) }
            if (original) Text(
                compare, style = MaterialTheme.typography.labelLarge, color = c.ink,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(c.surface.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

private val PREVIEW_PAD = 8.dp

@Composable
private fun FittedPicture(image: ImageBitmap) {
    Image(image, stringResource(R.string.img_preview), Modifier.fillMaxSize().padding(PREVIEW_PAD), contentScale = ContentScale.Fit)
}

/** Rotate (and any other tool with a cheap result): rendered from the small copy, one at a time. */
@Composable
private fun RenderedPreview(tool: ToolId, o: ImgOptions, card: Card, ai: (() -> ImageAi)?) {
    val context = LocalContext.current
    val shown by produceState<Bitmap?>(null, card.key, card.thumb, o) {
        value = withContext(Dispatchers.Default) {
            runCatching { card.thumb?.let { ImageJobs.apply(context, tool, o, it, ai) } }.getOrNull()
        }
    }
    val b = shown ?: card.thumb ?: return
    FittedPicture(remember(b) { b.asImageBitmap() })
}

/** The mark drawn live over the picture (as ImageKit.mark draws it): drag it, pinch it. */
@Composable
private fun WatermarkPreview(image: ImageBitmap, o: ImgOptions, set: (ImgOptions) -> Unit, sample: String) {
    val context = LocalContext.current
    val c = Power.colors
    // The mark changes only with its text, colour or picture — not with every slider move.
    val stamp by produceState<ImageBitmap?>(null, o.markText, o.markColour, o.markOutline, o.markShadow, o.markPicture, sample) {
        value = withContext(Dispatchers.IO) { runCatching { ImageJobs.stamp(context, o, sample).asImageBitmap() }.getOrNull() }
    }
    val opts by rememberUpdatedState(o)
    val change by rememberUpdatedState(set)
    val accent = c.accent
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pad = with(LocalDensity.current) { PREVIEW_PAD.toPx() }
        val area = fittedRect(image.width, image.height, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), pad)
        val areaNow by rememberUpdatedState(area)
        val st = stamp
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val a = areaNow
                    val s = stamp ?: return@awaitEachGesture
                    val start = opts
                    if (start.markTile) return@awaitEachGesture
                    // Only a touch on the mark (or a pinch) moves it; elsewhere the list scrolls.
                    val w = start.markSize * a.width
                    val h = w * s.height / s.width
                    val centre = Offset(a.left + start.markX * a.width, a.top + start.markY * a.height)
                    val reach = 16.dp.toPx()
                    val onMark = abs(down.position.x - centre.x) <= w / 2 + reach && abs(down.position.y - centre.y) <= h / 2 + reach
                    var now = start
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            now = now.copy(
                                markSize = (now.markSize * ev.calculateZoom()).coerceIn(0.05f, 1f),
                                markRotation = (now.markRotation + ev.calculateRotation()).coerceIn(-90f, 90f),
                            )
                        } else if (onMark) {
                            val d = pressed[0].positionChange()
                            now = now.copy(markX = (now.markX + d.x / a.width).coerceIn(0f, 1f), markY = (now.markY + d.y / a.height).coerceIn(0f, 1f))
                        } else continue
                        ev.changes.forEach { it.consume() }
                        change(now)
                    }
                }
            },
        ) {
            drawImage(image, dstOffset = IntOffset(area.left.roundToInt(), area.top.roundToInt()), dstSize = IntSize(area.width.roundToInt(), area.height.roundToInt()), filterQuality = FilterQuality.Medium)
            if (st == null) return@Canvas
            val w = o.markSize * area.width
            val h = w * st.height / st.width
            fun place(cx: Float, cy: Float) = rotate(o.markRotation, Offset(cx, cy)) {
                drawImage(st, dstOffset = IntOffset((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt()), dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)), alpha = o.markOpacity, filterQuality = FilterQuality.Medium)
            }
            clipRect(area.left, area.top, area.right, area.bottom) {
                if (o.markTile) {
                    val gx = w * 2
                    val gy = h * 2
                    var y = gy / 2
                    while (y < area.height + gy) {
                        var x = gx / 2
                        while (x < area.width + gx) { place(area.left + x, area.top + y); x += gx }
                        y += gy
                    }
                } else place(area.left + o.markX * area.width, area.top + o.markY * area.height)
            }
            if (!o.markTile) {
                val cx = area.left + o.markX * area.width
                val cy = area.top + o.markY * area.height
                rotate(o.markRotation, Offset(cx, cy)) {
                    drawRect(accent, Offset(cx - w / 2 - 4.dp.toPx(), cy - h / 2 - 4.dp.toPx()), Size(w + 8.dp.toPx(), h + 8.dp.toPx()), style = DrawStroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                }
            }
        }
    }
}

/**
 * Blur faces by hand: every area found is outlined; tap one to keep it sharp (dashed) or blur it
 * again; with "Draw an area" on, drag to add one.
 */
@Composable
private fun BlurPreview(card: Card, image: ImageBitmap, o: ImgOptions, drawArea: Boolean) {
    val c = Power.colors
    val thumb = card.thumb ?: return
    val areas = card.hide ?: emptyList()
    val shown by produceState<Bitmap?>(null, thumb, areas, o.hideStyle) {
        value = withContext(Dispatchers.Default) { runCatching { ImageExtras.hide(thumb, areas, o.hideStyle) }.getOrNull() }
    }
    val shownImage = remember(shown) { shown?.asImageBitmap() }
    var drawing by remember { mutableStateOf<Rect?>(null) }
    val drawMode by rememberUpdatedState(drawArea)
    val accent = c.accent
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pad = with(LocalDensity.current) { PREVIEW_PAD.toPx() }
        val area = fittedRect(image.width, image.height, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), pad)
        val areaNow by rememberUpdatedState(area)
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val a = areaNow
                    fun fx(x: Float) = ((x - a.left) / a.width).coerceIn(0f, 1f)
                    fun fy(y: Float) = ((y - a.top) / a.height).coerceIn(0f, 1f)
                    if (drawMode) {
                        down.consume()
                        drag(down.id) { ch ->
                            drawing = Rect(down.position, ch.position)
                            ch.consume()
                        }
                        val r = drawing
                        drawing = null
                        if (r != null) {
                            val x0 = fx(min(r.left, r.right)); val x1 = fx(maxOf(r.left, r.right))
                            val y0 = fy(min(r.top, r.bottom)); val y1 = fy(maxOf(r.top, r.bottom))
                            if (x1 - x0 > 0.02f && y1 - y0 > 0.02f) card.hide = (card.hide ?: emptyList()) + HideArea(x0, y0, x1 - x0, y1 - y0, face = false, drawn = true)
                        }
                        return@awaitEachGesture
                    }
                    // A tap (no drag) on an area turns it off or on; a drag scrolls the list.
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                    val x = fx(up.position.x)
                    val y = fy(up.position.y)
                    val list = card.hide ?: return@awaitEachGesture
                    val i = list.indexOfLast { x in it.x..(it.x + it.w) && y in it.y..(it.y + it.h) }
                    if (i >= 0) card.hide = list.mapIndexed { k, h -> if (k == i) h.copy(on = !h.on) else h }
                }
            },
        ) {
            drawImage(shownImage ?: image, dstOffset = IntOffset(area.left.roundToInt(), area.top.roundToInt()), dstSize = IntSize(area.width.roundToInt(), area.height.roundToInt()), filterQuality = FilterQuality.Medium)
            for (h in areas) {
                val tl = Offset(area.left + h.x * area.width, area.top + h.y * area.height)
                val sz = Size(h.w * area.width, h.h * area.height)
                drawRect(Color.White, tl, sz, style = DrawStroke(3.dp.toPx(), pathEffect = if (h.on) null else PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
                drawRect(if (h.on) accent else Color.Black, tl, sz, style = DrawStroke(1.5.dp.toPx(), pathEffect = if (h.on) null else PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
            }
            drawing?.let { r -> drawRect(accent.copy(alpha = 0.25f), r.topLeft, r.size); drawRect(accent, r.topLeft, r.size, style = DrawStroke(2.dp.toPx())) }
        }
        val err = card.aiError
        when {
            err != null -> AiNote(stringResource(R.string.img_blur_failed, err), true, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            card.hide == null -> AiNote(stringResource(R.string.img_blur_finding_ai), false, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
    }
}

/** A short line over the preview: the AI model at work, or why it could not run. */
@Composable
private fun AiNote(text: String, failed: Boolean, modifier: Modifier = Modifier) {
    val c = Power.colors
    Row(
        modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(12.dp)).background(c.surface.copy(alpha = 0.94f))
            .border(1.dp, if (failed) c.danger else c.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failed) Sym(R.drawable.ic_error, null, tint = c.danger, size = 20.dp)
        else androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
        Spacer(Modifier.width(10.dp))
        Text(bidi(text), style = MaterialTheme.typography.bodyMedium, color = if (failed) c.danger else c.ink)
    }
}

/** Remove background: the model runs once per picture; the colour behind is painted live. */
@Composable
private fun CutoutPreview(card: Card, image: ImageBitmap, o: ImgOptions, ai: (() -> ImageAi)?) {
    val thumb = card.thumb ?: return
    val model = LocalModel.current
    LaunchedEffect(card.key, thumb) {
        if (card.cutout != null || card.aiError != null || ai == null) return@LaunchedEffect
        val r = withContext(Dispatchers.Default) { runCatching { ai().removeBackground(thumb, null) } }
        card.cutout = r.getOrNull()
        // A model that could not run says so (the half-faded picture used to stay up for ever).
        card.aiError = r.exceptionOrNull()?.let { e -> if (e is OcrException.Unsupported) model.str(R.string.img_ai_64bit) else e.message ?: e.javaClass.simpleName }
    }
    val cut = card.cutout
    val cutImage = remember(cut) { cut?.asImageBitmap() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val err = card.aiError
        if (cut == null) AiNote(if (err != null) stringResource(R.string.img_ai_failed, err) else stringResource(R.string.img_ai_working), err != null, Modifier.align(Alignment.Center).zIndex(1f))
        val pad = with(LocalDensity.current) { PREVIEW_PAD.toPx() }
        val area = fittedRect(image.width, image.height, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), pad)
        Canvas(Modifier.fillMaxSize()) {
            val dst = IntOffset(area.left.roundToInt(), area.top.roundToInt())
            val size = IntSize(area.width.roundToInt(), area.height.roundToInt())
            if (cutImage == null) {
                drawImage(image, dstOffset = dst, dstSize = size, alpha = 0.5f)
                return@Canvas
            }
            if (o.bgTransparent) {
                // A checkerboard shows what is transparent.
                val cell = 10.dp.toPx()
                drawRect(Color.White, area.topLeft, area.size)
                clipRect(area.left, area.top, area.right, area.bottom) {
                    var y = area.top
                    var row = 0
                    while (y < area.bottom) {
                        var x = area.left + if (row % 2 == 0) 0f else cell
                        while (x < area.right) { drawRect(Color(0xFFDDDDDD), Offset(x, y), Size(cell, cell)); x += cell * 2 }
                        y += cell
                        row++
                    }
                }
            } else drawRect(Color(o.bgColour), area.topLeft, area.size)
            drawImage(cutImage, dstOffset = dst, dstSize = size, filterQuality = FilterQuality.Medium)
        }
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    val c = Power.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
            Text(bidi(shown), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun Options(tool: ToolId, o: ImgOptions, set: (ImgOptions) -> Unit, chosen: Card?, onLogo: () -> Unit, drawArea: Boolean, setDrawArea: (Boolean) -> Unit, totalBytes: Long = 0, pictures: Int = 0) {
    val c = Power.colors
    val model = LocalModel.current
    when (tool) {
        ToolId.ImgCompress -> {
            // The pictures' size now, before anything runs (UX review finding 13).
            if (totalBytes > 0) Text(
                if (pictures > 1) stringResource(R.string.size_now_many, Files.formatSize(totalBytes, LocalContext.current.resources.configuration.locales[0].language == "ar"))
                else stringResource(R.string.size_now, Files.formatSize(totalBytes, LocalContext.current.resources.configuration.locales[0].language == "ar")),
                style = MaterialTheme.typography.titleSmall, color = c.ink,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
            SectionTitle(stringResource(R.string.img_level))
            // One order for PDF and pictures: Light, Recommended, Strong, then a size of your own (decisions §46).
            Group {
                RadioRow(stringResource(R.string.img_level_light), o.level == Level.Light, { set(o.copy(level = Level.Light)) }, stringResource(R.string.img_level_light_hint))
                GroupDivider()
                RadioRow(stringResource(R.string.img_level_recommended), o.level == Level.Recommended, { set(o.copy(level = Level.Recommended)) }, stringResource(R.string.img_level_recommended_hint))
                GroupDivider()
                RadioRow(stringResource(R.string.img_level_strong), o.level == Level.Strong, { set(o.copy(level = Level.Strong)) }, stringResource(R.string.img_level_strong_hint))
                GroupDivider()
                RadioRow(stringResource(R.string.img_level_target), o.level == Level.Target, { set(o.copy(level = Level.Target)) }, stringResource(R.string.img_level_target_hint))
            }
            if (o.level == Level.Target) {
                Field(o.targetKb.toString(), { v -> v.toIntOrNull()?.let { set(o.copy(targetKb = it.coerceIn(5, 100_000))) } }, stringResource(R.string.img_target_kb), Modifier.padding(16.dp), number = true)
            }
            SectionTitle(stringResource(R.string.img_format))
            Segmented(listOf<Pair<ImgFormat?, String>>(null to stringResource(R.string.img_keep_format), ImgFormat.WEBP to "WebP", ImgFormat.JPG to "JPG"), o.compressTo, { set(o.copy(compressTo = it)) }, Modifier.padding(horizontal = 16.dp))
        }
        ToolId.ImgResize -> {
            Segmented(listOf(ResizeBy.Longest to stringResource(R.string.img_by_longest), ResizeBy.Percent to stringResource(R.string.img_by_percent), ResizeBy.Pixels to stringResource(R.string.img_by_pixels)), o.resizeBy, { set(o.copy(resizeBy = it)) }, Modifier.padding(16.dp))
            when (o.resizeBy) {
                ResizeBy.Longest -> {
                    Field(o.longest.toString(), { v -> v.toIntOrNull()?.let { set(o.copy(longest = it.coerceIn(1, 20_000))) } }, stringResource(R.string.img_longest), Modifier.padding(horizontal = 16.dp), number = true)
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1080, 1600, 1920, 2560).forEach { p -> SecondaryButton("$p", { set(o.copy(longest = p)) }, Modifier.weight(1f)) }
                    }
                }
                ResizeBy.Percent -> {
                    Segmented(listOf(75 to model.str(R.string.img_percent_smaller, 25), 50 to model.str(R.string.img_percent_smaller, 50), 25 to model.str(R.string.img_percent_smaller, 75)), o.percent, { set(o.copy(percent = it)) }, Modifier.padding(horizontal = 16.dp))
                }
                ResizeBy.Pixels -> {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(o.width?.toString() ?: "", { v -> set(o.copy(width = v.toIntOrNull()?.coerceIn(1, 20_000))) }, stringResource(R.string.img_width), Modifier.weight(1f), number = true)
                        Field(o.height?.toString() ?: "", { v -> set(o.copy(height = v.toIntOrNull()?.coerceIn(1, 20_000))) }, stringResource(R.string.img_height), Modifier.weight(1f), number = true)
                    }
                    Group(Modifier.padding(top = 12.dp)) { SwitchRow(stringResource(R.string.img_keep_shape), o.keepShape, { set(o.copy(keepShape = it)) }) }
                }
            }
            if (o.resizeBy != ResizeBy.Percent) Group(Modifier.padding(top = 12.dp)) { SwitchRow(stringResource(R.string.img_no_enlarge), o.noEnlarge, { set(o.copy(noEnlarge = it)) }) }
        }
        ToolId.ImgCrop -> {
            SectionTitle(stringResource(R.string.img_shape))
            val ratios = listOf<Pair<Float?, String>>(null to stringResource(R.string.ed_crop_free), 1f to "1:1", 4f / 3 to "4:3", 3f / 4 to "3:4", 16f / 9 to "16:9", 9f / 16 to "9:16", 4f / 5 to "4:5", 3f / 2 to "3:2")
            // A shape keeps the box's middle and fills as much as it can; Free keeps the box as it is.
            fun pick(r: Float?) {
                val t = chosen?.thumb
                val box = if (t != null) cropFor(o, t.width, t.height) else o.cropFree
                set(if (r == null) o.copy(ratio = null, cropFree = box) else o.copy(ratio = r, cropScale = 1f, cropCx = box.cx, cropCy = box.cy))
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ratios.take(4).forEach { (r, label) -> ChoiceChip(label, o.ratio == r, Modifier.weight(1f)) { pick(r) } }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ratios.drop(4).forEach { (r, label) -> ChoiceChip(label, o.ratio == r, Modifier.weight(1f)) { pick(r) } }
            }
            Hint(stringResource(R.string.ed_crop_hint), Modifier.padding(16.dp))
        }
        ToolId.ImgRotate -> {
            SectionTitle(stringResource(R.string.img_turn))
            Segmented(listOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°"), o.turn, { set(o.copy(turn = it)) }, Modifier.padding(horizontal = 16.dp))
            Group(Modifier.padding(top = 12.dp)) {
                SwitchRow(stringResource(R.string.img_flip_h), o.flipH, { set(o.copy(flipH = it)) })
                GroupDivider()
                SwitchRow(stringResource(R.string.img_flip_v), o.flipV, { set(o.copy(flipV = it)) })
            }
            LabelledSlider(stringResource(R.string.img_angle), o.angle, -45f..45f, "${o.angle.toInt()}°") { set(o.copy(angle = it.toInt().toFloat())) }
        }
        ToolId.ImgConvert -> {
            SectionTitle(stringResource(R.string.img_convert_to))
            val formats = ImgFormat.writableNow.filter { it != ImgFormat.PDF && it != ImgFormat.ICO }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                formats.forEach { f -> ChoiceChip(f.label, o.convertTo == f, Modifier.weight(1f)) { set(o.copy(convertTo = f)) } }
            }
            if (o.convertTo.lossy) LabelledSlider(stringResource(R.string.img_quality), o.quality.toFloat(), 10f..100f, "${o.quality}") { set(o.copy(quality = it.toInt())) }
            if (o.convertTo == ImgFormat.HEIC) Hint(stringResource(R.string.img_heic_note), Modifier.padding(horizontal = 16.dp))
            if (!o.convertTo.alpha) Hint(stringResource(R.string.img_no_alpha), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        ToolId.ImgWatermark -> {
            if (o.markPicture == null) {
                Field(o.markText, { set(o.copy(markText = it)) }, stringResource(R.string.img_mark_text), Modifier.padding(16.dp), placeholder = stringResource(R.string.img_mark_sample))
                SectionTitle(stringResource(R.string.img_mark_colour))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(AColor.WHITE, AColor.BLACK, 0xFFE11D48.toInt(), 0xFFFFD23F.toInt(), 0xFF1473E6.toInt(), 0xFF16A34A.toInt()).forEach { col ->
                        Swatch(Color(col), o.markColour == col, { set(o.copy(markColour = col)) })
                    }
                }
                Group(Modifier.padding(top = 12.dp)) {
                    SwitchRow(stringResource(R.string.img_mark_outline), o.markOutline, { set(o.copy(markOutline = it)) })
                    GroupDivider()
                    SwitchRow(stringResource(R.string.img_mark_shadow), o.markShadow, { set(o.copy(markShadow = it)) })
                    GroupDivider()
                    ListRow(stringResource(R.string.img_mark_picture), icon = R.drawable.ic_add_photo_alternate, onClick = onLogo)
                }
            } else {
                Group(Modifier.padding(16.dp)) { ListRow(stringResource(R.string.img_mark_picture_clear), icon = R.drawable.ic_text_fields, onClick = { set(o.copy(markPicture = null)) }) }
            }
            SectionTitle(stringResource(R.string.img_mark_position))
            Hint(stringResource(R.string.img_mark_drag_hint), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            val grid = listOf(0.12f, 0.5f, 0.88f)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                grid.forEach { y ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        grid.forEach { x ->
                            val on = !o.markTile && o.markX == x && o.markY == y
                            Box(Modifier.size(width = 56.dp, height = 36.dp).clip(RoundedCornerShape(8.dp)).background(if (on) c.accent else c.surface2).border(1.dp, c.line, RoundedCornerShape(8.dp)).clickable { set(o.copy(markX = x, markY = y, markTile = false)) })
                        }
                    }
                }
            }
            LabelledSlider(stringResource(R.string.img_mark_size), o.markSize, 0.05f..1f, "${(o.markSize * 100).toInt()}%") { set(o.copy(markSize = it)) }
            LabelledSlider(stringResource(R.string.img_mark_opacity), o.markOpacity, 0.1f..1f, "${(o.markOpacity * 100).toInt()}%") { set(o.copy(markOpacity = it)) }
            LabelledSlider(stringResource(R.string.img_mark_rotation), o.markRotation, -90f..90f, "${o.markRotation.toInt()}°") { set(o.copy(markRotation = it.toInt().toFloat())) }
            Group { SwitchRow(stringResource(R.string.img_mark_tile), o.markTile, { set(o.copy(markTile = it)) }) }
        }
        ToolId.ImgUpscale -> {
            SectionTitle(stringResource(R.string.img_upscale_by))
            Segmented(listOf(2 to stringResource(R.string.img_upscale_2), 4 to stringResource(R.string.img_upscale_4)), o.upscale, { set(o.copy(upscale = it)) }, Modifier.padding(horizontal = 16.dp))
            Hint(stringResource(R.string.img_upscale_note), Modifier.padding(16.dp))
        }
        ToolId.ImgRemoveBg -> {
            Segmented(listOf(true to stringResource(R.string.img_bg_transparent), false to stringResource(R.string.img_bg_colour)), o.bgTransparent, { set(o.copy(bgTransparent = it)) }, Modifier.padding(16.dp))
            if (!o.bgTransparent) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(AColor.WHITE, AColor.BLACK, 0xFF1473E6.toInt(), 0xFF16A34A.toInt(), 0xFFE11D48.toInt(), 0xFFF2F2F2.toInt()).forEach { col -> Swatch(Color(col), o.bgColour == col, { set(o.copy(bgColour = col)) }) }
            }
            Hint(stringResource(R.string.img_bg_note), Modifier.padding(16.dp))
        }
        ToolId.ImgBlur -> {
            SectionTitle(stringResource(R.string.img_blur_find))
            Group {
                SwitchRow(stringResource(R.string.img_blur_faces), o.findFaces, { set(o.copy(findFaces = it)) })
                GroupDivider()
                SwitchRow(stringResource(R.string.img_blur_plates), o.findPlates, { set(o.copy(findPlates = it)) })
            }
            SectionTitle(stringResource(R.string.img_blur_how))
            Segmented(listOf(HideStyle.Blur to stringResource(R.string.img_blur_blur), HideStyle.Pixels to stringResource(R.string.img_blur_pixels), HideStyle.Box to stringResource(R.string.img_blur_box)), o.hideStyle, { set(o.copy(hideStyle = it)) }, Modifier.padding(horizontal = 16.dp))
            SectionTitle(stringResource(R.string.img_blur_by_hand))
            Group { SwitchRow(stringResource(R.string.img_blur_draw), drawArea, setDrawArea) }
            Hint(stringResource(R.string.img_blur_tap_hint), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            Hint(stringResource(R.string.img_blur_note), Modifier.padding(16.dp))
        }
        ToolId.ImgInfo -> InfoPanel(chosen)
        else -> {}
    }
}

@Composable
private fun ChoiceChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Power.colors
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent else c.surface).border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(bidi(label), style = MaterialTheme.typography.labelLarge, color = if (on) c.accentInk else c.ink, maxLines = 1)
    }
}

/** Image info: the chosen picture's facts and its camera details, with a warning when it has a location. */
@Composable
private fun InfoPanel(card: Card?) {
    val context = LocalContext.current
    val c = Power.colors
    val f = card?.facts ?: return
    val meta by produceState<Pair<List<Pair<String, String>>, Boolean>?>(null, card.key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(Files.workDir(context), "meta-${System.nanoTime()}")
                Files.copyIn(context, card.uri, tmp)
                try { ImageExtras.metadata(tmp) to ImageExtras.hasLocation(tmp) } finally { tmp.delete() }
            }.getOrNull()
        }
    }
    val arabic = context.resources.configuration.locales[0].language == "ar"
    Group(Modifier.padding(16.dp)) {
        ListRow(stringResource(R.string.img_info_format), trailing = { Text(f.format.label, color = c.muted) })
        GroupDivider()
        ListRow(stringResource(R.string.img_info_size), trailing = { Text(ltr("${f.width} × ${f.height}"), color = c.muted) })
        GroupDivider()
        ListRow(stringResource(R.string.img_info_file), trailing = { Text(Files.formatSize(f.bytes, arabic), color = c.muted) })
    }
    val m = meta ?: return
    if (m.second) Hint(stringResource(R.string.img_info_gps), Modifier.padding(horizontal = 16.dp))
    if (m.first.isEmpty()) Hint(stringResource(R.string.img_info_none), Modifier.padding(16.dp))
    else Group(Modifier.padding(16.dp)) {
        m.first.forEachIndexed { i, (k, v) ->
            if (i > 0) GroupDivider()
            ListRow(metaLabel(k), description = bidi(v))
        }
    }
}

/** The engine's English labels for camera details, in the interface's language. */
@Composable
private fun metaLabel(key: String): String = when (key) {
    "Make" -> stringResource(R.string.img_meta_make)
    "Model" -> stringResource(R.string.img_meta_model)
    "Lens" -> stringResource(R.string.img_meta_lens)
    "Taken" -> stringResource(R.string.img_meta_taken)
    "Changed" -> stringResource(R.string.img_meta_changed)
    "Exposure" -> stringResource(R.string.img_meta_exposure)
    "Aperture" -> stringResource(R.string.img_meta_aperture)
    "ISO" -> "ISO"
    "Focal length" -> stringResource(R.string.img_meta_focal)
    "Software" -> stringResource(R.string.img_meta_software)
    "Artist" -> stringResource(R.string.img_meta_artist)
    "Copyright" -> stringResource(R.string.img_meta_copyright)
    "Description" -> stringResource(R.string.img_meta_description)
    "Owner" -> stringResource(R.string.img_meta_owner)
    "Location" -> stringResource(R.string.img_meta_location)
    else -> key
}

/** The folder names results go into (Pictures › HonoKit › …), kept in English as folder names. */
object ImageJobsFolder {
    fun of(tool: ToolId): String = when (tool) {
        ToolId.ImgCompress -> "Compressed"
        ToolId.ImgResize -> "Resized"
        ToolId.ImgCrop -> "Cropped"
        ToolId.ImgConvert -> "Converted"
        ToolId.ImgRotate -> "Rotated"
        ToolId.ImgWatermark -> "Watermarked"
        ToolId.ImgInfo -> "Cleaned"
        ToolId.ImgUpscale -> "Upscaled"
        ToolId.ImgRemoveBg -> "Cut out"
        else -> "Hidden"
    }
}
