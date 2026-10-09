package com.aamus.powerpdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.OnDebug
import com.aamus.powerpdf.R
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.Box as PageBox
import com.aamus.powerpdf.engine.eraseArea
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Erase area (desktop features: true removal with redaction): drag a box over what should go —
 * text, pictures, drawings — and confirm. The content is removed from the file, not covered.
 */
@Composable
fun rememberEraseTool(session: DocumentSession, viewport: ViewportState, onExit: () -> Unit): ModeTool {
    val model = LocalModel.current
    // Outlives the mode: Done writes the last change while the mode closes.
    val scope = LocalModel.current.appScope
    val density = LocalDensity.current
    var onPage by remember { mutableStateOf(-1) }
    var from by remember { mutableStateOf(Offset.Zero) }
    var to by remember { mutableStateOf<Offset?>(null) }
    var ready by remember { mutableStateOf(false) }

    fun box(): PageBox? {
        val t = to ?: return null
        return PageBox(minOf(from.x, t.x), minOf(from.y, t.y), maxOf(from.x, t.x), maxOf(from.y, t.y))
    }

    fun erase() {
        val b = box() ?: return
        val p = onPage
        ready = false
        to = null
        scope.launch {
            runCatching { session.change("erase") { it.eraseArea(p, b) } }
                .onSuccess { model.say(model.str(R.string.erased), action = model.str(R.string.undo)) { scope.launch { session.undo() } } }
                .onFailure { model.say(model.errorText(it)) }
        }
    }

    OnDebug { c ->
        when (c.cmd) {
            // "page,x0,y0,x1,y1" in points, page from 1.
            "erasebox" -> {
                val v = c.value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (v.size == 5) {
                    onPage = v[0].toInt() - 1
                    from = Offset(v[1], v[2])
                    to = Offset(v[3], v[4])
                    ready = true
                }
            }
            "eraseok" -> erase()
        }
    }

    val gestures = remember(session) {
        object : PageGestures {
            override val doubleTapZooms: Boolean get() = false

            override fun wantsDrag(page: Int?, point: Offset, view: Offset): Boolean =
                page != null && session.state.value.rights?.edit != false

            override fun onDragStart(page: Int, point: Offset, view: Offset) {
                ready = false
                val size = session.state.value.pageSizes.getOrNull(page)
                val p = if (size != null) Offset(point.x.coerceIn(0f, size.width), point.y.coerceIn(0f, size.height)) else point
                onPage = page
                from = p
                to = p
            }

            override fun onDrag(page: Int, point: Offset, view: Offset) {
                val size = session.state.value.pageSizes.getOrNull(page) ?: return
                to = Offset(point.x.coerceIn(0f, size.width), point.y.coerceIn(0f, size.height))
            }

            override fun onDragEnd() {
                val t = to ?: return
                // A tap-sized box is not meant.
                if (abs(t.x - from.x) < 4f || abs(t.y - from.y) < 4f) {
                    to = null
                    return
                }
                ready = true
            }

            override fun onDragCancel() {
                to = null
                ready = false
            }

            override fun onTap(page: Int?, point: Offset, view: Offset) {
                to = null
                ready = false
            }
        }
    }

    val overlay: DrawScope.(PageLayout) -> Unit = { layout ->
        val b = box()
        if (b != null && onPage >= 0) {
            val r = layout.viewRect(viewport, onPage, b.x0, b.y0, b.x1, b.y1)
            drawRect(Color(0x33E34850), r.topLeft, r.size)
            // Hatching, so the area reads as "to be removed".
            clipRect(r.left, r.top, r.right, r.bottom) {
                val step = 10.dp.toPx()
                var x = r.left - r.height
                while (x < r.right) {
                    drawLine(Color(0x66E34850), Offset(x, r.bottom), Offset(x + r.height, r.top), 1.dp.toPx())
                    x += step
                }
            }
            drawRect(Color(0xFFE34850), r.topLeft, r.size, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }
    }

    val chrome: @Composable BoxScope.() -> Unit = {
        val c = Power.colors
        Column(Modifier.align(Alignment.TopCenter)) {
            ModeTopBar(stringResource(R.string.tool_erase), onDone = onExit) { UndoRedo(session, scope) }
        }
        val b = box()
        if (ready && b != null) {
            val r = viewport.layout.viewRect(viewport, onPage, b.x0, b.y0, b.x1, b.y1)
            val y = r.bottom + with(density) { 12.dp.toPx() }
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, y.roundToInt()) }
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(c.surface)
                        .border(1.dp, c.line, CircleShape)
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconAction(R.drawable.ic_close, stringResource(R.string.cancel), { to = null; ready = false })
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(c.danger)
                            .clickable { erase() }
                            .heightIn(min = 40.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sym(R.drawable.ic_delete, null, tint = Color.White, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.erase), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            ModeHint(stringResource(if (session.state.value.rights?.edit == false) R.string.error_restricted else R.string.erase_hint))
            ModeBottom {
                Text(
                    stringResource(R.string.erase_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }

    return remember(gestures, overlay, chrome) { ModeTool(gestures, overlay, chrome) }
}
