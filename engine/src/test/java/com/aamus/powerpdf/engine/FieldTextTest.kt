package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size a field with no size of its own is drawn at. One line fills its box, as Acrobat
 * draws it; it used to stop at 12 pt however tall the box was, so a tall field showed small
 * text sitting low in it. Many lines keep their own limit.
 */
class FieldTextTest {
    @Test
    fun oneLineFollowsTheBox() {
        assertEquals(18.6f, autoFieldSize(30f, false), 1e-4f)
        assertEquals(37.2f, autoFieldSize(60f, false), 1e-4f)
        // Past the old limit: this is the case that was wrong.
        assertTrue(autoFieldSize(30f, false) > 12f)
        assertTrue(autoFieldSize(60f, false) > autoFieldSize(30f, false))
    }

    @Test
    fun manyLinesKeepTheirOwnLimit() {
        assertEquals(11f, autoFieldSize(60f, true), 1e-4f)
        assertEquals(20f / 2.6f, autoFieldSize(20f, true), 1e-4f)
    }

    @Test
    fun aStrangeBoxStillGivesAUsableSize() {
        assertEquals(6f, autoFieldSize(0f, false), 1e-4f)
        assertEquals(6f, autoFieldSize(-50f, false), 1e-4f)
        assertEquals(6f, autoFieldSize(Float.NaN, false), 1e-4f)
        assertEquals(MAX_FIELD_HEIGHT * 0.62f, autoFieldSize(Float.POSITIVE_INFINITY, false), 1e-2f)
        assertEquals(MAX_FIELD_HEIGHT * 0.62f, autoFieldSize(1e30f, false), 1e-2f)
        assertEquals(0f, autoFieldSize(Float.NaN, true), 1e-4f)
    }
}
