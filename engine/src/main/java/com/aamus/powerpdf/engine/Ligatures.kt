package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DefaultColorSpaces
import com.artifex.mupdf.fitz.Device
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Shade
import com.artifex.mupdf.fitz.StrokeState
import com.artifex.mupdf.fitz.Text
import com.artifex.mupdf.fitz.TextWalker
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Letters lost in ligatures (decisions §22). Some files map a ligature glyph to only one of its
 * letters: EuclidFlex's «ft» and «tt» read «t» («after» → «ater», «Matthew» → «Mathew»),
 * HelveticaNeue's «fi», «fl», «ff» read «i», «l», «f» («first» → «irst»). MuPDF reports what the
 * file says, so the edit box showed the broken words and retyping made the loss permanent.
 *
 * The drawing is still right. A glyph standing for a letter while being much wider than that
 * letter's own glyph in the same font is drawn and compared with the font's plain letters drawn
 * side by side («f»+«t», «t»+«t», «f»+«i»…); the closest drawing names its letters.
 */
object TextRepair {
    /** Debug builds turn this off to measure what the repair changes. */
    @Volatile
    var ligatures: Boolean = true
}

/** A coverage mask of drawn glyphs: [w] × [h] cells, ink between columns [x0] and [x1]. */
internal class Mask(val w: Int, val h: Int, val ink: BooleanArray) {
    val x0: Int
    val x1: Int

    init {
        var lo = w
        var hi = -1
        for (y in 0 until h) for (x in 0 until w) if (ink[y * w + x]) {
            if (x < lo) lo = x
            if (x > hi) hi = x
        }
        x0 = lo
        x1 = hi
    }

    val empty: Boolean get() = x1 < 0

    fun at(x: Int, y: Int) = x in 0 until w && y in 0 until h && ink[y * w + x]

    fun near(x: Int, y: Int) = at(x, y) || at(x - 1, y) || at(x + 1, y) || at(x, y - 1) || at(x, y + 1)
}

/** The decisions, apart from MuPDF, so they are tested on the JVM. */
internal object LigatureLogic {
    /** Latin ligatures, longest last. */
    val LIGATURES = listOf("ff", "fi", "fl", "ffi", "ffl", "ft", "fft", "tt", "fb", "fh", "fj", "fk", "ffb", "ffh", "ffj", "ffk", "Th", "st", "ct")

    /** A glyph this much wider than its letter's own glyph is suspected of hiding letters. */
    const val WIDER = 1.3f

    class Scored(val text: String, val shape: Float, val widthFit: Float) {
        val score: Float get() = shape * minOf(1f, widthFit / 0.85f)
    }

    class Choice(val text: String, val guessed: Boolean)

    /**
     * How alike two drawings are, 0..1: [b]'s ink is stretched to [a]'s ink width, then the cells
     * inked in both are counted against those inked in only one (a cell next to ink counts as near).
     */
    fun similarity(a: Mask, b: Mask): Float {
        if (a.empty || b.empty) return 0f
        val wa = a.x1 - a.x0 + 1
        val wb = b.x1 - b.x0 + 1
        var both = 0
        var onlyA = 0
        var onlyB = 0
        for (y in 0 until a.h) for (i in 0 until wa) {
            val xa = a.x0 + i
            val xb = b.x0 + (i.toFloat() * wb / wa).roundToInt()
            val pa = a.at(xa, y)
            val pb = b.at(xb, y)
            when {
                pa && pb -> both++
                pa && !b.near(xb, y) -> onlyA++
                pb && !a.near(xa, y) -> onlyB++
            }
        }
        val all = both + onlyA + onlyB
        return if (all == 0) 0f else both.toFloat() / all
    }

    /**
     * The ligatures a glyph standing for [mapped] could be: those starting or ending with it whose
     * letters all have a plain glyph ([has]). Null [mapped]: the glyph has no letter at all.
     */
    fun candidates(mapped: Char?, has: (Char) -> Boolean): List<String> =
        LIGATURES.filter { l -> (mapped == null || l.first() == mapped || l.last() == mapped) && l.all(has) }

