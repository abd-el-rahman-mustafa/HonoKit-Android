package com.aamus.powerpdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.OnDebug
import com.aamus.powerpdf.R
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.FieldKind
import com.aamus.powerpdf.engine.FormField
import com.aamus.powerpdf.engine.formFields
import com.aamus.powerpdf.engine.setField
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The form fields of each page, for the pages on screen, again after every change. */
@Stable
class FieldCache(private val session: DocumentSession) {
    private val pages = mutableStateMapOf<Int, List<FormField>>()
    private var version by mutableIntStateOf(-1)

    fun on(page: Int): List<FormField> = if (version == session.state.value.version) pages[page] ?: emptyList() else emptyList()

    suspend fun load(visible: IntRange) {
        val v = session.state.value.version
        if (v != version) {
            pages.clear()
            version = v
        }
        for (p in visible) {
            if (pages.containsKey(p)) continue
            val list = runCatching { session.engine { it.formFields(p) } }.getOrDefault(emptyList())
            if (session.state.value.version != v) return
            pages[p] = list
        }
    }

    fun at(page: Int, x: Float, y: Float): FormField? =
        on(page).filter { x in it.box.x0 - 2..it.box.x1 + 2 && y in it.box.y0 - 2..it.box.y1 + 2 }.minByOrNull { it.box.width * it.box.height }
}

/** The form tool's entry in [DocumentSession.typing]. */
private const val TYPING_KEY = "form"

/** Fields that take typing, in reading order across the document (for Previous / Next). */
private suspend fun textFields(session: DocumentSession): List<FormField> =
    session.engine { it.formFields() }
        .filter { it.kind == FieldKind.Text && !it.readOnly }
        .sortedWith(compareBy<FormField> { it.page }.thenBy { (it.box.y0 / 6).toInt() }.thenBy { it.box.x0 })

