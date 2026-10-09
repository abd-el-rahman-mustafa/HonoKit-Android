package com.aamus.powerpdf.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.Box
import com.aamus.powerpdf.engine.TextLine
import com.aamus.powerpdf.engine.TextLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** A search hit: a box on a page. */
data class Hit(val page: Int, val box: Box)

/** A text selection on one page (selections do not cross pages on phones). */
data class TextSelection(val page: Int, val sel: TextLogic.Selection, val start: Pair<Int, Int>, val end: Pair<Int, Int>)

/** The viewer's own state: search, selection, which tool is active. */
@Stable
class ViewerModel(val session: DocumentSession, private val scope: CoroutineScope) {
    /** A mark picked in the comments list: Comment mode selects it when it opens. */
    var pendingMark by androidx.compose.runtime.mutableStateOf<CommentEntry?>(null)


    // --- search ---------------------------------------------------------------------------------

    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<Hit>>(emptyList())
        private set
    var hitIndex by mutableIntStateOf(-1)
    var searching by mutableStateOf(false)
        private set
    /** True when the search reached the end of the document. */
    var searchDone by mutableStateOf(false)
        private set
    var noText by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    /** Searches every page, nearest to [fromPage] first in reading order; results arrive as they are found. */
    fun search(text: String, fromPage: Int, onFirst: (Hit) -> Unit) {
        searchJob?.cancel()
        query = text
        hits = emptyList()
        hitIndex = -1
        searchDone = false
        noText = false
        if (text.isBlank()) {
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            val count = session.state.value.pageCount
            val found = ArrayList<Hit>()
            var anyText = false
            var announced = false
            for (p in 0 until count) {
                val boxes = session.engine { doc ->
                    val lines = doc.lines(p)
                    if (!anyText && lines.any { l -> l.glyphs.any { !it.c.isBlank() } }) anyText = true
                    TextLogic.searchLines(lines, text)
                }
                boxes.forEach { found.add(Hit(p, it)) }
                hits = found.toList()
                if (!announced && found.isNotEmpty()) {
                    // Go to the first hit at or after the page being read.
                    val idx = found.indexOfFirst { it.page >= fromPage }.takeIf { it >= 0 }
                    if (idx != null) {
                        announced = true
                        hitIndex = idx
                        onFirst(found[idx])
                    }
                }
                yield()
            }
            if (!announced && found.isNotEmpty()) {
                hitIndex = 0
                onFirst(found[0])
            }
            noText = !anyText
            searching = false
            searchDone = true
        }
    }

    fun step(delta: Int): Hit? {
        if (hits.isEmpty()) return null
        hitIndex = ((hitIndex + delta) % hits.size + hits.size) % hits.size
        return hits[hitIndex]
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchOpen = false
        query = ""
        hits = emptyList()
        hitIndex = -1
        searching = false
    }

    // --- text selection -------------------------------------------------------------------------

    var selection by mutableStateOf<TextSelection?>(null)

    /** Lines of pages already read, for quick selection on the main thread. */
    private val linesCache = HashMap<Int, List<TextLine>>()
    private var linesVersion = -1

    suspend fun linesOf(page: Int): List<TextLine> {
        val v = session.state.value.version
        if (v != linesVersion) {
            linesCache.clear()
            linesVersion = v
        }
        linesCache[page]?.let { return it }
        val l = session.engine { it.lines(page) }
        linesCache[page] = l
        return l
    }

    fun cachedLines(page: Int): List<TextLine>? = if (linesVersion == session.state.value.version) linesCache[page] else null

    /** Long press: select the word under the finger. */
    suspend fun selectWordAt(page: Int, point: Offset): Boolean {
        val lines = linesOf(page)
        val li = TextLogic.lineAt(lines, point.x, point.y, slack = 0.4f)
        if (li < 0) return false
        val gi = TextLogic.glyphAt(lines[li], point.x)
        val sel = TextLogic.selectText(lines, point.x to point.y, point.x to point.y, TextLogic.Unit.Word) ?: return false
        // Anchor and focus are the word's ends, found again from the selection's first/last character.
        val a = positionOf(lines, sel.from) ?: (li to gi)
        val b = positionOf(lines, sel.to) ?: (li to gi)
        selection = TextSelection(page, sel, a, b)
        pressWord = a to b
        return true
    }

    private fun positionOf(lines: List<TextLine>, p: Pair<Float, Float>): Pair<Int, Int>? {
        val li = TextLogic.lineAt(lines, p.first, p.second, slack = 0.4f)
        if (li < 0) return null
        return li to TextLogic.glyphAt(lines[li], p.first)
    }

    private fun ordered(p: Pair<Int, Int>, q: Pair<Int, Int>) =
        if (p.first < q.first || (p.first == q.first && p.second <= q.second)) p to q else q to p

    /** Where a long press started; moving the finger extends the selection from its word. */
    private var pressWord: Pair<Pair<Int, Int>, Pair<Int, Int>>? = null

    /** Moves one end of the selection to a point (dragging a handle). */
    fun moveSelectionEnd(start: Boolean, point: Offset) {
        val s = selection ?: return
        val lines = cachedLines(s.page) ?: return
        val li = TextLogic.lineAt(lines, point.x, point.y, slack = 1.2f)
        if (li < 0) return
        val moving = li to TextLogic.glyphAt(lines[li], point.x)
        val fixed = if (start) s.end else s.start
        val (a, b) = ordered(moving, fixed)
        val sel = TextLogic.spanOf(lines, a.first, a.second, b.first, b.second) ?: return
        selection = TextSelection(s.page, sel, a, b)
    }

    /** Extends the selection while the finger moves after a long press. */
    fun extendTo(point: Offset) {
        val s = selection ?: return
        val word = pressWord ?: (s.start to s.end)
        val lines = cachedLines(s.page) ?: return
        val li = TextLogic.lineAt(lines, point.x, point.y, slack = 1.2f)
        if (li < 0) return
        val moving = li to TextLogic.glyphAt(lines[li], point.x)
        val (a, b) = if (ordered(moving, word.first).first == moving) moving to word.second else word.first to moving
        val sel = TextLogic.spanOf(lines, a.first, a.second, b.first, b.second) ?: return
        selection = TextSelection(s.page, sel, a, b)
    }

    suspend fun selectAll(page: Int) {
        val lines = linesOf(page)
        val sel = TextLogic.selectAll(lines) ?: return
        val first = lines.indexOfFirst { it.glyphs.isNotEmpty() }
        val last = lines.indexOfLast { it.glyphs.isNotEmpty() }
        selection = TextSelection(page, sel, first to 0, last to lines[last].glyphs.lastIndex)
    }

    fun clearSelection() {
        selection = null
    }
}
