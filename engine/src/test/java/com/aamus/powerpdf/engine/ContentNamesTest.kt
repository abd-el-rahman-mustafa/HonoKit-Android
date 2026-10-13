package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentNamesTest {
    @Test
    fun readsNamesWithEscapesAndDelimiters() {
        val names = ContentNames.of("q 1 0 0 1 0 0 cm /Im0 Do Q/Fm#201 Do[/X1]<</GS0 gs>>")
        assertTrue("Im0" in names)
        assertTrue("Fm 1" in names)
        assertTrue("X1" in names)
        assertTrue("GS0" in names)
        assertFalse("Im" in names)
        assertEquals(emptySet<String>(), ContentNames.of("BT (no names here) Tj ET"))
    }
}
