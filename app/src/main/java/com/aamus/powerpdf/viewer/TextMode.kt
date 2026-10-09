package com.aamus.powerpdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ui.edgeFade
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.Rgb
import com.aamus.powerpdf.engine.StampText
import com.aamus.powerpdf.engine.TextStyle as StampStyle
import com.aamus.powerpdf.engine.addText
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.Swatch
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.theme.PlexArabic
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.aamus.powerpdf.engine.R as EngineR

private data class TextEdit(val page: Int, val x: Float, val y: Float, val text: String, val size: Float, val colour: Int, val bold: Boolean)

/** The Latin face used when writing (Noto Sans), so the box shows what the page will get. */
private val NotoSans = FontFamily(Font(EngineR.font.noto_sans_regular, FontWeight.Normal), Font(EngineR.font.noto_sans_bold, FontWeight.Bold))

@Composable
fun rememberTextTool(session: DocumentSession, viewport: ViewportState, onExit: () -> Unit): ModeTool {
    val model = LocalModel.current
    // Outlives the mode: Done writes the last change while the mode closes.
    val scope = LocalModel.current.appScope
    val density = LocalDensity.current
    val uiRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var edit by remember { mutableStateOf<TextEdit?>(null) }
    var lastSize by remember { mutableStateOf(14f) }
    var lastColour by remember { mutableStateOf(0xFF1F1F1F.toInt()) }
    var lastBold by remember { mutableStateOf(false) }

    fun rtlOf(text: String) = if (text.isBlank()) uiRtl else StampText.isRtl(text)

    fun commit() {
        val e = edit ?: return
        edit = null
        if (e.text.isBlank()) return
        val size = session.state.value.pageSizes.getOrNull(e.page) ?: return
        val rtl = rtlOf(e.text)
        val maxWidth = if (rtl) e.x - 18f else size.width - e.x - 18f
        scope.launch {
            runCatching {
                session.change("add text") {
                    it.addText(e.page, e.x, e.y, maxOf(40f, maxWidth), e.text.trimEnd(), StampStyle(e.size, Rgb.of(e.colour), e.bold))
                }
            }.onSuccess { model.say(model.str(R.string.text_added)) }
                .onFailure { model.say(model.errorText(it)) }
        }
    }

    val gestures = remember(session) {
        object : PageGestures {
            override val doubleTapZooms: Boolean get() = false

            override fun onTap(page: Int?, point: Offset, view: Offset) {
                if (edit != null) {
                    commit()
                    return
                }
                if (page == null) return
                if (session.state.value.rights?.edit == false) {
                    model.say(model.str(R.string.error_restricted))
                    return
                }
                edit = TextEdit(page, point.x, point.y - lastSize * 0.6f, "", lastSize, lastColour, lastBold)
            }
        }
    }

    val overlay: DrawScope.(PageLayout) -> Unit = { }

    val chrome: @Composable BoxScope.() -> Unit = {
        val c = Power.colors
        Column(Modifier.align(Alignment.TopCenter)) {
            ModeTopBar(stringResource(R.string.tool_add_text), onDone = {
                commit()
                onExit()
            }) { UndoRedo(session, scope) }
        }

        edit?.let { e ->
            val focus = remember { FocusRequester() }
            val l = viewport.layout
            val at = viewport.toView(l.toContent(e.page, Offset(e.x, e.y)))
            val rtl = rtlOf(e.text)
            var boxW by remember { mutableStateOf(0) }
            val fontPx = e.size * l.scale
            val fontSp = with(density) { (fontPx / fontScale / this.density).sp }
            val family = if (rtl) PlexArabic else NotoSans
            LaunchedEffect(e.page, e.x, e.y) {
                viewport.reveal(e.page, e.x, e.y, e.x + 20f, e.y + e.size * 2)
                focus.requestFocus()
            }
            Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                val left = if (rtl) at.x - boxW else at.x
                BasicTextField(
                    value = e.text,
                    onValueChange = { edit = e.copy(text = it) },
                    textStyle = TextStyle(
                        fontSize = fontSp,
                        color = Color(e.colour),
                        fontFamily = family,
                        fontWeight = if (e.bold) FontWeight.Bold else FontWeight.Normal,
                        textAlign = if (rtl) TextAlign.Right else TextAlign.Left,
                        textDirection = TextDirection.Content,
                        lineHeight = (fontSp.value * 1.25f).sp,
                    ),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier
                        .absoluteOffset { IntOffset(left.roundToInt(), at.y.roundToInt()) }
                        .onSizeChanged { boxW = it.width }
                        .widthIn(min = 24.dp, max = with(density) { (viewport.viewportWidth * 0.9f).toDp() })
                        .border(1.dp, c.accent, RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.001f))
                        .padding(horizontal = 2.dp)
                        .focusRequester(focus),
                    decorationBox = { inner ->
                        Box {
                            if (e.text.isEmpty()) Text(stringResource(R.string.type_here), style = TextStyle(fontSize = fontSp, color = Color(0x991F1F1F), fontFamily = family))
                            inner()
                        }
                    },
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).imePadding()) {
            if (edit == null) ModeHint(stringResource(R.string.add_text_hint))
            ModeBottom {
                val e = edit
                Row(
                    rememberScrollState().let { s -> Modifier.fillMaxWidth().edgeFade(s).horizontalScroll(s) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val size = e?.size ?: lastSize
                    IconAction(R.drawable.ic_zoom_out, stringResource(R.string.font_size), {
                        val s = (size - 2).coerceAtLeast(6f)
                        lastSize = s
                        edit = e?.copy(size = s)
                    })
                    Text(stringResource(R.string.font_size_value, size.roundToInt()), style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.widthIn(min = 44.dp), textAlign = TextAlign.Center)
                    IconAction(R.drawable.ic_zoom_in, stringResource(R.string.font_size), {
                        val s = (size + 2).coerceAtMost(96f)
                        lastSize = s
                        edit = e?.copy(size = s)
                    })
                    IconAction(R.drawable.ic_format_bold, stringResource(R.string.bold), {
                        lastBold = !(e?.bold ?: lastBold)
                        edit = e?.copy(bold = lastBold)
                    }, selected = e?.bold ?: lastBold)
                    Spacer(Modifier.width(4.dp))
                    listOf(0xFF1F1F1F.toInt(), 0xFF1473E6.toInt(), 0xFFE34850.toInt(), 0xFF2D9D78.toInt(), 0xFF7E57E6.toInt()).forEach { col ->
                        Swatch(Color(col), selected = (e?.colour ?: lastColour) == col, onClick = {
                            lastColour = col
                            edit = e?.copy(colour = col)
                        }, size = 24.dp)
                    }
                    if (e != null) {
                        Spacer(Modifier.width(8.dp))
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
        ModeTool(gestures, overlay, chrome, pending = { edit?.text?.isNotBlank() == true }, finish = {
            commit()
            onExit()
        })
    }
}
