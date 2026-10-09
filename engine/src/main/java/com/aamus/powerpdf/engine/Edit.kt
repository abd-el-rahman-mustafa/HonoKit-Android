package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.DisplayListDevice
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Story
import com.artifex.mupdf.fitz.StructuredTextWalker
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editing the text already on a page, a paragraph at a time (desktop decisions §26, §27):
 *   1. the paragraph is found among lines rebuilt with their style (MuPDF's own blocks over-merge:
 *      a title with its body text), and its words are REMOVED with a text-only redaction per line,
 *      so the old words are really gone, not covered;
 *   2. the new text is laid out in the same column, at the same size, colour, weight and line
 *      spacing, keeping the alignment, by MuPDF's HTML engine (Arabic shaped by HarfBuzz;
 *      decisions §7), and its first baseline is put where the old one was.
 * The original typeface is usually an embedded subset without the new letters, so it is matched by
 * kind (serif, sans, mono: MuPDF's Times, Helvetica and Courier clones; IBM Plex Sans Arabic for
 * Arabic), not reused. Coordinates are the page as displayed: points from the top-left.
 */
enum class FontKind { Serif, Sans, Mono }

enum class ParagraphAlign { Start, End, Centre, Justify }

/** A paragraph of a page, for the edit box. */
data class ParagraphInfo(
    val page: Int,
    /** The words, lines joined by spaces: the person edits sentences, the engine re-wraps them. */
    val text: String,
    val box: Box,
    val lines: List<Box>,
    val rtl: Boolean,
    val size: Float,
    val colour: Int,
    val bold: Boolean,
    val italic: Boolean,
    val kind: FontKind,
    val align: ParagraphAlign,
    /** Distance between baselines. */
    val pitch: Float,
    /** Some words are in another style (a bold word): retyped, they take the paragraph's style. */
    val mixed: Boolean,
    /** Where the first line starts, where the others start, and the column's far edge. */
    val firstStart: Float,
    val restStart: Float,
    val farEdge: Float,
    val firstBaseline: Float,
    /**
     * Characters the file does not store as text (shown as U+FFFD): retyped as they are, they
     * would be lost, so they must be typed before the paragraph is changed.
     */
    val unreadable: Int = 0,
    /** Letters rebuilt from a ligature's drawing without a clear match (Ligatures.kt): worth a look. */
    val guessed: Int = 0,
)

/** Changes to a paragraph's look; null keeps what it has. */
data class ParagraphChange(
    val size: Float? = null,
    val colour: Int? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val kind: FontKind? = null,
    val align: ParagraphAlign? = null,
)

data class EditOutcome(
    /** The new text needs more lines than the old: it may run into what follows. */
    val grew: Boolean,
    /** Words in another style now share the paragraph's style. */
    val unified: Boolean,
)

private class GlyphStyle(val size: Float, val colour: Int, val bold: Boolean, val italic: Boolean, val kind: FontKind, val baseline: Float) {
    val key = "${(size * 2).roundToInt()}|$bold|$italic|$kind|$colour"
}

internal class StyledLine(
    var text: String,
    var box: Box,
    val baseline: Float,
    var start: Float,
    val rtl: Boolean,
    val size: Float,
    val colour: Int,
    val bold: Boolean,
    val italic: Boolean,
    val kind: FontKind,
    val horizontal: Boolean,
    /** Some letters differ from the line's main style (a bold word in a sentence). */
    val mixed: Boolean,
    /** A table-of-contents entry or a table row: never joined into a flowing paragraph. */
    val tabular: Boolean,
    /** Characters without text in the file (U+FFFD), and letters guessed from a ligature's drawing. */
    var unreadable: Int = 0,
    var guessed: Int = 0,
)

internal class Paragraph(
    val lines: List<StyledLine>,
    val text: String,
    val box: Box,
    val size: Float,
    val colour: Int,
    val bold: Boolean,
    val italic: Boolean,
    val kind: FontKind,
    val rtl: Boolean,
    val align: ParagraphAlign,
    val pitch: Float,
    val firstStart: Float,
    val restStart: Float,
    val farEdge: Float,
    val mixed: Boolean,
    val unreadable: Int,
    val guessed: Int,
    /** The page's words, which decide whether a hyphen at a line's end joins a word (joinLines). */
    val written: Set<String> = emptySet(),
    /** Some lines of a paragraph laid out again (the rest kept): never treated as a one-line label. */
    val part: Boolean = false,
) {
    /** The text of lines [from] until [to], joined as [text] is. */
    fun textOf(from: Int, to: Int): String =
        TextLogic.joinLines(lines.subList(from, to).map { it.text }, lines.subList(from, to).map { it.rtl }, written).let { if (rtl) TextLogic.unmirrorText(it) else it }

    /** Lines [from] until [to] as a paragraph of their own in this one's column. */
    fun part(from: Int, to: Int): Paragraph {
        val ls = lines.subList(from, to)
        return Paragraph(
            ls, textOf(from, to),
            Box(ls.minOf { it.box.x0 }, ls.minOf { it.box.y0 }, ls.maxOf { it.box.x1 }, ls.maxOf { it.box.y1 }),
            size, colour, bold, italic, kind, rtl, align, pitch,
            firstStart = if (from == 0) firstStart else restStart, restStart = restStart, farEdge = farEdge,
            mixed = mixed, unreadable = unreadable, guessed = guessed, written = written, part = true,
        )
    }

    fun info(page: Int) = ParagraphInfo(
        page, text, box, lines.map { it.box }, rtl, size, colour, bold, italic, kind, align, pitch, mixed,
        firstStart, restStart, farEdge, lines.first().baseline, unreadable, guessed,
    )
}

private val MONO = Regex("courier|mono", RegexOption.IGNORE_CASE)
private val SANS = Regex("sans|arial|helvetica|verdana|tahoma|calibri|segoe", RegexOption.IGNORE_CASE)
private val SERIF = Regex("times|serif|roman|georgia|garamond|century|book", RegexOption.IGNORE_CASE)
private val BOLD = Regex("bold|black|heavy|semibold", RegexOption.IGNORE_CASE)
private val ITALIC = Regex("italic|oblique", RegexOption.IGNORE_CASE)
private val TABULAR = Regex("""(\.\s?){4,}|…{2,}|_{4,}|(·\s?){3,}""")
private val SPACES = Regex("\\s+")
private val MARKER_ONLY = Regex("""^([•▪◦‣●○■□·\-–*]|\d{1,3}[.)])$""")

