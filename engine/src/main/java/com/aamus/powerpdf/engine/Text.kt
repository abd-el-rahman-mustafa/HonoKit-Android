package com.aamus.powerpdf.engine

/**
 * Reading text out of a page, searching it and selecting it — the part that needs no MuPDF,
 * so it is tested on the JVM (src/test). Ported from the desktop app's src/engine/text.ts.
 *
 * MuPDF hands back the two letters of an Arabic ligature glyph in the wrong order («عىل» for
 * «على», «االتحاد» for «الاتحاد»). It gives each character its width, so the order is repaired
 * here (desktop decisions §17: agreement with pdftotext went from ~77% to 99.8%). Do not
 * upgrade MuPDF without re-checking this on a real Arabic PDF: if MuPDF fixes the order itself,
 * this swap would break correct text.
 */

/** One character with its box on the page as displayed, in points, top-left origin. */
data class Glyph(val c: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
    val cx: Float get() = (x0 + x1) / 2
    val cy: Float get() = (y0 + y1) / 2
}

data class TextLine(
    val glyphs: List<Glyph>,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
) {
    /** The line's text in reading order. */
    val text: String = glyphs.joinToString("") { it.c }
    val height: Float get() = y1 - y0
}

/** A rectangle in page points (displayed, top-left origin). */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
    fun contains(x: Float, y: Float) = x in x0..x1 && y in y0..y1
}

object TextLogic {
    private const val ZERO = 0.01f

    fun isArabicBaseLetter(c: String): Boolean {
        if (c.length != 1) return false
        val ch = c[0]
        return ch in 'ء'..'ي' || ch in 'ٮ'..'ۓ' || ch in 'ۺ'..'ۿ'
    }

    /** How far [x] is from the span [x0]–[x1] (0 inside it). */
    private fun distance(x: Float, x0: Float, x1: Float) = if (x < x0) x0 - x else if (x > x1) x - x1 else 0f

    /**
     * Repairs ligature order in one line: a zero-width base letter followed by the one carrying the
     * width (Edge's PDFs: «عىل» for «على»). The zero-width letter is moved only when it sits on the
     * next glyph; when it sits on the previous one, it is that glyph's partner and already in place
     * (IBM Plex's «تر» ligature, written by this app: ت wide, then ر at its edge).
     */
    fun repairLigatureOrder(glyphs: MutableList<Glyph>) {
        var i = 0
        while (i + 1 < glyphs.size) {
            val a = glyphs[i]
            val b = glyphs[i + 1]
            val prev = glyphs.getOrNull(i - 1)?.takeIf { it.width >= ZERO && isArabicBaseLetter(it.c) }
            val ownsPrevious = prev != null && distance(a.x0, prev.x0, prev.x1) < distance(a.x0, b.x0, b.x1)
            if (a.width < ZERO && b.width >= ZERO && isArabicBaseLetter(a.c) && isArabicBaseLetter(b.c) && !ownsPrevious) {
                glyphs[i] = b
                glyphs[i + 1] = a
                i++
            }
            i++
        }
    }

    /**
     * Some fonts build dotted letters from a separate dot glyph with no Unicode value (Noto Naskh
     * does); readers report it as U+FFFD or U+200B at zero width. It is not text.
     */
    fun dropPhantoms(glyphs: MutableList<Glyph>) {
        glyphs.removeAll { it.width < ZERO && (it.c == "�" || it.c == "​") }
    }

    private val MIRROR_PAIRS = listOf("(" to ")", "[" to "]", "{" to "}", "«" to "»", "‹" to "›")

