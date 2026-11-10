package com.aamus.powerpdf.engine

/**
 * Splitting text into its hard lines. Text typed in the app breaks with "\n", but pasted text
 * carries other breaks: Word puts a vertical tab on the clipboard for a manual line break
 * (Shift+Enter), browsers and some editors use LINE SEPARATOR and PARAGRAPH SEPARATOR, old Mac
 * text a lone CR. Split on "\n" alone, those were left inside a line and drawn as a character no
 * font has. Every Unicode mandatory break (UAX #14 class BK, plus CR, LF, NEL and the information
 * separators of bidi class B) ends a line here; CR LF counts as one break.
 */
private val ROW_BREAK = Regex("\r\n|[\n\r\u000B\u000C\u0085\u001C-\u001E\u2028\u2029]")

fun hardLines(text: String): List<String> = text.split(ROW_BREAK)
