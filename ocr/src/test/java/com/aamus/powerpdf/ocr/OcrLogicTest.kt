package com.aamus.powerpdf.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLogicTest {

    private fun grid(steps: List<Int>, classes: Int): FloatArray {
        val out = FloatArray(steps.size * classes) { 0.01f }
        for ((s, c) in steps.withIndex()) out[s * classes + c] = 0.9f
        return out
    }

    @Test
    fun ctcMergesRepeatsAndDropsBlanks() {
        val alphabet = listOf("", "1", "0", "4", "5")
        // 1 1 blank 0 4 4 5 blank 5 → "10455" with the doubled 5 kept by the blank between.
        val r = OcrLogic.decodeCtc(grid(listOf(1, 1, 0, 2, 3, 3, 4, 0, 4), 5), 9, 5, alphabet)
        assertEquals("10455", r.text)
        assertEquals(0.9f, r.confidence, 0.001f)
        // Without the blank the second 5 is lost: why lines are stretched before reading.
        assertEquals("1045", OcrLogic.decodeCtc(grid(listOf(1, 0, 2, 3, 4, 4), 5), 6, 5, alphabet).text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aWrongDictionaryFails() {
        OcrLogic.decodeCtc(FloatArray(10), 2, 5, listOf("", "a"))
    }

    @Test
    fun alphabetHasBlankFirstAndSpaceLast() {
        val a = OcrLogic.alphabetFrom("!\n\"\n#\n")
        assertEquals(listOf("", "!", "\"", "#", " "), a)
    }

    @Test
    fun arabicVisualOrderIsReversedWithLatinRunsKept() {
        assertEquals("عقد(3/12000)", OcrLogic.visualToLogical("(3/12000)دقع"))
        // In the picture the opening « shows at the right, mirrored: the model sees «يبد».
        assertEquals("«دبي»", OcrLogic.visualToLogical("«يبد»"))
        assertEquals("abc 12", OcrLogic.visualToLogical("abc 12"))
        assertEquals("سلام", OcrLogic.visualToLogical("مالس"))
    }

    @Test
    fun wideGapsBecomeSpaces() {
        val chars = "Helloworld".mapIndexed { i, c -> DecodedChar(c.toString(), 0.9f, if (i < 5) i * 2 else 10 + (i - 5) * 2 + 8) }
        assertEquals("Hello world", OcrLogic.withSpaces(chars))
    }

    @Test
    fun boxesComeFromBlobsGrownOut() {
        val w = 64
        val h = 32
        val map = FloatArray(w * h)
        // One sure line, 40 × 6, and a speck of 2 × 2.
        for (y in 10 until 16) for (x in 10 until 50) map[y * w + x] = 0.9f
        for (y in 25 until 27) for (x in 5 until 7) map[y * w + x] = 0.9f
        val boxes = OcrLogic.boxesFromProbability(map, w, h, w, h)
        assertEquals(1, boxes.size)
        val b = boxes[0]
        assertTrue("grown left ${b.x}", b.x < 10f && b.x >= 0f)
        assertTrue("grown wide ${b.w}", b.w > 40f)
        assertTrue("covers the line", b.y < 10f && b.y + b.h > 16f)
        assertEquals(0.9f, b.score, 0.001f)
    }

    @Test
    fun blobsDoNotWrapAcrossRows() {
        val w = 16
        val h = 12
        val map = FloatArray(w * h)
        // The end of row 3 and the start of row 4 are neighbours in memory, not on the page.
        for (y in 0 until 4) for (x in 8 until 16) map[y * w + x] = 0.9f
        for (y in 4 until 8) for (x in 0 until 8) map[y * w + x] = 0.9f
        assertEquals(2, OcrLogic.boxesFromProbability(map, w, h, w, h).size)
    }

    @Test
    fun planesAreBgrNormalisedAndPaddedWhite() {
        // A 2 × 1 picture: pure red then pure blue, drawn into a 4 × 1 plane set.
        val img = OcrImage(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), 2, 1)
        val p = OcrLogic.toPlanes(img, 4, 1, 2, 1, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
        // Blue plane first: pixel 0 is red (blue 0 → −1), pixel 1 blue (→ +1), padding white (→ +1).
        assertEquals(-1f, p[0], 0.001f)
        assertEquals(1f, p[1], 0.001f)
        assertEquals(1f, p[2], 0.001f)
        // Red plane last.
        assertEquals(1f, p[8], 0.001f)
        assertEquals(-1f, p[9], 0.001f)
    }

    @Test
    fun cropStaysInsideThePicture() {
        val img = OcrImage(IntArray(100) { it }, 10, 10)
        val c = img.crop(7f, 8f, 10f, 10f)
        assertEquals(3, c.width)
        assertEquals(2, c.height)
        assertEquals(87, c.px[0])
    }

    @Test
    fun readWidthStretchesTheLine() {
        assertEquals(144, OcrLogic.readWidth(200, 100))
        assertEquals(16, OcrLogic.readWidth(2, 100))
    }
}