    /**
     * Text written right to left by MuPDF (this app's own) records the mirrored glyph of a bracket
     * or quote, so «قال: «نص»» reads back «قال: »نص«». In a line that reads right to left, a pair
     * whose closing mark comes before its opening mark is turned back. Lines that read left to
     * right are left alone (German quotes are »so«). Returns [glyphs] itself when nothing changed.
     */
    fun unmirrorPairs(glyphs: List<Glyph>): List<Glyph> {
        var r = 0
        var l = 0
        for (g in glyphs) when (dirOf(g.c)) {
            D.R -> r++
            D.L -> l++
            else -> {}
        }
        if (r <= l) return glyphs
        var out: MutableList<Glyph>? = null
        for ((open, close) in MIRROR_PAIRS) {
            val first = glyphs.indexOfFirst { it.c == open || it.c == close }
            if (first < 0 || glyphs[first].c != close) continue
            if (glyphs.subList(first + 1, glyphs.size).none { it.c == open }) continue
            val o = out ?: glyphs.toMutableList().also { out = it }
            for (i in o.indices) {
                if (o[i].c == open) o[i] = o[i].copy(c = close) else if (o[i].c == close) o[i] = o[i].copy(c = open)
            }
        }
        return out ?: glyphs
    }

    /**
     * [unmirrorPairs] for a whole paragraph's text: a quotation often starts on one line and ends
     * on another («»مدينة» … «صحة«»), which the line-by-line repair cannot see. Retyped as read,
     * the marks came out turned the wrong way (the round-trip test on an Arabic newspaper).
     */
    fun unmirrorText(text: String): String {
        var r = 0
        var l = 0
        for (ch in text) when (dirOf(ch.toString())) {
            D.R -> r++
            D.L -> l++
            else -> {}
        }
        if (r <= l) return text
        var out = text
        for ((open, close) in MIRROR_PAIRS) {
            val o = open[0]
            val c = close[0]
            val first = out.indexOfFirst { it == o || it == c }
            if (first < 0) continue
            if (out.indexOf(o) >= 0 && out.indexOf(c) >= 0) {
                if (out[first] != c || out.indexOf(o, first + 1) < 0) continue
                out = out.map { if (it == o) c else if (it == c) o else it }.joinToString("")
            } else {
                // Only one side of the pair (its partner is in another paragraph, or a lone brace
                // used as a marker): the shape drawn among Arabic letters is the mirror of the
                // character meant, so it is turned back.
                val chars = out.toCharArray()
                for (i in chars.indices) if ((chars[i] == o || chars[i] == c) && rtlAround(out, i)) chars[i] = if (chars[i] == o) c else o
                out = String(chars)
            }
        }
        return out
    }

    /** The nearest letters with a direction on both sides of [i] (or the only side) are right to left. */
    private fun rtlAround(s: String, i: Int): Boolean {
        fun near(range: IntProgression): D? = range.firstNotNullOfOrNull { k -> dirOf(s[k].toString()).takeIf { it == D.R || it == D.L } }
        val before = near(i - 1 downTo 0)
        val after = near(i + 1 until s.length)
        return (before ?: after) == D.R && (after ?: before) == D.R
    }

    /** One printed line made of one or more of MuPDF's lines ([members], by index), in reading order. */
    class Joined(val members: List<Int>, val glyphs: List<Glyph>)

