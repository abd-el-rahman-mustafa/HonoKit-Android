package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScanFiltersTest {
    private val w = 600
    private val h = 800

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun r(p: Int) = (p shr 16) and 255
    private fun g(p: Int) = (p shr 8) and 255
    private fun b(p: Int) = p and 255
    private fun lum(p: Int) = (r(p) * 299 + g(p) * 587 + b(p) * 114) / 1000

    private fun isText(x: Int, y: Int) = (y / 20) % 2 == 1 && y % 20 in 6..13 && (x / 9) % 4 != 3 && x in 40..560
    private fun isStamp(x: Int, y: Int) = x in 420..520 && y in 660..740

    /**
     * A photographed page: slightly yellow paper, a heavy shadow over the left third (paper at
     * 40% of the light), lines of dark "text", a red stamp at the bottom right.
     */
    private fun page(): IntArray {
        val rnd = Random(3)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val light = if (x < 200) 0.4f + 0.6f * x / 200f else 1f
            val (pr, pg, pb) = when {
                isText(x, y) -> Triple(40, 40, 45)
                isStamp(x, y) -> Triple(200, 40, 40)
                else -> Triple(235, 225, 195)
            }
            val n = rnd.nextInt(-5, 6)
            rgb((pr * light).toInt() + n, (pg * light).toInt() + n, (pb * light).toInt() + n)
        }
    }

    private fun average(px: IntArray, pick: (Int, Int) -> Boolean, of: (Int) -> Int): Float {
        var sum = 0L
        var n = 0
        for (y in 0 until h) for (x in 0 until w) if (pick(x, y)) {
            sum += of(px[y * w + x])
            n++
        }
        return sum.toFloat() / n
    }

    @Test
    fun autoMakesTheShadowedPaperWhiteAndKeepsTheInk() {
        val px = page()
        ScanFilters.apply(px, w, h, ScanLook.Auto)
        val shadowPaper = average(px, { x, y -> x < 100 && !isText(x, y) && !isStamp(x, y) }) { lum(it) }
        val lightPaper = average(px, { x, y -> x > 300 && !isText(x, y) && !isStamp(x, y) }) { lum(it) }
        val text = average(px, { x, y -> isText(x, y) && x > 300 }) { lum(it) }
        assertTrue("paper in the shadow: $shadowPaper", shadowPaper > 235f)
        assertTrue("paper in the light: $lightPaper", lightPaper > 240f)
        assertTrue("text: $text", text < 70f)
    }

    @Test
    fun autoKeepsColour() {
        val px = page()
        ScanFilters.apply(px, w, h, ScanLook.Auto)
        val red = average(px, { x, y -> isStamp(x, y) }) { r(it) }
        val green = average(px, { x, y -> isStamp(x, y) }) { g(it) }
        assertTrue("the stamp stays red: r=$red g=$green", red > 150f && green < 100f)
        // The yellowed paper is neutral white, not cream.
        val paperBlue = average(px, { x, y -> x > 300 && !isText(x, y) && !isStamp(x, y) }) { b(it) }
        assertTrue("paper blue: $paperBlue", paperBlue > 235f)
    }

    @Test
    fun blackAndWhiteIgnoresTheShadow() {
        val px = page()
        ScanFilters.apply(px, w, h, ScanLook.BlackWhite)
        val shadowPaper = average(px, { x, y -> x < 100 && !isText(x, y) && !isStamp(x, y) }) { lum(it) }
        val shadowText = average(px, { x, y -> x in 50..150 && isText(x, y) }) { lum(it) }
        assertTrue("paper $shadowPaper", shadowPaper > 250f)
        assertTrue("text $shadowText", shadowText < 5f)
        assertTrue(px.all { it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun greyHasNoColourAndOriginalIsUntouched() {
        val px = page()
        ScanFilters.apply(px, w, h, ScanLook.Grey)
        assertTrue(px.all { r(it) == g(it) && g(it) == b(it) })
        val orig = page()
        val same = page()
        ScanFilters.apply(same, w, h, ScanLook.Original)
        assertTrue(orig.contentEquals(same))
    }

    @Test
    fun brightnessAndContrastMoveTheLevels() {
        val px = IntArray(4) { rgb(100, 100, 100) }
        ScanFilters.apply(px, 2, 2, ScanLook.Original, brightness = 0.2f)
        assertTrue(r(px[0]) > 120)
        val px2 = IntArray(4) { rgb(100, 100, 100) }
        ScanFilters.apply(px2, 2, 2, ScanLook.Original, contrast = 1.5f)
        assertTrue(r(px2[0]) < 100)
    }

    @Test
    fun aBlurredPageScoresLowerThanASharpOne() {
        val sharp = page()
        val blurred = page().also { blur(it, w, h, 3) }
        val qs = ScanFilters.quality(sharp, w, h)
        val qb = ScanFilters.quality(blurred, w, h)
        assertTrue("sharp ${qs.sharpness} blurred ${qb.sharpness}", qs.sharpness > qb.sharpness * 2)
        assertTrue("sharp ${qs.sharpness}", !qs.blurry)
        assertTrue("blurred ${qb.sharpness}", qb.blurry)
    }

    @Test
    fun sharpeningABlurredPageMakesItClearer() {
        val blurred = page().also { blur(it, w, h, 2) }
        val before = ScanFilters.quality(blurred, w, h).sharpness
        ScanFilters.sharpen(blurred, w, h, 1.5f)
        val after = ScanFilters.quality(blurred, w, h).sharpness
        assertTrue("before $before after $after", after > before * 1.8f)
    }

    @Test
    fun autoPaperIsPureWhiteNotLightGrey() {
        // The white point (docs/research-camscanner.md, change 1): paper grain clips to 255.
        val px = page()
        ScanFilters.apply(px, w, h, ScanLook.Auto)
        var paper = 0
        var white = 0
        for (y in 0 until h) for (x in 0 until w) if (x > 220 && !isText(x, y) && !isStamp(x, y) && (y / 20) % 2 == 0) {
            paper++
            if (px[y * w + x] == rgb(255, 255, 255)) white++
        }
        assertTrue("pure white $white of $paper", white > paper * 0.97)
    }

    @Test
    fun greyRemovesTheShadowToo() {
        val px = page()
        ScanFilters.apply(px, w, h, ScanLook.Grey)
        val shadowPaper = average(px, { x, y -> x < 100 && !isText(x, y) && !isStamp(x, y) }) { lum(it) }
        val text = average(px, { x, y -> isText(x, y) && x > 300 }) { lum(it) }
        assertTrue("paper in the shadow: $shadowPaper", shadowPaper > 235f)
        assertTrue("text: $text", text < 70f)
    }

    @Test
    fun aPageWithHardlyAnyInkKeepsItsGreys() {
        // One faint grey line on white: the black point stops at its cap, so the line is not
        // stretched to black and the paper's noise is not blown up.
        val px = IntArray(w * h) { i -> if (i / w in 400..403) rgb(180, 180, 180) else rgb(240, 240, 240) }
        ScanFilters.apply(px, w, h, ScanLook.Auto)
        val line = lum(px[401 * w + 300])
        assertTrue("line $line", line in 60..220)
    }

    @Test
    fun despeckleRemovesSpecksButKeepsDots() {
        val ww = 2000
        val hh = 60
        val black = 0xFF000000.toInt()
        val px = IntArray(ww * hh) { -1 }
        fun dot(x: Int, y: Int, size: Int) {
            for (dy in 0 until size) for (dx in 0 until size) px[(y + dy) * ww + x + dx] = black
        }
        dot(100, 10, 1)
        dot(300, 10, 1); dot(301, 10, 1)
        dot(500, 10, 2)
        dot(700, 10, 5)
        ScanFilters.despeckle(px, ww, hh)
        assertEquals(-1, px[10 * ww + 100])
        // At 2000 px a pair stays (only single pixels go below 3000 px).
        assertEquals(black, px[10 * ww + 300])
        assertEquals(black, px[10 * ww + 500])
        assertEquals(black, px[12 * ww + 702])

        val wide = 3200
        val px2 = IntArray(wide * hh) { -1 }
        for ((x, s) in listOf(100 to 1, 500 to 2, 900 to 5)) for (dy in 0 until s) for (dx in 0 until s) px2[(10 + dy) * wide + x + dx] = black
        px2[10 * wide + 300] = black
        px2[10 * wide + 301] = black
        ScanFilters.despeckle(px2, wide, hh)
        assertEquals(-1, px2[10 * wide + 100])
        assertEquals("a pair of pixels goes at 3000 px", -1, px2[10 * wide + 300])
        assertEquals("a 2×2 dot stays", black, px2[10 * wide + 500])
        assertEquals("an Arabic-sized dot stays", black, px2[12 * wide + 902])
    }

    @Test
    fun sharpeningLeavesPaperGrainAlone() {
        val rnd = Random(5)
        val px = IntArray(w * h) { val n = rnd.nextInt(-2, 3); rgb(200 + n, 200 + n, 200 + n) }
        val before = px.copyOf()
        ScanFilters.sharpen(px, w, h, 1.5f)
        assertTrue(px.contentEquals(before))
    }

    @Test
    fun aDimPhotoLosesItsColourSpeckle() {
        // A dark shot: paper at 30% light with strong colour noise (the light itself even, so only
        // the colour is noisy); letters still dark.
        val rnd = Random(9)
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (isText(x, y)) rgb(12, 12, 14) else {
                val red = 70 + rnd.nextInt(-12, 13)
                val blue = 62 + rnd.nextInt(-12, 13)
                val green = ((68f - 0.299f * red - 0.114f * blue) / 0.587f).toInt()
                rgb(red, green, blue)
            }
        }
        ScanFilters.apply(px, w, h, ScanLook.Auto)
        val spread = average(px, { x, y -> x > 300 && (y / 20) % 2 == 0 }) { maxOf(r(it), g(it), b(it)) - minOf(r(it), g(it), b(it)) }
        val text = average(px, { x, y -> isText(x, y) && x > 300 }) { lum(it) }
        assertTrue("colour spread on paper $spread", spread < 6f)
        assertTrue("text $text", text < 90f)
    }

    @Test
    fun pagesSnapToA4OrLetter() {
        assertEquals(PaperSize.A4, ScanFilters.snapPaper(2100, 2970))
        assertEquals(PaperSize.A4, ScanFilters.snapPaper(2970, 2150))
        assertEquals(PaperSize.Letter, ScanFilters.snapPaper(850, 1100))
        assertNull(ScanFilters.snapPaper(1000, 1000))
        assertNull(ScanFilters.snapPaper(800, 2000))
    }

    private fun blur(px: IntArray, w: Int, h: Int, r: Int) {
        val src = px.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val p = src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                sr += r(p); sg += g(p); sb += b(p); n++
            }
            px[y * w + x] = rgb(sr / n, sg / n, sb / n)
        }
    }
}
