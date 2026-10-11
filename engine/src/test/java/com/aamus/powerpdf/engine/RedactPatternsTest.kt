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
    fun internationalPhonesAreTakenWhole() {
        val text = "call +1 555.987.6543, office 0044 20 7946 0018, fax +49.2151.123456, desk +33 1 23 45 67 89 01 ext, home +971 50 123 4567"
        assertEquals(
            listOf("+1 555.987.6543", "0044 20 7946 0018", "+49.2151.123456", "+33 1 23 45 67 89 01", "+971 50 123 4567"),
            found(text, RedactPattern.Phone),
        )
        assertEquals(emptyList<String>(), found("on 01.10.2026 the ratio was 0.1234567 for item 12 345", RedactPattern.Phone))
    }

    @Test
    fun arabicIndicDigitsCountAsDigits() {
        val text = RedactPatterns.westernDigits("الهوية ٧٨٤-١٩٩٠-١٢٣٤٥٦٧-١")
        assertEquals(listOf("784-1990-1234567-1"), found(text, RedactPattern.EmiratesId))
    }

    @Test
    fun redactionFoldsTurkishIAndRepeatedSpaces() {
        val (text, map) = foldLineForRedact("İSTANBUL  ofisi".map { it.toString() })
        assertEquals("istanbul ofisi", text)
        assertEquals(text.length, map.size)
        assertEquals(10, map[9]) // the o after the two spaces
        assertEquals(foldLineForRedact(listOf("ırmak")).first, foldLineForRedact(listOf("IRMAK")).first)
    }

    @Test
    fun emailFindsAddressesInAnyScript() {
        assertEquals(listOf("müller@bücher.de"), found("write to müller@bücher.de today", RedactPattern.Email))
        assertEquals(listOf("иван@пример.рф"), found("почта иван@пример.рф", RedactPattern.Email))
        assertEquals(listOf("ayşe.yılmaz@örnek.com.tr"), found("ayşe.yılmaz@örnek.com.tr.", RedactPattern.Email))
        assertEquals(listOf("info@example.ae"), found("البريد info@example.ae للتواصل", RedactPattern.Email))
        // The domain still needs a dot and a top-level name made of letters.
        assertEquals(emptyList<String>(), found("user@localhost or a@b.1", RedactPattern.Email))
    }
}
