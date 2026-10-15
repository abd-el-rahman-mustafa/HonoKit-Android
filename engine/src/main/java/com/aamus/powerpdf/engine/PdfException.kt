package com.aamus.powerpdf.engine

/**
 * An error with a code the interface turns into a sentence in the person's language. [values]
 * carries the numbers that sentence needs (SaveCheck: the pages found, then the pages expected).
 */
class PdfException(val code: Code, message: String, val values: List<Int> = emptyList()) : Exception(message) {
    enum class Code {
        Corrupt,
        NotPdf,
        PasswordRequired,
        WrongPassword,
        BadArguments,
        /** The author does not allow this change (desktop decisions §23). */
        Restricted,
        Unsupported,
        /** Removing the old words would have disturbed the text around them: nothing was changed (desktop §58). */
        EditUnsafe,
        Io,
        /** The file is digitally signed; changing its password would break the signatures (desktop security.ts refuseSigned). */
        Signed,
        /** A dynamic XFA form (Adobe LiveCycle): only Adobe's readers can show and fill it (desktop forms.ts). */
        DynamicXfa,
        /** What was written would not open again with every page, so it did not replace anything (decisions §61). */
        SaveCheck,
    }
}
