package com.aamus.powerpdf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's pinned tools (decisions §44). */
class PinsTest {
    private val known = setOf("Sign", "Merge", "Compress", "ImgCompress", "EditText", "Split", "ImgResize")

    @Test fun neverSavedGivesTheDefaults() {
        assertEquals(listOf("Sign", "Merge", "Compress", "ImgCompress"), Pins.parse(null, known))
    }

    @Test fun anEmptyRowStaysEmpty() {
        assertEquals(emptyList<String>(), Pins.parse("", known))
    }

    @Test fun unknownAndRepeatedNamesAreDropped() {
        assertEquals(listOf("Split", "Sign"), Pins.parse("Split, Gone,Sign,Split", known))
    }

    @Test fun atMostFourAreKept() {
        assertEquals(4, Pins.parse("Sign,Merge,Compress,ImgCompress,EditText", known).size)
    }

    @Test fun writeAndParseRoundTrip() {
        val pins = listOf("EditText", "ImgResize")
        assertEquals(pins, Pins.parse(Pins.write(pins), known))
    }

    @Test fun togglePinsUnpinsAndRefusesAFifth() {
        val two = listOf("Sign", "Merge")
        assertEquals(listOf("Sign", "Merge", "Split"), Pins.toggle(two, "Split"))
        assertEquals(listOf("Merge"), Pins.toggle(two, "Sign"))
        val full = listOf("Sign", "Merge", "Compress", "ImgCompress")
        assertEquals(full, Pins.toggle(full, "Split"))
        assertFalse(Pins.canAdd(full, "Split"))
        assertTrue(Pins.canAdd(two, "Split"))
        assertFalse(Pins.canAdd(two, "Sign"))
    }

    @Test fun moveStaysInsideTheRow() {
        val pins = listOf("Sign", "Merge", "Compress")
        assertEquals(listOf("Merge", "Sign", "Compress"), Pins.move(pins, "Merge", -1))
        assertEquals(listOf("Sign", "Compress", "Merge"), Pins.move(pins, "Merge", 1))
        assertEquals(pins, Pins.move(pins, "Sign", -1))
        assertEquals(pins, Pins.move(pins, "Compress", 1))
        assertEquals(pins, Pins.move(pins, "Gone", 1))
    }
}
