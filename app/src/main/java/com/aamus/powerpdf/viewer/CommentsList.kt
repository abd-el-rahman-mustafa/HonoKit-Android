package com.aamus.powerpdf.viewer

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.CommentThreads
import com.aamus.powerpdf.engine.MarkInfo
import com.aamus.powerpdf.engine.MarkKind
import com.aamus.powerpdf.engine.markedText
import com.aamus.powerpdf.engine.marks
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.SheetTitle
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.theme.Power

/** One mark in the list: its page, what it is, and the words it marks (for highlights). */
data class CommentEntry(val page: Int, val mark: MarkInfo, val quote: String)

/** The groups the list filters by. */
private enum class CommentGroup(val label: Int, val kinds: Set<MarkKind>) {
    Notes(R.string.comments_notes, setOf(MarkKind.Note, MarkKind.TextBox)),
    Marks(R.string.comments_marks, setOf(MarkKind.Highlight, MarkKind.Underline, MarkKind.StrikeOut)),
    Drawings(R.string.comments_drawings, setOf(MarkKind.Ink, MarkKind.Rect, MarkKind.Ellipse, MarkKind.Line, MarkKind.Arrow)),
    Other(R.string.comments_other, setOf(MarkKind.Stamp, MarkKind.Other)),
}

fun markIcon(kind: MarkKind): Int = when (kind) {
    MarkKind.Highlight -> R.drawable.ic_ink_highlighter
    MarkKind.Underline -> R.drawable.ic_format_underlined
    MarkKind.StrikeOut -> R.drawable.ic_format_strikethrough
    MarkKind.Ink -> R.drawable.ic_draw
    MarkKind.Rect -> R.drawable.ic_shape_rect
    MarkKind.Ellipse -> R.drawable.ic_shape_ellipse
    MarkKind.Line -> R.drawable.ic_shape_line
    MarkKind.Arrow -> R.drawable.ic_shape_arrow
    MarkKind.Note -> R.drawable.ic_sticky_note_2
    MarkKind.TextBox -> R.drawable.ic_text_fields
    MarkKind.Stamp, MarkKind.Other -> R.drawable.ic_bookmark
}

fun markName(kind: MarkKind): Int = when (kind) {
    MarkKind.Highlight -> R.string.highlight
    MarkKind.Underline -> R.string.underline
    MarkKind.StrikeOut -> R.string.strikeout
    MarkKind.Ink -> R.string.pen
    MarkKind.Rect -> R.string.shape_rect
    MarkKind.Ellipse -> R.string.shape_ellipse
    MarkKind.Line -> R.string.shape_line
    MarkKind.Arrow -> R.string.shape_arrow
    MarkKind.Note -> R.string.note
    MarkKind.TextBox -> R.string.text_box
    MarkKind.Stamp -> R.string.stamp
    MarkKind.Other -> R.string.comments_other
}

/** Loads every mark of the document (engine thread), page by page. */
suspend fun loadComments(session: DocumentSession): List<CommentEntry> = session.engine { doc ->
    val out = ArrayList<CommentEntry>()
    for (p in 0 until doc.pageCount) {
        for (m in runCatching { doc.marks(p) }.getOrDefault(emptyList())) {
            val quote = if (m.kind == MarkKind.Highlight || m.kind == MarkKind.Underline || m.kind == MarkKind.StrikeOut) {
                runCatching { doc.markedText(p, m.index, m.box) }.getOrDefault("")
            } else ""
            out.add(CommentEntry(p, m, quote))
        }
    }
    out
}

