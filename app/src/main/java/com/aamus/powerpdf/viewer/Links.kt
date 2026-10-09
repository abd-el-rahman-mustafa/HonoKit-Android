package com.aamus.powerpdf.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.AppViewModel
import com.aamus.powerpdf.OnDebug
import com.aamus.powerpdf.R
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.Box as PageBox
import com.aamus.powerpdf.engine.LinkRules
import com.aamus.powerpdf.engine.OutlineEntry
import com.aamus.powerpdf.engine.PageLink
import com.aamus.powerpdf.engine.SafeLink
import com.aamus.powerpdf.engine.outline
import com.aamus.powerpdf.engine.pageLinks
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.SheetTitle
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.theme.Power

/*
 * Links and bookmarks in the reader (decisions §51; desktop SelectLayer, SidePanels, LinkDialog).
 * A link to a page of this document goes there at once; a web page or an email opens only after
 * the person says yes (or chose "Always allow" for that site), and only http, https and mailto
 * (engine LinkRules). Everything else never opens.
 */

/** The links on each page, read when a page comes into view and again after every change. */
@Stable
class LinksCache(private val session: DocumentSession) {
    private val pages = HashMap<Int, List<PageLink>>()
    private var version = -1

    suspend fun of(page: Int): List<PageLink> {
        val v = session.state.value.version
        if (v != version) {
            pages.clear()
            version = v
        }
        pages[page]?.let { return it }
        val list = runCatching { session.engine { it.pageLinks(page) } }.getOrDefault(emptyList())
        if (session.state.value.version == v) pages[page] = list
        return list
    }

    /**
     * The link under a tap (page points): one the point is inside, smallest first; else the nearest
     * within [slop] points (a fingertip is wider than a word).
     */
    suspend fun at(page: Int, x: Float, y: Float, slop: Float): PageLink? {
        val list = of(page)
        list.filter { it.box.contains(x, y) }.minByOrNull { it.box.width * it.box.height }?.let { return it }
        fun gap(b: PageBox): Float {
            val dx = maxOf(b.x0 - x, 0f, x - b.x1)
            val dy = maxOf(b.y0 - y, 0f, y - b.y1)
            return kotlin.math.hypot(dx, dy)
        }
        return list.filter { gap(it.box) <= slop }.minByOrNull { gap(it.box) }
    }
}

/**
 * Opens web and email links from a document: refused unless [LinkRules] allows them; asked first
 * ([asking] shows [LinkDialog]) unless the site is trusted.
 */
@Stable
class LinkFollower(private val model: AppViewModel, private val context: Context) {
    var asking by mutableStateOf<SafeLink?>(null)
        private set

    fun follow(address: String) {
        val link = LinkRules.safeLink(address)
        if (link == null) {
            model.say(model.str(R.string.link_refused), long = true)
            return
        }
        if (link.host in model.settings.value.trustedLinkHosts) open(link) else asking = link
    }

    /** The person's answer in [LinkDialog]. */
    fun answer(open: Boolean, always: Boolean) {
        val link = asking ?: return
        asking = null
        if (!open) return
        if (always) model.settings.update { it.copy(trustedLinkHosts = LinkRules.trust(it.trustedLinkHosts, link.host)) }
        open(link)
    }

    private fun open(link: SafeLink) {
        // Checked again right before it leaves the app.
        val safe = LinkRules.safeLink(link.url)
        if (safe == null) {
            model.say(model.str(R.string.link_refused), long = true)
            return
        }
        val uri = Uri.parse(safe.url)
        val intent = if (safe.kind == SafeLink.Kind.Email) Intent(Intent.ACTION_SENDTO, uri)
        else Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            android.util.Log.i("PowerPDF", "link: opened ${safe.kind} ${safe.host}")
        } catch (_: ActivityNotFoundException) {
            model.say(model.str(if (safe.kind == SafeLink.Kind.Email) R.string.link_no_email_app else R.string.link_no_app))
        } catch (e: SecurityException) {
            android.util.Log.w("PowerPDF", "link", e)
            model.say(model.str(R.string.link_no_app))
        }
    }
}