/** The name wins over the font's own flags, which files often get wrong (Work Sans flagged serif). */
private fun kindOf(font: Font?, name: String): FontKind = when {
    MONO.containsMatchIn(name) || runCatching { font?.isMono }.getOrNull() == true -> FontKind.Mono
    SANS.containsMatchIn(name) -> FontKind.Sans
    runCatching { font?.isSerif }.getOrNull() == true || SERIF.containsMatchIn(name) -> FontKind.Serif
    else -> FontKind.Sans
}

private fun <T> mostCommon(values: List<T>, key: (T) -> String): T =
    values.groupBy(key).maxBy { it.value.size }.value.first()

/** Every line of text on a page with its style, characters in reading order. Blank lines are left out. */
internal fun PdfDoc.styledLines(index: Int): List<StyledLine> {
    class Raw(val glyphs: MutableList<Glyph>, val horizontal: Boolean)
    val raws = ArrayList<Raw>()
    val styles = IdentityHashMap<Glyph, GlyphStyle>()
    // Letters rebuilt from a ligature's drawing without a clear match.
    val guessed = java.util.Collections.newSetFromMap(IdentityHashMap<Glyph, Boolean>())
    // Weight, slant and kind once per font, not per letter (tens of thousands of matches a page).
    class Face(val bold: Boolean, val italic: Boolean, val kind: FontKind)
    val faces = HashMap<String, Face>()
    withPage(index) { page ->
        val fixes = textFixes(index, page)
        val stext = page.toStructuredText("preserve-whitespace")
        try {
            var current = ArrayList<Glyph>()
            var horizontal = true
            stext.walk(object : StructuredTextWalker {
                override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: com.artifex.mupdf.fitz.Image?) {}
                override fun beginTextBlock(bbox: Rect?, flags: Int) {}
                override fun endTextBlock() {}
                override fun beginLine(bbox: Rect, wmode: Int, dir: Point?) {
                    current = ArrayList()
                    horizontal = dir == null || abs(dir.y) < 0.01f
                }
                override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, q: Quad, argb: Int, flags: Int, bidi: Int) {
                    val g = Glyph(
                        String(Character.toChars(c)),
                        minOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x), minOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y),
                        maxOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x), maxOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y),
                    )
                    val name = runCatching { font?.name }.getOrNull().orEmpty()
                    val face = faces.getOrPut(name) {
                        Face(
                            bold = runCatching { font?.isBold }.getOrNull() == true || BOLD.containsMatchIn(name),
                            italic = runCatching { font?.isItalic }.getOrNull() == true || ITALIC.containsMatchIn(name),
                            kind = kindOf(font, name),
                        )
                    }
                    val style = GlyphStyle(
                        size = size,
                        colour = argb or (0xFF shl 24),
                        bold = face.bold,
                        italic = face.italic,
                        kind = face.kind,
                        baseline = origin?.y ?: g.y1,
                    )
                    // A ligature the file gives too few letters for gets them all back.
                    val fix = if (fixes.isEmpty || origin == null) null else fixes.at(name, origin.x, origin.y)
                    val parts = if (fix != null) LigatureLogic.split(g, fix.text) else listOf(g)
                    for (part in parts) {
                        current.add(part)
                        styles[part] = style
                        if (fix?.guessed == true) guessed.add(part)
                    }
                }
                override fun endLine() {
                    TextLogic.dropPhantoms(current)
                    TextLogic.repairLigatureOrder(current)
                    // Two headlines side by side on one baseline are two lines.
                    if (horizontal) TextLogic.splitAtSizeJumps(current).forEach { raws.add(Raw(it.toMutableList(), true)) }
                    else raws.add(Raw(current, horizontal))
                }
                override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                override fun endStruct() {}
                override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
            })
        } finally {
            stext.destroy()
        }
    }
    // A second copy printed on top of a line is the same text, not more of it.
    val distinct = TextLogic.distinctLines(raws.map { it.glyphs }).map { raws[it] }
    val pageRtl = TextLogic.pageIsRtl(distinct.map { TextLine(it.glyphs, 0f, 0f, 0f, 0f) })
    val out = ArrayList<StyledLine>()
    // A line drawn run by run comes as several; joined back into one.
    for (joined in TextLogic.joinPieces(distinct.map { it.glyphs })) {
        val raw = Raw(joined.glyphs.toMutableList(), joined.members.all { distinct[it].horizontal })
        val ordered = TextLogic.logicalOrder(raw.glyphs, pageRtl)
        val kept = TextLogic.unmirrorPairs(ordered)
        // A turned-back bracket is a new glyph: it keeps the style of the one it replaces.
        if (kept !== ordered) kept.forEachIndexed { k, g -> if (styles[g] == null) styles[ordered[k]]?.let { styles[g] = it } }
        if (kept !== ordered) kept.forEachIndexed { k, g -> if (ordered[k] in guessed) guessed.add(g) }
        val printed = kept.filter { it.c.isNotBlank() }
        if (printed.isEmpty()) continue
        val text = kept.joinToString("") { it.c }.replace(SPACES, " ").trim()
        // Symbol-font bullets and the like are part of the line but not its style.
        val letters = printed.filter { Character.isLetterOrDigit(it.c.codePointAt(0)) }
        val body = letters.ifEmpty { printed }
        val main = styles.getValue(mostCommon(body) { styles.getValue(it).key })
        val rtl = majorityRtl(text) ?: false
        // A jump between neighbouring glyphs much wider than a space: a tab stop or a table column.
        // Justified text spreads every word gap alike, so only a gap far wider than the others counts.
        val byX = printed.sortedBy { it.x0 }
        val gaps = (1 until byX.size).map { byX[it].x0 - byX[it - 1].x1 }.filter { it > main.size * 0.15f }.sorted()
        val widest = gaps.lastOrNull() ?: 0f
        val typical = if (gaps.size > 1) gaps[(gaps.size - 1) / 2] else 0f
        val tabStop = widest > main.size * 1.6f && (gaps.size < 2 || widest > typical * 2.5f)
        val box = Box(printed.minOf { it.x0 }, printed.minOf { it.y0 }, printed.maxOf { it.x1 }, printed.maxOf { it.y1 })
        out.add(
            StyledLine(
                text = text,
                box = box,
                baseline = main.baseline,
                start = if (rtl) box.x1 else box.x0,
                rtl = rtl,
                size = main.size,
                colour = main.colour,
                bold = main.bold,
                italic = main.italic,
                kind = main.kind,
                horizontal = raw.horizontal,
                mixed = body.any { val s = styles.getValue(it); s.bold != main.bold || s.italic != main.italic || abs(s.size - main.size) > 0.6f },
                tabular = TABULAR.containsMatchIn(text) || tabStop,
                unreadable = printed.count { it.c == "�" },
                guessed = printed.count { it in guessed },
            ),
        )
    }
    return joinBullets(out)
}

