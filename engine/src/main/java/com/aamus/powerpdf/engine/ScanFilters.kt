package com.aamus.powerpdf.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a scanned page is cleaned (docs/research-scan.md M2). */
enum class ScanLook {
    /** The paper made white and even (shadows, yellowed paper), ink crisp, colour kept. The default. */
    Auto,
    Original,
    Grey,
    /** Ink or paper, nothing between (text pages; the smallest files). */
    BlackWhite,
    /** Whiter than Auto, with vivid marker colours. */
    Whiteboard,
    /**
     * Brighter and livelier without treating the picture as paper: ID cards, photos, colourful
     * pages (CamScanner's "Lighten"; docs/research-camscanner.md). Added last so saved names keep
     * their meaning.
     */
    Bright,
}

/**
 * Scan clean-up on plain ARGB pixels (no Android types, so it is tested on the JVM like
 * [PageEdges]). The idea every leading scanner shares: estimate the paper's own light at every
 * point of the page (ink removed by a local maximum, then smoothed), and divide it out, so a
 * shadow or a yellow sheet becomes white while the ink keeps its colour.
 */
object ScanFilters {

    /**
     * Applies [look] to [px] ([w] × [h], ARGB) in place. [brightness] −1..1 and [contrast]
     * 0.5..2 (1 = none) come after the look; [inkLevel] (0.5..0.95) is how much darker than the
     * paper a pixel must be to count as ink in black and white.
     */
    fun apply(px: IntArray, w: Int, h: Int, look: ScanLook, brightness: Float = 0f, contrast: Float = 1f, inkLevel: Float = 0.78f, sharpen: Float = 0f) {
        // The paper looks include a light sharpening of their own: letters as crisp and as heavy
        // as CamScanner's on the same photo (docs/research-camscanner.md, the 2026-10-02 comparison).
        val paperLook = look == ScanLook.Auto || look == ScanLook.Grey || look == ScanLook.Whiteboard
        val sharpen = if (paperLook) maxOf(sharpen, LOOK_SHARPEN) else sharpen
        val sharp = sharpen > 0.01f
        // A photo taken in dim light has coloured speckle on the paper; only the colour is smoothed,
        // so letters stay as sharp (docs/research-camscanner.md, change 5).
        if ((look == ScanLook.Auto || look == ScanLook.Whiteboard) && meanLight(px) < DIM) chromaDenoise(px, w, h)
        // Black and white: sharpened first, so the threshold meets steep letter edges.
        if (sharp && look == ScanLook.BlackWhite) sharpen(px, w, h, sharpen)
        // The paper is divided by a little less than its own light (white point), so paper grain,
        // JPEG noise and the estimate's error all become pure white: CamScanner's "Magic Color"
        // look, and smaller files (docs/research-camscanner.md, change 1). Grey is the same
        // clean-up without colour, so shadows and yellow paper go there too (change 2).
        when (look) {
            ScanLook.Original -> {}
            ScanLook.Grey -> evenPaper(px, w, h, white = 0.93f, strength = 1f, saturation = 0f, gamma = 1.1f)
            ScanLook.Auto -> evenPaper(px, w, h, white = 0.93f, strength = 1f, saturation = 1.12f, gamma = 1.1f)
            ScanLook.Whiteboard -> evenPaper(px, w, h, white = 0.88f, strength = 1.25f, saturation = 1.45f, gamma = 1.15f)
            ScanLook.BlackWhite -> {
                blackWhite(px, w, h, inkLevel)
                despeckle(px, w, h)
            }
            ScanLook.Bright -> bright(px)
        }
        // The other looks: sharpened last, on the clean page, so paper grain is not sharpened.
        if (sharp && look != ScanLook.BlackWhite) sharpen(px, w, h, sharpen)
        if (abs(brightness) > 0.001f || abs(contrast - 1f) > 0.001f) adjust(px, brightness, contrast)
    }

    /** The sharpening the paper looks always have (the adjustment can only raise it). */
    private const val LOOK_SHARPEN = 0.5f

    /** Below this mean light (0..255) a page photo counts as taken in dim light. */
    private const val DIM = 90

    /** The mean luminance of [px], from every 16th pixel. */
    private fun meanLight(px: IntArray): Int {
        var sum = 0L
        var n = 0
        var i = 0
        while (i < px.size) {
            sum += lum(px[i])
            n++
            i += 16
        }
        return if (n == 0) 255 else (sum / n).toInt()
    }

