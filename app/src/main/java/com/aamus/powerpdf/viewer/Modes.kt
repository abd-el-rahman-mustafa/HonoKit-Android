package com.aamus.powerpdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.AppViewModel
import com.aamus.powerpdf.R
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.MarkInfo
import com.aamus.powerpdf.engine.MarkKind
import com.aamus.powerpdf.engine.Rgb
import com.aamus.powerpdf.engine.addTextMarkup
import com.aamus.powerpdf.engine.marks
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a tool mode brings to the viewer: its own touch handling, marks on the pages, and bars. */
class ModeTool(
    val gestures: PageGestures,
    val overlay: DrawScope.(PageLayout) -> Unit,
    val chrome: @Composable BoxScope.() -> Unit,
    /** The reading bars and page pill step aside. */
    val hidesChrome: Boolean = true,
    /** Work Back would lose (a signature placed but not set down, text being typed): Back asks first. */
    val pending: () -> Boolean = { false },
    /** What Done does: applies that work, then the mode closes (common editor behaviour). */
    val finish: () -> Unit = {},
    /** Back › Discard: that work is thrown away (the mode closes after). */
    val discard: () -> Unit = {},
)

@Composable
fun rememberModeTool(mode: Mode, session: DocumentSession, viewport: ViewportState, vm: ViewerModel, onExit: () -> Unit): ModeTool? = when (mode) {
    Mode.Read, Mode.Organize -> null
    Mode.Comment -> rememberCommentTool(session, viewport, vm, onExit)
    Mode.Sign -> rememberSignTool(session, viewport, onExit)
    Mode.AddText -> rememberTextTool(session, viewport, onExit)
    Mode.EditText -> rememberEditTool(session, viewport, onExit)
    Mode.Erase -> rememberEraseTool(session, viewport, onExit)
    Mode.Forms -> rememberFormTool(session, viewport, onExit)
    Mode.Redact -> rememberRedactTool(session, viewport, vm, onExit)
    Mode.Watermark -> rememberStampTool(session, viewport, watermark = true, onExit)
    Mode.PageNumbers -> rememberStampTool(session, viewport, watermark = false, onExit)
}

enum class MarkupKind { Highlight, Underline, StrikeOut }

/** Default mark colours, as Acrobat's: yellow highlight, red underline and strike-through. */
fun defaultColour(kind: MarkupKind): Int = when (kind) {
    MarkupKind.Highlight -> 0xFFFFD600.toInt()
    MarkupKind.Underline -> 0xFF1473E6.toInt()
    MarkupKind.StrikeOut -> 0xFFE34850.toInt()
}

fun authorName(model: AppViewModel, setting: String): String = setting.ifBlank { model.str(R.string.default_author) }

/** Marks the selected text (from the selection menu). */
suspend fun addTextMarkup(session: DocumentSession, model: AppViewModel, s: TextSelection, kind: MarkupKind, author: String, colour: Int = defaultColour(kind)) {
    val rights = session.state.value.rights
    if (rights != null && !rights.annotate) {
        model.say(model.str(R.string.annotate_not_allowed))
        return
    }
    val mk = when (kind) {
        MarkupKind.Highlight -> MarkKind.Highlight
        MarkupKind.Underline -> MarkKind.Underline
        MarkupKind.StrikeOut -> MarkKind.StrikeOut
    }
    session.change("markup") { it.addTextMarkup(s.page, mk, s.sel.boxes, Rgb.of(colour), authorName(model, author)) }
}

/** The marks on each page, loaded for the pages on screen and reloaded after every change. */
@Stable
class MarksCache(private val session: DocumentSession) {
    private val pages = mutableStateMapOf<Int, List<MarkInfo>>()
    private var version by mutableIntStateOf(-1)

    fun on(page: Int): List<MarkInfo> = if (version == session.state.value.version) pages[page] ?: emptyList() else emptyList()

    /** Loads what is missing for [visible]; call from a LaunchedEffect. */
    suspend fun load(visible: IntRange) {
        val v = session.state.value.version
        if (v != version) {
            pages.clear()
            version = v
        }
        for (p in visible) {
            if (pages.containsKey(p)) continue
            val list = runCatching { session.engine { it.marks(p) } }.getOrDefault(emptyList())
            if (session.state.value.version != v) return
            pages[p] = list
        }
    }

    /** The mark under a point (page points), smallest first so a note on a highlight wins. */
    fun at(page: Int, x: Float, y: Float, slop: Float): MarkInfo? =
        on(page).filter { m -> x >= m.box.x0 - slop && x <= m.box.x1 + slop && y >= m.box.y0 - slop && y <= m.box.y1 + slop }
            .minByOrNull { it.box.width * it.box.height }
}

/** The bar at the top of a tool mode: Done (tonal, so it does not read as Back), a title, actions. */
@Composable
fun ModeTopBar(title: String, onDone: () -> Unit, actions: @Composable () -> Unit = {}) {
    val c = Power.colors
    Column(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().windowInsetsPadding(com.aamus.powerpdf.ui.SideBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(c.accentSoft)
                    .clickable(onClick = onDone)
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym(R.drawable.ic_check, null, tint = c.accent, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.done), style = MaterialTheme.typography.labelLarge, color = c.accent, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
        }
        HorizontalDivider(color = c.line)
    }
}

@Composable
fun UndoRedo(session: DocumentSession, scope: CoroutineScope) {
    val state = session.state.value
    IconAction(R.drawable.ic_undo, stringResource(R.string.undo), { scope.launch { session.undo() } }, enabled = state.canUndo)
    IconAction(R.drawable.ic_redo, stringResource(R.string.redo), { scope.launch { session.redo() } }, enabled = state.canRedo)
}

/**
 * A hint over the bottom bar ("Drag across text to mark it"). A [warning] (something to check
 * before going on) is amber; a long one wraps inside rounded corners.
 */
@Composable
fun ModeHint(text: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(if (warning) Color(0xF07A4500) else Color(0xCC2C2C2C))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** The bottom surface of a tool mode. */
@Composable
fun ModeBottom(content: @Composable () -> Unit) {
    val c = Power.colors
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(color = c.line)
        // Clear of the navigation buttons below, or beside it when the phone is sideways.
        Box(Modifier.windowInsetsPadding(com.aamus.powerpdf.ui.SideBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Bottom))) { content() }
    }
}

/** The mark colours offered everywhere (Acrobat-like set). */
val MarkColours = listOf(
    0xFFFFD600.toInt(), // yellow
    0xFF7ED957.toInt(), // green
    0xFF4FC3F7.toInt(), // light blue
    0xFFFF80AB.toInt(), // pink
    0xFFE34850.toInt(), // red
    0xFF1473E6.toInt(), // blue
    0xFF7E57E6.toInt(), // violet
    0xFF1F1F1F.toInt(), // black
)