/**
 * A bullet drawn as its own piece of text comes back as a line of its own; joined to the text
 * beside it, so the bullet and its words form one line and the paragraph keeps its hanging indent.
 */
private fun joinBullets(lines: List<StyledLine>): List<StyledLine> {
    val gone = HashSet<StyledLine>()
    for (marker in lines) {
        if (!MARKER_ONLY.matches(marker.text)) continue
        fun gap(l: StyledLine) = if (marker.rtl) marker.box.x0 - l.box.x1 else l.box.x0 - marker.box.x1
        val text = lines.firstOrNull { l ->
            l !== marker && l !in gone && abs(l.baseline - marker.baseline) < marker.size * 0.3f && gap(l) > 0 && gap(l) < marker.size * 3
        } ?: continue
        gone.add(marker)
        text.text = "${marker.text} ${text.text}"
        text.unreadable += marker.unreadable
        text.box = Box(minOf(marker.box.x0, text.box.x0), minOf(marker.box.y0, text.box.y0), maxOf(marker.box.x1, text.box.x1), maxOf(marker.box.y1, text.box.y1))
        text.start = if (text.rtl) text.box.x1 else text.box.x0
    }
    return lines.filter { it !in gone }
}

private fun sameStyle(a: StyledLine, b: StyledLine) = abs(a.size - b.size) < 0.6f && a.bold == b.bold && a.rtl == b.rtl && a.kind == b.kind

/**
 * Groups lines into paragraphs, top to bottom: same size, weight and direction, regular spacing,
 * overlapping columns. A blank line, a jump in spacing or a change of style ends a paragraph.
 */
internal fun paragraphsOf(lines: List<StyledLine>): List<Paragraph> {
    val sorted = lines.filter { it.horizontal }.sortedWith(compareBy<StyledLine> { it.baseline }.thenBy { it.box.x0 })
    val groups = ArrayList<MutableList<StyledLine>>()
    for (line in sorted) {
        var joined = false
        for (g in groups.lastIndex downTo maxOf(0, groups.size - 4)) {
            val group = groups[g]
            val last = group.last()
            val gap = line.baseline - last.baseline
            val pitch = if (group.size > 1) group[group.size - 1].baseline - group[group.size - 2].baseline else null
            val overlap = minOf(line.box.x1, last.box.x1) - maxOf(line.box.x0, last.box.x0)
            val regular = if (pitch == null) gap > last.size * 0.9f && gap < last.size * 1.75f else abs(gap - pitch) < last.size * 0.25f
            // A line well short of the column ended its paragraph (the usual last line).
            val column = maxOf(group.maxOf { it.box.width }, line.box.width)
            val lastWasFull = last.box.width >= column * 0.6f
            val joinable = !line.tabular && !last.tabular && lastWasFull
            if (joinable && sameStyle(line, last) && regular && overlap > minOf(line.box.width, last.box.width) * 0.3f) {
                group.add(line)
                joined = true
                break
            }
        }
        if (!joined) groups.add(arrayListOf(line))
    }
    val written = TextLogic.wordsOf(lines.map { it.text })
    return groups.map { describe(it, written) }
}

private fun describe(lines: List<StyledLine>, written: Set<String>): Paragraph {
    val first = lines.first()
    val rtl = mostCommon(lines) { it.rtl.toString() }.rtl
    val body = mostCommon(lines) { "${(it.size * 2).roundToInt()}|${it.bold}|${it.italic}|${it.kind}" }
    val box = Box(lines.minOf { it.box.x0 }, lines.minOf { it.box.y0 }, lines.maxOf { it.box.x1 }, lines.maxOf { it.box.y1 })
    val pitches = (1 until lines.size).map { lines[it].baseline - lines[it - 1].baseline }.sorted()
    val pitch = if (pitches.isNotEmpty()) pitches[pitches.size / 2] else first.size * 1.2f
    // Which edges line up decides the alignment; the last line of a paragraph is usually short.
    val starts = lines.map { if (rtl) it.box.x1 else it.box.x0 }
    val ends = lines.map { if (rtl) it.box.x0 else it.box.x1 }
    val tol = maxOf(2f, first.size * 0.35f)
    val bodyStarts = starts.drop(1)
    val startsAlign = bodyStarts.isEmpty() || bodyStarts.all { abs(it - bodyStarts[0]) < tol }
    val fullLines = ends.dropLast(1)
    // Most lines reach the column's far edge. One ending early (a long word carried over, a forced
    // break) does not make a justified newspaper column ragged: retyped ragged, it read back as
    // two paragraphs (the round-trip test, decisions §22).
    val edge = (if (rtl) fullLines.minOrNull() else fullLines.maxOrNull()) ?: 0f
    val endsAlign = fullLines.isNotEmpty() && fullLines.count { abs(it - edge) < tol } * 5 >= fullLines.size * 4
    val centres = lines.map { (it.box.x0 + it.box.x1) / 2 }
    val centred = lines.size > 1 && centres.all { abs(it - centres[0]) < tol } && !startsAlign
    val align = when {
        centred -> ParagraphAlign.Centre
        lines.size > 2 && startsAlign && endsAlign -> ParagraphAlign.Justify
        lines.size > 1 && !startsAlign && endsAlign -> ParagraphAlign.End
        else -> ParagraphAlign.Start
    }
    val farEdge = if (rtl) lines.minOf { it.box.x0 } else lines.maxOf { it.box.x1 }
    return Paragraph(
        lines = lines,
        // A word broken across lines is whole again, so it is not retyped with a hyphen mid-line.
        text = TextLogic.joinLines(lines.map { it.text }, lines.map { it.rtl }, written).let { if (rtl) TextLogic.unmirrorText(it) else it },
        box = box,
        size = body.size,
        colour = body.colour,
        bold = body.bold,
        italic = body.italic,
        kind = body.kind,
        rtl = rtl,
        align = align,
        pitch = pitch,
        firstStart = starts[0],
        restStart = if (lines.size > 1) starts[1] else starts[0],
        farEdge = farEdge,
        mixed = lines.any { it.mixed || !sameStyle(it, body) },
        unreadable = lines.sumOf { it.unreadable },
        guessed = lines.sumOf { it.guessed },
        written = written,
    )
}