    /**
     * MuPDF reports a printed line in pieces when it was drawn run by run in screen order (what a
     * bidi layout does: the app's own retyped Arabic among them), so one sentence became several
     * lines and could not be selected or edited as one. Pieces on the same band that nearly touch
     * (a gap under about an em) are joined, in reading order, with a space where there was a gap.
     */
    fun joinPieces(lines: List<List<Glyph>>): List<Joined> {
        class Piece(val index: Int, val glyphs: List<Glyph>) {
            val ink = glyphs.filter { it.width >= ZERO && it.c.isNotBlank() }
            val x0 = ink.minOfOrNull { it.x0 } ?: 0f
            val x1 = ink.maxOfOrNull { it.x1 } ?: 0f
            val y0 = ink.minOfOrNull { it.y0 } ?: 0f
            val y1 = ink.maxOfOrNull { it.y1 } ?: 0f
            val h get() = y1 - y0
            val cy get() = (y0 + y1) / 2
        }
        val pieces = lines.mapIndexed { i, g -> Piece(i, g) }
        val parent = IntArray(pieces.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in pieces.indices) for (j in i + 1 until pieces.size) {
            val a = pieces[i]
            val b = pieces[j]
            if (a.ink.isEmpty() || b.ink.isEmpty()) continue
            val h = minOf(a.h, b.h)
            if (h <= 0f || maxOf(a.h, b.h) > h * 1.4f) continue
            if (kotlin.math.abs(a.cy - b.cy) > h * 0.25f) continue
            val gap = maxOf(a.x0, b.x0) - minOf(a.x1, b.x1)
            if (gap < -h * 0.3f || gap > h * 0.8f) continue
            parent[root(j)] = root(i)
        }
        val groups = LinkedHashMap<Int, MutableList<Piece>>()
        for (p in pieces) groups.getOrPut(root(p.index)) { ArrayList() }.add(p)
        return groups.values.map { group ->
            if (group.size == 1) return@map Joined(listOf(group[0].index), group[0].glyphs)
            var r = 0
            var l = 0
            for (p in group) for (g in p.glyphs) when (dirOf(g.c)) {
                D.R -> r++
                D.L -> l++
                else -> {}
            }
            val rtl = r > l
            val ordered = if (rtl) group.sortedByDescending { it.x1 } else group.sortedBy { it.x0 }
            val out = ArrayList<Glyph>()
            ordered.forEachIndexed { k, p ->
                if (k > 0) {
                    val prev = ordered[k - 1]
                    val gapFrom = if (rtl) p.x1 else prev.x1
                    val gapTo = if (rtl) prev.x0 else p.x0
                    val spaced = out.lastOrNull()?.c?.isBlank() == true || p.glyphs.firstOrNull()?.c?.isBlank() == true
                    if (!spaced && gapTo - gapFrom > minOf(p.h, prev.h) * 0.12f) out.add(Glyph(" ", gapFrom, minOf(p.y0, prev.y0), gapTo, maxOf(p.y1, prev.y1)))
                }
                out.addAll(p.glyphs)
            }
            Joined(group.map { it.index }.sorted(), out)
        }
    }

    /**
     * One of MuPDF's lines split where the letters jump in size across a gap: two headlines side by
     * side on one baseline (35 pt and 20 pt, 16 pt apart) came as one line and read as one
     * sentence (the round-trip test, decisions §22). A size change between touching letters (a
     * drop cap, a superscript) is left alone.
     */
    fun splitAtSizeJumps(glyphs: List<Glyph>): List<List<Glyph>> {
        val out = ArrayList<List<Glyph>>()
        var start = 0
        var prev: Glyph? = null
        for ((i, g) in glyphs.withIndex()) {
            if (g.c.isBlank() || g.height <= 0f) continue
            val p = prev
            if (p != null) {
                val small = minOf(p.height, g.height)
                val gap = maxOf(g.x0 - p.x1, p.x0 - g.x1)
                if (maxOf(p.height, g.height) > small * 1.4f && gap > small * 0.3f) {
                    out.add(glyphs.subList(start, i))
                    start = i
                }
            }
            prev = g
        }
        out.add(glyphs.subList(start, glyphs.size))
        return out.filter { it.isNotEmpty() }
    }

    /**
     * Lines printed twice in the same place (a newspaper headline with a second, partial copy on
     * top: «Guardiola quit» and «Guar»): a line whose every letter lies on the same letter of a
     * line kept already, longest first, is left out. Returns the indexes of the lines to keep.
     */
    fun distinctLines(lines: List<List<Glyph>>): List<Int> {
        fun key(g: Glyph) = Triple(g.c, (g.x0 * 2).toInt(), (g.y0 * 2).toInt())
        val seen = HashSet<Triple<String, Int, Int>>()
        val keep = BooleanArray(lines.size)
        for (i in lines.indices.sortedByDescending { lines[it].count { g -> g.c.isNotBlank() } }) {
            val printed = lines[i].filter { it.c.isNotBlank() && it.width >= ZERO }
            if (printed.isNotEmpty() && printed.all { key(it) in seen }) continue
            printed.forEach { seen.add(key(it)) }
            keep[i] = true
        }
        return lines.indices.filter { keep[it] }
    }

