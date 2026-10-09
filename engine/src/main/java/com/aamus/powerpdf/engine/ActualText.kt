package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DefaultColorSpaces
import com.artifex.mupdf.fitz.Device
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Shade
import com.artifex.mupdf.fitz.StrokeState
import com.artifex.mupdf.fitz.Text
import com.artifex.mupdf.fitz.TextWalker
import kotlin.math.abs

/**
 * The text we write carries its real words (decisions §24). Read back, our own retyped Arabic lost
 * vowel marks («يُعَرِّض» → «يُعَرض»), letters with a shadda came back as U+FFFD and some numbers
 * were split («٢٩٪» → «٩ ٢٪»): the drawing is right, but MuPDF's PDF writer maps glyphs back to
 * letters through the font alone. Each retyped line is wrapped in
 * `/Span <</ActualText …>> BDC … EMC`, which MuPDF, Acrobat and other readers use when they read
 * text. MuPDF's writer cannot write it, so it is added to the overlay's content afterwards.
 *
 * The layout says, line by line, how many glyphs it drew and how many characters they stand for
 * ([inkLines]); the characters split the paragraph's own text into lines ([ActualText.splitLines]);
 * the glyphs find each line's `TJ` arrays in the content ([ActualText.wrap]). Anything that does
 * not add up leaves the content as it was.
 */
object ActualText {
    /** Off: see decisions §24 (tried 2026-10-01; the reading got worse). */
    @Volatile
    var enabled: Boolean = false

