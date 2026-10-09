package com.aamus.powerpdf.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The parts of text recognition that are plain arithmetic, ported line for line from the
 * desktop app (src/engine/ocr: boxes.ts, ctc.ts, order.ts and the scaling
 * in engine.ts) so both apps read a page the same way. No Android or onnxruntime types here, so
 * it is tested on the JVM. docs/research-ocr-android.md explains each step.
 */

/** A picture as ARGB pixels (Android's Bitmap order). */
class OcrImage(val px: IntArray, val width: Int, val height: Int) {
    init {
        require(px.size >= width * height) { "picture data too short" }
    }

    /** The part at ([x], [y]) of [w] × [h], clipped to the picture; never empty. */
    fun crop(x: Float, y: Float, w: Float, h: Float): OcrImage {
        val left = max(0, x.roundToInt())
        val top = max(0, y.roundToInt())
        val cw = max(1, min(width - left, w.roundToInt()))
        val ch = max(1, min(height - top, h.roundToInt()))
        val out = IntArray(cw * ch)
        for (row in 0 until ch) System.arraycopy(px, (top + row) * width + left, out, row * cw, cw)
        return OcrImage(out, cw, ch)
    }
}

/** A line of text the detector found, in pixels of the picture it was given. */
data class TextBox(val x: Float, val y: Float, val w: Float, val h: Float, /** The mean probability inside the blob before it was grown. */ val score: Float)

/** How the detector's probability map becomes boxes (PaddleOCR's DB post-processing). */
data class BoxOptions(
    /** A pixel is text above this. */
    val threshold: Float = 0.3f,
    /** A blob is kept when its mean probability is at least this. */
    val minScore: Float = 0.6f,
    /** Blobs narrower or shorter than this, in pixels, are noise. */
    val minSide: Int = 4,
    /** How far to grow a blob back out: PaddleOCR's unclip_ratio. */
    val unclip: Float = 1.5f,
)

/** One character read from a line: where across the line and how sure. */
data class DecodedChar(val char: String, val p: Float, val step: Int)

class CtcReading(val text: String, val chars: List<DecodedChar>, /** The mean of the characters' probabilities; 0 for nothing read. */ val confidence: Float)

object OcrLogic {

    /** Recognisers read a line scaled to this height, as they were trained. */
    const val LINE_HEIGHT = 48

    /** Lines are read a little wider than their shape: doubled digits survive (desktop S5). */
    const val LINE_STRETCH = 1.5f

    /**
     * Text lines from a detector's probability map ([width] × [height], one number per pixel):
     * threshold, connected blobs (4-neighbour), keep the sure ones, grow each back out by
     * area × unclip / perimeter, clip to the picture ([limitW] × [limitH]; the map is padded to a
     * multiple of 32). Top to bottom, right to left within a row.
     */
    fun boxesFromProbability(probability: FloatArray, width: Int, height: Int, limitW: Int, limitH: Int, options: BoxOptions = BoxOptions()): List<TextBox> {
        val seen = BooleanArray(width * height)
        val boxes = ArrayList<TextBox>()
        val stack = IntArrayStack()
        for (start in 0 until width * height) {
            if (seen[start] || probability[start] < options.threshold) continue
            var x0 = width
            var y0 = height
            var x1 = 0
            var y1 = 0
            var count = 0
            var sum = 0.0
            stack.push(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val at = stack.pop()
                val x = at % width
                val y = at / width
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                count++
                sum += probability[at]
                // Four neighbours; the column test stops a blob wrapping from one row's end to the next row's start.
                for (next in intArrayOf(at - 1, at + 1, at - width, at + width)) {
                    if (next < 0 || next >= width * height || seen[next]) continue
                    if (abs(next % width - x) > 1 || probability[next] < options.threshold) continue
                    seen[next] = true
                    stack.push(next)
                }
            }
            val w = x1 - x0 + 1
            val h = y1 - y0 + 1
            val score = (sum / count).toFloat()
            if (w < options.minSide || h < options.minSide || score < options.minScore) continue
            val grow = w * h * options.unclip / (2f * (w + h))
            val left = max(0f, x0 - grow)
            val top = max(0f, y0 - grow)
            val right = min(limitW.toFloat(), x1 + grow)
            val bottom = min(limitH.toFloat(), y1 + grow)
            if (right <= left || bottom <= top) continue
            boxes.add(TextBox(left, top, right - left, bottom - top, score))
        }
        return boxes.sortedWith(compareBy<TextBox> { it.y }.thenByDescending { it.x })
    }

    /** Overlap of two boxes as a share of the smaller one: how tiled detections are de-duplicated. */
    fun overlapOfSmaller(a: TextBox, b: TextBox): Float {
        val w = min(a.x + a.w, b.x + b.w) - max(a.x, b.x)
        val h = min(a.y + a.h, b.y + b.h) - max(a.y, b.y)
        if (w <= 0f || h <= 0f) return 0f
        return w * h / min(a.w * a.h, b.w * b.h)
    }

    /**
     * A model's alphabet from its dictionary file: blank first, then one character per line, then
     * the space every PaddleOCR recogniser here is exported with.
     */
    fun alphabetFrom(dictionary: String): List<String> =
        listOf("") + dictionary.split("\r\n", "\n").filter { it.isNotEmpty() } + listOf(" ")

