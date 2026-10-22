package com.aamus.powerpdf.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Comparing shows a file's words, so it follows the author's copy restriction. */
@RunWith(AndroidJUnit4::class)
class CompareRightsTest {
    @Before
    fun setUp() = Fixtures.init()

    @Test
    fun aFileThatForbidsCopyingIsNotCompared() {
        val src = Fixtures.pdf(content = "BT /F1 12 Tf 50 300 Td (Account 98765) Tj ET")
        val locked = Fixtures.temp("nocopy")
        val doc = Fixtures.open(src)
        try {
            PdfSecurity.protect(doc, locked, ProtectOptions("", "owner-secret", allowCopy = false))
        } finally {
            doc.close()
        }
        val restricted = Fixtures.open(locked)
        try {
            restricted.words()
            fail("a file that forbids copying gave its words to a comparison")
        } catch (e: PdfException) {
            assertEquals(PdfException.Code.Restricted, e.code)
        } finally {
            restricted.close()
        }
        val open = Fixtures.open(src)
        try {
            assertTrue(open.words().any { it.text == "98765" })
        } finally {
            open.close()
        }
    }
}