    private fun blank(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    /**
     * [text] cut into lines holding [chars] non-blank characters each, in order; null when the
     * counts do not use up the text exactly.
     */
    fun splitLines(text: String, chars: List<Int>): List<String>? {
        val out = ArrayList<String>()
        var i = 0
        for (n in chars) {
            while (i < text.length && blank(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
            val start = i
            var left = n
            var end = i
            while (left > 0 && i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (!blank(cp)) {
                    left--
                    end = i
                }
            }
            if (left > 0) return null
            out.add(text.substring(start, end))
        }
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!blank(cp)) return null
            i += Character.charCount(cp)
        }
        return out
    }

    /** [s] as a PDF text string in UTF-16 with a byte-order mark, written in hex. */
    fun pdfString(s: String): String {
        val b = StringBuilder("<FEFF")
        for (ch in s) b.append(String.format(java.util.Locale.US, "%04X", ch.code))
        return b.append('>').toString()
    }

    private val TJ = Regex("""\[([^\]]*)\]TJ""")
    private val HEX = Regex("""<([0-9A-Fa-f]*)>""")

    /**
     * [content] with each line's `TJ` arrays wrapped in an ActualText span. [lines] are, in
     * drawing order, how many glyphs each line drew and its text. Null when the glyphs in the
     * content do not match the lines exactly (the caller keeps the content as it was).
     */
    fun wrap(content: String, lines: List<Pair<Int, String>>): String? {
        val out = StringBuilder()
        var at = 0
        var k = 0
        fun skipEmpty() {
            while (k < lines.size && lines[k].first == 0) k++
        }
        skipEmpty()
        var left = lines.getOrNull(k)?.first ?: 0
        for (m in TJ.findAll(content)) {
            val glyphs = HEX.findAll(m.groupValues[1]).sumOf { it.groupValues[1].length / 4 }
            if (glyphs == 0) continue
            if (k >= lines.size) return null
            out.append(content, at, m.range.first)
            if (left == lines[k].first) out.append("/Span<</ActualText").append(pdfString(lines[k].second)).append(">>BDC\n")
            out.append(m.value)
            at = m.range.last + 1
            left -= glyphs
            if (left < 0) return null
            if (left == 0) {
                out.append("\nEMC")
                k++
                skipEmpty()
                left = lines.getOrNull(k)?.first ?: 0
            }
        }
        if (k < lines.size) return null
        out.append(content, at, content.length)
        return out.toString()
    }
}

/** A line of laid-out text: how many glyphs it draws and how many characters they stand for. */
internal class InkLine(val glyphs: Int, val chars: Int)

/** Calls [onText] for every piece of text a drawing shows; everything else is ignored. */
internal class TextOnlyDevice(private val onText: (Text, Matrix) -> Unit) : Device() {
    override fun close() {}
    override fun fillPath(path: Path?, evenOdd: Boolean, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
    override fun strokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
    override fun clipPath(path: Path?, evenOdd: Boolean, ctm: Matrix?) {}
    override fun clipStrokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?) {}
    override fun fillText(text: Text, ctm: Matrix, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) = onText(text, ctm)
    override fun strokeText(text: Text, stroke: StrokeState?, ctm: Matrix, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) = onText(text, ctm)
    override fun clipText(text: Text, ctm: Matrix) = onText(text, ctm)
    override fun clipStrokeText(text: Text, stroke: StrokeState?, ctm: Matrix) = onText(text, ctm)
    override fun ignoreText(text: Text, ctm: Matrix) = onText(text, ctm)
    override fun fillShade(shade: Shade?, ctm: Matrix?, alpha: Float, cp: Int) {}
    override fun fillImage(image: Image?, ctm: Matrix?, alpha: Float, cp: Int) {}
    override fun fillImageMask(image: Image?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
    override fun clipImageMask(image: Image?, ctm: Matrix?) {}
    override fun popClip() {}
    override fun beginMask(area: Rect?, luminosity: Boolean, cs: ColorSpace?, bc: FloatArray?, cp: Int) {}
    override fun endMask() {}
    override fun beginGroup(area: Rect?, cs: ColorSpace?, isolated: Boolean, knockout: Boolean, blendmode: Int, alpha: Float) {}
    override fun endGroup() {}
    override fun beginTile(area: Rect?, view: Rect?, xstep: Float, ystep: Float, ctm: Matrix?, id: Int, docId: Int): Int = 0
    override fun endTile() {}
    override fun renderFlags(set: Int, clear: Int) {}
    override fun setDefaultColorSpaces(dcs: DefaultColorSpaces?) {}
    override fun beginLayer(name: String?) {}
    override fun endLayer() {}
    override fun beginStructure(standard: Int, raw: String?, idx: Int) {}
    override fun endStructure() {}
    override fun beginMetatext(meta: Int, text: String?) {}
    override fun endMetatext() {}
}

/**
 * The lines of a laid-out paragraph in drawing order: a new line where the baseline moves by more
 * than [size] × 0.6 (a vowel mark sits a little higher than its letter). Glyphs are those with a glyph number (what the PDF writer writes);
 * characters are the non-blank ones, including those sharing a glyph (a ligature's second letter).
 */
internal fun inkLines(list: DisplayList, size: Float): List<InkLine> {
    val out = ArrayList<InkLine>()
    var glyphs = 0
    var chars = 0
    var y = Float.NaN
    val dev = TextOnlyDevice { text, _ ->
        text.walk(object : TextWalker {
            override fun showGlyph(font: com.artifex.mupdf.fitz.Font, trm: Matrix, glyph: Int, unicode: Int, wmode: Boolean) {
                if (glyph >= 0 && !y.isNaN() && abs(trm.f - y) > size * 0.6f) {
                    out.add(InkLine(glyphs, chars))
                    glyphs = 0
                    chars = 0
                }
                if (glyph >= 0) {
                    glyphs++
                    y = trm.f
                }
                if (unicode > 0 && !Character.isWhitespace(unicode) && !Character.isSpaceChar(unicode)) chars++
            }
        })
    }
    try {
        list.run(dev, Matrix.Identity(), null)
    } finally {
        dev.destroy()
    }
    if (glyphs > 0 || chars > 0) out.add(InkLine(glyphs, chars))
    return out
}