/** A brief outline on a link just tapped, so the person sees what was followed. */
fun DrawScope.drawLinkFlash(page: Int, box: PageBox, viewport: ViewportState, layout: PageLayout, accent: Color) {
    if (page !in layout.pages.indices) return
    val r = layout.viewRect(viewport, page, box.x0, box.y0, box.x1, box.y1)
    val pad = 2.dp.toPx()
    val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    val topLeft = r.topLeft - androidx.compose.ui.geometry.Offset(pad, pad)
    val size = androidx.compose.ui.geometry.Size(r.width + 2 * pad, r.height + 2 * pad)
    drawRoundRect(accent.copy(alpha = 0.18f), topLeft = topLeft, size = size, cornerRadius = radius)
    drawRoundRect(accent.copy(alpha = 0.7f), topLeft = topLeft, size = size, cornerRadius = radius, style = Stroke(1.5.dp.toPx()))
}

/**
 * Acrobat's "Security Warning" for a link in a document (desktop LinkDialog): the whole address
 * (left to right, the site's name in bold; look-alike letters show as xn--…), why to be careful,
 * and "Always allow" for that site. Cancel is the safe default: it takes the keyboard focus, and
 * Back or a tap outside cancels.
 */
@Composable
fun LinkDialog(link: SafeLink, onAnswer: (open: Boolean, always: Boolean) -> Unit) {
    val c = Power.colors
    val email = link.kind == SafeLink.Kind.Email
    var always by remember(link) { mutableStateOf(false) }
    val cancelFocus = remember { FocusRequester() }
    // Focus goes to Cancel with a keyboard only (Compose buttons take no focus in touch mode).
    LaunchedEffect(link) { runCatching { cancelFocus.requestFocus() } }
    OnDebug { cmd ->
        when (cmd.cmd) {
            "linkok" -> onAnswer(true, cmd.value == "always")
            "linkcancel" -> onAnswer(false, false)
        }
    }
    AlertDialog(
        onDismissRequest = { onAnswer(false, false) },
        icon = { Sym(if (email) R.drawable.ic_mail else R.drawable.ic_language, null, tint = c.accent) },
        title = { Text(stringResource(if (email) R.string.link_ask_email_title else R.string.link_ask_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (email) R.string.link_ask_email else R.string.link_ask), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                val address = buildAnnotatedString {
                    val start = link.hostStart.coerceIn(0, link.url.length)
                    val end = (start + link.host.length).coerceAtMost(link.url.length)
                    val shown = link.url.substring(start, end).equals(link.host, ignoreCase = true)
                    if (shown) {
                        withStyle(SpanStyle(color = c.muted)) { append(link.url.substring(0, start)) }
                        withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.Bold)) { append(link.url.substring(start, end)) }
                        withStyle(SpanStyle(color = c.muted)) { append(link.url.substring(end)) }
                    } else {
                        withStyle(SpanStyle(color = c.ink)) { append(link.url) }
                    }
                }
                // An address reads left to right in Arabic too.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.sunken)
                            .border(1.dp, c.line, RoundedCornerShape(12.dp))
                            .heightIn(max = 168.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .semantics { contentDescription = link.url },
                    ) {
                        Text(
                            address,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                        )
                    }
                }
                Text(stringResource(R.string.link_trust_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
                val alwaysLabel = stringResource(if (email) R.string.link_always_email else R.string.link_always, bidi(link.host))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(value = always, role = Role.Checkbox, onValueChange = { always = it })
                        .heightIn(min = 48.dp)
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(always, null, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.faint))
                    Spacer(Modifier.width(8.dp))
                    Text(alwaysLabel, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true, always) }) {
                Text(stringResource(if (email) R.string.link_open_email else R.string.link_open), color = c.accent, style = MaterialTheme.typography.labelLarge, maxLines = 2)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false, false) }, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(R.string.cancel), color = c.accent, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        },
        containerColor = c.surface,
    )
}

/** One row of the bookmark tree as shown (open branches unfolded). */
private data class BookmarkRow(val entry: OutlineEntry, val key: String, val level: Int)

