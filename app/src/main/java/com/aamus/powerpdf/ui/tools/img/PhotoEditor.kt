package com.aamus.powerpdf.ui.tools.img

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.alpha
import com.aamus.powerpdf.ui.edgeFade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.engine.img.Adjust
import com.aamus.powerpdf.engine.img.CropBox
import com.aamus.powerpdf.engine.img.CropMath
import com.aamus.powerpdf.engine.img.EditFilter
import com.aamus.powerpdf.engine.img.EditLayer
import com.aamus.powerpdf.engine.img.EditRender
import com.aamus.powerpdf.engine.img.EditState
import com.aamus.powerpdf.engine.img.ImageExtras
import com.aamus.powerpdf.engine.img.ImageKit
import com.aamus.powerpdf.engine.img.ImgFormat
import com.aamus.powerpdf.engine.img.Stroke
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.Group
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.SecondaryButton
import com.aamus.powerpdf.ui.SectionTitle
import com.aamus.powerpdf.ui.SwitchRow
import com.aamus.powerpdf.ui.Swatch
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.ToolCatalog
import com.aamus.powerpdf.ui.ToolIcon
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.theme.Power
import com.aamus.powerpdf.ui.tools.ToolFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke

/**
 * The Photo editor on the phone (decisions §37, §40): one picture, seven tabs — Adjust, Filters,
 * Crop & turn, Text, Stickers, Draw, Frame — with Undo, Redo, Compare and Save a copy.
 *
 * Only the photo itself (colours, turn, crop) is rendered into a bitmap, from a 1,600 px copy, one
 * render at a time; drawing, text, stickers and the frame are drawn live on top in the same
 * fractions, so a finger never waits. Saving draws everything on the full picture
 * (engine/img/EditRender), so the file looks as the screen did.
 */

private enum class Tab(val icon: Int, val label: Int) {
    Adjust(R.drawable.ic_tune, R.string.ed_adjust),
    Filters(R.drawable.ic_auto_fix_high, R.string.ed_filters),
    Crop(R.drawable.ic_crop_rotate, R.string.ed_crop),
    Text(R.drawable.ic_text_fields, R.string.ed_text),
    Stickers(R.drawable.ic_star, R.string.ed_stickers),
    Draw(R.drawable.ic_draw, R.string.ed_draw),
    Frame(R.drawable.ic_fit_page_width, R.string.ed_frame),
}

/** Stickers by kind, as a phone's emoji keyboard groups them (emoji up to Unicode 13). */
private class StickerSet(val icon: String, val label: Int, list: String) {
    val items = list.split(' ').filter { it.isNotBlank() }
}

private val STICKER_SETS = listOf(
    StickerSet("😀", R.string.ed_st_smileys, "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 😎 🥳 🤗 🤔 🤫 🙄 😏 😴 😌 😮 😲 😳 🥺 😢 😭 😤 😡 🤯 😱 🤢 🤠 😈 👻 💀 🤖 👽"),
    StickerSet("👍", R.string.ed_st_people, "👍 👎 👌 ✌️ 🤞 🤟 🤘 👏 🙌 👐 🤲 🙏 ✍️ 💪 👋 🤙 ☝️ 👆 👇 👉 👈 ✊ 👊 🤝 👀 👑 🎓 💍 💄 👶 🧕 👳 🧔 👩 👨 👵 👴 🧒"),
    StickerSet("❤️", R.string.ed_st_hearts, "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 ✨ ⭐ 🌟 💫 🔥 💯 ✅ ❌ ❗ ❓ 💡 🎵 🎶 💤 💬 💭 ➡️ ⬅️ ⬆️ ⬇️ ☑️ 🔴 🟢 🔵"),
    StickerSet("🌸", R.string.ed_st_nature, "🌹 🌸 🌺 🌻 🌼 🌷 🌱 🌿 🍀 🍁 🍂 🌴 🌵 🌙 ☀️ ⛅ 🌈 ❄️ 🌊 🐪 🐎 🦅 🐱 🐶 🐰 🦋 🐝 🐢 🐬 🐠 🦁 🐼 🦄 🐧"),
    StickerSet("☕", R.string.ed_st_food, "☕ 🍵 🧃 🥤 🍰 🎂 🧁 🍩 🍪 🍫 🍬 🍭 🍦 🍕 🍔 🍟 🌯 🥗 🍉 🍓 🍒 🍇 🍊 🍋 🍌 🍎 🥑 🌶️ 🍞 🧀 🍯 🥐"),
    StickerSet("🎉", R.string.ed_st_fun, "🎉 🎊 🎈 🎁 🎀 🏆 🥇 ⚽ 🏀 🎮 🎨 📷 🎬 🎤 🎧 ✈️ 🚗 🚀 ⛵ 🏖️ 🏝️ 🏔️ 🕌 🕋 🏙️ 🌍 🗺️ ⛺ 🎡 🎆 🎇"),
    StickerSet("💡", R.string.ed_st_objects, "📱 💻 ⌚ 📚 📖 ✏️ 📌 📍 🔑 🔒 💼 🛍️ 💰 💎 🕯️ 🪔 ⏰ 📅 📢 🎯 🧭 ☂️ 👓 🕶️ 🎒 🧳 📝 📎"),
    StickerSet("🇦🇪", R.string.ed_st_flags, "🇦🇪 🇸🇦 🇪🇬 🇯🇴 🇰🇼 🇶🇦 🇧🇭 🇴🇲 🇱🇧 🇸🇾 🇮🇶 🇵🇸 🇲🇦 🇩🇿 🇹🇳 🇱🇾 🇸🇩 🇾🇪 🇲🇷 🇬🇧 🇺🇸 🇫🇷 🇩🇪 🇮🇹 🇪🇸 🇹🇷 🇮🇳 🇵🇰 🏳️ 🏁"),
)

