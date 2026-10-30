package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFObject

/**
 * A MuPDF 1.28 redaction bug, found by the desktop app (desktop decisions §58). MuPDF confuses Td
 * with TD both when reading and when writing a text block: after "195.59 266 Td" a following '
 * moved 266 pt up instead of 9.5 pt down, and when the first line of a block is removed it
 * rewrites the block starting with "x y TD", which also sets the leading, so every following T*
 * jumps off the page (on a newspaper page, removing one line threw away the five under it).
 *
 * Before every removal the page's text moves are rewritten so nothing depends on the leading; the
 * page draws exactly as before:
 *   T* → 0 −L Td    (s) ' → 0 −L Td (s) Tj    aw ac (s) " → aw Tw ac Tc 0 −L Td (s) Tj
 *   x y TD → x y Td (and L = −y)    L TL → dropped (L remembered)
 * L is followed through the page's streams, saved and restored by q and Q like the graphics state.
 */
object ContentFix {

    /** The leading in force, and the ones q saved. */
    class Leading(var value: Float = 0f, val stack: ArrayDeque<Float> = ArrayDeque())

    internal enum class Kind { Number, Op, Other }

    internal class Token(val kind: Kind, val value: String, val start: Int, val end: Int)

    /** [s] (a content stream as Latin-1 text) with every move made explicit; null when nothing changes. */
    fun explicitLeading(s: String, state: Leading = Leading()): String? {
        val ts = tokens(s)
        class Edit(val start: Int, val end: Int, val text: String)
        val edits = ArrayList<Edit>()
        fun num(v: Float): String {
            val r = Math.round(v * 100000.0) / 100000.0
            return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else r.toString()
        }
        fun down() = "0 ${num(-state.value)} Td"
        for (k in ts.indices) {
            val t = ts[k]
            if (t.kind != Kind.Op) continue
            val p1 = ts.getOrNull(k - 1)
            val p2 = ts.getOrNull(k - 2)
            val p3 = ts.getOrNull(k - 3)
            when (t.value) {
                "q" -> state.stack.addLast(state.value)
                "Q" -> state.value = state.stack.removeLastOrNull() ?: state.value
                // The leading itself is still set (E6): a form drawn later, or text MuPDF does not
                // rewrite, may still use the leading the page set.
                "TL" -> if (p1?.kind == Kind.Number) state.value = p1.value.toFloat()
                "TD" -> if (p1?.kind == Kind.Number && p2?.kind == Kind.Number) {
                    state.value = -p1.value.toFloat()
                    edits.add(Edit(t.start, t.end, "Td ${num(state.value)} TL"))
                }
                "T*" -> edits.add(Edit(t.start, t.end, down()))
                "'" -> if (p1 != null && p1.kind == Kind.Other && (p1.value == "()" || p1.value == "<>")) {
                    edits.add(Edit(p1.start, t.end, "${down()} ${s.substring(p1.start, p1.end)} Tj"))
                }
                "\"" -> if (p3?.kind == Kind.Number && p2?.kind == Kind.Number && p1 != null && (p1.value == "()" || p1.value == "<>")) {
                    edits.add(Edit(p3.start, t.end, "${p3.value} Tw ${p2.value} Tc ${down()} ${s.substring(p1.start, p1.end)} Tj"))
                }
            }
        }
        if (edits.isEmpty()) return null
        val out = StringBuilder(s)
        for (e in edits.sortedByDescending { it.start }) out.replace(e.start, e.end, e.text)
        return out.toString()
    }

    private fun delim(c: Char) = c.isWhitespace() || c in "()<>[]{}/%"