/**
 * How much of a paragraph stays at whole lines when only its words change (desktop decisions §58):
 * its first [head] and last [tail] lines are word for word in the new text, which changes only
 * between [from] and [to]. At least one line is always set again.
 */
internal class KeptLines(val head: Int, val tail: Int, val from: Int, val to: Int)

internal fun keptLines(p: Paragraph, text: String): KeptLines {
    val n = p.lines.size
    var head = 0
    var from = 0
    while (head < n - 1) {
        val prefix = p.textOf(0, head + 1)
        if (!text.startsWith("$prefix ")) break
        head++
        from = prefix.length + 1
    }
    var tail = 0
    var to = text.length
    while (head + tail < n - 1) {
        val suffix = p.textOf(n - tail - 1, n)
        if (text.length - suffix.length - 1 < from || !text.endsWith(" $suffix")) break
        tail++
        to = text.length - suffix.length - 1
    }
    return KeptLines(head, tail, from, maxOf(from, to))
}

private fun Paragraph.hit(x: Float, y: Float, slack: Float) = lines.any { l ->
    x >= l.box.x0 - slack && x <= l.box.x1 + slack && y >= l.box.y0 - slack && y <= l.box.y1 + slack
}

private fun paragraphAt(all: List<Paragraph>, x: Float, y: Float): Paragraph? =
    all.firstOrNull { it.hit(x, y, 1f) } ?: all.firstOrNull { it.hit(x, y, 4f) }

/** The page's paragraphs (horizontal text only). */
fun PdfDoc.paragraphs(pageIndex: Int): List<ParagraphInfo> =
    paragraphsOf(styledLines(pageIndex)).filter { it.text.isNotBlank() }.map { it.info(pageIndex) }

/** The paragraph at a point of the page (a tap), or null. */
fun PdfDoc.paragraphAt(pageIndex: Int, x: Float, y: Float): ParagraphInfo? =
    paragraphAt(paragraphsOf(styledLines(pageIndex)), x, y)?.info(pageIndex)

/** A line's box trimmed to the middle of its height, so neighbouring lines keep their letters. */
private fun core(b: Box): Box {
    val h = b.height
    return Box(b.x0 - 0.5f, b.y0 + h * 0.2f, b.x1 + 0.5f, b.y1 - h * 0.2f)
}

/**
 * What to redact to remove [lines] and nothing else. MuPDF removes every glyph whose box (its
 * advance, from the font's descender to its ascender, shrunk by a tenth) touches a redaction
 * area, so a drop cap beside a paragraph's first lines went with them (the round-trip test,
 * decisions §22). Each line's area is cut back on the side where another line's glyphs reach in;
 * a line sitting inside it (a superscript) does not shrink it.
 */
private fun redactionBoxes(lines: List<StyledLine>, all: List<StyledLine>): List<Box> {
    val own = java.util.Collections.newSetFromMap(IdentityHashMap<StyledLine, Boolean>()).apply { addAll(lines) }
    return lines.mapNotNull { line ->
        var b = core(line.box)
        for (o in all) {
            if (o in own) continue
            val sx = minOf(o.box.width, o.box.height * 0.6f) / 10f
            val sy = o.box.height / 10f
            val ob = Box(o.box.x0 + sx, o.box.y0 + sy, o.box.x1 - sx, o.box.y1 - sy)
            if (ob.x1 <= b.x0 || ob.x0 >= b.x1 || ob.y1 <= b.y0 || ob.y0 >= b.y1) continue
            // From the side: cut across. From above or below (a drop cap reaching down over the
            // line that starts under it): cut along, keeping the middle of this line's letters.
            val across = when {
                ob.x0 < b.x0 && ob.x1 < b.x1 -> b.copy(x0 = ob.x1 + 0.01f)
                ob.x1 > b.x1 && ob.x0 > b.x0 -> b.copy(x1 = ob.x0 - 0.01f)
                else -> null
            }
            val along = when {
                ob.y0 < b.y0 && ob.y1 < b.y1 -> b.copy(y0 = ob.y1 + 0.01f)
                ob.y1 > b.y1 && ob.y0 > b.y0 -> b.copy(y1 = ob.y0 - 0.01f)
                else -> null
            }
            // The cut that keeps more of this line, as long as some height or width is left.
            b = listOfNotNull(across?.takeIf { it.width > 0.5f }, along?.takeIf { it.height > 0.5f })
                .maxByOrNull { it.width * it.height } ?: b
        }
        if ("logboxes" in EngineDebug.skip) {
            android.util.Log.i("PowerPDF", "redact line '${line.text.take(30)}' ${line.box} → $b")
            all.filter { it !in own && it.box.x1 > line.box.x0 - 40 && it.box.x0 < line.box.x1 + 40 && it.box.y1 > line.box.y0 - 30 && it.box.y0 < line.box.y1 + 30 }
                .forEach { android.util.Log.i("PowerPDF", "   near '${it.text.take(20)}' ${it.box} size=${it.size}") }
        }
        b.takeIf { it.width > 0.5f }
    }
}

/** Removes content inside [boxes] for real: text only, or everything. */
private fun PdfDoc.redact(pageIndex: Int, boxes: List<Box>, everything: Boolean) {
    // MuPDF's TD bug would move or drop the lines after a removed one (ContentFix).
    if ("noleading" !in EngineDebug.skip) explicitLeading(pageIndex)
    // Only what lies under these boxes: the person's pending Redact marks, links and text boxes on
    // the page stay (E1; MuPDF's applyRedactions would apply and delete them).
    safeRedact(
        pageIndex,
        boxes,
        images = if (everything) RedactImages.Pixels else RedactImages.Keep,
        lineArt = if (everything) RedactLineArt.Covered else RedactLineArt.Keep,
    )
}

/** Removes everything inside [box] (text, pictures, drawings) and leaves it blank. */
fun PdfDoc.eraseArea(pageIndex: Int, box: Box) {
    requireEdit()
    if (box.width < 1f || box.height < 1f) throw PdfException(PdfException.Code.BadArguments, "Draw a larger area to erase.")
    redact(pageIndex, listOf(box), everything = true)
}

/** How a paragraph is set: the look, and the column it flows in. */
private data class Setting(
    val size: Float,
    val colour: Int,
    val bold: Boolean,
    val italic: Boolean,
    val kind: FontKind,
    val rtl: Boolean,
    val align: ParagraphAlign,
    val pitch: Float,
    /** Space before the lines after the first, at the start side, and the first line's extra indent. */
    val padStart: Float,
    val indent: Float,
)

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun isArabicScript(cp: Int) = cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF

/**
 * True for right to left, false for left to right: what most letters are, the first letter with a
 * direction breaking a tie; null when there are no letters with a direction.
 */
internal fun majorityRtl(text: String): Boolean? {
    var r = 0
    var l = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> r++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> l++
        }
        i += Character.charCount(cp)
    }
    return if (r == l) strongDirection(text) else r > l
}

/** True for right to left, false for left to right, by the first letter with a direction; null if none. */
private fun strongDirection(text: String): Boolean? {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
        i += Character.charCount(cp)
    }
    return null
}

private fun isStrong(cp: Int): Boolean {
    val d = Character.getDirectionality(cp)
    return d == Character.DIRECTIONALITY_LEFT_TO_RIGHT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
}

private val COMPOUND = Regex("""\S*\p{L}-\p{L}\S*""")

/**
 * [s] as HTML with each hyphenated word («two-time», «self-sufficiency») kept on one line. MuPDF
 * breaks lines after a hyphen; read back, «two-» at a line's end was taken for a broken word and
 * joined as «twotime» (the round-trip test, decisions §22).
 */
private fun keepCompounds(s: String): String {
    val out = StringBuilder()
    var at = 0
    for (m in COMPOUND.findAll(s)) {
        out.append(esc(s.substring(at, m.range.first)))
        out.append("<span style=\"white-space:nowrap\">").append(esc(m.value)).append("</span>")
        at = m.range.last + 1
    }
    out.append(esc(s.substring(at)))
    return out.toString()
}

/** One HTML paragraph; line breaks in [text] are kept. Arabic runs get the Arabic face. */
private fun paragraphHtml(text: String, s: Setting): String {
    val family = when (s.kind) {
        FontKind.Serif -> "serif"
        FontKind.Sans -> "sans-serif"
        FontKind.Mono -> "monospace"
    }
    // MuPDF reads left/right as start/end inside a right-to-left paragraph (decisions §7).
    val align = when (s.align) {
        ParagraphAlign.Start -> "left"
        ParagraphAlign.End -> "right"
        ParagraphAlign.Centre -> "center"
        ParagraphAlign.Justify -> "justify"
    }
    val out = StringBuilder()
    out.append("<p dir=\"").append(if (s.rtl) "rtl" else "ltr").append("\" style=\"")
    out.append("font-family:").append(family).append(';')
    out.append(String.format(java.util.Locale.US, "font-size:%.2fpt;line-height:%.2fpt;", s.size, s.pitch))
    out.append(String.format("color:#%06x;", s.colour and 0xFFFFFF))
    out.append("font-weight:").append(if (s.bold) "bold" else "normal").append(';')
    out.append("font-style:").append(if (s.italic) "italic" else "normal").append(';')
    out.append("text-align:").append(align).append(';')
    if (s.padStart > 0.5f) out.append(String.format(java.util.Locale.US, "padding-%s:%.2fpt;", if (s.rtl) "right" else "left", s.padStart))
    if (abs(s.indent) > 0.5f) out.append(String.format(java.util.Locale.US, "text-indent:%.2fpt;", s.indent))
    out.append("\">")
    text.split('\n').forEachIndexed { n, line ->
        if (n > 0) out.append("<br/>")
        var i = 0
        var arabic: Boolean? = null
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            if (arabic == true) out.append("<span style=\"font-family:Plex\">").append(esc(run.toString())).append("</span>")
            else out.append(keepCompounds(run.toString()))
            run.setLength(0)
        }
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val len = Character.charCount(cp)
            if (isStrong(cp) || isArabicScript(cp)) {
                val a = isArabicScript(cp)
                if (arabic != null && a != arabic) flush()
                arabic = a
            }
            run.append(line, i, i + len)
            i += len
        }
        flush()
        if (line.isEmpty()) out.append("&#160;")
    }
    out.append("</p>")
    return out.toString()
}

/** A paragraph laid out in a column [width] wide: its drawing, first baseline and line count. */
private class Laid(val list: DisplayList, val firstBaseline: Float, val lineCount: Int, val ink: List<InkLine> = emptyList())

private fun layParagraph(text: String, s: Setting, width: Float): Laid {
    val story = Story(paragraphHtml(text, s), StampFonts.CSS, 12f, StampFonts.archive())
    try {
        val where = Rect(0f, 0f, width, 100_000f)
        story.place(where, Rect())
        val list = DisplayList(where)
        val dev = DisplayListDevice(list)
        try {
            story.draw(dev, Matrix())
            dev.close()
        } finally {
            dev.destroy()
        }
        var baseline = Float.MAX_VALUE
        val baselines = ArrayList<Float>()
        val stext = list.toStructuredText("preserve-whitespace")
        try {
            for (block in stext.blocks ?: emptyArray()) for (line in block.lines ?: emptyArray()) {
                val ch = line.chars?.firstOrNull { !it.isWhitespace } ?: continue
                val y = ch.origin?.y ?: continue
                if (y < baseline) baseline = y
                if (baselines.none { abs(it - y) < s.size * 0.3f }) baselines.add(y)
            }
        } finally {
            stext.destroy()
        }
        if (baseline == Float.MAX_VALUE) {
            list.destroy()
            throw PdfException(PdfException.Code.Unsupported, "The text could not be drawn.")
        }
        return Laid(list, baseline, baselines.size, runCatching { inkLines(list, s.size) }.getOrDefault(emptyList()))
    } finally {
        story.destroy()
    }
}

/**
 * Replaces the paragraph at ([x], [y]) with [text] (empty text removes it). Line breaks in [text]
 * are kept; everything else is wrapped into the paragraph's column.
 */
