package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFObject

/**
 * Marked content can carry its own text: /ActualText (what copy and search read instead of the
 * glyphs), /Alt (a description for screen readers), /E (an abbreviation's expansion) and /T (a
 * title). MuPDF's redaction removes the glyphs and leaves the span and its dictionary, so
 * "/Span <</ActualText (Account 98765)>> BDC … EMC" kept the account number in the file after the
 * number itself was gone. Only the page's own content is read: spans inside a form XObject the page
 * draws are not changed here.
 *
 * [clearReplacementText] runs a removal and then clears these four entries from every span the
 * removal changed: one whose text, pictures or shadings are no longer exactly what they were.
 * Spans the removal did not touch keep theirs. A span is matched by its tag, its properties and
 * what it draws, so MuPDF rewriting the page's layout and numbers does not count as a change;
 * anything that does not match is treated as changed (the safe side).
 *
 * A span that names its properties in the page's /Properties gets its own dictionary (only its
 * MCID), and the named entry is removed from the page's resources, and from a dictionary the page
 * inherits or shares, once nothing drawing through them still names it.
 */
object ReplacementText {
    internal val KEYS = setOf("ActualText", "Alt", "E", "T")
    internal val MCID = Regex("/MCID (\\d+)")
    private val DRAWS = setOf("Tj", "TJ", "'", "\"", "Do", "EI", "sh")

    class Span(val tag: String) {
        /** Where the properties operand is in the stream: an inline dictionary or a resource name. */
        var start = -1
        var end = -1
        var named: String? = null
        var props = ""
        val text = StringBuilder()
        val objects = ArrayList<String>()

        val key: String get() = listOf(tag, named ?: props, text.toString(), objects.joinToString(",")).joinToString("\u0000")
    }

    /** Every marked-content span of a stream, with what it draws (nested spans count for each parent). */
    fun spans(s: String): List<Span> {
        val out = ArrayList<Span>()
        val open = ArrayList<Span>()
        var operands = ArrayList<ContentFix.Token>()
        for (t in ContentFix.tokens(s)) {
            if (t.kind != ContentFix.Kind.Op) {
                operands.add(t)
                continue
            }
            when (t.value) {
                "BDC", "BMC" -> {
                    val span = Span(operands.firstOrNull()?.value ?: "")
                    val last = operands.lastOrNull()
                    if (t.value == "BDC" && last != null && operands.size >= 2) {
                        if (last.value == ">>") {
                            var depth = 0
                            var j = operands.size - 1
                            while (j >= 0) {
                                val x = operands[j].value
                                if (x == ">>") depth++ else if (x == "<<" && --depth == 0) break
                                j--
                            }
                            if (j >= 0) {
                                span.start = operands[j].start
                                span.end = last.end
                                span.props = canonical(s.substring(span.start, span.end))
                            }
                        } else if (last.value.startsWith("/")) {
                            span.start = last.start
                            span.end = last.end
                            span.named = last.value.substring(1)
                        }
                    }
                    out.add(span)
                    open.add(span)
                }
                "EMC" -> if (open.isNotEmpty()) open.removeAt(open.size - 1)
                in DRAWS -> {
                    val strings = operands.filter { it.value == "()" || it.value == "<>" }.map { decoded(s, it) }
                    val names = operands.filter { it.value.startsWith("/") }.joinToString("") { it.value }
                    for (span in open) {
                        for (x in strings) span.text.append(x)
                        if (t.value == "Do" || t.value == "sh") span.objects.add(t.value + names)
                        else if (t.value == "EI") span.objects.add("EI")
                    }
                }
            }
            operands = ArrayList()
        }
        return out
    }

    /** A dictionary written the same way however it was spaced or its strings escaped. */
    private fun canonical(text: String): String =
        ContentFix.tokens(text).joinToString(" ") { if (it.value == "()" || it.value == "<>") "(${decoded(text, it)})" else it.value }

    /** An inline properties dictionary without the four replacement-text entries, or null if it has none. */
    fun withoutKeys(text: String): String? {
        val ts = ContentFix.tokens(text)
        val cut = ArrayList<Pair<Int, Int>>()
        var depth = 0
        var k = 0
        while (k < ts.size) {
            val t = ts[k]
            if (t.value == "<<") depth++
            else if (t.value == ">>") depth--
            else if (depth == 1 && t.value.startsWith("/")) {
                var end = k + 1
                val first = ts.getOrNull(end)
                if (first != null && (first.value == "<<" || first.value == "[")) {
                    var d = 0
                    while (end < ts.size) {
                        val x = ts[end].value
                        if (x == "<<" || x == "[") d++ else if ((x == ">>" || x == "]") && --d == 0) break
                        end++
                    }
                } else if (first?.kind == ContentFix.Kind.Number && ts.getOrNull(end + 2)?.value == "R") end += 2
                end = minOf(end, ts.size - 1)
                if (t.value.substring(1) in KEYS) cut.add(t.start to ts[end].end)
                k = end + 1
                continue
            }
            k++
        }
        if (cut.isEmpty()) return null
        var out = text
        for ((a, b) in cut.asReversed()) out = out.substring(0, a) + " " + out.substring(b)
        return out
    }

