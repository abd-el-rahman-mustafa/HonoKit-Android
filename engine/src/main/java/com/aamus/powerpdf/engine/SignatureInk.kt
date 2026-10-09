package com.aamus.powerpdf.engine

/**
 * A signature from a photo of it on paper (research-ux-mobile.md 3.3): the ink is kept, the paper
 * made transparent, and the result cropped to the ink. Plain Kotlin over ARGB pixels.
 */
object SignatureInk {

    class Result(val argb: IntArray, val width: Int, val height: Int)

    /**
     * [ink] makes every stroke one colour (RGB); null keeps the photo's colours. Each pixel is
     * compared with the paper around it (a wide blur of the photo), so uneven light and shadows do
     * not count as ink; a global threshold (Otsu) split the paper itself when the ink was thin. The
     * photo's outer 3 % (a shadow, the table) is ignored. Null when no ink stands out.
     */
    fun extract(px: IntArray, w: Int, h: Int, ink: Int?): Result? {
        if (w < 8 || h < 8) return null
        val lum = IntArray(w * h) { i ->
            val c = px[i]
            (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000
        }
        // The paper around each pixel: a wide blur, in which thin strokes all but vanish.
        val paper = lum.copyOf()
        val radius = maxOf(6, minOf(w, h) / 14)
        blur(paper, w, h, radius)
        blur(paper, w, h, radius)
        val edgeX = (w * 0.03).toInt()
        val edgeY = (h * 0.03).toInt()
        val alpha = IntArray(w * h)
        var inked = 0
        for (y in edgeY until h - edgeY) for (x in edgeX until w - edgeX) {
            val i = y * w + x
            // Darker than 80 % of the paper around it is ink; at 55 % it is fully opaque.
            val q = lum[i].toFloat() / paper[i].coerceAtLeast(1)
            if (q >= 0.8f) continue
            alpha[i] = if (q <= 0.55f) 255 else (255 * (0.8f - q) / 0.25f).toInt()
            if (alpha[i] > 96) inked++
        }
        // Too little is noise; too much is not a signature on paper (a dark photo).
        if (inked < 30 || inked > w * h * 0.35) return null
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (alpha[y * w + x] > 96) {
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        val m = 8
        val l = (x0 - m).coerceAtLeast(0)
        val tp = (y0 - m).coerceAtLeast(0)
        val r = (x1 + m).coerceAtMost(w - 1)
        val b = (y1 + m).coerceAtMost(h - 1)
        val ow = r - l + 1
        val oh = b - tp + 1
        val out = IntArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            val i = (y + tp) * w + (x + l)
            val a = alpha[i]
            if (a == 0) continue
            val rgb = ink ?: (px[i] and 0xFFFFFF)
            out[y * ow + x] = (a shl 24) or (rgb and 0xFFFFFF)
        }
        return Result(out, ow, oh)
    }

    /** Box blur of radius [r], rows then columns. */
    private fun blur(v: IntArray, w: Int, h: Int, r: Int) {
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
}
