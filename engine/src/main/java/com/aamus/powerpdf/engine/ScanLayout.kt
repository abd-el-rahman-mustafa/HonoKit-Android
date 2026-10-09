package com.aamus.powerpdf.engine

import kotlin.math.abs

/**
 * Where scanned pictures go on paper for the scanner's special kinds (docs/research-camscanner.md,
 * part 2): an ID card at its real size, an open book split at its spine. Plain pixels, no Android
 * types, so it is tested on the JVM.
 */
object ScanLayout {

    /** ID-1 cards (ID cards, bank cards, driving licences): ISO/IEC 7810, millimetres. */
    const val CARD_W = 85.6f
    const val CARD_H = 53.98f

    /** ID-3 (a passport's photo page). */
    const val PASSPORT_W = 125f
    const val PASSPORT_H = 88f

    /**
     * The size in millimetres to print a straightened card [w] × [h] pixels: the exact ID-1 or
     * passport size when its shape is within 8% of one (turned to match the picture), else its
     * own shape with the long side of an ID-1 card.
     */
    fun cardSize(w: Int, h: Int): Pair<Float, Float> {
        if (w <= 0 || h <= 0) return CARD_W to CARD_H
        val wide = w >= h
        val ratio = maxOf(w, h).toFloat() / minOf(w, h)
        val (long, short) = listOf(CARD_W to CARD_H, PASSPORT_W to PASSPORT_H)
            .map { (lw, sh) -> Triple(lw, sh, abs(ratio - lw / sh) / (lw / sh)) }
            .filter { it.third < 0.08f }
            .minByOrNull { it.third }
            ?.let { it.first to it.second }
            ?: (CARD_W to CARD_W / ratio)
        return if (wide) long to short else short to long
    }

    /**
     * Where an open book's spine is in a straightened picture of both pages, as a fraction of the
     * width: the darkest column band (the fold's shadow) in the middle third. The middle (0.5)
     * when no fold stands out, so a split never lands somewhere surprising.
     */
    fun spine(px: IntArray, w: Int, h: Int): Float {
        if (w < 30 || h < 10) return 0.5f
        val from = (w * 0.35f).toInt()
        val to = (w * 0.65f).toInt()
        val y0 = (h * 0.08f).toInt()
        val y1 = (h * 0.92f).toInt()
        val ystep = maxOf(1, (y1 - y0) / 200)
        // Each column's mean light over most of its height (the top and bottom are often table).
        val col = FloatArray(to - from)
        for (x in from until to) {
            var sum = 0L
            var n = 0
            var y = y0
            while (y < y1) {
                val p = px[y * w + x]
                sum += (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
                n++
                y += ystep
            }
            col[x - from] = sum.toFloat() / maxOf(1, n)
        }
        // Smoothed over about 1% of the width, so a column of text or a rule does not win.
        val r = maxOf(1, w / 100)
        val smooth = FloatArray(col.size) { i ->
            var s = 0f
            var n = 0
            for (k in maxOf(0, i - r)..minOf(col.size - 1, i + r)) {
                s += col[k]
                n++
            }
            s / n
        }
        val best = smooth.indices.minByOrNull { smooth[it] } ?: return 0.5f
        val typical = smooth.sortedArray()[smooth.size / 2]
        // A fold is clearly darker than the pages beside it.
        if (smooth[best] > typical * 0.93f) return 0.5f
        return (from + best).toFloat() / w
    }
}