/**
 * Every comment and mark of the document, grouped by page (research-ux-mobile.md 2.10): a tap
 * jumps to the mark and selects it. Filter chips by kind, with counts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(session: DocumentSession, onDismiss: () -> Unit, onOpen: (CommentEntry) -> Unit) {
    val c = Power.colors
    val context = LocalContext.current
    val state by session.state.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries by produceState<List<CommentEntry>?>(null, state.version) { value = loadComments(session) }
    var filter by remember { mutableStateOf<CommentGroup?>(null) }
    val locale = remember(context) {
        java.util.Locale.Builder().setLocale(context.resources.configuration.locales[0]).setUnicodeLocaleKeyword("nu", "latn").build()
    }
    fun whenText(t: Long): String = when {
        t <= 0L -> ""
        DateUtils.isToday(t) -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(java.util.Date(t))
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date(t))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        val all = entries
        SheetTitle(stringResource(R.string.comments_list))
        if (all == null) {
            Hint(stringResource(R.string.working), Modifier.padding(20.dp))
            return@ModalBottomSheet
        }
        if (all.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Sym(R.drawable.ic_sticky_note_2, null, tint = c.muted, size = 40.dp)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.comments_empty), style = MaterialTheme.typography.titleSmall, color = c.ink)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.comments_empty_hint), style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
            return@ModalBottomSheet
        }
        // Replies and review states go under the comment they answer, wherever they sit (decisions §52).
        val threads = remember(all) { CommentThreads(all, { it.mark.id }, { it.mark.replyTo }, { it.mark.state }) }
        // Filter chips: only kinds this document has, each with its count (comments; their replies show under them).
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(stringResource(R.string.comments_all), threads.top.size, filter == null) { filter = null }
            CommentGroup.entries.forEach { g ->
                val n = threads.top.count { it.mark.kind in g.kinds }
                if (n > 0) FilterChip(stringResource(g.label), n, filter == g) { filter = if (filter == g) null else g }
            }
        }
        val shown = threads.top.filter { e -> filter?.let { e.mark.kind in it.kinds } ?: true }
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.85f), contentPadding = PaddingValues(bottom = 24.dp)) {
            shown.groupBy { it.page }.forEach { (page, list) ->
                item(key = "p$page") {
                    Text(
                        stringResource(R.string.page_n, page + 1),
                        style = MaterialTheme.typography.labelLarge,
                        color = c.muted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { "${it.page}-${it.mark.index}-${it.mark.box}" }) { e ->
                    val m = e.mark
                    val main = when {
                        m.text.isNotBlank() -> m.text
                        e.quote.isNotBlank() -> "«${e.quote}»"
                        else -> stringResource(markName(m.kind))
                    }
                    val meta = listOf(stringResource(markName(m.kind)), m.author, whenText(m.modified)).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    val states = threads.statesOf(e)
                    val replies = threads.repliesOf(e)
                    Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(e) }
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(m.colour.toArgb()).copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) { Sym(markIcon(m.kind), null, tint = Color(m.colour.toArgb()).let { if (m.kind == MarkKind.Highlight) c.ink else it }, size = 20.dp) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(bidi(main), style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (meta.isNotBlank()) Text(bidi(meta), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // Review states ("Accepted by Sara") and replies, indented under the comment.
                    if (states.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 70.dp, end = 20.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            states.forEach { s ->
                                val name = reviewStateName(s.mark.state ?: "")
                                val label = if (s.mark.author.isBlank()) name else stringResource(R.string.review_by, name, s.mark.author)
                                Text(
                                    bidi(label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.muted,
                                    maxLines = 1,
                                    modifier = Modifier.clip(CircleShape).border(1.dp, c.line, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    replies.forEach { (r, depth) -> ReplyRow(r, depth, showPage = r.page != e.page, whenText = ::whenText) { onOpen(r) } }
                    }
                }
            }
        }
    }
}

/** A review state's name as the person reads it; another program's own word as it is. */
@Composable
private fun reviewStateName(state: String): String = when (state) {
    "Accepted" -> stringResource(R.string.review_accepted)
    "Rejected" -> stringResource(R.string.review_rejected)
    "Cancelled" -> stringResource(R.string.review_cancelled)
    "Completed" -> stringResource(R.string.review_completed)
    "None" -> stringResource(R.string.review_none)
    "Marked" -> stringResource(R.string.review_marked)
    "Unmarked" -> stringResource(R.string.review_unmarked)
    else -> state
}

/** A reply under its comment: who and when (and its page when it sits on another), then its words. A tap opens it. */
@Composable
private fun ReplyRow(e: CommentEntry, depth: Int, showPage: Boolean, whenText: (Long) -> String, onClick: () -> Unit) {
    val c = Power.colors
    val m = e.mark
    val meta = listOfNotNull(
        m.author.takeIf { it.isNotBlank() },
        whenText(m.modified).takeIf { it.isNotBlank() },
        if (showPage) stringResource(R.string.page_n, e.page + 1) else null,
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.comment_reply), onClick = onClick)
            .padding(start = 70.dp + 12.dp * (minOf(depth, 6) - 1), end = 20.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(c.line))
        Column(Modifier.weight(1f).heightIn(min = 44.dp).padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.Center) {
            Text(bidi(meta), style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.text.isNotBlank()) Text(bidi(m.text), style = MaterialTheme.typography.bodySmall, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val c = Power.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (on) c.accentSoft else Color.Transparent)
            .border(1.dp, if (on) c.accent else c.line, CircleShape)
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) c.accent else c.ink, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = if (on) c.accent else c.muted)
    }
}
