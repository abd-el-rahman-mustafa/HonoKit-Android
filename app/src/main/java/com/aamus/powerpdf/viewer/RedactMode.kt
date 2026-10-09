package com.aamus.powerpdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.OnDebug
import com.aamus.powerpdf.R
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.Box as PageBox
import com.aamus.powerpdf.engine.RedactHit
import com.aamus.powerpdf.engine.RedactPattern
import com.aamus.powerpdf.engine.Rgb
import com.aamus.powerpdf.engine.TextLogic
import com.aamus.powerpdf.engine.applyRedactions
import com.aamus.powerpdf.engine.findToRedact
import com.aamus.powerpdf.engine.isSigned
import com.aamus.powerpdf.engine.markForRedaction
import com.aamus.powerpdf.engine.redactionMarks
import com.aamus.powerpdf.engine.removeRedactionMark
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.SheetTitle
import com.aamus.powerpdf.ui.Swatch
import com.aamus.powerpdf.ui.SwitchRow
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class RTool { Text, Area }

private val RED = Color(0xFFE34850)

@Composable
fun rememberRedactTool(session: DocumentSession, viewport: ViewportState, vm: ViewerModel, onExit: () -> Unit): ModeTool {
    val model = LocalModel.current
    // Outlives the mode: Done writes the last change while the mode closes.
    val scope = LocalModel.current.appScope
    val density = LocalDensity.current
    val state by session.state.collectAsStateWithLifecycle()
    val prefs by model.settings.flow.collectAsStateWithLifecycle()
    var tool by remember { mutableStateOf(RTool.Text) }
    val marks by produceState<List<Pair<Int, PageBox>>>(emptyList(), state.version) {
        value = runCatching { session.engine { it.redactionMarks() } }.getOrDefault(emptyList())
    }
    var selected by remember { mutableStateOf<Pair<Int, PageBox>?>(null) }
    var findOpen by remember { mutableStateOf(false) }
    var applyOpen by remember { mutableStateOf(false) }
    // Whether the file is digitally signed: applying then breaks the signatures, and the dialog says so.
    val signed by produceState(false, session, state.version) { value = runCatching { session.engine { it.isSigned() } }.getOrDefault(false) }
    // A drag in progress: text chosen, or a box.
    var dragPage by remember { mutableStateOf(-1) }
    var dragFrom by remember { mutableStateOf(Offset.Zero) }
    var dragTo by remember { mutableStateOf<Offset?>(null) }
    var preview by remember { mutableStateOf<TextLogic.Selection?>(null) }

    fun allowed() = session.state.value.rights?.edit != false

    LaunchedEffect(session) {
        snapshotFlow { viewport.layout.visiblePages(viewport.contentTop, viewport.contentTop + viewport.viewportHeight) to state.version }
            .collect { (visible, _) -> for (p in visible) vm.linesOf(p) }
    }

    fun mark(list: List<Pair<Int, PageBox>>) {
        if (list.isEmpty()) return
        scope.launch {
            runCatching { session.change("mark for redaction") { it.markForRedaction(list, prefs.author) } }
                .onFailure { model.say(model.errorText(it)) }
        }
    }

    fun apply(fill: Int, label: String, sanitize: Boolean) {
        scope.launch {
            model.busy = model.str(R.string.working)
            runCatching { session.change("redact") { it.applyRedactions(Rgb.of(fill), label.trim().ifEmpty { null }, sanitize) } }
                .onSuccess { r ->
                    model.busy = null
                    if (r.leftover > 0) model.say(model.str(R.string.redact_leftover, r.leftover), long = true)
                    else if (r.signed) model.say(model.str(R.string.redacted_signed), action = model.str(R.string.save_copy), long = true) {
                        scope.launch { model.save(session, forceCopy = true) }
                    }
                    else model.say(model.plural(R.plurals.redacted_areas, r.areas, r.areas, r.pages), action = model.str(R.string.save_copy), long = true) {
                        scope.launch { model.save(session, forceCopy = true) }
                    }
                }
                .onFailure {
                    model.busy = null
                    model.say(model.errorText(it))
                }
        }
    }

    OnDebug { c ->
        when (c.cmd) {
            // "words|patterns" e.g. "Dubai|Email,Phone": finds and marks everything found.
            "redactfind" -> {
                val words = c.value.substringBefore('|').split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val patterns = c.value.substringAfter('|', "").split(',').mapNotNull { n -> RedactPattern.entries.firstOrNull { it.name.equals(n.trim(), true) } }.toSet()
                val hits = session.engine { it.findToRedact(words, patterns, wholeWords = false) }
                android.util.Log.i("PowerPDF", "redact find: ${hits.size} hits: ${hits.take(8).map { it.text }}")
                mark(hits.map { it.page to it.box })
            }
            "redactapply" -> apply(0xFF000000.toInt(), c.value, sanitize = true)
        }
    }

    val gestures = remember(session) {
        object : PageGestures {
            override val doubleTapZooms: Boolean get() = false

            override fun wantsDrag(page: Int?, point: Offset, view: Offset): Boolean = page != null && allowed()

            override fun onDragStart(page: Int, point: Offset, view: Offset) {
                selected = null
                dragPage = page
                dragFrom = point
                dragTo = point
                preview = null
            }

            override fun onDrag(page: Int, point: Offset, view: Offset) {
                dragTo = point
                if (tool == RTool.Text) {
                    val lines = vm.cachedLines(dragPage) ?: return
                    preview = TextLogic.selectText(lines, dragFrom.x to dragFrom.y, point.x to point.y)
                }
            }

            override fun onDragEnd() {
                val page = dragPage
                val to = dragTo
                val sel = preview
                dragPage = -1
                dragTo = null
                preview = null
                if (tool == RTool.Text) {
                    if (sel != null && sel.boxes.isNotEmpty()) mark(sel.boxes.map { page to it })
                } else if (to != null && abs(to.x - dragFrom.x) >= 4f && abs(to.y - dragFrom.y) >= 4f) {
                    mark(listOf(page to PageBox(minOf(dragFrom.x, to.x), minOf(dragFrom.y, to.y), maxOf(dragFrom.x, to.x), maxOf(dragFrom.y, to.y))))
                }
            }

            override fun onDragCancel() {
                dragPage = -1
                dragTo = null
                preview = null
            }

            override fun onTap(page: Int?, point: Offset, view: Offset) {
                if (page == null) {
                    selected = null
                    return
                }
                val slop = with(density) { 8.dp.toPx() } / viewport.layout.scale
                selected = marks.filter { it.first == page }.firstOrNull { (_, b) -> point.x in b.x0 - slop..b.x1 + slop && point.y in b.y0 - slop..b.y1 + slop }
            }
        }
    }

    val overlay: DrawScope.(PageLayout) -> Unit = { layout ->
        for ((p, b) in marks) {
            val r = layout.viewRect(viewport, p, b.x0, b.y0, b.x1, b.y1)
            drawRect(RED.copy(alpha = 0.18f), r.topLeft, r.size)
            drawRect(RED, r.topLeft, r.size, style = Stroke(if (selected == (p to b)) 2.5.dp.toPx() else 1.5.dp.toPx()))
        }
        preview?.boxes?.forEach { b ->
            val r = layout.viewRect(viewport, dragPage, b.x0, b.y0, b.x1, b.y1)
            drawRect(RED.copy(alpha = 0.3f), r.topLeft, r.size)
        }
        val to = dragTo
        if (tool == RTool.Area && to != null && dragPage >= 0) {
            val r = layout.viewRect(viewport, dragPage, minOf(dragFrom.x, to.x), minOf(dragFrom.y, to.y), maxOf(dragFrom.x, to.x), maxOf(dragFrom.y, to.y))
            drawRect(RED.copy(alpha = 0.2f), r.topLeft, r.size)
            drawRect(RED, r.topLeft, r.size, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }
    }

    val chrome: @Composable BoxScope.() -> Unit = {
        val c = Power.colors
        Column(Modifier.align(Alignment.TopCenter)) {
            ModeTopBar(stringResource(R.string.tool_redact), onDone = onExit) {
                IconAction(R.drawable.ic_search, stringResource(R.string.redact_find), { findOpen = true })
                UndoRedo(session, scope)
            }
        }
        selected?.let { (p, b) ->
            val r = viewport.layout.viewRect(viewport, p, b.x0, b.y0, b.x1, b.y1)
            val y = r.bottom + with(density) { 10.dp.toPx() }
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, y.roundToInt()) }
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(c.surface)
                        .border(1.dp, c.line, CircleShape)
                        .clickable {
                            selected = null
                            scope.launch { runCatching { session.change("remove mark") { it.removeRedactionMark(p, b) } }.onFailure { e -> model.say(model.errorText(e)) } }
                        }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym(R.drawable.ic_close, null, tint = c.ink, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.redact_remove_mark), style = MaterialTheme.typography.labelLarge, color = c.ink)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            ModeHint(stringResource(if (!allowed()) R.string.error_restricted else if (tool == RTool.Text) R.string.redact_hint_text else R.string.redact_hint_area))
            ModeBottom {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.aamus.powerpdf.ui.Segmented(
                        listOf(RTool.Text to stringResource(R.string.redact_tool_text), RTool.Area to stringResource(R.string.redact_tool_area)),
                        tool, { tool = it }, Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    PrimaryButton(
                        if (marks.isEmpty()) stringResource(R.string.redact_apply) else pluralStringResource(R.plurals.redact_apply_n, marks.size, marks.size),
                        { applyOpen = true },
                        enabled = marks.isNotEmpty() && allowed(),
                    )
                }
            }
        }
    }

    if (findOpen) RedactFindSheet(session, onDismiss = { findOpen = false }) { hits ->
        findOpen = false
        mark(hits.map { it.page to it.box })
        hits.firstOrNull()?.let { h -> viewport.reveal(h.page, h.box.x0, h.box.y0, h.box.x1, h.box.y1) }
    }
    if (applyOpen) RedactApplyDialog(marks.size, signed, onDismiss = { applyOpen = false }) { fill, label, sanitize ->
        applyOpen = false
        apply(fill, label, sanitize)
    }

    return remember(gestures, overlay, chrome) { ModeTool(gestures, overlay, chrome) }
}

