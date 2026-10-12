package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordTest {
    private fun cp(vararg points: Int) = points.joinToString("") { String(Character.toChars(it)) }
    private val decomposed = "cafe" + cp(0x301)
    private val precomposed = "caf" + cp(0xe9)

    private fun refused(o: ProtectOptions): Boolean =
        runCatching { PdfSecurity.encryptOptions(o) }.exceptionOrNull().let { it is PdfException && it.code == PdfException.Code.BadArguments }

    @Test
    fun preparesPasswordsAsOtherReadersKeyThem() {
        assertEquals(precomposed, Passwords.prepared(decomposed))
        assertEquals("open sesame", Passwords.prepared("open" + cp(0xa0) + "sesame"))
        assertEquals("ab", Passwords.prepared("a" + cp(0xad) + "b"))
        assertEquals("كلمة-سر", Passwords.prepared("كلمة-سر"))
    }

    @Test
    fun writesThePreparedPassword() {
        val options = PdfSecurity.encryptOptions(ProtectOptions(decomposed))
        assertTrue(options.contains("user-password=$precomposed,"))
    }

    @Test
    fun refusesPasswordsReadersWouldCutOrEmpty() {
        val long = "ب".repeat(64)
        assertTrue(refused(ProtectOptions(long + "X")))
        assertFalse(refused(ProtectOptions("ب".repeat(63))))
        assertTrue(refused(ProtectOptions(cp(0xad))))
        assertTrue(refused(ProtectOptions("secret", "secret")))
        assertTrue(refused(ProtectOptions(precomposed, decomposed)))
    }

    @Test
    fun opensWithTheTypedOrThePreparedForm() {
        val tried = mutableListOf<String>()
        assertTrue(Passwords.authenticate(decomposed) { tried += it; it == precomposed })
        assertEquals(listOf(decomposed, precomposed), tried)
        assertFalse(Passwords.authenticate("plain") { false })
    }
}