    // --- a paragraph's lines as one text ------------------------------------------------------

    private val HYPHENS = "-‐"
    private val CLOSE_UP = "—–/"

    /**
     * The page's words in lower case, for [joinLines]: how the page itself writes a word. The
     * pieces of a word broken at a line's end («differ-», «ent») are not words of their own.
     */
    fun wordsOf(lines: List<String>): Set<String> {
        val out = HashSet<String>()
        var broken = false
        for (raw in lines) {
            val words = raw.trim().split(' ').filter { it.isNotEmpty() }
            words.forEachIndexed { k, w ->
                val piece = (k == 0 && broken) || (k == words.lastIndex && w.last() in HYPHENS)
                val clean = w.trim { !it.isLetterOrDigit() }.lowercase()
                if (!piece && clean.isNotEmpty()) out.add(clean)
            }
            broken = words.lastOrNull()?.last()?.let { it in HYPHENS } == true
        }
        return out
    }

    /**
     * First halves that make compounds («self-sufficiency», «two-time»), not broken words. Only
     * those no common word is hyphenated after («pre-sent», «part-ner», «high-er», «six-ty» are).
     */
    private val PREFIXES = setOf(
        "self", "non", "well", "all", "half", "anti", "multi", "semi", "cross",
        "one", "two", "three", "five", "first", "second", "third",
    )

    /** «head-» + «tail» is a hyphenated compound broken at its hyphen rather than a broken word. */
    private fun compound(head: String, tail: String, written: Set<String>): Boolean =
        head in PREFIXES ||
            ("$head-$tail" in written && "$head$tail" !in written) ||
            (head.length >= 3 && tail.length >= 3 && head in written && tail in written && "$head$tail" !in written)

    /**
     * A paragraph's lines as one text for the edit box, joined by spaces. A Latin word broken
     * across lines is joined again so it is not retyped with a hyphen mid-line: a soft hyphen is
     * dropped; «cen-» + «tury» gives «century» unless the page writes the word with its hyphen
     * ([written], from [wordsOf]: «well-» + «known» gives «well-known»); a capital after the
     * hyphen («Abu-» + «Dhabi») or digits («2026-» + «27») keep it. A dash or slash closing a line
     * right after a letter («and/» + «or») is joined without a space. Right-to-left lines are
     * joined by spaces only.
     */
    fun joinLines(lines: List<String>, rtl: List<Boolean> = lines.map { false }, written: Set<String> = emptySet()): String {
        val out = StringBuilder()
        for ((i, raw) in lines.withIndex()) {
            var line = raw.trim()
            // A vowel mark read at the start of a line (a tanween ending the line before:
            // «…أسيرا» / «ًوساطة») belongs to the last letter of the line before.
            if (out.isNotEmpty()) {
                val marks = line.takeWhile { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
                if (marks.isNotEmpty()) {
                    out.append(marks)
                    line = line.substring(marks.length).trim()
                }
            }
            if (line.isEmpty()) continue
            if (out.isEmpty()) {
                out.append(line)
                continue
            }
            val n = out.length
            val last = out[n - 1]
            val before = if (n >= 2) out[n - 2] else ' '
            val next = line[0]
            val latin = !rtl[i] && !rtl.getOrElse(i - 1) { false }
            when {
                latin && last == '­' -> {
                    out.setLength(n - 1)
                    out.append(line)
                }
                latin && last in HYPHENS && before.isLetter() && next.isLetter() -> {
                    val head = out.substring(0, n - 1).takeLastWhile { it.isLetter() }.lowercase()
                    val tail = line.takeWhile { it.isLetter() }.lowercase()
                    val brokenWord = before.isLowerCase() && next.isLowerCase()
                    val keep = !brokenWord || compound(head, tail, written)
                    if (!keep) out.setLength(n - 1)
                    out.append(line)
                }
                latin && last in HYPHENS && before.isDigit() && next.isLetterOrDigit() -> out.append(line)
                latin && last in CLOSE_UP && before.isLetterOrDigit() && next.isLetterOrDigit() -> out.append(line)
                else -> out.append(' ').append(line)
            }
        }
        return out.toString()
    }

    // --- reading order of mixed lines ---------------------------------------------------------

    private enum class D { R, L, NUM, SEP, N }

    private fun dirOf(c: String): D? {
        if (c.isEmpty()) return D.N
        return when (Character.getDirectionality(c.codePointAt(0))) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> D.R
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> D.L
            Character.DIRECTIONALITY_EUROPEAN_NUMBER, Character.DIRECTIONALITY_ARABIC_NUMBER -> D.NUM
            Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR, Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR -> D.SEP
            Character.DIRECTIONALITY_NONSPACING_MARK -> null
            else -> D.N
        }
    }

