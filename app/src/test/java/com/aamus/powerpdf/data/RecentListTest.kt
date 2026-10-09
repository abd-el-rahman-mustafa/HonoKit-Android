package com.aamus.powerpdf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each file once in Recent, on Home and in the chooser (decisions §48; phone test 2026-10-04). */
class RecentListTest {
    private fun r(id: String, uri: String, name: String, size: Long, at: Long, kind: String = RecentFile.KIND_PDF, starred: Boolean = false) =
        RecentFile(id = id, uri = uri, name = name, size = size, pages = 1, openedAt = at, cached = null, starred = starred, kind = kind)

    // As on the owner's phone: pdf-sample.pdf opened by "Open with" (content Uri) and from inside
    // the app (a file path), fractal.jpg likewise; newest first, as the store keeps them.
    private val phone = listOf(
        r("1", "file:///data/files/test/pdf-sample.pdf", "pdf-sample.pdf", 8_100, at = 138),
        r("2", "content://media/external/file/41", "ielts.pdf", 1_400_000, at = 137),
        r("3", "content://media/external/images/media/12", "fractal.jpg", 443_000, at = 137, kind = RecentFile.KIND_IMAGE),
        r("4", "content://com.android.providers.downloads/9", "pdf-sample.pdf", 8_100, at = 137),
        r("5", "file:///sdcard/Pictures/hk-test/fractal.jpg", "fractal.jpg", 443_000, at = 136, kind = RecentFile.KIND_IMAGE),
    )

    @Test fun sameNameAndSizeIsOneFileKeepingTheNewest() {
        assertEquals(listOf("1", "2", "3"), RecentList.distinct(phone).map { it.id })
    }

    @Test fun chooserShowsEachFileOnceToo() {
        assertEquals(listOf("1", "2"), Choosing.recentsFor(Choosing.Kind.Pdf, phone).map { it.id })
        assertEquals(listOf("3"), Choosing.recentsFor(Choosing.Kind.Image, phone).map { it.id })
    }

    @Test fun newestKeptEvenWhenListedLater() {
        val list = listOf(r("old", "a", "x.pdf", 10, at = 1), r("new", "b", "X.PDF", 10, at = 9))
        assertEquals(listOf("new"), RecentList.distinct(list).map { it.id })
    }

    @Test fun aStarOnAHiddenTwinStays() {
        val list = listOf(r("new", "a", "x.pdf", 10, at = 9), r("old", "b", "x.pdf", 10, at = 1, starred = true))
        val one = RecentList.distinct(list).single()
        assertEquals("new", one.id)
        assertTrue(one.starred)
    }

    @Test fun differentFilesStaySeparate() {
        // Same name, other size; same size, other name; a PDF and a picture of the same name and size.
        assertFalse(RecentList.sameFile(r("a", "u1", "a.pdf", 10, 1), r("b", "u2", "a.pdf", 11, 1)))
        assertFalse(RecentList.sameFile(r("a", "u1", "a.pdf", 10, 1), r("b", "u2", "b.pdf", 10, 1)))
        assertFalse(RecentList.sameFile(r("a", "u1", "a", 10, 1), r("b", "u2", "a", 10, 1, kind = RecentFile.KIND_IMAGE)))
        // An unknown size never matches by name alone.
        assertFalse(RecentList.sameFile(r("a", "u1", "a.pdf", -1, 1), r("b", "u2", "a.pdf", -1, 1)))
        // The same address is the same file whatever the name says.
        assertTrue(RecentList.sameFile(r("a", "u1", "a.pdf", 10, 1), r("b", "u1", "renamed.pdf", 12, 1)))
    }

    @Test fun othersFindsTheTwins() {
        assertEquals(listOf("4"), RecentList.others(phone[0], phone).map { it.id })
    }
}