fun PdfDoc.replaceParagraph(pageIndex: Int, x: Float, y: Float, text: String, change: ParagraphChange = ParagraphChange()): EditOutcome {
    requireEdit()
    val all = styledLines(pageIndex)
    val paras = paragraphsOf(all)
    val p = paragraphAt(paras, x, y)
    if (p == null) {
        // Refused only when the text there is not horizontal (a diagonal watermark's box covers
        // most of the page, so a horizontal line under it is still the one meant).
        if (all.any { !it.horizontal && it.box.contains(x, y) }) throw PdfException(PdfException.Code.Unsupported, "Only horizontal lines of text can be edited.")
        throw PdfException(PdfException.Code.BadArguments, "There is no text there to edit.")
    }
    val words = text.trim()
    val plain = change == ParagraphChange()
    // Retyping the same words changes nothing (desktop §58).
    if (plain && words == p.text) return EditOutcome(grew = false, unified = false)
    if (words.isEmpty()) {
        if ("redact" !in EngineDebug.skip) removeChecked(pageIndex, p.lines, all)
        return EditOutcome(grew = false, unified = false)
    }
    if ("layout" in EngineDebug.skip) {
        if ("redact" !in EngineDebug.skip) redact(pageIndex, redactionBoxes(p.lines, all), everything = false)
        return EditOutcome(grew = false, unified = false)
    }
    val n = p.lines.size
    // Only the words changed: the lines before the first change, and after the last one, keep
    // their own letters untouched (desktop §58). The lines after stay only if the new words fill
    // exactly the lines they had; in a justified paragraph they always flow again (MuPDF does not
    // justify the last line it sets, which would then sit ragged above kept lines).
    var kept = if (plain && "keep" !in EngineDebug.skip) keptLines(p, words) else KeptLines(0, 0, 0, words.length)
    if (kept.tail > 0 && p.align == ParagraphAlign.Justify) kept = KeptLines(kept.head, 0, kept.from, words.length)
    // The lines set again must share one edge: beside a drop cap or an inset they are indented,
    // and set in that narrower column they took five lines more and ran into a caption (a newspaper
    // page). Then the whole paragraph is set again, as before.
    if (kept.head > 0 || kept.tail > 0) {
        val starts = p.lines.subList(kept.head, n).map { if (p.rtl) it.box.x1 else it.box.x0 }
        val tol = maxOf(2f, p.size * 0.35f)
        if (starts.drop(1).any { abs(it - starts.drop(1).first()) > tol } || (kept.head > 0 && abs(starts.first() - p.restStart) > tol)) kept = KeptLines(0, 0, 0, words.length)
    }
    fun middle(k: KeptLines) = words.substring(k.from, k.to).trim()
    fun place(k: KeptLines) = placeParagraph(if (k.head == 0 && k.tail == 0) p else p.part(k.head, n - k.tail), middle(k), all, pageSize(pageIndex), change)
    var placed = place(kept)
    if (kept.tail > 0 && placed.laid.lineCount != n - kept.head - kept.tail) {
        placed.laid.list.destroy()
        kept = KeptLines(kept.head, 0, kept.from, words.length)
        placed = place(kept)
    }
    if (kept.head > 0 || kept.tail > 0) android.util.Log.i("PowerPDF", "edit keeps lines: ${kept.head} before, ${kept.tail} after, of $n")
    try {
        if ("redact" !in EngineDebug.skip) removeChecked(pageIndex, p.lines.subList(kept.head, n - kept.tail), all)
        if ("stamp" !in EngineDebug.skip) stamp(listOf(pageIndex), 1f, placed.actual) { dev, _ -> placed.laid.list.run(dev, placed.matrix, null) }
    } finally {
        placed.laid.list.destroy()
    }
    return EditOutcome(grew = kept.head + placed.laid.lineCount + kept.tail > n, unified = p.mixed)
}

/** The letters of [s] that a reader must find again: no spaces, tatweel, vowel marks or format characters. */
private fun letters(s: String): String {
    val out = StringBuilder()
    for (c in s) {
        if (c.isWhitespace() || c == '\u0640' || c == '\uFFFD') continue
        val t = Character.getType(c)
        if (t == Character.NON_SPACING_MARK.toInt() || t == Character.FORMAT.toInt() || t == Character.ENCLOSING_MARK.toInt()) continue
        // A mirrored pair reads either way round after a rewrite: counted as one.
        out.append(
            when (c) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                '>' -> '<'
                '\u00BB' -> '\u00AB'
                else -> c
            },
        )
    }
    return out.toString()
}

/** Whether every letter of [part] (with its count) is in [whole]. */
private fun containsLetters(whole: String, part: String): Boolean {
    val counts = HashMap<Char, Int>()
    for (c in whole) counts[c] = (counts[c] ?: 0) + 1
    for (c in part) {
        val k = counts[c] ?: 0
        if (k == 0) return false
        counts[c] = k - 1
    }
    return true
}

/**
 * Removes [lines] (of [all], the page's lines before) and checks the page by place (desktop §58):
 * every other line must still be read on its baseline, over its old place, with all its letters,
 * and the removed lines' letters must be gone. MuPDF has removed nothing while moving a whole block
 * (a newspaper page) and taken letters of a neighbouring line on tightly set pages; then the edit is
 * refused, and the caller's change is dropped, rather than leave the page damaged.
 */
private fun PdfDoc.removeChecked(pageIndex: Int, lines: List<StyledLine>, all: List<StyledLine>) {
    redact(pageIndex, redactionBoxes(lines, all), everything = false)
    if ("check" in EngineDebug.skip) return
    val gone = java.util.Collections.newSetFromMap(IdentityHashMap<StyledLine, Boolean>()).apply { addAll(lines) }
    val after = styledLines(pageIndex)
    fun readAt(l: StyledLine): String = after.filter { a ->
        abs(a.baseline - l.baseline) <= maxOf(1f, l.size * 0.2f) &&
            minOf(a.box.x1, l.box.x1) - maxOf(a.box.x0, l.box.x0) > 0f
    }.joinToString("") { letters(it.text) }
    for (l in all) {
        if (l in gone || !l.horizontal) continue
        val own = letters(l.text)
        if (own.length < 2) continue
        // Glyphs right under an edited line (a hidden label behind a headline) may go with it.
        if (lines.any { r -> overlapShare(l.box, r.box) > 0.5f }) continue
        if (!containsLetters(readAt(l), own)) {
            android.util.Log.w("PowerPDF", "edit check: '${l.text.take(40)}' moved or lost")
            throw PdfException(PdfException.Code.EditUnsafe, "Nothing was changed: removing the old words would have disturbed the text around them.")
        }
    }
    for (l in lines) {
        val own = letters(l.text)
        if (own.length < 3) continue
        if (containsLetters(readAt(l), own)) {
            android.util.Log.w("PowerPDF", "edit check: '${l.text.take(40)}' still there")
            throw PdfException(PdfException.Code.EditUnsafe, "Nothing was changed: the old words could not be removed.")
        }
    }
}