    /**
     * The letters a glyph stands for, from its comparisons ([scored]); null when none is
     * convincing. [mapped] is the one letter the file gives it (null: none). [advance] is the
     * glyph's width in ems and [ratio] that width over its letter's own glyph.
     */
    fun choose(mapped: Char?, scored: List<Scored>, advance: Float, ratio: Float): Choice? {
        val sorted = scored.sortedByDescending { it.score }
        val best = sorted.firstOrNull()
        val second = sorted.getOrNull(1)?.score ?: 0f
        if (mapped == null) {
            // Nothing to go by but the drawing: only a clear match.
            return if (best != null && best.score >= 0.9f && best.score - second >= 0.15f) Choice(best.text, guessed = false) else null
        }
        if (best != null && best.score >= 0.8f && best.score - second >= 0.1f) return Choice(best.text, guessed = false)
        if (best != null && best.score >= 0.8f) return Choice(best.text, guessed = true)
        // The letters to compare with are missing (an «f» never drawn alone on the page). Only
        // «i» and «l» are safe to guess: the ligatures ending with them are «fi», «fl», «ffi», «ffl».
        if ((mapped == 'i' || mapped == 'l') && ratio >= 1.8f && advance >= 0.4f) {
            return Choice(if (advance < 0.75f) "f$mapped" else "ff$mapped", guessed = true)
        }
        return null
    }

    /** [g] cut into one glyph per character of [text], side by side. */
    fun split(g: Glyph, text: String): List<Glyph> {
        val cps = text.codePoints().toArray()
        if (cps.size <= 1) return listOf(g.copy(c = text))
        val step = g.width / cps.size
        return cps.mapIndexed { i, cp -> Glyph(String(Character.toChars(cp)), g.x0 + step * i, g.y0, g.x0 + step * (i + 1), g.y1) }
    }
}

/** Glyphs whose text the file got wrong on one page, found by [findTextFixes]. */
internal class TextFixes(private val fixes: List<Fix>) {
    class Fix(val font: String, val x: Float, val y: Float, val text: String, val guessed: Boolean)

    private val fonts = fixes.mapTo(HashSet()) { it.font }

    val isEmpty: Boolean get() = fixes.isEmpty()

    /** The fix for the glyph of [font] drawn at ([x], [y]) on the page, if any. */
    fun at(font: String, x: Float, y: Float): Fix? {
        if (font !in fonts) return null
        return fixes.firstOrNull { it.font == font && abs(it.x - x) < 0.05f && abs(it.y - y) < 0.05f }
    }

    override fun toString() = fixes.groupBy { "${it.font}→${it.text}${if (it.guessed) "?" else ""}" }.map { "${it.key}×${it.value.size}" }.joinToString(", ")

    companion object {
        val NONE = TextFixes(emptyList())
    }
}

private val pointerField = runCatching { Font::class.java.getDeclaredField("pointer").apply { isAccessible = true } }.getOrNull()

/** The same font object for every glyph drawn with it (each callback gets a new Java wrapper). */
private fun fontKey(font: Font): Long = pointerField?.let { runCatching { it.getLong(font) }.getOrNull() } ?: font.name.hashCode().toLong()

private class Drawn(val font: Long, val gid: Int, val uni: Int, val x: Float, val y: Float) {
    /** Followed by more characters for the same glyph: the file gives all its letters. */
    var whole = false
}

private class FontUse(val font: Font, val name: String) {
    val advances = HashMap<Int, Float>()
    /** For each character, the glyphs drawn for it and how often. */
    val glyphs = HashMap<Int, HashMap<Int, Int>>()

    fun advance(gid: Int) = advances.getOrPut(gid) { runCatching { font.advanceGlyph(gid, false) }.getOrDefault(0f) }
}

/** Every glyph a page draws, with its font, glyph number and character, in page coordinates. */
private fun drawnGlyphs(page: Page, fonts: HashMap<Long, FontUse>): List<Drawn> {
    val out = ArrayList<Drawn>()
    fun collect(text: Text, ctm: Matrix) {
        text.walk(object : TextWalker {
            override fun showGlyph(font: Font, trm: Matrix, glyph: Int, unicode: Int, wmode: Boolean) {
                if (glyph < 0) {
                    out.lastOrNull()?.whole = true
                    return
                }
                val key = fontKey(font)
                if (key !in fonts) fonts[key] = FontUse(font, runCatching { font.name }.getOrDefault(""))
                val x = trm.e * ctm.a + trm.f * ctm.c + ctm.e
                val y = trm.e * ctm.b + trm.f * ctm.d + ctm.f
                out.add(Drawn(key, glyph, unicode, x, y))
            }
        })
    }
    val dev = TextOnlyDevice { text, ctm -> collect(text, ctm) }
    try {
        page.run(dev, Matrix.Identity(), null)
    } finally {
        dev.destroy()
    }
    return out
}

/** Draws glyphs of one font into a reused pixmap to compare their shapes. */
private class Drawer {
    private val pix = Pixmap(ColorSpace.DeviceGray, 0, 0, W, H, false)