/**
 * The document's bookmarks as a tree (desktop Bookmarks panel): a tap goes to the page (a web
 * entry, with a globe, asks first); the chevron opens and closes a branch. Branches start as the
 * file has them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(session: DocumentSession, current: Int, onDismiss: () -> Unit, onPage: (OutlineEntry) -> Unit, onAddress: (String) -> Unit) {
    val c = Power.colors
    val state by session.state.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val items by produceState<List<OutlineEntry>?>(null, state.version) {
        value = runCatching { session.engine { it.outline() } }.getOrDefault(emptyList())
    }
    // Which branches are open: from the file at first, then the person's choice.
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val rows = remember(items, open.toMap()) {
        val out = ArrayList<BookmarkRow>()
        fun flatten(list: List<OutlineEntry>, prefix: String, level: Int) {
            list.forEachIndexed { i, e ->
                val key = "$prefix$i"
                out.add(BookmarkRow(e, key, level))
                if (e.children.isNotEmpty() && (open[key] ?: e.open)) flatten(e.children, "$key.", level + 1)
            }
        }
        flatten(items ?: emptyList(), "", 0)
        out
    }
    fun toggle(row: BookmarkRow) {
        open[row.key] = !(open[row.key] ?: row.entry.open)
    }
    fun follow(row: BookmarkRow) {
        val e = row.entry
        when {
            e.page != null -> onPage(e)
            e.uri != null -> onAddress(e.uri!!)
            e.children.isNotEmpty() -> toggle(row)
        }
    }
    LaunchedEffect(rows) {
        rows.forEachIndexed { i, r ->
            android.util.Log.i("PowerPDF", "bookmark ${i + 1}: ${"  ".repeat(r.level)}${r.entry.title} → ${r.entry.page?.let { "page ${it + 1}" } ?: r.entry.uri ?: "nowhere"}" + if (r.entry.children.isNotEmpty()) (if (open[r.key] ?: r.entry.open) " [open]" else " [closed]") else "")
        }
    }
    OnDebug { cmd ->
        val i = (cmd.value.toIntOrNull() ?: 0) - 1
        when (cmd.cmd) {
            "bookmark" -> rows.getOrNull(i)?.let { follow(it) }
            "bookmarkexpand" -> rows.getOrNull(i)?.takeIf { it.entry.children.isNotEmpty() }?.let { toggle(it) }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        SheetTitle(stringResource(R.string.bookmarks))
        val all = items
        when {
            all == null -> Hint(stringResource(R.string.working), Modifier.padding(20.dp))
            all.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Sym(R.drawable.ic_bookmark, null, tint = c.muted, size = 40.dp)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.bookmarks_empty), style = MaterialTheme.typography.titleSmall, color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.bookmarks_empty_hint), style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.size(24.dp))
            }
            else -> LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
                itemsIndexed(rows, key = { _, r -> r.key }) { _, row ->
                    BookmarkLine(row, here = row.entry.page == current, expanded = open[row.key] ?: row.entry.open, onToggle = { toggle(row) }, onClick = { follow(row) })
                }
            }
        }
    }
}

@Composable
private fun BookmarkLine(row: BookmarkRow, here: Boolean, expanded: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val c = Power.colors
    val e = row.entry
    val goes = e.page != null || e.uri != null
    val hasKids = e.children.isNotEmpty()
    val title = e.title.ifBlank { stringResource(R.string.bookmarks_untitled) }
    val where = when {
        e.uri != null -> stringResource(R.string.bookmarks_outside, e.uri!!)
        e.page != null -> stringResource(R.string.link_go_to, e.page!! + 1)
        else -> stringResource(R.string.bookmarks_nowhere)
    }
    val expandLabel = stringResource(if (expanded) R.string.bookmarks_collapse else R.string.bookmarks_expand)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (here) c.accentSoft else Color.Transparent)
            .clickable(enabled = goes || hasKids, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $where"
                if (hasKids) stateDescription = expandLabel
            }
            .heightIn(min = 48.dp)
            // Indented by depth (capped so deep trees keep room for their titles); start side in Arabic too.
            .padding(start = (row.level.coerceAtMost(6) * 18).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasKids) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = expandLabel, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                // chevron_right mirrors itself in Arabic, so a closed branch points into the text.
                Sym(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right, expandLabel, tint = c.muted, size = 20.dp)
            }
        } else {
            Spacer(Modifier.width(44.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (row.level == 0) FontWeight.Medium else FontWeight.Normal,
            color = if (goes || hasKids) c.ink else c.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        Spacer(Modifier.width(12.dp))
        when {
            e.uri != null -> Sym(R.drawable.ic_language, null, tint = c.muted, size = 18.dp)
            e.page != null -> Text("${e.page!! + 1}", style = MaterialTheme.typography.labelMedium, color = if (here) c.accent else c.muted, maxLines = 1)
        }
    }
}