/** Find words and patterns to mark (research-ux-mobile.md 6.8): results listed with their line, all ticked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RedactFindSheet(session: DocumentSession, onDismiss: () -> Unit, onMark: (List<RedactHit>) -> Unit) {
    val c = Power.colors
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var words by remember { mutableStateOf("") }
    var whole by remember { mutableStateOf(true) }
    val patterns = remember { mutableStateListOf<RedactPattern>() }
    var hits by remember { mutableStateOf<List<RedactHit>?>(null) }
    val chosen = remember { mutableStateListOf<Int>() }
    var searching by remember { mutableStateOf(false) }
    val names = mapOf(
        RedactPattern.Email to R.string.pattern_email,
        RedactPattern.Phone to R.string.pattern_phone,
        RedactPattern.EmiratesId to R.string.pattern_emirates_id,
        RedactPattern.Iban to R.string.pattern_iban,
        RedactPattern.Card to R.string.pattern_card,
        RedactPattern.Date to R.string.pattern_date,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        SheetTitle(stringResource(R.string.redact_find))
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(words, { words = it; hits = null }, stringResource(R.string.redact_words), placeholder = stringResource(R.string.redact_words_hint))
            SwitchRow(stringResource(R.string.redact_whole_words), whole, { whole = it; hits = null })
            Text(stringResource(R.string.redact_patterns), style = MaterialTheme.typography.labelLarge, color = c.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RedactPattern.entries.forEach { p ->
                    val on = p in patterns
                    Text(
                        stringResource(names.getValue(p)),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) c.accent else c.ink,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) c.accentSoft else Color.Transparent)
                            .border(1.dp, if (on) c.accent else c.line, CircleShape)
                            .clickable { if (on) patterns.remove(p) else patterns.add(p); hits = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            val found = hits
            if (found == null) {
                PrimaryButton(stringResource(if (searching) R.string.working else R.string.redact_find_button), {
                    searching = true
                    scope.launch {
                        val list = runCatching {
                            session.engine { it.findToRedact(words.split(',', '،').map { w -> w.trim() }, patterns.toSet(), whole) }
                        }.getOrDefault(emptyList())
                        hits = list
                        chosen.clear()
                        chosen.addAll(list.indices)
                        searching = false
                    }
                }, Modifier.fillMaxWidth(), enabled = !searching && (words.isNotBlank() || patterns.isNotEmpty()))
            } else if (found.isEmpty()) {
                Text(stringResource(R.string.redact_none_found), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                PrimaryButton(pluralStringResource(R.plurals.redact_mark_n, chosen.size, chosen.size), { onMark(chosen.sorted().map { found[it] }) }, Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty())
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(found) { i, h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (i in chosen) chosen.remove(i) else chosen.add(i) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(i in chosen, { on -> if (on) chosen.add(i) else chosen.remove(i) }, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                            Column(Modifier.weight(1f)) {
                                Text(bidi(h.text), style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(bidi(stringResource(R.string.page_n, h.page + 1) + " · " + h.context), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Applying for good: box colour, an optional label, and hidden information (research 6.9). */