    /** [parts]: glyph numbers and where each starts, in ems from the first. */
    fun mask(font: Font, parts: List<Pair<Int, Float>>): Mask {
        pix.clear(255)
        val dev = DrawDevice(Matrix.Identity(), pix)
        val text = Text()
        try {
            for ((gid, dx) in parts) text.showGlyph(font, Matrix(EM, 0f, 0f, -EM, EM * 0.5f + dx * EM, BASE), gid, 0, false)
            dev.fillText(text, Matrix.Identity(), ColorSpace.DeviceGray, floatArrayOf(0f), 1f, 0)
            dev.close()
        } finally {
            dev.destroy()
            text.destroy()
        }
        val s = pix.samples
        return Mask(W, H, BooleanArray(W * H) { (s[it].toInt() and 0xFF) < 128 })
    }

    fun destroy() = pix.destroy()

    companion object {
        const val EM = 48f
        const val W = 192
        const val H = 96
        const val BASE = 67f
    }
}

private fun isLatinLetter(cp: Int) = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code

/**
 * Finds the glyphs of [page] that stand for fewer letters than they draw (see the top of this
 * file) and what they really say. Pages without such glyphs cost one pass over their text.
 */
internal fun findTextFixes(page: Page): TextFixes {
    if (!TextRepair.ligatures) return TextFixes.NONE
    val fonts = HashMap<Long, FontUse>()
    val drawn = drawnGlyphs(page, fonts)
    for (d in drawn) {
        if (d.whole) continue
        fonts.getValue(d.font).glyphs.getOrPut(d.uni) { HashMap() }.merge(d.gid, 1, Int::plus)
    }
    val decided = HashMap<Pair<Long, Int>, LigatureLogic.Choice?>()
    var drawer: Drawer? = null
    try {
        for ((key, use) in fonts) {
            if (runCatching { use.font.isMono }.getOrDefault(false)) continue
            // Each letter's own glyph: the narrowest drawn for it, the most used among equals.
            val plain = HashMap<Char, Int>()
            for ((cp, gids) in use.glyphs) {
                if (!isLatinLetter(cp)) continue
                plain[cp.toChar()] = gids.entries.minWith(compareBy<Map.Entry<Int, Int>> { use.advance(it.key) }.thenByDescending { it.value }).key
            }
            if (plain.size < 3) continue
            // A font whose text is mostly missing has no letter map at all: nothing to repair here.
            val unknownDraws = use.glyphs.entries.filter { it.key <= 0 || it.key == 0xFFFD }.sumOf { it.value.values.sum() }
            val allDraws = use.glyphs.values.sumOf { it.values.sum() }
            val tryUnknown = unknownDraws * 10 < allDraws
            var unknownTried = 0
            for ((cp, gids) in use.glyphs) {
                val unknown = cp <= 0 || cp == 0xFFFD
                if (unknown && !tryUnknown) continue
                if (!unknown && !isLatinLetter(cp)) continue
                val mapped = if (unknown) null else cp.toChar()
                val own = mapped?.let { plain[it] }
                for (gid in gids.keys) {
                    if (gid == own) continue
                    val adv = use.advance(gid)
                    val ownAdv = own?.let { use.advance(it) } ?: 0f
                    if (unknown && (adv < 0.3f || ++unknownTried > 12)) continue
                    if (!unknown && adv < ownAdv * LigatureLogic.WIDER) continue
                    val d = drawer ?: Drawer().also { drawer = it }
                    val lig = d.mask(use.font, listOf(gid to 0f))
                    val scored = LigatureLogic.candidates(mapped) { it in plain }.map { cand ->
                        var dx = 0f
                        val parts = cand.map { ch -> val g = plain.getValue(ch); (g to dx).also { dx += use.advance(g) } }
                        val widthFit = if (dx > 0 && adv > 0) minOf(dx, adv) / maxOf(dx, adv) else 0f
                        LigatureLogic.Scored(cand, LigatureLogic.similarity(lig, d.mask(use.font, parts)), widthFit)
                    }
                    val ratio = if (ownAdv > 0) adv / ownAdv else 0f
                    decided[key to gid] = LigatureLogic.choose(mapped, scored, adv, ratio)
                    android.util.Log.i("PowerPDF", "ligature ${use.name} gid $gid '${mapped ?: '?'}' adv $adv: " +
                        scored.sortedByDescending { it.score }.take(3).joinToString { "${it.text} ${"%.2f".format(it.score)}" } + " → ${decided[key to gid]?.text}")
                }
            }
        }
    } finally {
        drawer?.destroy()
    }
    if (decided.values.all { it == null }) return TextFixes.NONE
    val fixes = drawn.mapNotNull { d ->
        if (d.whole) return@mapNotNull null
        val c = decided[d.font to d.gid] ?: return@mapNotNull null
        TextFixes.Fix(fonts.getValue(d.font).name, d.x, d.y, c.text, c.guessed)
    }
    return TextFixes(fixes)
}
