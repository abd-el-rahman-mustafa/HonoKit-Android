package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Page lists and page numbers (review items P8 and N1; decisions §50). */
class PagesLogicTest {
    private fun ranges(s: String, count: Int = 20) = PdfFiles.parseRanges(s, count)?.map { (it.first + 1) to (it.last + 1) }

    @Test
    fun commasAndSpacesSeparateRanges() {
        assertEquals(listOf(1 to 3, 5 to 5, 7 to 9), ranges("1-3, 5, 7-9"))
        // P8: "1-3 5-7" was read as one range 1–7 on the desktop and refused here.
        assertEquals(listOf(1 to 3, 5 to 7), ranges("1-3 5-7"))
        assertEquals(listOf(1 to 3), ranges("1 - 3"))
        assertEquals(listOf(1 to 3, 6 to 6, 8 to 10), ranges(" 1 -3  6   8– 10 "))
        assertEquals(listOf(2 to 4, 9 to 9), ranges("٢-٤ ،٩"))
    }

    @Test
    fun badListsAreRefused() {
        assertNull(ranges(""))
        assertNull(ranges("3-1"))
        assertNull(ranges("1-30"))
        assertNull(ranges("1-2-3"))
        assertNull(ranges("a"))
        assertNull(ranges("0"))
    }

    /** N1 (owner's decision): "Start at 1" on pages 3–10 shows 1 on page 3; {N} is the last number shown. */
    @Test
    fun pageNumbersCountWithinTheChosenPages() {
        assertEquals("1 of 8", PageNumbers.label("{n} of {N}", 1, 0, 8, false))
        assertEquals("8 of 8", PageNumbers.label("{n} of {N}", 1, 7, 8, false))
        assertEquals("Page 12", PageNumbers.label("Page {n}", 5, 7, 8, false))
        assertEquals("٣ / ١٠", PageNumbers.label("{n} / {N}", 3, 0, 8, true))
    }
}
