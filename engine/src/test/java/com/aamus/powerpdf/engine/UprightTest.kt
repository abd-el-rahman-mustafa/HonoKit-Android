package com.aamus.powerpdf.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page geometry for markup on turned pages (desktop §78). Transforms as MuPDF gives them for a 500 pt square page. */
class UprightTest {
    private val plain = floatArrayOf(1f, 0f, 0f, -1f, 0f, 500f)
    private val r90 = floatArrayOf(0f, 1f, 1f, 0f, 0f, 0f)
    private val r180 = floatArrayOf(-1f, 0f, 0f, 1f, 500f, 0f)
    private val r270 = floatArrayOf(0f, -1f, -1f, 0f, 500f, 500f)
    /** /Rotate 90 with the MediaBox starting at 30,-60. */
    private val r90moved = floatArrayOf(0f, 1f, 1f, 0f, 60f, -30f)

    @Test
    fun uprightMatrixUndoesTheTurn() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), Upright.uprightMatrix(plain), 1e-5f)
        assertArrayEquals(Upright.turnMatrix(90), Upright.uprightMatrix(r90), 1e-5f)
        assertArrayEquals(Upright.turnMatrix(180), Upright.uprightMatrix(r180), 1e-5f)
        assertArrayEquals(Upright.turnMatrix(270), Upright.uprightMatrix(r270), 1e-5f)
        assertTrue(Upright.isIdentity(Upright.uprightMatrix(plain)))
    }

    @Test
    fun shownTurnIsTheRotation() {
        assertEquals(0, Upright.shownTurn(plain))
        assertEquals(90, Upright.shownTurn(r90))
        assertEquals(180, Upright.shownTurn(r180))
        assertEquals(270, Upright.shownTurn(r270))
        assertEquals(90, Upright.shownTurn(r90moved))
    }

    @Test
    fun turnMatrixSnapsToQuarterTurns() {
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0f, 0f, 0f), Upright.turnMatrix(90), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0f, 0f, 0f), Upright.turnMatrix(-270), 0f)
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f, -1f, 0f, 0f), Upright.turnMatrix(180), 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), Upright.turnMatrix(0), 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), Upright.turnMatrix(360), 0f)
    }

    @Test
    fun pointsGoThereAndBack() {
        for (t in listOf(plain, r90, r180, r270, r90moved)) {
            val (px, py) = Upright.toPdf(t, 123f, 45f)
            val (x, y) = Upright.toShown(t, px, py)
            assertEquals(123f, x, 1e-3f)
            assertEquals(45f, y, 1e-3f)
        }
    }

    /** The icon is drawn from the PDF top-left of /Rect, so that corner must show where the note was put. */
    @Test
    fun noteRectPutsThePdfTopLeftWhereThePersonTapped() {
        for (t in listOf(plain, r90, r180, r270, r90moved)) {
            val shown = Upright.noteRect(t, 300f, 120f)
            assertEquals(20f, shown[2] - shown[0], 1e-3f)
            assertEquals(20f, shown[3] - shown[1], 1e-3f)
            val pdf = Upright.pdfBounds(t, shown)
            val (x, y) = Upright.toShown(t, pdf[0], pdf[3])
            assertEquals(300f, x, 1e-3f)
            assertEquals(120f, y, 1e-3f)
        }
        assertArrayEquals(floatArrayOf(300f, 120f, 320f, 140f), Upright.noteRect(plain, 300f, 120f), 1e-3f)
    }

    @Test
    fun aMoveAsShownIsTheSameMoveInPdfSpace() {
        for (t in listOf(plain, r90, r180, r270, r90moved)) {
            val (dx, dy) = Upright.moveToPdf(t, 10f, -4f)
            val (x0, y0) = Upright.toPdf(t, 100f, 100f)
            val (x, y) = Upright.toShown(t, x0 + dx, y0 + dy)
            assertEquals(110f, x, 1e-3f)
            assertEquals(96f, y, 1e-3f)
        }
    }
}
