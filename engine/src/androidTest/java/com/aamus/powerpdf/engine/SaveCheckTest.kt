package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Save wrote replaces the person's file only when it opens again with every page (decisions
 * §61): a copy with fewer pages, a cut-off file or an empty one is refused, with the numbers.
 */
@RunWith(AndroidJUnit4::class)
class SaveCheckTest {
    @Before
    fun setUp() = Fixtures.init()

    private fun text(page: Int) = "BT /F1 20 Tf 60 400 Td (Page ${page + 1}) Tj ET"

    private fun refused(expect: List<Int>, check: () -> Unit) {
        try {
            check()
            fail("the check let it through")
        } catch (e: PdfException) {
            assertEquals(PdfException.Code.SaveCheck, e.code)
            assertEquals(expect, e.values)
        }
    }

    @Test
    fun aCopyWithEveryPagePasses() {
        val three = Fixtures.pages(3, ::text)
        PdfEngine.checkSaved(three, null, 3)
    }

    @Test
    fun aCopyWithFewerPagesIsRefused() {
        val three = Fixtures.pages(3, ::text)
        refused(listOf(3, 4)) { PdfEngine.checkSaved(three, null, 4) }
    }

    @Test
    fun aCutOffOrEmptyFileIsRefused() {
        val three = Fixtures.pages(3, ::text)
        val cut = Fixtures.temp("cut").apply { writeBytes(three.readBytes().copyOf(40)) }
        refused(listOf(0, 3)) { PdfEngine.checkSaved(cut, null, 3) }
        val empty = Fixtures.temp("empty")
        refused(listOf(0, 3)) { PdfEngine.checkSaved(empty, null, 3) }
    }

    @Test
    fun anEncryptedCopyOpensWithItsPassword() {
        val doc = Fixtures.open(Fixtures.pages(2, ::text))
        val locked = Fixtures.temp("locked")
        doc.use { it.save(locked, final = true, extra = "encrypt=aes-256,user-password=open-me,owner-password=owner-me") }
        PdfEngine.checkSaved(locked, "open-me", 2)
        refused(listOf(0, 2)) { PdfEngine.checkSaved(locked, null, 2) }
    }
}