    /**
     * CTC greedy decoding of a recogniser's grid ([steps] rows × [classes] columns): the likeliest
     * class at every step, a class repeated on neighbouring steps merged into one character, blanks
     * (class 0) dropped.
     */
    fun decodeCtc(probabilities: FloatArray, steps: Int, classes: Int, alphabet: List<String>): CtcReading {
        // A dictionary that does not match its model shifts every character by one: fail, never guess.
        require(classes == alphabet.size) { "recogniser has $classes classes but its dictionary gives ${alphabet.size}" }
        val chars = ArrayList<DecodedChar>()
        var previous = -1
        for (step in 0 until steps) {
            var best = Float.NEGATIVE_INFINITY
            var index = 0
            val row = step * classes
            for (c in 0 until classes) {
                val v = probabilities[row + c]
                if (v > best) {
                    best = v
                    index = c
                }
            }
            if (index != previous && index != 0) chars.add(DecodedChar(alphabet[index], best, step))
            previous = index
        }
        val text = chars.joinToString("") { it.char }
        val confidence = if (chars.isEmpty()) 0f else (chars.sumOf { it.p.toDouble() } / chars.size).toFloat()
        return CtcReading(text, chars, confidence)
    }

    private val KEEPS_ORDER = Regex("[a-zA-Z0-9 :*./%+-]")

    /** Brackets and quotes that face the other way once a line is read right to left. */
    private val MIRROR = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    /**
     * An Arabic recogniser's text from the order it saw into the order it is read: PaddleOCR's
     * own pred_reverse, character for character. Runs of Latin letters, digits and a few marks
     * stay together; every other character stands alone; the sequence is reversed. One step more
     * than PaddleOCR: a bracket seen in the picture is turned round, so «(3/12000)» stays a
     * bracketed value in the text instead of «)3/12000(» (the desktop accepts either; here the
     * text goes into a searchable layer, so it is made right).
     */
    fun visualToLogical(visual: String): String {
        val runs = ArrayList<String>()
        val run = StringBuilder()
        var i = 0
        while (i < visual.length) {
            val cp = visual.codePointAt(i)
            val ch = String(Character.toChars(cp))
            if (KEEPS_ORDER.matches(ch)) {
                run.append(ch)
            } else {
                if (run.isNotEmpty()) {
                    runs.add(run.toString())
                    run.setLength(0)
                }
                runs.add(MIRROR[ch[0]]?.toString() ?: ch)
            }
            i += Character.charCount(cp)
        }
        if (run.isNotEmpty()) runs.add(run.toString())
        return runs.reversed().joinToString("")
    }

    /**
     * Where two letters were read much further apart than letters usually are, a word ended: a
     * space goes there (the recognisers trained on Chinese run words together).
     */
    fun withSpaces(chars: List<DecodedChar>): String {
        val gaps = ArrayList<Int>()
        for (i in 1 until chars.size) if (chars[i].char != " " && chars[i - 1].char != " ") gaps.add(chars[i].step - chars[i - 1].step)
        val typical = gaps.sorted().getOrNull(gaps.size / 2) ?: 0
        val out = StringBuilder(chars.firstOrNull()?.char ?: "")
        for (i in 1 until chars.size) {
            val gap = chars[i].step - chars[i - 1].step
            if (chars[i].char != " " && chars[i - 1].char != " " && typical > 0 && gap >= max(3, (typical * 1.8).toInt())) out.append(' ')
            out.append(chars[i].char)
        }
        return out.toString().replace(Regex(" {2,}"), " ")
    }

    val ARABIC = Regex("[\u0600-\u06FF]")

    /**
     * [image] scaled into a white [width] × [height] picture (drawn at [drawWidth] × [drawHeight]
     * from the top left) as the planes the models take: blue first (trained on OpenCV's BGR),
     * normalised by [mean] and [std]. Bilinear.
     */
    fun toPlanes(image: OcrImage, width: Int, height: Int, drawWidth: Int, drawHeight: Int, mean: FloatArray, std: FloatArray): FloatArray {
        val plane = width * height
        val out = FloatArray(3 * plane)
        val sx = image.width.toFloat() / drawWidth
        val sy = image.height.toFloat() / drawHeight
        val px = image.px
        val iw = image.width
        val ih = image.height
        val kb = 1f / std[0]
        val kg = 1f / std[1]
        val kr = 1f / std[2]
        for (y in 0 until height) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (ih - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(ih - 1, y0 + 1)
            val dy = fy - y0
            for (x in 0 until width) {
                var r = 255f
                var g = 255f
                var b = 255f
                if (x < drawWidth && y < drawHeight) {
                    val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (iw - 1).toFloat())
                    val x0 = fx.toInt()
                    val x1 = min(iw - 1, x0 + 1)
                    val dx = fx - x0
                    val p00 = px[y0 * iw + x0]
                    val p10 = px[y0 * iw + x1]
                    val p01 = px[y1 * iw + x0]
                    val p11 = px[y1 * iw + x1]
                    fun mix(shift: Int): Float {
                        val a = ((p00 shr shift) and 255) * (1 - dx) + ((p10 shr shift) and 255) * dx
                        val c = ((p01 shr shift) and 255) * (1 - dx) + ((p11 shr shift) and 255) * dx
                        return a * (1 - dy) + c * dy
                    }
                    r = mix(16)
                    g = mix(8)
                    b = mix(0)
                }
                val i = y * width + x
                out[i] = (b / 255f - mean[0]) * kb
                out[plane + i] = (g / 255f - mean[1]) * kg
                out[2 * plane + i] = (r / 255f - mean[2]) * kr
            }
        }
        return out
    }

    /** The width a line of [w] × [h] pixels is read at (its shape at LINE_HEIGHT, stretched). */
    fun readWidth(w: Int, h: Int): Int = max(16, (w.toFloat() * LINE_HEIGHT / h * LINE_STRETCH).roundToInt())

    private class IntArrayStack {
        private var data = IntArray(1024)
        private var size = 0
        fun push(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun pop(): Int = data[--size]
        fun isNotEmpty() = size > 0
    }
}
