package com.aamus.powerpdf.engine

import java.text.Normalizer

/**
 * AES-256 passwords (revision 6) are keyed on the password after SASLprep (ISO 32000-2
 * 7.6.4.3.3): characters that read as nothing are dropped, other spaces become a plain space, and
 * the text is NFKC-normalised. Acrobat does this; MuPDF uses the bytes as typed. So a password
 * with a no-break space, a soft hyphen, a zero-width joiner or a decomposed accent opened in one
 * and not the other.
 *
 * This is the mapping and normalisation part of SASLprep; a password it would refuse (bidi rules,
 * prohibited characters) is used as typed, as other readers fall back to.
 */
object Passwords {
    private val NOTHING = Regex("[\u00AD\u034F\u1806\u180B-\u180D\u200B-\u200D\u2060\uFE00-\uFE0F\uFEFF]")
    private val SPACES = Regex("[\u00A0\u1680\u2000-\u200A\u202F\u205F\u3000]")

    fun prepared(password: String): String =
        Normalizer.normalize(password.replace(NOTHING, "").replace(SPACES, " "), Normalizer.Form.NFKC)

    /** The bytes a reader keys the file on: the prepared password's UTF-8, of which only 127 count. */
    fun byteLength(password: String): Int = prepared(password).toByteArray(Charsets.UTF_8).size

    /** Tries the password as typed, then prepared (a file written by Acrobat from the same text). */
    fun authenticate(password: String, attempt: (String) -> Boolean): Boolean {
        if (attempt(password)) return true
        val p = prepared(password)
        return p != password && attempt(p)
    }
}