@Composable
private fun RedactApplyDialog(count: Int, signed: Boolean, onDismiss: () -> Unit, onApply: (Int, String, Boolean) -> Unit) {
    val c = Power.colors
    var fill by remember { mutableStateOf(0xFF000000.toInt()) }
    var label by remember { mutableStateOf("") }
    var sanitize by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.redact_apply_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pluralStringResource(R.plurals.redact_apply_message, count, count), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                // A signed file is written again in full: its signatures will not hold (desktop §80, as Acrobat warns).
                if (signed) Text(stringResource(R.string.redact_signed_warning), style = MaterialTheme.typography.bodyMedium, color = c.danger)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.redact_fill), style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.width(72.dp))
                    listOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt()).forEach { col ->
                        Swatch(Color(col), selected = fill == col, onClick = { fill = col }, size = 30.dp)
                    }
                }
                Field(label, { label = it }, stringResource(R.string.redact_label), placeholder = stringResource(R.string.redact_label_hint))
                SwitchRow(stringResource(R.string.redact_sanitize), sanitize, { sanitize = it }, description = stringResource(R.string.redact_sanitize_desc))
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.redact_apply), { onApply(fill, label, sanitize) }, danger = true) },
        dismissButton = { DialogButton(stringResource(R.string.cancel), onDismiss) },
        containerColor = c.surface,
    )
}
