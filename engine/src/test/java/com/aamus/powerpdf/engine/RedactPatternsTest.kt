package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactPatternsTest {
    private fun found(text: String, p: RedactPattern) = RedactPatterns.matches(text, p).map { text.substring(it.first, it.last + 1) }

    @Test
    fun cardNumbersNeedTheLuhnCheck() {
        assertTrue(RedactPatterns.luhn("4111111111111111"))
        assertFalse(RedactPatterns.luhn("4111111111111112"))
        assertEquals(listOf("4111 1111 1111 1111"), found("card: 4111 1111 1111 1111 ok", RedactPattern.Card))
        assertEquals(emptyList<String>(), found("order 1234 5678 9012 3456", RedactPattern.Card))
    }

    @Test
    fun ibanNeedsItsChecksum() {
        assertTrue(RedactPatterns.ibanValid("AE07 0331 2345 6789 0123 456"))
        assertFalse(RedactPatterns.ibanValid("AE08 0331 2345 6789 0123 456"))
        assertEquals(listOf("ae07 0331 2345 6789 0123 456"), found("iban ae07 0331 2345 6789 0123 456", RedactPattern.Iban))
    }

    @Test
    fun uaePhonesEmiratesIdEmailAndDates() {
        assertEquals(listOf("+971 50 123 4567"), found("call +971 50 123 4567 now", RedactPattern.Phone))
        assertEquals(listOf("050 123 4567"), found("mobile 050 123 4567", RedactPattern.Phone))
        assertEquals(listOf("784-1990-1234567-1"), found("ID 784-1990-1234567-1.", RedactPattern.EmiratesId))
        assertEquals(listOf("info@example.com"), found("mail info@example.com today", RedactPattern.Email))
        assertEquals(listOf("30/09/2026", "2026-09-30"), found("on 30/09/2026 or 2026-09-30", RedactPattern.Date))
    }

    @Test
    fun arabicIndicDigitsCountAsDigits() {
        val text = RedactPatterns.westernDigits("الهوية ٧٨٤-١٩٩٠-١٢٣٤٥٦٧-١")
        assertEquals(listOf("784-1990-1234567-1"), found(text, RedactPattern.EmiratesId))
    }
}