/** How much of [a]'s area [b] covers. */
private fun overlapShare(a: Box, b: Box): Float {
    val w = minOf(a.x1, b.x1) - maxOf(a.x0, b.x0)
    val h = minOf(a.y1, b.y1) - maxOf(a.y0, b.y0)
    if (w <= 0f || h <= 0f) return 0f
    return w * h / maxOf(1e-3f, a.width * a.height)
}

/** A paragraph laid out and where it goes: its drawing moves by ([left], [dy]). */
/** A paragraph laid out and where it goes: condensed by [condense], moved by ([left], [dy]). */
private class Placed(val laid: Laid, val left: Float, val dy: Float, val condense: Float, val text: String) {
    /** Each line's glyph count and real text, for ActualText (decisions §24); null if they do not add up. */
    val actual: List<Pair<Int, String>>? by lazy {
        ActualText.splitLines(text, laid.ink.map { it.chars })?.let { lines -> laid.ink.map { it.glyphs }.zip(lines) }
    }

    val matrix: Matrix get() = Matrix(condense, 0f, 0f, 1f, left, dy)
}

/** Narrowest the matched face is drawn to follow a condensed original (Arial Narrow and the like). */
private const val CONDENSE_MIN = 0.8f

/** How wide [text] is set on one line with [s]: its ink, in points (0 when nothing is drawn). */
private fun naturalWidth(text: String, s: Setting): Float {
    val laid = layParagraph(text.replace('\n', ' '), s.copy(align = ParagraphAlign.Start, padStart = 0f, indent = 0f), 100_000f)
    try {
        val stext = laid.list.toStructuredText("preserve-whitespace")
        try {
            var x0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            for (block in stext.blocks ?: emptyArray()) for (line in block.lines ?: emptyArray()) for (ch in line.chars ?: emptyArray()) {
                if (ch.isWhitespace) continue
                val q = ch.quad ?: continue
                x0 = minOf(x0, q.ul_x, q.ll_x)
                x1 = maxOf(x1, q.ur_x, q.lr_x)
            }
            return if (x1 > x0) x1 - x0 else 0f
        } finally {
            stext.destroy()
        }
    } finally {
        laid.list.destroy()
    }
}

/**
 * Lays out [text] as paragraph [p] of a page ([all] its lines, [frame] its size): the same column,
 * size, colour, weight, spacing and alignment unless [change] says otherwise; its first baseline
 * goes where the paragraph's was.
 */
private fun placeParagraph(p: Paragraph, text: String, all: List<StyledLine>, frame: Size, change: ParagraphChange): Placed {
    // Most of the text's letters decide (an Arabic sentence may start with «Android»); digits and
    // signs alone keep the paragraph's direction.
    val rtl = majorityRtl(text) ?: p.rtl
    val size = change.size ?: p.size
    // A new size keeps the paragraph's proportions: line spacing scales with it.
    val pitch = p.pitch * size / p.size
    var firstStart = p.firstStart
    var restStart = p.restStart
    var farEdge = p.farEdge
    // The direction changed (Arabic typed over English): anchored at the other edge, indents dropped.
    if (rtl != p.rtl) {
        firstStart = if (rtl) p.box.x1 else p.box.x0
        restStart = firstStart
        farEdge = if (rtl) p.box.x0 else p.box.x1
    }
    // One-line text (a heading, a label) may grow to the margin; not a line inside a paragraph.
    val oneLine = p.lines.size == 1 && !p.part
    // Only as far as the next text on the same band: a column's last line grew into the next
    // column and ran over its words (the round-trip test, decisions §22).
    if (oneLine) {
        val own = p.lines.first()
        val band = all.filter { l -> l !== own && l.box.y1 > own.box.y0 && l.box.y0 < own.box.y1 }
        farEdge = if (rtl) {
            val wall = band.filter { it.box.x1 <= own.box.x0 + 1f }.maxOfOrNull { it.box.x1 + own.size * 0.6f } ?: 36f
            minOf(farEdge, maxOf(36f, wall))
        } else {
            val wall = band.filter { it.box.x0 >= own.box.x1 - 1f }.minOfOrNull { it.box.x0 - own.size * 0.6f } ?: (frame.width - 36f)
            maxOf(farEdge, minOf(frame.width - 36f, wall))
        }
    }
    // A single line centred on the page (a title) stays centred, unless other lines share its edges.
    var align = p.align
    if (oneLine) {
        val own = p.lines.first()
        val middle = (p.box.x0 + p.box.x1) / 2
        val onCentre = abs(middle - frame.width / 2) < frame.width * 0.02f && p.box.width < frame.width * 0.8f
        val sharesEdge = all.any { l -> l !== own && l.horizontal && (abs(l.box.x0 - own.box.x0) < 2f || abs(l.box.x1 - own.box.x1) < 2f) }
        if (onCentre && !sharesEdge) align = ParagraphAlign.Centre
    }
    change.align?.let { align = it }

    // The column in displayed points.
    var left: Float
    var right: Float
    if (rtl) {
        right = maxOf(firstStart, restStart)
        left = farEdge
    } else {
        left = minOf(firstStart, restStart)
        right = farEdge
    }
    if (align == ParagraphAlign.Centre && oneLine) {
        val middle = (p.box.x0 + p.box.x1) / 2
        val half = minOf(middle, frame.width - middle) - 36f
        if (half > 0) {
            left = middle - half
            right = middle + half
        }
    }
    val width = maxOf(right - left, size * 2)
    val padStart = if (rtl) right - restStart else restStart - left
    val indent = if (rtl) restStart - firstStart else firstStart - restStart
    val setting = Setting(
        size = size,
        colour = change.colour ?: p.colour,
        bold = change.bold ?: p.bold,
        italic = change.italic ?: p.italic,
        kind = change.kind ?: p.kind,
        rtl = rtl,
        align = align,
        pitch = pitch,
        padStart = if (align == ParagraphAlign.Centre) 0f else maxOf(0f, padStart),
        indent = if (align == ParagraphAlign.Centre) 0f else indent,
    )
    // The matched face is often wider than the original (a newspaper's condensed Helvetica): set
    // as it is, an unchanged paragraph needed a line more and ran into the next one (found by the
    // round-trip test, decisions §22). It is drawn condensed by the ratio of the original's printed
    // width to the same words in the matched face, so it keeps its lines. A new typeface is not
    // the original's, so it is not condensed.
    var condense = 1f
    val sameFace = change.kind == null || change.kind == p.kind
    if (sameFace) {
        val original = setting.copy(size = p.size, bold = p.bold, italic = p.italic, kind = p.kind, rtl = p.rtl)
        val natural = naturalWidth(p.text, original)
        val printed = p.lines.sumOf { it.box.width.toDouble() }.toFloat() + (p.lines.size - 1) * p.size * 0.25f
        if (natural > 0f) condense = (printed / natural).coerceIn(CONDENSE_MIN, 1f)
        if (condense > 0.98f) condense = 1f
    }
    fun lay(k: Float) = layParagraph(text, setting.copy(padStart = setting.padStart / k, indent = setting.indent / k), width / k)
    var laid = lay(condense)
    // Still a line more at the same size: a little tighter rather than running into what follows
    // (at most 6%; 12% and down to 75% when the words are no longer than before, as when one word
    // is corrected). A clearly longer text or a bigger size grows, as asked.
    if (sameFace && abs(size - p.size) < 0.05f && laid.lineCount > p.lines.size) {
        var k = condense
        val sameLength = text.length <= p.text.length * 1.05f
        val floor = if (sameLength) maxOf(0.75f, condense - 0.12f) else maxOf(CONDENSE_MIN, condense - 0.06f)
        while (k - 0.02f >= floor - 0.0001f) {
            k -= 0.02f
            val tighter = lay(k)
            if (tighter.lineCount <= p.lines.size) {
                laid.list.destroy()
                laid = tighter
                condense = k
                break
            }
            tighter.list.destroy()
        }
    }
    android.util.Log.i("PowerPDF", "lay paragraph: ${p.lines.size} → ${laid.lineCount} lines, rtl=$rtl align=$align size=$size kind=${setting.kind} condense=${"%.2f".format(condense)}")
    return Placed(laid, left, p.lines.first().baseline - laid.firstBaseline, condense, text)
}