private val COLOURS = listOf(AColor.WHITE, AColor.BLACK, 0xFFE11D48.toInt(), 0xFFFFD23F.toInt(), 0xFF16A34A.toInt(), 0xFF1473E6.toInt(), 0xFF7C3AED.toInt())

private var layerIds = 0

/** The stickers used last, newest first (kept on this phone only). */
private object RecentStickers {
    private const val KEY = "recent_stickers"
    fun read(context: Context): List<String> = context.getSharedPreferences("photo_editor", Context.MODE_PRIVATE).getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    fun add(context: Context, s: String): List<String> {
        val next = (listOf(s) + read(context).filter { it != s }).take(24)
        context.getSharedPreferences("photo_editor", Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString("\n")).apply()
        return next
    }
}

/** The photo's part of an edit: what changes the rendered bitmap (marks are drawn live). */
private fun photoKey(e: EditState, uncropped: Boolean) =
    e.copy(layers = emptyList(), strokes = emptyList(), frame = 0f, frameColour = 0, radius = 0f, ratio = null, crop = if (uncropped) CropBox.Full else e.crop)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoEditor(inputs: List<Uri>) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Power.colors
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(ImgFormat.JPG) }
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var edit by remember { mutableStateOf(EditState()) }
    val past = remember { ArrayList<EditState>() }
    val future = remember { ArrayList<EditState>() }
    var historyVersion by remember { mutableIntStateOf(0) }
    var lastKey by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var tab by remember { mutableStateOf(Tab.Adjust) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var brushColour by remember { mutableIntStateOf(0xFFE11D48.toInt()) }
    var brushSize by remember { mutableStateOf(0.012f) }
    var saving by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf<ImgFormat?>(null) }
    // A one-time request to put the cursor in the text box (Add text, or a tap on the selected text).
    var wantTyping by remember { mutableStateOf(false) }
    // What was last saved (or opened): Back asks before throwing away anything newer.
    var savedEdit by remember { mutableStateOf(EditState()) }
    var askLeave by remember { mutableStateOf(false) }
    val typing = WindowInsets.isImeVisible

    /**
     * Changes the edit. Moves of the same control within a second are one undo step; a finger
     * gesture (key "g:…") is always one step, however long it lasts.
     */
    fun change(next: EditState, key: String = "") {
        val now = System.currentTimeMillis()
        val last = lastKey
        val coalesce = key.isNotEmpty() && last?.first == key && (key.startsWith("g:") || now - last.second < 1000)
        if (!coalesce) {
            past.add(edit)
            if (past.size > 50) past.removeAt(0)
            future.clear()
        }
        lastKey = if (key.isNotEmpty()) key to now else null
        edit = next
        comparing = false
        historyVersion++
    }

    /** Selects a layer; text left empty is removed when it loses the selection. */
    fun select(id: Int?) {
        val old = edit.layers.firstOrNull { it.id == selected }
        if (old != null && old.id != id && !old.sticker && old.text.isBlank()) change(edit.copy(layers = edit.layers.filter { it.id != old.id }))
        selected = id
    }

    fun open(u: Uri) {
        scope.launch {
            val picture = withContext(Dispatchers.IO) {
                runCatching {
                    val facts = ImageKit.facts(context.contentResolver, u, Files.size(context, u))
                    Triple(ImageKit.decode(context.contentResolver, u, 1600), facts?.format ?: ImgFormat.JPG, Files.displayName(context, u))
                }.getOrNull()
            }
            if (picture == null) {
                model.say(model.str(R.string.img_cannot_read))
                return@launch
            }
            uri = u
            base = picture.first
            format = picture.second
            name = picture.third
            edit = EditState()
            savedEdit = edit
            selected = null
            past.clear()
            future.clear()
            historyVersion++
        }
    }
    LaunchedEffect(Unit) { inputs.firstOrNull()?.let { open(it) } }

    // The photo, rendered one at a time: while a slider moves, renders in between are skipped
    // (they used to pile up, and drawing showed late). Crop shows the whole picture under the box.
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(base) {
        val b = base ?: return@LaunchedEffect
        photo = null
        snapshotFlow { photoKey(edit, tab == Tab.Crop) to comparing }
            .distinctUntilChanged()
            .conflate()
            .collect { (key, original) ->
                withContext(Dispatchers.Default) { runCatching { if (original) b else EditRender.photo(b, key) }.getOrNull() }?.let { photo = it }
            }
    }

    // Small square previews of every filter, with the current adjustments, for the Filters tab.
    val thumbs by produceState<Map<EditFilter, ImageBitmap>?>(null, base, edit.adjust) {
        val b = base ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val side = min(b.width, b.height)
                val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side), 192, 192, true)
                EditFilter.entries.associateWith { f ->
                    val out = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(EditRender.colourMatrix(f, edit.adjust)) }
                    android.graphics.Canvas(out).drawBitmap(small, 0f, 0f, paint)
                    out.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    fun save() {
        val u = uri ?: return
        select(null)
        // The edit as saved: later changes while it is written are not in the file.
        val saving0 = edit
        saving = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val full = ImageKit.decode(context.contentResolver, u)
                    val out = EditRender.render(full, saving0)
                    // Rounded corners keep their transparency in PNG and WebP.
                    val target = saveAs ?: sameFormat(format).let { if (saving0.radius > 0f && !it.alpha) ImgFormat.PNG else it }
                    val work = java.io.File(Files.workDir(context), "edit-${System.nanoTime()}.heic")
                    val bytes = ImageKit.encode(out, target, 92, work = work)
                    val fileName = "${name.substringBeforeLast('.')} (${model.str(R.string.ed_suffix)}).${target.ext}"
                    Files.saveToPictures(context, bytes, "Edited", fileName, target.mime) to target
                }
                savedEdit = saving0
                // The results sheet, as the other tools: Open (the image viewer) and Share (§41, §42).
                // With the photo it came from, for Before / After on the result screen (decisions §48).
                model.showResult(ToolId.ImgEditor, listOf(com.aamus.powerpdf.SavedItem(result.first, source = u)), "Pictures › HonoKit › Edited", mime = result.second.mime, again = false, under = com.aamus.powerpdf.ResultUnder.Editor)
            } catch (e: Throwable) {
                model.say(e.message ?: e.toString())
            } finally {
                saving = false
            }
        }
    }

    val info = ToolCatalog.of(ToolId.ImgEditor)
    if (base == null) {
        ToolFrame(ToolId.ImgEditor, bottom = { PrimaryButton(stringResource(R.string.ed_choose), { model.choose(com.aamus.powerpdf.data.Choosing.Kind.Image, multiple = false, tool = ToolId.ImgEditor) { p -> model.withUris(p) { open(it.first()) } } }, Modifier.fillMaxWidth(), icon = R.drawable.ic_photo_library) }) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                ToolIcon(info, 72)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.tool_img_editor_desc), style = MaterialTheme.typography.bodyLarge, color = c.muted, textAlign = TextAlign.Center)
            }
        }
        return
    }

    // Back with unsaved edits asks first, from the bar and from the phone's own Back.
    BackHandler(enabled = edit != savedEdit) { askLeave = true }
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.ed_discard_title)) },
            text = { Text(stringResource(R.string.ed_discard_body), style = MaterialTheme.typography.bodyMedium, color = c.muted) },
            confirmButton = { DialogButton(stringResource(R.string.ed_discard), { askLeave = false; model.pop() }, danger = true) },
            dismissButton = { DialogButton(stringResource(R.string.cancel), { askLeave = false }) },
            containerColor = c.surface,
        )
    }

    // The history lists are plain lists; reading the version makes the buttons follow them.
    val canUndo = historyVersion >= 0 && past.isNotEmpty()
    val canRedo = historyVersion >= 0 && future.isNotEmpty()
    ToolFrame(
        ToolId.ImgEditor,
        onBack = { if (edit != savedEdit) askLeave = true else model.pop() },
        subtitle = name,
        actions = {
            IconAction(R.drawable.ic_undo, stringResource(R.string.undo), {
                if (past.isNotEmpty()) { future.add(0, edit); edit = past.removeAt(past.lastIndex); lastKey = null; comparing = false; historyVersion++ }
            }, enabled = canUndo)
            IconAction(R.drawable.ic_redo, stringResource(R.string.redo), {
                if (future.isNotEmpty()) { past.add(edit); edit = future.removeAt(0); lastKey = null; comparing = false; historyVersion++ }
            }, enabled = canRedo)
            IconAction(R.drawable.ic_save, stringResource(R.string.ed_save), { save() }, enabled = !saving)
        },
        bottom = {
            // The tabs step aside while typing; the picture and the text box need the room.
            if (typing) return@ToolFrame
            // All seven tabs share the width: a scrolling row hid Draw and Frame off the edge.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Tab.entries.forEach { t ->
                    val on = t == tab
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) c.accentSoft else Color.Transparent)
                            .clickable { if (t != tab) select(null); tab = t; comparing = false }.padding(horizontal = 2.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Sym(t.icon, null, tint = if (on) c.accent else c.muted, size = 22.dp)
                        Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall, color = if (on) c.accent else c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
    ) {
        @Composable
        fun TheStage(modifier: Modifier) = Stage(
            modifier, photo ?: base!!, edit, tab, comparing, selected, brushColour, brushSize,
            onSelect = ::select,
            onChange = ::change,
            onEditText = { wantTyping = true },
            onCompare = { comparing = !comparing },
        )

        @Composable
        fun ThePanel(modifier: Modifier) = Column(modifier.background(c.surface).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Panel(
                tab, edit, base!!, ::change, selected, ::select, { wantTyping = true }, wantTyping, typing, { wantTyping = false },
                brushColour, { brushColour = it }, brushSize, { brushSize = it }, saveAs, { saveAs = it }, thumbs,
            )
        }
        // Sideways the panel stands beside the photo: below it, it left no room for the photo at
        // all (decisions §43).
        if (com.aamus.powerpdf.ui.isCompactHeight()) {
            Row(Modifier.fillMaxSize()) {
                TheStage(Modifier.weight(1f).fillMaxHeight())
                androidx.compose.material3.VerticalDivider(color = c.line)
                ThePanel(Modifier.width(360.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TheStage(Modifier.weight(1f).fillMaxWidth())
                ThePanel(Modifier.fillMaxWidth().heightIn(max = 300.dp))
            }
        }
    }
}

/** Where a layer's corners and handles are on the screen. */
private class LayerGeometry(l: EditLayer, area: Rect, pad: Float) {
    val centre = Offset(area.left + l.x * area.width, area.top + l.y * area.height)
    val w = l.width * area.width
    val h = w * l.stamp.height / l.stamp.width
    private val a = l.rotate * PI.toFloat() / 180
    private val cosA = cos(a)
    private val sinA = sin(a)

    /** A point in the layer's own frame (centre at 0, 0, before turning) on the screen. */
    fun toScreen(x: Float, y: Float) = Offset(centre.x + x * cosA - y * sinA, centre.y + x * sinA + y * cosA)
    val corners = listOf(toScreen(-w / 2 - pad, -h / 2 - pad), toScreen(w / 2 + pad, -h / 2 - pad), toScreen(w / 2 + pad, h / 2 + pad), toScreen(-w / 2 - pad, h / 2 + pad))
    val delete = corners[0]
    val resize = corners[2]

    fun contains(p: Offset, slack: Float): Boolean {
        val dx = p.x - centre.x
        val dy = p.y - centre.y
        val lx = dx * cosA + dy * sinA
        val ly = -dx * sinA + dy * cosA
        return abs(lx) <= w / 2 + slack && abs(ly) <= h / 2 + slack
    }
}

/** A turn near a quarter snaps to it, as in other editors. */
private fun snapTurn(deg: Float): Float {
    val q = (deg / 90f).roundToInt() * 90f
    return if (abs(deg - q) < 4f) q else deg
}

private enum class LayerGrip { None, Move, Resize, Delete }

/**
 * The picture as it will be saved. Crop: drag the box. Draw: the line shows under the finger.
 * Text and stickers: tap to select (tap the selected text to type), drag to move, pinch or drag
 * the corner handle to resize and turn, the × to delete; tap empty space to deselect.
 */
@Composable
private fun Stage(
    modifier: Modifier,
    photo: Bitmap,
    e: EditState,
    tab: Tab,
    comparing: Boolean,
    selected: Int?,
    brushColour: Int,
    brushSize: Float,
    onSelect: (Int?) -> Unit,
    onChange: (EditState, String) -> Unit,
    onEditText: () -> Unit,
    onCompare: () -> Unit,
) {
    val c = Power.colors
    val density = LocalDensity.current
    val image = remember(photo) { photo.asImageBitmap() }
    val live = remember { mutableStateListOf<Pair<Float, Float>>() }
    var cropActive by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(e)
    val chosen by rememberUpdatedState(selected)
    val brush by rememberUpdatedState(brushColour to brushSize)
    val select by rememberUpdatedState(onSelect)
    val push by rememberUpdatedState(onChange)
    val editText by rememberUpdatedState(onEditText)
    val accent = c.accent
    val accentInk = c.accentInk
    val danger = Color(0xFFE11D48)

    BoxWithConstraints(modifier.background(c.sunken)) {
        val pad = with(density) { (if (tab == Tab.Crop) 24.dp else 16.dp).toPx() }
        val area = fittedRect(photo.width, photo.height, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), pad)
        val areaNow by rememberUpdatedState(area)
        val layerPad = with(density) { 6.dp.toPx() }

        val gestures = when {
            comparing -> Modifier
            tab == Tab.Crop -> Modifier.cropGestures(
                { current.crop }, { current.ratio }, { areaNow }, { cropActive = it },
                { b, g -> push(current.copy(crop = b), "g:crop-$g") },
            )
            tab == Tab.Draw -> Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val a = areaNow
                    fun frac(o: Offset) = ((o.x - a.left) / a.width) to ((o.y - a.top) / a.height)
                    live.clear()
                    live.add(frac(down.position))
                    down.consume()
                    drag(down.id) { ch -> live.add(frac(ch.position)); ch.consume() }
                    val (colour, size) = brush
                    push(current.copy(strokes = current.strokes + Stroke(live.toList(), colour, size)), "")
                    live.clear()
                }
            }
            tab == Tab.Text || tab == Tab.Stickers -> Modifier.pointerInput(tab) {
                val reach = 24.dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val a = areaNow
                    val start = current
                    val sel = start.layers.firstOrNull { it.id == chosen }
                    val p = down.position
                    var grip = LayerGrip.None
                    if (sel != null) {
                        val g = LayerGeometry(sel, a, layerPad)
                        if ((p - g.delete).getDistance() < reach) grip = LayerGrip.Delete
                        else if ((p - g.resize).getDistance() < reach) grip = LayerGrip.Resize
                    }
                    val hit = if (grip == LayerGrip.None) start.layers.lastOrNull { LayerGeometry(it, a, layerPad).contains(p, layerPad) } else sel
                    if (grip == LayerGrip.None && hit != null) grip = LayerGrip.Move
                    // A pinch works on the selected layer even when the fingers are beside it.
                    val target = hit ?: sel
                    var working = target
                    val key = "g:layer-${System.nanoTime()}"
                    var moved = false
                    fun commit() {
                        val w = working ?: return
                        push(start.copy(layers = start.layers.map { if (it.id == w.id) w else it }), key)
                    }
                    if (hit != null && hit.id != chosen && grip == LayerGrip.Move) select(hit.id)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val w0 = working ?: continue
                        if (pressed.size >= 2) {
                            val zoom = ev.calculateZoom()
                            val turn = ev.calculateRotation()
                            val pan = ev.calculatePan()
                            working = w0.copy(
                                width = (w0.width * zoom).coerceIn(0.03f, 3f),
                                rotate = w0.rotate + turn,
                                x = (w0.x + pan.x / a.width).coerceIn(0f, 1f),
                                y = (w0.y + pan.y / a.height).coerceIn(0f, 1f),
                            )
                            moved = true
                            commit()
                            ev.changes.forEach { it.consume() }
                        } else {
                            val ch = pressed[0]
                            if (!moved && (ch.position - p).getDistance() < slop) continue
                            when (grip) {
                                LayerGrip.Move -> {
                                    val d = ch.positionChange()
                                    working = w0.copy(x = (w0.x + d.x / a.width).coerceIn(0f, 1f), y = (w0.y + d.y / a.height).coerceIn(0f, 1f))
                                }
                                LayerGrip.Resize -> {
                                    val t = target!!
                                    val centre = LayerGeometry(t, a, layerPad).centre
                                    val v0 = p - centre
                                    val v = ch.position - centre
                                    val scale = v.getDistance() / v0.getDistance().coerceAtLeast(1f)
                                    val turn = (atan2(v.y, v.x) - atan2(v0.y, v0.x)) * 180 / PI.toFloat()
                                    working = w0.copy(width = (t.width * scale).coerceIn(0.03f, 3f), rotate = snapTurn(t.rotate + turn))
                                }
                                else -> continue
                            }
                            moved = true
                            commit()
                            ch.consume()
                        }
                    }
                    val last = working
                    if (moved && last != null && last.rotate != snapTurn(last.rotate)) {
                        working = last.copy(rotate = snapTurn(last.rotate))
                        commit()
                    }
                    if (!moved) when {
                        grip == LayerGrip.Delete && sel != null -> { push(start.copy(layers = start.layers.filter { it.id != sel.id }), ""); select(null) }
                        hit != null && hit.id == chosen && !hit.sticker -> editText()
                        hit != null -> select(hit.id)
                        else -> select(null)
                    }
                }
            }
            else -> Modifier
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val topLeft = IntOffset(area.left.roundToInt(), area.top.roundToInt())
            val size = IntSize(area.width.roundToInt(), area.height.roundToInt())
            if (comparing || tab == Tab.Crop) {
                drawImage(image, dstOffset = topLeft, dstSize = size, filterQuality = FilterQuality.Medium)
                if (!comparing) drawCropBox(e.crop, area, cropActive)
                return@Canvas
            }
            val short = min(area.width, area.height)
            val radius = e.radius / 100f * short
            clipPath(Path().apply { addRoundRect(RoundRect(area, CornerRadius(radius))) }) {
                drawImage(image, dstOffset = topLeft, dstSize = size, filterQuality = FilterQuality.Medium)
                for (s in e.strokes) drawLine(s.points, s.colour, s.size, area)
                if (live.isNotEmpty()) drawLine(live, brushColour, brushSize, area)
                for (l in e.layers) drawLayer(l, area)
                if (e.frame > 0f) {
                    val f = e.frame / 100f * short
                    drawRoundRect(Color(e.frameColour), area.topLeft, area.size, CornerRadius(radius), style = DrawStroke(f * 2))
                }
            }
            // The selected text or sticker: an outline, × to delete, and a handle to resize and turn.
            val sel = e.layers.firstOrNull { it.id == selected }
            if (sel != null && (tab == Tab.Text || tab == Tab.Stickers)) {
                val g = LayerGeometry(sel, area, layerPad)
                val outline = Path().apply { moveTo(g.corners[0].x, g.corners[0].y); g.corners.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(outline, Color.White, style = DrawStroke(3.dp.toPx()))
                drawPath(outline, accent, style = DrawStroke(1.5.dp.toPx()))
                val r = 12.dp.toPx()
                drawCircle(danger, r, g.delete)
                drawCircle(Color.White, r, g.delete, style = DrawStroke(1.5.dp.toPx()))
                val x = 4.5.dp.toPx()
                drawLine(Color.White, g.delete + Offset(-x, -x), g.delete + Offset(x, x), 2.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White, g.delete + Offset(-x, x), g.delete + Offset(x, -x), 2.dp.toPx(), StrokeCap.Round)
                drawCircle(accent, r, g.resize)
                drawCircle(Color.White, r, g.resize, style = DrawStroke(1.5.dp.toPx()))
                // A curved arrow: resize and turn.
                drawArc(accentInk, -20f, 230f, false, g.resize - Offset(x, x), Size(x * 2, x * 2), style = DrawStroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }

        // Compare: a toggle, as asked — tap to see the original, tap again to go back.
        val compare = stringResource(R.string.ed_compare)
        val on = stringResource(R.string.ed_compare_on)
        val off = stringResource(R.string.ed_compare_off)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(if (comparing) c.accent else c.surface.copy(alpha = 0.92f))
                .border(1.dp, if (comparing) c.accent else c.line, CircleShape)
                .clickable(onClick = onCompare)
                .semantics { contentDescription = compare; stateDescription = if (comparing) on else off; role = Role.Switch },
            contentAlignment = Alignment.Center,
        ) { Sym(R.drawable.ic_visibility, null, tint = if (comparing) c.accentInk else c.ink, size = 22.dp) }
        if (comparing) {
            Text(
                compare,
                style = MaterialTheme.typography.labelLarge,
                color = c.ink,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(c.surface.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** A drawn line, in fractions of [area] (as EditRender draws it). */
private fun DrawScope.drawLine(points: List<Pair<Float, Float>>, colour: Int, size: Float, area: Rect) {
    if (points.isEmpty()) return
    val width = size * area.width
    fun at(p: Pair<Float, Float>) = Offset(area.left + p.first * area.width, area.top + p.second * area.height)
    if (points.size == 1) {
        drawCircle(Color(colour), width / 2, at(points[0]))
        return
    }
    val path = Path()
    at(points[0]).let { path.moveTo(it.x, it.y) }
    for (p in points.drop(1)) at(p).let { path.lineTo(it.x, it.y) }
    drawPath(path, Color(colour), style = DrawStroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Text or a sticker, as EditRender draws it. */
private fun DrawScope.drawLayer(l: EditLayer, area: Rect) {
    val w = l.width * area.width
    val h = w * l.stamp.height / l.stamp.width
    val cx = area.left + l.x * area.width
    val cy = area.top + l.y * area.height
    rotate(l.rotate, Offset(cx, cy)) {
        drawImage(l.stamp.asImageBitmap(), dstOffset = IntOffset((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt()), dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.Medium)
    }
}

@Composable
private fun LabelSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    val c = Power.colors
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
            Text(bidi(shown), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        // A two-way setting (below and above 0) gets one track colour: a half-filled track read as 50%.
        val colours = if (range.start < 0f) SliderDefaults.colors().let { it.copy(activeTrackColor = it.inactiveTrackColor) } else SliderDefaults.colors()
        Slider(value = value, onValueChange = onChange, valueRange = range, colors = colours)
    }
}

/** An icon over a short label; [on] marks a setting that is switched on (mirror, upside down). */
@Composable
private fun ActionTile(icon: Int, label: String, on: Boolean, modifier: Modifier = Modifier, iconTurn: Float = 0f, onClick: () -> Unit) {
    val c = Power.colors
    Column(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.accentSoft else c.surface).border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.rotate(iconTurn)) { Sym(icon, null, tint = if (on) c.accent else c.ink, size = 22.dp) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Power.colors
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent else c.surface).border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(bidi(label), style = MaterialTheme.typography.labelLarge, color = if (on) c.accentInk else c.ink, maxLines = 1) }
}

@Composable
private fun Panel(
    tab: Tab,
    e: EditState,
    base: Bitmap,
    change: (EditState, String) -> Unit,
    selected: Int?,
    select: (Int?) -> Unit,
    focusText: () -> Unit,
    wantTyping: Boolean,
    typing: Boolean,
    typingStarted: () -> Unit,
    brushColour: Int,
    setBrushColour: (Int) -> Unit,
    brushSize: Float,
    setBrushSize: (Float) -> Unit,
    saveAs: ImgFormat?,
    setSaveAs: (ImgFormat?) -> Unit,
    thumbs: Map<EditFilter, ImageBitmap>?,
) {
    val c = Power.colors
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    when (tab) {
        Tab.Adjust -> {
            val a = e.adjust
            fun set(key: String, n: Adjust) = change(e.copy(adjust = n), "adjust-$key")
            // One adjustment at a time (decisions §46; UX review finding 20, as Google Photos and
            // Snapseed): chips choose it, one slider sets it, and the photo keeps the height. A
            // dot marks an adjustment that is not at its middle.
            data class Knob(val key: String, val label: Int, val value: Int, val range: ClosedFloatingPointRange<Float>, val unit: String, val with: (Int) -> Adjust)
            val knobs = listOf(
                Knob("b", R.string.ed_brightness, a.brightness, -100f..100f, "") { a.copy(brightness = it) },
                Knob("c", R.string.ed_contrast, a.contrast, -100f..100f, "") { a.copy(contrast = it) },
                Knob("s", R.string.ed_saturation, a.saturation, -100f..100f, "") { a.copy(saturation = it) },
                Knob("w", R.string.ed_warmth, a.warmth, -100f..100f, "") { a.copy(warmth = it) },
                Knob("h", R.string.ed_hue, a.hue, -180f..180f, "°") { a.copy(hue = it) },
                Knob("f", R.string.ed_fade, a.fade, 0f..100f, "") { a.copy(fade = it) },
                Knob("v", R.string.ed_vignette, a.vignette, 0f..100f, "") { a.copy(vignette = it) },
            )
            var which by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("b") }
            com.aamus.powerpdf.OnDebug { cmd -> if (cmd.cmd == "adjust") knobs.firstOrNull { it.key == cmd.value }?.let { which = it.key } }
            val k = knobs.firstOrNull { it.key == which } ?: knobs.first()
            val chips = rememberScrollState()
            Row(
                Modifier.fillMaxWidth().edgeFade(chips).horizontalScroll(chips).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                knobs.forEach { n ->
                    val name = stringResource(n.label)
                    Chip(if (n.value != 0 && n.key != k.key) "$name •" else name, n.key == k.key) { which = n.key }
                }
                Chip(stringResource(R.string.ed_reset), false, Modifier.alpha(if (a != Adjust()) 1f else 0.4f)) { if (a != Adjust()) change(e.copy(adjust = Adjust()), "") }
            }
            LabelSlider(stringResource(k.label), k.value.toFloat(), k.range, "${k.value}${k.unit}") { set(k.key, k.with(it.toInt())) }
        }
        Tab.Filters -> {
            val names = mapOf(
                EditFilter.None to R.string.ed_f_none, EditFilter.Vivid to R.string.ed_f_vivid, EditFilter.Warm to R.string.ed_f_warm, EditFilter.Cool to R.string.ed_f_cool,
                EditFilter.Mono to R.string.ed_f_mono, EditFilter.Noir to R.string.ed_f_noir, EditFilter.Sepia to R.string.ed_f_sepia, EditFilter.Vintage to R.string.ed_f_vintage,
                EditFilter.Faded to R.string.ed_f_faded, EditFilter.Dramatic to R.string.ed_f_dramatic, EditFilter.Polaroid to R.string.ed_f_polaroid,
                EditFilter.Kodachrome to R.string.ed_f_kodachrome, EditFilter.Negative to R.string.ed_f_negative,
            )
            // Each filter shown on the picture itself, as photo editors do: names alone say little.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditFilter.entries.forEach { f ->
                    val on = e.filter == f
                    Column(Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable { change(e.copy(filter = f), "") }.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(c.sunken).border(if (on) 3.dp else 1.dp, if (on) c.accent else c.line, RoundedCornerShape(10.dp))) {
                            thumbs?.get(f)?.let { Image(it, null, Modifier.fillMaxSize().padding(if (on) 3.dp else 1.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                        }
                        Text(stringResource(names[f]!!), style = MaterialTheme.typography.labelMedium, color = if (on) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Tab.Crop -> {
            val (tw, th) = EditRender.turnedSize(base.width, base.height, e.turn)
            /** A visual quarter turn: the box turns with the picture, and its shape too. */
            fun turned(clockwise: Boolean): EditState {
                // The picture is turned before it is mirrored, so with one mirror on a visual
                // clockwise turn is an anticlockwise turn of the source.
                val sourceClockwise = if (e.flipH != e.flipV) !clockwise else clockwise
                return e.copy(turn = (e.turn + if (sourceClockwise) 1 else 3) % 4, crop = CropMath.turn(e.crop, clockwise), ratio = e.ratio?.let { 1f / it })
            }
            // A mirror reverses the straightening too, so the picture looks mirrored, not re-tilted.
            fun mirrored(horizontal: Boolean) = e.copy(
                flipH = if (horizontal) !e.flipH else e.flipH,
                flipV = if (horizontal) e.flipV else !e.flipV,
                straighten = -e.straighten,
                crop = CropMath.mirror(e.crop, horizontal),
            )
            // Turn and flip in one row of tiles, so the shapes below stay in view.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(R.drawable.ic_rotate_left, stringResource(R.string.img_left), false, Modifier.weight(1f)) { change(turned(false), "") }
                ActionTile(R.drawable.ic_rotate_right, stringResource(R.string.img_right), false, Modifier.weight(1f)) { change(turned(true), "") }
                ActionTile(R.drawable.ic_swap_vert, stringResource(R.string.img_flip_h), e.flipH, Modifier.weight(1f), iconTurn = 90f) { change(mirrored(true), "") }
                ActionTile(R.drawable.ic_swap_vert, stringResource(R.string.img_flip_v), e.flipV, Modifier.weight(1f)) { change(mirrored(false), "") }
            }
            LabelSlider(stringResource(R.string.img_angle), e.straighten, -45f..45f, "${e.straighten.toInt()}°") { change(e.copy(straighten = it.toInt().toFloat()), "straighten") }
            SectionTitle(stringResource(R.string.img_shape))
            val own = tw.toFloat() / th
            val shapes = listOf<Pair<Float?, String>>(null to stringResource(R.string.ed_crop_free), own to stringResource(R.string.img_free), 1f to "1:1", 4f / 3 to "4:3", 3f / 4 to "3:4", 16f / 9 to "16:9", 9f / 16 to "9:16", 4f / 5 to "4:5", 3f / 2 to "3:2")
            fun same(a: Float?, b: Float?) = if (a == null || b == null) a == b else abs(a - b) < 0.001f
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shapes.forEachIndexed { i, (r, label) ->
                    // "Original" is the picture's own shape; it wins over a ratio chip that happens to match.
                    val on = if (i == 1) same(e.ratio, r) else same(e.ratio, r) && !(i > 1 && same(e.ratio, own))
                    Chip(label, on) {
                        change(if (r == null) e.copy(ratio = null) else e.copy(ratio = r, crop = if (i == 1) CropBox.Full else CropMath.fit(r, tw, th, e.crop.cx, e.crop.cy)), "")
                    }
                }
            }
            Hint(stringResource(R.string.ed_crop_hint), Modifier.padding(16.dp))
        }
        Tab.Text -> {
            val layer = e.layers.firstOrNull { it.id == selected && !it.sticker }
            val sample = stringResource(R.string.ed_text_sample)
            fun stamp(text: String, colour: Int, outline: Boolean) = ImageExtras.textStamp(text.ifBlank { sample }, colour, outline, true)
            if (!(typing && layer != null)) PrimaryButton(stringResource(R.string.ed_add_text), {
                // A new text starts empty with the keyboard up; the picture shows the hint until you type.
                val l = EditLayer(layerIds++, "", AColor.WHITE, outline = true, sticker = false, stamp = stamp("", AColor.WHITE, true), x = 0.5f, y = 0.5f, width = 0.5f, rotate = 0f)
                // An earlier text left empty goes, rather than piling up empty boxes.
                change(e.copy(layers = e.layers.filter { it.sticker || it.text.isNotBlank() } + l), "")
                select(l.id)
                focusText()
            }, Modifier.padding(16.dp).fillMaxWidth(), icon = R.drawable.ic_text_fields)
            if (layer == null) Hint(stringResource(R.string.ed_layer_hint), Modifier.padding(horizontal = 16.dp))
            else {
                fun set(l: EditLayer, key: String) = change(e.copy(layers = e.layers.map { if (it.id == l.id) l else it }), key)
                val focus = remember { FocusRequester() }
                LaunchedEffect(wantTyping, layer.id) { if (wantTyping) { runCatching { focus.requestFocus() }; typingStarted() } }
                Field(
                    layer.text,
                    { t -> set(layer.copy(text = t, stamp = stamp(t, layer.colour, layer.outline)), "text-${layer.id}") },
                    stringResource(R.string.img_mark_text),
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().focusRequester(focus),
                    placeholder = sample,
                    singleLine = false,
                    onDone = { focusManager.clearFocus() },
                )
                // While the keyboard is up only the text box shows, so the picture keeps its room.
                if (typing) return@Panel
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    COLOURS.forEach { col -> Swatch(Color(col), layer.colour == col, { set(layer.copy(colour = col, stamp = stamp(layer.text, col, layer.outline)), "") }) }
                }
                Group { SwitchRow(stringResource(R.string.img_mark_outline), layer.outline, { o -> set(layer.copy(outline = o, stamp = stamp(layer.text, layer.colour, o)), "") }) }
                LabelSlider(stringResource(R.string.img_mark_size), layer.width, 0.05f..1f, "${(layer.width * 100).toInt()}%") { set(layer.copy(width = it), "size-${layer.id}") }
                LabelSlider(stringResource(R.string.img_mark_rotation), layer.rotate, -180f..180f, "${layer.rotate.toInt()}°") { set(layer.copy(rotate = it.toInt().toFloat()), "turn-${layer.id}") }
                SecondaryButton(stringResource(R.string.delete), { change(e.copy(layers = e.layers.filter { it.id != layer.id }), ""); select(null) }, Modifier.padding(16.dp).fillMaxWidth(), icon = R.drawable.ic_delete)
            }
        }
        Tab.Stickers -> {
            val layer = e.layers.firstOrNull { it.id == selected && it.sticker }
            var recent by remember { mutableStateOf(RecentStickers.read(context)) }
            var set by remember { mutableIntStateOf(if (recent.isEmpty()) 1 else 0) }
            val sets = listOf<Triple<String, Int, List<String>>>(Triple("🕘", R.string.ed_st_recent, recent)) + STICKER_SETS.map { Triple(it.icon, it.label, it.items) }
            // Kinds along the top, as on an emoji keyboard.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                sets.forEachIndexed { i, (icon, label, items) ->
                    if (i == 0 && items.isEmpty()) return@forEachIndexed
                    val on = i == set
                    val name = stringResource(label)
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.accentSoft else Color.Transparent)
                            .clickable { set = i }.semantics { contentDescription = name; role = Role.Tab },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (i == 0) Sym(R.drawable.ic_history, null, tint = if (on) c.accent else c.muted, size = 22.dp)
                        else Text(icon, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            val items = sets[set.coerceIn(0, sets.lastIndex)].third
            LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxWidth().height(176.dp).padding(horizontal = 8.dp)) {
                items(items) { s ->
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).clickable {
                            // A new sticker goes in the middle, a little aside if one is already there.
                            val k = e.layers.count { it.sticker && abs(it.x - 0.5f) < 0.05f && abs(it.y - 0.5f) < 0.05f }
                            val l = EditLayer(layerIds++, s, AColor.BLACK, outline = false, sticker = true, stamp = ImageExtras.textStamp(s, AColor.BLACK, false, false, bold = false), x = 0.5f + 0.04f * k, y = 0.5f + 0.04f * k, width = 0.22f, rotate = 0f)
                            change(e.copy(layers = e.layers + l), "")
                            select(l.id)
                            recent = RecentStickers.add(context, s)
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text(s, style = MaterialTheme.typography.headlineSmall) }
                }
            }
            if (layer == null) Hint(stringResource(R.string.ed_sticker_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            else SecondaryButton(stringResource(R.string.delete), { change(e.copy(layers = e.layers.filter { it.id != layer.id }), ""); select(null) }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), icon = R.drawable.ic_delete)
        }
        Tab.Draw -> {
            Hint(stringResource(R.string.ed_draw_hint), Modifier.padding(16.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                COLOURS.forEach { col -> Swatch(Color(col), brushColour == col, { setBrushColour(col) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { LabelSlider(stringResource(R.string.ed_brush), brushSize * 1000, 3f..40f, "${(brushSize * 1000).toInt()}") { setBrushSize(it / 1000) } }
                // The brush as it will draw, beside its size.
                Box(Modifier.padding(end = 16.dp).size(44.dp).clip(CircleShape).background(c.sunken), contentAlignment = Alignment.Center) {
                    Box(Modifier.size((brushSize * 1000).dp.coerceAtMost(40.dp)).clip(CircleShape).background(Color(brushColour)).border(1.dp, c.line, CircleShape))
                }
            }
            SecondaryButton(stringResource(R.string.ed_clear), { change(e.copy(strokes = emptyList()), "") }, Modifier.padding(16.dp).fillMaxWidth(), enabled = e.strokes.isNotEmpty())
        }
        Tab.Frame -> {
            LabelSlider(stringResource(R.string.ed_frame_size), e.frame, 0f..20f, "${e.frame.toInt()}%") { change(e.copy(frame = it.toInt().toFloat()), "frame") }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                COLOURS.forEach { col -> Swatch(Color(col), e.frameColour == col, { change(e.copy(frameColour = col), "") }) }
            }
            LabelSlider(stringResource(R.string.ed_corners), e.radius, 0f..50f, "${e.radius.toInt()}%") { change(e.copy(radius = it.toInt().toFloat()), "radius") }
            SectionTitle(stringResource(R.string.ed_save_as))
            // One row of chips, like the shapes: a radio list ran past the end of the panel.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Pair<ImgFormat?, String>>(null to stringResource(R.string.ed_same_format), ImgFormat.JPG to "JPG", ImgFormat.PNG to "PNG", ImgFormat.WEBP to "WebP").forEach { (f, label) -> Chip(label, saveAs == f) { setSaveAs(f) } }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