@Composable
fun rememberFormTool(session: DocumentSession, viewport: ViewportState, onExit: () -> Unit): ModeTool {
    val model = LocalModel.current
    // Outlives the mode: Done writes the last change while the mode closes.
    val scope = LocalModel.current.appScope
    val density = LocalDensity.current
    val state by session.state.collectAsStateWithLifecycle()
    val fields = remember(session) { FieldCache(session) }
    var editing by remember { mutableStateOf<FormField?>(null) }
    var text by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf<FormField?>(null) }

    LaunchedEffect(session) {
        snapshotFlow { viewport.layout.visiblePages(viewport.contentTop, viewport.contentTop + viewport.viewportHeight) to state.version }
            .collect { (visible, _) -> fields.load(visible) }
    }

    fun allowed(): Boolean {
        if (session.state.value.rights?.forms == false) {
            model.say(model.str(R.string.forms_not_allowed))
            return false
        }
        return true
    }

    fun set(f: FormField, value: String? = null, checked: Boolean? = null) {
        scope.launch {
            runCatching { session.change("fill in") { it.setField(f.page, f.index, value, checked) } }
                .onFailure { model.say(model.errorText(it)) }
        }
    }

    /**
     * Commits what is being typed when the document is about to be written or read without the
     * person leaving the field first: Save, Share, a tool, leaving (decisions §52). Closes
     * the box, as leaving the field does. False when the change failed.
     */
    suspend fun commitTyping(): Boolean {
        val f = editing ?: return true
        val value = text
        editing = null
        if (value == f.value) return true
        return runCatching { session.change("fill in") { it.setField(f.page, f.index, value) } }
            .onFailure { model.say(model.errorText(it)) }
            .isSuccess
    }

    /** Typing has started in [editing]: until it is committed or dropped, Save and the rest commit it first. */
    fun track() = session.typing.set(TYPING_KEY) { commitTyping() }

    /** Writes what was typed (if it changed) and closes the box. */
    fun finish() {
        val f = editing ?: return
        session.typing.set(TYPING_KEY, null)
        editing = null
        if (text != f.value) set(f, value = text)
    }

    fun open(f: FormField) {
        when {
            f.readOnly -> model.say(model.str(R.string.field_read_only))
            f.kind == FieldKind.Text -> {
                editing = f
                text = f.value
                track()
            }
            f.kind == FieldKind.Checkbox || f.kind == FieldKind.Radio -> set(f, checked = !f.checked)
            f.kind == FieldKind.Combo || f.kind == FieldKind.List -> choosing = f
            f.kind == FieldKind.Signature -> model.say(model.str(R.string.field_signature_hint))
            else -> {}
        }
    }

    /** Moves to the next (or previous) text field, writing this one first. */
    fun step(forward: Boolean) {
        val f = editing ?: return
        scope.launch {
            val list = textFields(session)
            val i = list.indexOfFirst { it.page == f.page && it.index == f.index }
            finish()
            val next = list.getOrNull(if (forward) i + 1 else i - 1) ?: return@launch
            viewport.reveal(next.page, next.box.x0, next.box.y0, next.box.x1, next.box.y1)
            editing = next
            text = next.value
            track()
        }
    }

    // The mode closing any other way than Done or Discard (the screen rebuilt, another document
    // opened over it): what was typed is written, not lost.
    androidx.compose.runtime.DisposableEffect(session) {
        onDispose { if (session.typing.has()) scope.launch { session.typing.commit() } }
    }

    OnDebug { c ->
        when (c.cmd) {
            // "page,x,y" (page from 1): as a tap on a field.
            "fieldtap" -> {
                val v = c.value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (v.size == 3) {
                    fields.load(v[0].toInt() - 1..v[0].toInt() - 1)
                    fields.at(v[0].toInt() - 1, v[1], v[2])?.let { if (allowed()) open(it) }
                }
            }
            "fieldtext" -> text = c.value
            "fieldchoose" -> choosing?.let { f ->
                choosing = null
                set(f, value = c.value)
            }
            "fieldnext" -> step(true)
            "fielddone" -> finish()
            "fields" -> session.engine { it.formFields() }.forEach { f ->
                android.util.Log.i("PowerPDF", "field p${f.page + 1} #${f.index} ${f.kind} '${f.name}' ${f.box} value='${f.value}' checked=${f.checked} options=${f.options}")
            }
        }
    }

    val gestures = remember(session) {
        object : PageGestures {
            override val doubleTapZooms: Boolean get() = false

            override fun onTap(page: Int?, point: Offset, view: Offset) {
                val e = editing
                if (e != null) {
                    val inside = page == e.page && point.x in e.box.x0..e.box.x1 && point.y in e.box.y0..e.box.y1
                    if (!inside) finish()
                    return
                }
                if (page == null) return
                val f = fields.at(page, point.x, point.y)
                if (f == null) {
                    if (fields.on(page).isEmpty()) model.say(model.str(R.string.fields_none_here))
                    return
                }
                if (allowed()) open(f)
            }
        }
    }

    val overlay: DrawScope.(PageLayout) -> Unit = { layout ->
        val visible = layout.visiblePages(viewport.contentTop, viewport.contentTop + viewport.viewportHeight)
        for (p in visible) for (f in fields.on(p)) {
            if (f.kind == FieldKind.Button) continue
            val r = layout.viewRect(viewport, p, f.box.x0, f.box.y0, f.box.x1, f.box.y1)
            val on = editing?.let { it.page == p && it.index == f.index } == true
            if (!f.readOnly) drawRect(Color(0x221473E6), r.topLeft, r.size)
            drawRect(
                when {
                    on -> Color(0xFF1473E6)
                    f.required -> Color(0xCCE34850)
                    else -> Color(0x661473E6)
                },
                r.topLeft, r.size, style = Stroke(if (on) 2.dp.toPx() else 1.dp.toPx()),
            )
        }
    }

    val chrome: @Composable BoxScope.() -> Unit = {
        val c = Power.colors
        Column(Modifier.align(Alignment.TopCenter)) {
            ModeTopBar(stringResource(R.string.tool_forms), onDone = {
                finish()
                onExit()
            }) { UndoRedo(session, scope) }
        }

        editing?.let { f ->
            val focus = remember { FocusRequester() }
            val l = viewport.layout
            val r = l.viewRect(viewport, f.page, f.box.x0, f.box.y0, f.box.x1, f.box.y1)
            val sizePt = if (f.multiline) minOf(11f, f.box.height / 2.6f) else minOf(12f, maxOf(6f, f.box.height * 0.62f))
            val fontSp = with(density) { (sizePt * l.scale / fontScale / this.density).sp }
            val covered = WindowInsets.ime.getBottom(density) + with(density) { 72.dp.toPx() }
            LaunchedEffect(f.page, f.index, covered) { viewport.reveal(f.page, f.box.x0, f.box.y0, f.box.x1, f.box.y1, covered) }
            LaunchedEffect(f.page, f.index) { focus.requestFocus() }
            Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                BasicTextField(
                    value = text,
                    onValueChange = { v -> text = if (f.maxLen > 0) v.take(f.maxLen) else v },
                    singleLine = !f.multiline,
                    textStyle = TextStyle(fontSize = fontSp, color = Color(0xFF111111), textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = if (f.multiline) ImeAction.Default else ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { step(true) }),
                    modifier = Modifier
                        .absoluteOffset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                        .background(Color.White)
                        .border(2.dp, c.accent)
                        .padding(horizontal = 2.dp)
                        .focusRequester(focus),
                )
            }
        }

        choosing?.let { f ->
            val r = viewport.layout.viewRect(viewport, f.page, f.box.x0, f.box.y0, f.box.x1, f.box.y1)
            Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                Box(Modifier.absoluteOffset { IntOffset(r.left.roundToInt(), r.bottom.roundToInt()) }.size(with(density) { r.width.toDp() }, 1.dp)) {
                    DropdownMenu(expanded = true, onDismissRequest = { choosing = null }, containerColor = c.surface) {
                        if (f.options.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.field_no_options)) }, onClick = { choosing = null }, enabled = false)
                        f.options.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(com.aamus.powerpdf.ui.bidi(o), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { if (o == f.value) Sym(R.drawable.ic_check, null, tint = c.accent) else Spacer(Modifier.size(24.dp)) },
                                onClick = {
                                    choosing = null
                                    if (o != f.value) set(f, value = o)
                                },
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).imePadding()) {
            val e = editing
            if (e == null) ModeHint(stringResource(R.string.forms_hint))
            ModeBottom {
                if (e == null) {
                    Text(
                        stringResource(R.string.forms_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    // Previous / Next above the keyboard (research-ux-mobile.md 6.2).
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconAction(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.field_previous), { step(false) })
                        IconAction(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.field_next), { step(true) })
                        Text(
                            com.aamus.powerpdf.ui.bidi(e.name.ifBlank { stringResource(R.string.field) }),
                            style = MaterialTheme.typography.labelLarge,
                            color = c.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(c.accent)
                                .clickable { finish() }
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
        ModeTool(gestures, overlay, chrome, pending = { editing != null }, finish = {
            finish()
            onExit()
        }, discard = {
            // Back › Discard: the typing is dropped, not committed later.
            session.typing.set(TYPING_KEY, null)
            editing = null
        })
    }
}