    /** Right-to-left letters outnumber left-to-right ones: the page reads right to left. */
    fun pageIsRtl(lines: List<TextLine>): Boolean {
        var r = 0
        var l = 0
        for (line in lines) for (g in line.glyphs) when (dirOf(g.c)) {
            D.R -> r++
            D.L -> l++
            else -> {}
        }
        return r > l
    }

    /**
     * Puts a line that mixes directions into reading order. MuPDF hands such lines over in pieces
     * ordered left to right on the page (an Arabic sentence ending in «… 15 صورة» comes out
     * «صورة15 …», and «٢٨» as «٨٢»). The characters are put in screen order, then, for a
     * right-to-left line, runs of Latin letters and numbers are turned back (what the Unicode bidi
     * algorithm displays left to right), as pdftotext does; a left-to-right line turns back its
     * Arabic runs instead. Numbers join a neighbouring Latin word through spaces («PDF 2026»), but
     * separate numbers do not join each other («800 1234 567» stays three numbers, as displayed).
     * Characters without width (a ligature's second letter, vowel marks) stay with the one before.
     */
    fun logicalOrder(glyphs: List<Glyph>, rtlBase: Boolean): List<Glyph> {
        var hasR = false
        var hasLatin = false
        var hasOther = false
        for (g in glyphs) when (dirOf(g.c)) {
            D.R -> hasR = true
            D.L -> {
                hasLatin = true
                hasOther = true
            }
            D.NUM, D.N, D.SEP -> hasOther = true
            else -> {}
        }
        if (!hasR || !hasOther) return glyphs
        // A line with Arabic and no Latin letters reads right to left whatever the page does.
        @Suppress("NAME_SHADOWING") val rtlBase = if (!hasLatin) true else rtlBase
        // Clusters: a glyph without width stays with the glyph before it.
        val clusters = ArrayList<MutableList<Glyph>>()
        for (g in glyphs) {
            if (g.width < ZERO && clusters.isNotEmpty()) clusters.last().add(g) else clusters.add(mutableListOf(g))
        }
        fun centre(cl: List<Glyph>): Float {
            val w = cl.filter { it.width >= ZERO }
            return if (w.isEmpty()) cl[0].cx else (w.minOf { it.x0 } + w.maxOf { it.x1 }) / 2
        }
        fun typeOf(cl: List<Glyph>): D = cl.firstNotNullOfOrNull { dirOf(it.c) } ?: D.N
        // Where each cluster sits on the line. Inside a stretch of Arabic letters that MuPDF gives
        // in a row, each touching the one before and further left, MuPDF's order is kept: some
        // letters reach under their neighbour (ر before ن in IBM Plex), so their centres alone
        // put them the wrong way round (seen on a phone: «إنترنت» read «إنتنرت»).
        val key = FloatArray(clusters.size) { centre(clusters[it]) }
        var start = 0
        while (start < clusters.size) {
            var end = start
            if (typeOf(clusters[start]) == D.R) {
                while (end + 1 < clusters.size && typeOf(clusters[end + 1]) == D.R) {
                    val prev = clusters[end].filter { it.width >= ZERO }
                    val next = clusters[end + 1].filter { it.width >= ZERO }
                    if (prev.isEmpty() || next.isEmpty()) break
                    val px0 = prev.minOf { it.x0 }
                    val px1 = prev.maxOf { it.x1 }
                    val nx0 = next.minOf { it.x0 }
                    val nx1 = next.maxOf { it.x1 }
                    val leftwards = (nx0 + nx1) / 2 < px1 - (px1 - px0) * 0.2f
                    val touching = nx1 >= px0 - (px1 - px0) * 0.5f
                    if (!leftwards || !touching) break
                    end++
                }
            }
            if (end > start) {
                val ink = (start..end).flatMap { clusters[it] }.filter { it.width >= ZERO }
                val bx0 = ink.minOf { it.x0 }
                val bx1 = ink.maxOf { it.x1 }
                val n = end - start + 1
                for (k in 0 until n) key[start + k] = bx1 - (k + 0.5f) * (bx1 - bx0) / n
            }
            start = end + 1
        }
        // Screen order: right to left for a right-to-left line, left to right otherwise.
        val byPlace = clusters.indices.let { ix -> if (rtlBase) ix.sortedByDescending { key[it] } else ix.sortedBy { key[it] } }
        val v = byPlace.map { clusters[it] }
        val t = v.map { typeOf(it) }.toMutableList()
        // A separator between two digits belongs to the number ("3:30", "1,234.5"); so does one
        // right after a number ("50%").
        for (i in t.indices) {
            if (t[i] != D.SEP) continue
            val prevNum = i > 0 && t[i - 1] == D.NUM
            val nextNum = i + 1 < t.size && t[i + 1] == D.NUM
            if (prevNum && nextNum) t[i] = D.NUM
        }
        val out = ArrayList<Glyph>(glyphs.size)
        val strong = if (rtlBase) D.L else D.R
        var i = 0
        while (i < v.size) {
            val ti = t[i]
            val starts = if (rtlBase) (ti == D.L || ti == D.NUM) else ti == D.R
            if (!starts) {
                out.addAll(v[i])
                i++
                continue
            }
            var j = i
            var hasLetter = ti == strong
            while (j + 1 < v.size) {
                val next = t[j + 1]
                if (rtlBase) {
                    if (next == D.L || (next == D.NUM && t[j] == D.NUM)) {
                        if (next == D.L) hasLetter = true
                        j++
                        continue
                    }
                    // Across neutrals only when a Latin letter is involved.
                    var k = j + 1
                    while (k < v.size && (t[k] == D.N || t[k] == D.SEP)) k++
                    if (k > j + 1 && k < v.size && (t[k] == D.L || t[k] == D.NUM) && (hasLetter || t[k] == D.L)) {
                        if (t[k] == D.L) hasLetter = true
                        j = k
                        continue
                    }
                    break
                } else {
                    if (next == D.R) {
                        j++
                        continue
                    }
                    // Neutrals and numbers inside an Arabic run belong to it.
                    var k = j + 1
                    while (k < v.size && (t[k] == D.N || t[k] == D.SEP || t[k] == D.NUM)) k++
                    if (k > j + 1 && k < v.size && t[k] == D.R) {
                        j = k
                        continue
                    }
                    break
                }
            }
            val run = v.subList(i, j + 1).asReversed()
            if (rtlBase) {
                run.forEach { out.addAll(it) }
            } else {
                // Numbers inside an Arabic run still read left to right.
                val types = t.subList(i, j + 1).asReversed()
                var k = 0
                while (k < run.size) {
                    if (types[k] == D.NUM) {
                        var e = k
                        while (e + 1 < run.size && types[e + 1] == D.NUM) e++
                        for (m in e downTo k) out.addAll(run[m])
                        k = e + 1
                    } else {
                        out.addAll(run[k])
                        k++
                    }
                }
            }
            i = j + 1
        }
        return out
    }