    /** Smooths the colour (Cb, Cr) of [px] and keeps its light (Y) as it is. */
    private fun chromaDenoise(px: IntArray, w: Int, h: Int) {
        val r = maxOf(2, maxOf(w, h) / 1000)
        // Blue and red minus the light, times 8 and offset, so the blurs work on positive integers.
        val cb = IntArray(px.size)
        val cr = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val y = lum(p)
            cb[i] = ((p and 255) - y) * 8 + 2048
            cr[i] = (((p shr 16) and 255) - y) * 8 + 2048
        }
        for (c in listOf(cb, cr)) {
            boxBlur(c, w, h, r)
            boxBlur(c, w, h, r)
        }
        for (i in px.indices) {
            val p = px[i]
            val y = lum(p)
            val b = y + (cb[i] - 2048) / 8f
            val rr = y + (cr[i] - 2048) / 8f
            // Y = 0.299 R + 0.587 G + 0.114 B, solved for G.
            val g = (y - 0.299f * rr - 0.114f * b) / 0.587f
            px[i] = (p and 0xFF000000.toInt()) or (clamp(rr) shl 16) or (clamp(g) shl 8) or clamp(b)
        }
    }

    private fun lum(p: Int): Int = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000

    /**
     * The paper's light, per channel, on a grid [step] pixels apart: a local maximum removes the
     * ink (letters are thinner than the window), two box blurs smooth what is left.
     */
    private class Paper(val r: IntArray, val g: IntArray, val b: IntArray, val sw: Int, val sh: Int, val step: Int)

    private fun paper(px: IntArray, w: Int, h: Int): Paper {
        val step = maxOf(1, maxOf(w, h) / 300)
        val sw = (w + step - 1) / step
        val sh = (h + step - 1) / step
        val r = IntArray(sw * sh)
        val g = IntArray(sw * sh)
        val b = IntArray(sw * sh)
        // Each grid cell: its brightest pixel (by luminance), so a cell with a letter keeps its paper.
        for (cy in 0 until sh) for (cx in 0 until sw) {
            var best = -1
            var bp = 0
            val y1 = minOf(h, (cy + 1) * step)
            val x1 = minOf(w, (cx + 1) * step)
            var y = cy * step
            while (y < y1) {
                var x = cx * step
                while (x < x1) {
                    val p = px[y * w + x]
                    val l = lum(p)
                    if (l > best) {
                        best = l
                        bp = p
                    }
                    x++
                }
                y++
            }
            val i = cy * sw + cx
            r[i] = (bp shr 16) and 255
            g[i] = (bp shr 8) and 255
            b[i] = bp and 255
        }
        // Only cells that look like paper say what the paper is: light enough (a shadow still
        // counts) and not strongly coloured. A stamp, a coloured box or a photo bigger than the
        // window below was taken for paper and washed out; such cells take the paper around them.
        fillNonPaper(r, g, b, sw, sh)
        // A closing wider than any letter: the local maximum removes the ink, the local minimum
        // after it brings the level back where the light falls off (a shadow's edge), and two light
        // blurs smooth what is left.
        val reach = maxOf(2, minOf(sw, sh) / 30)
        for (ch in listOf(r, g, b)) {
            rankFilter(ch, sw, sh, reach, max = true)
            rankFilter(ch, sw, sh, reach, max = false)
            boxBlur(ch, sw, sh, maxOf(1, reach / 2))
            boxBlur(ch, sw, sh, maxOf(1, reach / 2))
        }
        return Paper(r, g, b, sw, sh, step)
    }

    /**
     * Cells that are not paper (dark: ink; strongly coloured: a stamp, a picture) get the average
     * of the paper cells around them, from ever wider rings until every cell has a value. When
     * hardly any cell looks like paper (coloured paper, a photo), colour is not held against them.
     */
    private fun fillNonPaper(r: IntArray, g: IntArray, b: IntArray, sw: Int, sh: Int) {
        val n = r.size
        val l = IntArray(n) { (r[it] * 299 + g[it] * 587 + b[it] * 114) / 1000 }
        val bright = l.sortedArray()[(n * 0.95).toInt().coerceAtMost(n - 1)]
        fun colourful(i: Int): Boolean {
            val mx = maxOf(r[i], g[i], b[i])
            val mn = minOf(r[i], g[i], b[i])
            return mx > 0 && (mx - mn).toFloat() / mx > 0.3f
        }
        var paper = BooleanArray(n) { l[it] >= bright * 0.3f && !colourful(it) }
        if (paper.count { it } < n / 10) paper = BooleanArray(n) { l[it] >= bright * 0.3f }
        if (paper.count { it } < n / 10) return
        var missing = paper.count { !it }
        var reach = 2
        while (missing > 0 && reach < maxOf(sw, sh)) {
            val known = paper.copyOf()
            for (y in 0 until sh) for (x in 0 until sw) {
                val i = y * sw + x
                if (known[i]) continue
                var sr = 0L
                var sg = 0L
                var sb = 0L
                var k = 0
                // A sparse ring of samples is enough and keeps wide reaches cheap.
                val stride = maxOf(1, reach / 4)
                var yy = maxOf(0, y - reach)
                while (yy <= minOf(sh - 1, y + reach)) {
                    var xx = maxOf(0, x - reach)
                    while (xx <= minOf(sw - 1, x + reach)) {
                        val j = yy * sw + xx
                        if (known[j]) {
                            sr += r[j]; sg += g[j]; sb += b[j]; k++
                        }
                        xx += stride
                    }
                    yy += stride
                }
                if (k > 0) {
                    r[i] = (sr / k).toInt(); g[i] = (sg / k).toInt(); b[i] = (sb / k).toInt()
                    paper[i] = true
                    missing--
                }
            }
            reach *= 2
        }
    }

    /**
     * Divides the paper's light out, then stretches the darkest ink to black. Anything at least
     * [white] of the paper's light becomes white; [gamma] above 1 darkens the ink's soft edges.
     */
    private fun evenPaper(px: IntArray, w: Int, h: Int, white: Float, strength: Float, saturation: Float, gamma: Float) {
        val p = paper(px, w, h)
        // First pass: each channel over its paper level (paper → 255, and a little under it too).
        val hist = IntArray(256)
        for (y in 0 until h) {
            val cy = minOf(p.sh - 1, y / p.step)
            for (x in 0 until w) {
                val i = y * w + x
                val c = cy * p.sw + minOf(p.sw - 1, x / p.step)
                val v = px[i]
                val r = scale((v shr 16) and 255, p.r[c] * white, strength)
                val g = scale((v shr 8) and 255, p.g[c] * white, strength)
                val b = scale(v and 255, p.b[c] * white, strength)
                px[i] = (v and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                hist[lum(px[i])]++
            }
        }
        // The ink's own darkness becomes black: the level below which 0.5% of the pixels lie. On a
        // page with hardly any ink that level is paper, so it stops at BLACK_CAP (a stronger
        // stretch only makes noise).
        var black = 0
        var seen = 0
        val cut = px.size / 200
        while (black < BLACK_CAP && seen + hist[black] <= cut) {
            seen += hist[black]
            black++
        }
        val k = 1f / (255 - black).coerceAtLeast(1)
        val tone = FloatArray(256) { v -> 255f * Math.pow(((v - black) * k).coerceIn(0f, 1f).toDouble(), gamma.toDouble()).toFloat() }
        for (i in px.indices) {
            val v = px[i]
            var r = tone[(v shr 16) and 255]
            var g = tone[(v shr 8) and 255]
            var b = tone[v and 255]
            // A little more colour: stamps, highlights and signatures stay visible.
            val l = (r * 0.299f + g * 0.587f + b * 0.114f)
            r = l + (r - l) * saturation
            g = l + (g - l) * saturation
            b = l + (b - l) * saturation
            px[i] = (v and 0xFF000000.toInt()) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
        }
    }

    /**
     * Levels stretched over the whole picture (no paper estimate: a card or a photo is not paper):
     * the darkest 0.5% becomes black and the lightest 1% white, within limits so a picture without
     * real black or white is not forced; a gentle grey-world colour balance (at most ±12%); a
     * little lighter in the middle tones and a little more colour.
     */
    private fun bright(px: IntArray) {
        if (px.isEmpty()) return
        val hist = IntArray(256)
        var sr = 0L
        var sg = 0L
        var sb = 0L
        for (p in px) {
            hist[lum(p)]++
            sr += (p shr 16) and 255
            sg += (p shr 8) and 255
            sb += p and 255
        }
        fun level(fraction: Double, fromTop: Boolean): Int {
            val cut = (px.size * fraction).toLong()
            var seen = 0L
            if (fromTop) {
                for (v in 255 downTo 0) {
                    seen += hist[v]
                    if (seen > cut) return v
                }
                return 0
            }
            for (v in 0..255) {
                seen += hist[v]
                if (seen > cut) return v
            }
            return 255
        }
        val black = level(0.005, false).coerceAtMost(70)
        val white = level(0.01, true).coerceAtLeast(170)
        val grey = (sr + sg + sb) / 3.0
        fun gain(sum: Long) = if (sum == 0L) 1f else (grey / sum).toFloat().coerceIn(0.88f, 1.12f)
        val gr = gain(sr)
        val gg = gain(sg)
        val gb = gain(sb)
        val span = (white - black).coerceAtLeast(1).toFloat()
        val tone = FloatArray(256) { v -> 255f * Math.pow(((v - black) / span).coerceIn(0f, 1f).toDouble(), 0.9).toFloat() }
        for (i in px.indices) {
            val v = px[i]
            var r = tone[clamp(((v shr 16) and 255) * gr)]
            var g = tone[clamp(((v shr 8) and 255) * gg)]
            var b = tone[clamp((v and 255) * gb)]
            val l = r * 0.299f + g * 0.587f + b * 0.114f
            r = l + (r - l) * 1.15f
            g = l + (g - l) * 1.15f
            b = l + (b - l) * 1.15f
            px[i] = (v and 0xFF000000.toInt()) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
        }
    }

    /** The darkest level the black point may reach (docs/research-camscanner.md, change 1). */
    private const val BLACK_CAP = 110

    /** [v] over the paper level [paper] (paper → white); [strength] above 1 whitens more. */
    private fun scale(v: Int, paper: Float, strength: Float): Int {
        val out = v * 255f / paper.coerceAtLeast(24f)
        val pushed = if (strength > 1f) 255f - (255f - out) * strength else out
        return clamp(pushed)
    }

    private fun clamp(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /** Ink when clearly darker than the paper around it; everything else white. */
    private fun blackWhite(px: IntArray, w: Int, h: Int, level: Float) {
        val p = paper(px, w, h)
        val lv = level.coerceIn(0.5f, 0.95f)
        for (y in 0 until h) {
            val cy = minOf(p.sh - 1, y / p.step)
            for (x in 0 until w) {
                val i = y * w + x
                val c = cy * p.sw + minOf(p.sw - 1, x / p.step)
                val paperLum = (p.r[c] * 299 + p.g[c] * 587 + p.b[c] * 114) / 1000
                px[i] = if (lum(px[i]) < paperLum * lv) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
    }

    /**
     * Black specks with no ink around them (paper grain, dust) become paper: single pixels from
     * about 1800 px across, pairs from 3000 px. Never more: an Arabic letter's dot is only 5 or 6
     * pixels wide at 300 dpi (docs/research-camscanner.md, T8).
     */
    fun despeckle(px: IntArray, w: Int, h: Int) {
        val side = maxOf(w, h)
        if (side < 1800) return
        val pairs = side >= 3000
        val black = 0xFF000000.toInt()
        // The number of black 8-neighbours of (x, y); the last one found goes to last[0].
        fun around(x: Int, y: Int, last: IntArray): Int {
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx
                val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                if (px[yy * w + xx] == black) {
                    n++
                    last[0] = yy * w + xx
                }
            }
            return n
        }
        val gone = ArrayList<Int>()
        val last = IntArray(1)
        val other = IntArray(1)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (px[i] != black) continue
            when (around(x, y, last)) {
                0 -> gone.add(i)
                1 -> if (pairs) {
                    val j = last[0]
                    if (around(j % w, j / w, other) == 1) gone.add(i)
                }
            }
        }
        for (i in gone) px[i] = -1
    }

    /**
     * An unsharp mask for a shaken or out-of-focus photo of a page, on the light only (colour noise
     * and colour fringes are not sharpened): each pixel moves away from the blur of its
     * neighbourhood by [amount] (0..2.5), so soft letter edges become steep again. Differences of a
     * few levels (paper grain, JPEG noise) are left alone. The reach grows with the picture.
     */
    fun sharpen(px: IntArray, w: Int, h: Int, amount: Float) {
        val r = maxOf(1, (maxOf(w, h) / 1500f * (if (amount > 1.2f) 1.4f else 1f)).roundToInt())
        val k = amount.coerceIn(0f, 2.5f)
        val l = IntArray(px.size) { lum(px[it]) }
        val blurred = l.copyOf()
        boxBlur(blurred, w, h, r)
        boxBlur(blurred, w, h, r)
        for (i in px.indices) {
            val d = l[i] - blurred[i]
            val over = abs(d) - SHARPEN_THRESHOLD
            if (over <= 0) continue
            val delta = (if (d > 0) over else -over) * k
            val v = px[i]
            px[i] = (v and 0xFF000000.toInt()) or
                (clamp(((v shr 16) and 255) + delta) shl 16) or
                (clamp(((v shr 8) and 255) + delta) shl 8) or
                clamp((v and 255) + delta)
        }
    }

    /** Light differences up to this many levels are noise, not an edge, to [sharpen]. */
    private const val SHARPEN_THRESHOLD = 4

    private fun adjust(px: IntArray, brightness: Float, contrast: Float) {
        val add = brightness.coerceIn(-1f, 1f) * 128f
        val k = contrast.coerceIn(0.3f, 3f)
        val lut = IntArray(256) { v -> clamp((v - 128f) * k + 128f + add) }
        for (i in px.indices) {
            val v = px[i]
            px[i] = (v and 0xFF000000.toInt()) or (lut[(v shr 16) and 255] shl 16) or (lut[(v shr 8) and 255] shl 8) or lut[v and 255]
        }
    }

    /** The largest ([max]) or smallest value within [r] cells, in a square window. */
    private fun rankFilter(v: IntArray, w: Int, h: Int, r: Int, max: Boolean) {
        val tmp = IntArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = if (max) 0 else 255
            for (k in maxOf(0, x - r)..minOf(w - 1, x + r)) m = if (max) maxOf(m, v[y * w + k]) else minOf(m, v[y * w + k])
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = if (max) 0 else 255
            for (k in maxOf(0, y - r)..minOf(h - 1, y + r)) m = if (max) maxOf(m, tmp[k * w + x]) else minOf(m, tmp[k * w + x])
            v[y * w + x] = m
        }
    }

    private fun boxBlur(v: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(v.size)
        for (y in 0 until h) {
            var sum = 0
            val row = y * w
            for (x in -r..r) sum += v[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / (2 * r + 1)
                sum += v[row + (x + r + 1).coerceAtMost(w - 1)] - v[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                v[y * w + x] = sum / (2 * r + 1)
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    /** How a photo of a page came out: [sharpness] (Laplacian spread) and [light] (mean, 0..255). */
    class Quality(val sharpness: Float, val light: Float) {
        /** Clearly blurred (a shaken or out-of-focus shot). */
        val blurry: Boolean get() = sharpness < BLURRY
        val dark: Boolean get() = light < 60f
    }

    /** Below this a page photo looks blurred, measured on a ~600 px copy (a sharp test page 54, blurred 7). */
    const val BLURRY = 20f

    /** Measures [px]; best on a copy about 600 px across, the same size each time. */
    fun quality(px: IntArray, w: Int, h: Int): Quality {
        if (w < 3 || h < 3) return Quality(0f, 0f)
        val l = IntArray(px.size) { lum(px[it]) }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var light = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = 4 * l[i] - l[i - 1] - l[i + 1] - l[i - w] - l[i + w]
            sum += lap
            sumSq += lap.toDouble() * lap
            n++
            light += l[i]
        }
        val mean = sum / n
        return Quality(sqrt(sumSq / n - mean * mean).toFloat(), (light / n).toFloat())
    }

    /**
     * The paper a straightened page most likely is: A4 or Letter when its shape is within 6% of
     * theirs (portrait or landscape), else null (fit the page to the picture).
     */
    fun snapPaper(w: Int, h: Int): PaperSize? {
        if (w <= 0 || h <= 0) return null
        val ratio = maxOf(w, h).toFloat() / minOf(w, h)
        // The nearer of the two (their shapes are only 9% apart).
        return listOf(PaperSize.A4 to 297f / 210f, PaperSize.Letter to 11f / 8.5f)
            .map { (size, r) -> size to abs(ratio - r) / r }
            .filter { it.second < 0.06f }
            .minByOrNull { it.second }?.first
    }
}