/** What Format all text changes; null keeps each paragraph's own. */
data class FormatAll(
    val kind: FontKind? = null,
    /** One size for all text, or… */
    val size: Float? = null,
    /** …every size multiplied by this (headings stay bigger than body text). */
    val scale: Float? = null,
    val colour: Int? = null,
)

/** [skipped]: paragraphs left as they were because the file does not store all their characters as text. */
data class FormatAllResult(val paragraphs: Int, val mixed: Int, val skipped: Int = 0)

/**
 * Format all text (desktop decisions §48): every paragraph of the chosen pages retyped in place —
 * same words, column and alignment — in a new typeface, size or colour. One redaction and one
 * overlay per page. Bigger text can run into what follows; Undo restores it.
 */
fun PdfDoc.formatAllText(pages: Collection<Int>, change: FormatAll, progress: (Int) -> Unit = {}): FormatAllResult {
    requireEdit()
    if (change.kind == null && change.size == null && change.scale == null && change.colour == null) {
        throw PdfException(PdfException.Code.BadArguments, "Choose what to change first.")
    }
    var total = 0
    var mixed = 0
    var skipped = 0
    for (pageIndex in pages.sorted()) {
        progress(pageIndex)
        val all = styledLines(pageIndex)
        // Characters without text would be retyped as U+FFFD: those paragraphs stay as they are.
        val (paras, unreadable) = paragraphsOf(all).filter { it.text.isNotBlank() }.partition { it.unreadable == 0 }
        skipped += unreadable.size
        if (paras.isEmpty()) continue
        val frame = pageSize(pageIndex)
        redact(pageIndex, redactionBoxes(paras.flatMap { it.lines }, all), everything = false)
        val placed = paras.map { p ->
            val size = change.size ?: ((p.size * (change.scale ?: 1f) * 2).roundToInt() / 2f)
            placeParagraph(p, p.text, all, frame, ParagraphChange(size = size, kind = change.kind, colour = change.colour))
        }
        try {
            val actual = placed.map { it.actual }.takeIf { all -> all.none { it == null } }?.flatMap { it!! }
            stamp(listOf(pageIndex), 1f, actual) { dev, _ -> for (pl in placed) pl.laid.list.run(dev, pl.matrix, null) }
        } finally {
            placed.forEach { it.laid.list.destroy() }
        }
        total += paras.size
        mixed += paras.count { it.mixed }
    }
    if (total == 0 && skipped > 0) throw PdfException(PdfException.Code.Unsupported, "The text on these pages is not stored as letters in the file, so it cannot be retyped.")
    if (total == 0) throw PdfException(PdfException.Code.BadArguments, "There is no text on these pages to change.")
    return FormatAllResult(total, mixed, skipped)
}

/**
 * The document's text, paragraph by paragraph in reading order (desktop features: PDF → text /
 * Markdown). In Markdown, text much larger than the body is a heading and each page starts with a
 * comment naming it; plain text puts a blank line between paragraphs and a rule between pages.
 * Pages without text (scans) are skipped. [progress] is told each page as it is read.
 */
fun PdfDoc.exportText(markdown: Boolean, progress: (Int) -> Unit = {}): String {
    val out = StringBuilder()
    val all = (0 until pageCount).map { p ->
        progress(p)
        p to paragraphsOf(styledLines(p)).filter { it.text.isNotBlank() }
    }
    // The body size: the size most words are set in.
    val sizes = all.flatMap { (_, ps) -> ps.map { (it.size * 2).roundToInt() to it.text.length } }
    val body = sizes.groupBy { it.first }.maxByOrNull { g -> g.value.sumOf { it.second } }?.key?.div(2f) ?: 12f
    for ((page, paras) in all) {
        if (paras.isEmpty()) continue
        if (out.isNotEmpty()) out.append(if (markdown) "\n\n" else "\n\n—————\n\n")
        if (markdown) out.append("<!-- page ").append(page + 1).append(" -->\n\n")
        // Top to bottom; side by side, the reading side first.
        val rtl = paras.count { it.rtl } * 2 > paras.size
        val ordered = paras.sortedWith(compareBy<Paragraph> { (it.box.y0 / 4).toInt() }.thenBy { if (rtl) -it.box.x1 else it.box.x0 })
        ordered.forEachIndexed { i, p ->
            if (i > 0) out.append("\n\n")
            val heading = when {
                !markdown || p.lines.size > 3 -> 0
                p.size >= body * 1.8f -> 1
                p.size >= body * 1.3f || (p.bold && p.lines.size == 1 && p.text.length < 80 && !p.text.trimEnd().endsWith('.')) -> 2
                else -> 0
            }
            if (heading > 0) out.append("#".repeat(heading)).append(' ')
            out.append(p.text.trim())
        }
    }
    return out.append('\n').toString()
}