    // --- search --------------------------------------------------------------------------

    /**
     * Folds a character for comparison. Deliberately mild: it removes what readers do not type
     * (tatweel, vowel marks, zero-width characters) and unifies the alef and yeh families.
     */
    fun foldChar(c: String): String {
        if (c.length == 1) {
            val ch = c[0]
            if (ch == 'ـ' || ch in 'ً'..'ٟ' || ch == 'ٰ' ||
                ch == '​' || ch == '‌' || ch == '‍' || ch == '﻿'
            ) return ""
            if (ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ') return "ا"
            if (ch == 'ى') return "ي"
            if (ch == ' ') return " "
        }
        return java.text.Normalizer.normalize(c, java.text.Normalizer.Form.NFKC).lowercase()
    }

    fun foldString(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            out.append(foldChar(s.substring(i, i + n)))
            i += n
        }
        return out.toString()
    }

    /** Finds a phrase in one page's lines. One box per match. */
    fun searchLines(lines: List<TextLine>, needle: String, limit: Int = 500): List<Box> {
        val query = foldString(needle).trim()
        if (query.isEmpty()) return emptyList()
        val hits = ArrayList<Box>()
        for (line in lines) {
            val folded = StringBuilder()
            val owner = ArrayList<Int>()
            line.glyphs.forEachIndexed { gi, g ->
                val f = foldChar(g.c)
                for (k in f.indices) {
                    folded.append(f[k])
                    owner.add(gi)
                }
            }
            var from = 0
            while (true) {
                val at = folded.indexOf(query, from)
                if (at < 0) break
                val first = owner[at]
                val last = owner[at + query.length - 1]
                var x0 = Float.MAX_VALUE
                var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE
                var y1 = -Float.MAX_VALUE
                for (gi in minOf(first, last)..maxOf(first, last)) {
                    val g = line.glyphs[gi]
                    x0 = minOf(x0, g.x0); y0 = minOf(y0, g.y0)
                    x1 = maxOf(x1, g.x1); y1 = maxOf(y1, g.y1)
                }
                hits.add(Box(x0, y0, x1, y1))
                if (hits.size >= limit) return hits
                from = at + maxOf(1, query.length)
            }
        }
        return hits
    }

