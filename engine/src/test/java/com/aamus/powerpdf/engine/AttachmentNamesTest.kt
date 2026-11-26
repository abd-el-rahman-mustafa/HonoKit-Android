package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** An incoming attachment whose name is taken gets " (2)", " (3)"… before its extension (desktop pages.ts). */
class AttachmentNamesTest {
    @Test
    fun aFreeNameIsKept() {
        assertEquals("notes.txt", freeAttachmentName("notes.txt", setOf("data.csv")))
    }

    @Test
    fun aTakenNameIsNumberedBeforeItsExtension() {
        assertEquals("notes (2).txt", freeAttachmentName("notes.txt", setOf("notes.txt")))
        assertEquals("notes (3).txt", freeAttachmentName("notes.txt", setOf("notes.txt", "notes (2).txt")))
        assertEquals("سجل (2).txt", freeAttachmentName("سجل.txt", setOf("سجل.txt")))
    }

    @Test
    fun aNameWithoutExtensionOrLeadingDotIsNumberedAtTheEnd() {
        assertEquals("README (2)", freeAttachmentName("README", setOf("README")))
        assertEquals(".env (2)", freeAttachmentName(".env", setOf(".env")))
    }
}