    /** The bytes of a literal or hex string token, as latin1 characters. */
    private fun decoded(s: String, t: ContentFix.Token): String {
        if (t.end - t.start < 2) return ""
        val body = s.substring(t.start + 1, t.end - 1)
        if (t.value == "<>") {
            val hex = body.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            val out = StringBuilder()
            var i = 0
            while (i < hex.length) {
                out.append(hex.substring(i, minOf(i + 2, hex.length)).padEnd(2, '0').toInt(16).toChar())
                i += 2
            }
            return out.toString()
        }
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            i++
            if (i >= body.length) break
            val next = body[i]
            when {
                next == 'n' -> out.append('\n')
                next == 'r' -> out.append('\r')
                next == 't' -> out.append('\t')
                next == 'b' -> out.append('\b')
                next == 'f' -> out.append('\u000c')
                next in '0'..'7' -> {
                    var digits = next.toString()
                    while (digits.length < 3 && i + 1 < body.length && body[i + 1] in '0'..'7') digits += body[++i]
                    out.append((digits.toInt(8) and 255).toChar())
                }
                next == '\r' -> if (i + 1 < body.length && body[i + 1] == '\n') i++
                next == '\n' -> {}
                else -> out.append(next)
            }
            i++
        }
        return out.toString()
    }
}

/** Runs [removal] on page [index], then clears the replacement text of the spans it changed (see [ReplacementText]). */
internal fun <T> PdfDoc.clearReplacementText(index: Int, removal: () -> T): T {
    // Each span's key, with the MCIDs of the spans that have it (one entry per span).
    val before = HashMap<String, ArrayDeque<Int?>>()
    runCatching {
        for (span in ReplacementText.spans(streamsOf(index).joinToString(" ") { String(it.readStream(), Charsets.ISO_8859_1) })) {
            before.getOrPut(span.key) { ArrayDeque() }.addLast(mcidOf(index, span))
        }
    }
    val result = removal()
    val changedMcids = HashSet<Int>()
    val page = pdf.findPage(index)
    val contents = page.get("Contents")
    val items = streamsOf(index)
    val retired = HashSet<String>()
    var changed = 0
    val out = ArrayList<PDFObject>()
    for (stream in items) {
        val source = String(stream.readStream(), Charsets.ISO_8859_1)
        class Edit(val start: Int, val end: Int, val text: String)
        val edits = ArrayList<Edit>()
        for (span in ReplacementText.spans(source)) {
            if (span.start < 0) continue
            val left = before[span.key]
            if (left != null && left.isNotEmpty()) {
                left.removeFirst()
                continue
            }
            mcidOf(index, span)?.let { changedMcids.add(it) }
            val named = span.named
            if (named != null) {
                val dict = namedProperties(index, named) ?: continue
                if (ReplacementText.KEYS.none { !dict.get(it).isNull }) continue
                retired.add(named)
                val mcid = dict.get("MCID")
                edits.add(Edit(span.start, span.end, if (mcid.isInteger) "<</MCID ${mcid.asInteger()}>>" else "<<>>"))
            } else {
                val stripped = ReplacementText.withoutKeys(source.substring(span.start, span.end)) ?: continue
                edits.add(Edit(span.start, span.end, stripped))
            }
        }
        if (edits.isEmpty()) {
            out.add(stream)
            continue
        }
        val text = StringBuilder(source)
        for (e in edits.sortedByDescending { it.start }) text.replace(e.start, e.end, e.text)
        val b = Buffer()
        try {
            b.writeBytes(text.toString().toByteArray(Charsets.ISO_8859_1))
            // A new stream for this page only: one shared with other pages is never changed in place.
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
    }
    if (retired.isNotEmpty()) retireProperties(index, retired)
    // Spans the removal took away altogether; then the structure tree's elements tagging them.
    for (list in before.values) for (mcid in list) if (mcid != null) changedMcids.add(mcid)
    clearTagText(index, changedMcids)
    return result
}

/** The span's marked-content ID, from its inline dictionary or the named one. */
private fun PdfDoc.mcidOf(index: Int, span: ReplacementText.Span): Int? {
    val named = span.named
    if (named != null) {
        val id = namedProperties(index, named)?.get("MCID") ?: return null
        return if (id.isInteger) id.asInteger() else null
    }
    return ReplacementText.MCID.find(span.props)?.groupValues?.get(1)?.toIntOrNull()
}

private fun PdfDoc.streamsOf(index: Int): List<PDFObject> {
    val contents = pdf.findPage(index).get("Contents")
    return when {
        contents.isArray -> (0 until contents.size()).map { contents.get(it) }.filter { it.isStream }
        contents.isStream -> listOf(contents)
        else -> emptyList()
    }
}

private fun PdfDoc.namedProperties(index: Int, name: String): PDFObject? = runCatching {
    pdf.findPage(index).getInheritable("Resources").get("Properties").get(name).takeIf { it.isDictionary }
}.getOrNull()

/** Drops property lists page [index] no longer names from its own resources and the page tree's. */
private fun PdfDoc.retireProperties(index: Int, names: Set<String>) {
    val used = ContentNames.of(streamsOf(index).joinToString(" ") { String(it.readStream(), Charsets.ISO_8859_1) })
    val gone = names.filter { it !in used }.toSet()
    if (gone.isEmpty()) return
    val page = pdf.findPage(index)
    val res = page.get("Resources")
    val props = if (res.isDictionary) res.get("Properties") else null
    if (props != null && props.isDictionary) {
        // New dictionaries for this page: its resources may be shared with other pages.
        val kept = pdf.newDictionary()
        for (key in props) if (key.asName() !in gone) kept.put(key, props.get(key))
        val own = pdf.newDictionary()
        for (key in res) own.put(key, res.get(key))
        own.put("Properties", kept)
        page.put("Resources", own)
    }
    // A page tree that cannot be followed keeps its shared dictionaries; this page no longer uses them.
    runCatching { unlinkProperties(gone) }
}