    // --- selection -----------------------------------------------------------------------

    enum class Unit { Char, Word, Line }

    data class Selection(
        /** The selected text in reading order; lines joined by newlines. */
        val text: String,
        /** One box per selected line part. */
        val boxes: List<Box>,
        /** Centres of the first and last selected character: the same span for markup. */
        val from: Pair<Float, Float>,
        val to: Pair<Float, Float>,
        /** Where the selection handles go: start and end edges, at the line's bottom. */
        val startHandle: Pair<Float, Float>,
        val endHandle: Pair<Float, Float>,
        val rtlStart: Boolean,
        val rtlEnd: Boolean,
    )

    /** The line a point is on, or the nearest one within three quarters of a line height. */
    fun lineAt(lines: List<TextLine>, x: Float, y: Float, slack: Float = 0.75f): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        lines.forEachIndexed { i, l ->
            if (l.glyphs.isEmpty()) return@forEachIndexed
            val h = l.height
            if (h > 72) return@forEachIndexed // a rotated watermark's box would swallow the page
            val dy = if (y < l.y0) l.y0 - y else if (y > l.y1) y - l.y1 else 0f
            if (dy > h * slack) return@forEachIndexed
            val dx = if (x < l.x0) l.x0 - x else if (x > l.x1) x - l.x1 else 0f
            val d = dy * 1000 + dx
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** The character nearest to x in a line (logical index). */
    fun glyphAt(line: TextLine, x: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        line.glyphs.forEachIndexed { i, g ->
            if (g.width < ZERO) return@forEachIndexed
            val d = if (x < g.x0) g.x0 - x else if (x > g.x1) x - g.x1 else 0f
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun isSpace(c: String) = c.isNotEmpty() && c.all { it.isWhitespace() }

    fun isRtl(line: TextLine, from: Int, to: Int): Boolean {
        for (i in from..to) {
            val c = line.glyphs.getOrNull(i)?.c ?: continue
            val d = Character.getDirectionality(c.codePointAt(0))
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false
        }
        return false
    }

    /**
     * The text between two points, as a reader's selection takes it: from the character under the
     * first point to the one under the second, in reading order, whole lines in between. `Word`
     * and `Line` widen a single point to its word or line (long press on a phone).
     */
    fun selectText(lines: List<TextLine>, from: Pair<Float, Float>, to: Pair<Float, Float>, unit: Unit = Unit.Char): Selection? {
        var la = lineAt(lines, from.first, from.second)
        var lb = lineAt(lines, to.first, to.second)
        if (la < 0 || lb < 0) return null
        var ga = glyphAt(lines[la], from.first)
        var gb = glyphAt(lines[lb], to.first)
        if (lb < la || (la == lb && gb < ga)) {
            la = lb.also { lb = la }
            ga = gb.also { gb = ga }
        }
        if (unit == Unit.Word) {
            val a = lines[la].glyphs
            val b = lines[lb].glyphs
            if (isSpace(a[ga].c) && ga == gb && la == lb) return null
            while (ga > 0 && !isSpace(a[ga - 1].c)) ga--
            while (gb + 1 < b.size && !isSpace(b[gb + 1].c)) gb++
        } else if (unit == Unit.Line) {
            ga = 0
            gb = lines[lb].glyphs.size - 1
        }
        return spanOf(lines, la, ga, lb, gb)
    }

    /** A selection between two (line, glyph) positions, already in reading order. */
    fun spanOf(lines: List<TextLine>, la: Int, ga: Int, lb: Int, gb: Int): Selection? {
        val parts = ArrayList<String>()
        val boxes = ArrayList<Box>()
        var first: Glyph? = null
        var last: Glyph? = null
        var firstLine = -1
        var lastLine = -1
        for (i in la..lb) {
            val glyphs = lines[i].glyphs
            if (glyphs.isEmpty()) continue
            val start = if (i == la) ga else 0
            val end = if (i == lb) gb else glyphs.size - 1
            if (start > end) continue
            val chosen = glyphs.subList(start, end + 1)
            parts.add(chosen.joinToString("") { it.c })
            val visible = chosen.filter { it.width >= ZERO }
            if (visible.isNotEmpty()) {
                boxes.add(Box(visible.minOf { it.x0 }, lines[i].y0, visible.maxOf { it.x1 }, lines[i].y1))
                if (first == null) {
                    first = visible.first()
                    firstLine = i
                }
                last = visible.last()
                lastLine = i
            }
        }
        if (first == null || last == null) return null
        val rtlA = isRtl(lines[firstLine], if (firstLine == la) ga else 0, lines[firstLine].glyphs.size - 1)
        val rtlB = isRtl(lines[lastLine], 0, if (lastLine == lb) gb else lines[lastLine].glyphs.size - 1)
        // The selection starts at the reading-order start edge of the first character.
        val sx = if (rtlA) first.x1 else first.x0
        val ex = if (rtlB) last.x0 else last.x1
        return Selection(
            text = parts.joinToString("\n").trim(),
            boxes = boxes,
            from = first.cx to first.cy,
            to = last.cx to last.cy,
            startHandle = sx to lines[firstLine].y1,
            endHandle = ex to lines[lastLine].y1,
            rtlStart = rtlA,
            rtlEnd = rtlB,
        )
    }

    /** Every character on the page. */
    fun selectAll(lines: List<TextLine>): Selection? {
        val firstLine = lines.indexOfFirst { it.glyphs.isNotEmpty() }
        val lastLine = lines.indexOfLast { it.glyphs.isNotEmpty() }
        if (firstLine < 0) return null
        return spanOf(lines, firstLine, 0, lastLine, lines[lastLine].glyphs.size - 1)
    }

    // --- digits --------------------------------------------------------------------------

    /** 0-9 as Arabic-Indic digits (٠-٩). */
    fun toArabicIndic(s: String): String = buildString {
        for (ch in s) append(if (ch in '0'..'9') ('٠' + (ch - '0')) else ch)
    }
}