    /** The tokens of a content stream; strings, hex strings, brackets and dictionaries are "other". */
    internal fun tokens(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '%' -> while (i < n && s[i] != '\n' && s[i] != '\r') i++
                c == '(' -> {
                    val start = i
                    var depth = 0
                    while (i < n) {
                        val ch = s[i]
                        if (ch == '\\') i++
                        else if (ch == '(') depth++
                        else if (ch == ')' && --depth == 0) {
                            i++
                            break
                        }
                        i++
                    }
                    out.add(Token(Kind.Other, "()", start, minOf(i, n)))
                }
                c == '<' && i + 1 < n && s[i + 1] == '<' -> {
                    out.add(Token(Kind.Other, "<<", i, i + 2))
                    i += 2
                }
                c == '>' && i + 1 < n && s[i + 1] == '>' -> {
                    out.add(Token(Kind.Other, ">>", i, i + 2))
                    i += 2
                }
                c == '<' -> {
                    val start = i
                    while (i < n && s[i] != '>') i++
                    i++
                    out.add(Token(Kind.Other, "<>", start, minOf(i, n)))
                }
                c in "[]{}>" -> {
                    out.add(Token(Kind.Other, c.toString(), i, i + 1))
                    i++
                }
                c == '/' -> {
                    val start = i++
                    while (i < n && !delim(s[i])) i++
                    out.add(Token(Kind.Other, s.substring(start, i), start, i))
                }
                else -> {
                    val start = i
                    while (i < n && !delim(s[i])) i++
                    if (i == start) i++
                    val value = s.substring(start, i)
                    out.add(Token(if (NUMBER.matches(value)) Kind.Number else Kind.Op, value, start, i))
                    // Inline images carry binary data: skip to the EI that ends it — whitespace before
                    // it (or none, for empty data) and whitespace or a delimiter after (E6). "EI"
                    // bytes inside the picture itself do not stand alone like that.
                    if (value == "ID") {
                        val begin = i + 1
                        var j = begin
                        while (j + 1 < n) {
                            if (s[j] == 'E' && s[j + 1] == 'I' && (j == begin || s[j - 1].isWhitespace()) && (j + 2 >= n || delim(s[j + 2]))) break
                            j++
                        }
                        if (j + 1 >= n) {
                            // No EI standing alone (a producer that writes no space before it): the
                            // first one followed by a delimiter, rather than skipping the rest.
                            j = LOOSE_EI.find(s, minOf(begin, n))?.range?.first ?: n
                        }
                        i = minOf(j, n)
                    }
                }
            }
        }
        return out
    }

    private val NUMBER = Regex("""^[+-]?(\d+\.?\d*|\.\d+)$""")
    private val LOOSE_EI = Regex("""EI(?=[\s()<>\[\]{}/%]|$)""")

    /**
     * How a content stream moves the graphics-state stack (E5): the lowest depth it reaches (0 or
     * less: a stray Q) and where it ends, relative to where it starts. Strings, inline images and
     * comments are skipped.
     */
    fun qDepth(s: String): Pair<Int, Int> {
        var depth = 0
        var low = 0
        for (t in tokens(s)) {
            if (t.kind != Kind.Op) continue
            if (t.value == "q") depth++
            else if (t.value == "Q") low = minOf(low, --depth)
        }
        return low to depth
    }

    /**
     * How many q (or Q) the wrapper will write: PDF 32000 Annex C puts the graphics-state stack at
     * 28, so a page needing more than this is already past what any reader honours.
     */
    private const val MAX_WRAP = 1024

    /**
     * How many q go in front of a page's own content and how many Q after it, for the [qDepth] it
     * reported, so that what is drawn next starts from the default graphics state (E5). Both counts
     * come from the page's own operators, so a damaged or hostile stream of nothing but q or Q
     * would otherwise make a wrapper as large as itself; every real file stays exact.
     */
    fun wrapCounts(low: Int, end: Int): Pair<Int, Int> {
        val lead = minOf(1 - low, MAX_WRAP)
        return lead to maxOf(1, minOf(lead + end, MAX_WRAP))
    }
}

/**
 * Makes page [index]'s text moves explicit before a removal (see [ContentFix]); how many streams
 * changed. A changed stream is written as a NEW stream object for this page only, and the page gets
 * its own /Contents (E6): a stream shared with other pages, or with a form, is never changed in
 * place, so those keep drawing exactly what they drew.
 */
internal fun PdfDoc.explicitLeading(index: Int): Int {
    val page = pdf.findPage(index)
    val contents = page.get("Contents")
    val items = when {
        contents.isArray -> (0 until contents.size()).map { contents.get(it) }
        contents.isStream -> listOf(contents)
        else -> emptyList()
    }
    val state = ContentFix.Leading()
    var changed = 0
    val out = ArrayList<PDFObject>()
    for (stream in items) {
        val fixed = if (stream.isStream) ContentFix.explicitLeading(String(stream.readStream(), Charsets.ISO_8859_1), state) else null
        if (fixed == null) {
            out.add(stream)
            continue
        }
        val b = Buffer()
        try {
            b.writeBytes(fixed.toByteArray(Charsets.ISO_8859_1))
            out.add(pdf.addStream(b, pdf.newDictionary()))
        } finally {
            b.destroy()
        }
        changed++
    }
    if (changed > 0) {
        if (contents.isArray) {
            val array = pdf.newArray()
            for (o in out) array.append(o)
            page.put("Contents", array)
        } else page.put("Contents", out[0])
        android.util.Log.i("PowerPDF", "explicit leading: page ${index + 1}, $changed streams")
    }
    return changed
}
