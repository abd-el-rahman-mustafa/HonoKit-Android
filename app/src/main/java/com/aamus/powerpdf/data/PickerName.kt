package com.aamus.powerpdf.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Names for pictures from Android's photo picker (UX review finding 12). The picker hides a
 * picture's real file name on purpose: its DISPLAY_NAME is the media number ("1000000063.jpg"),
 * and the results were called "1000000063 (compressed).jpg". When the name is only that number,
 * the picture is named the way HonoKit names scans: "Photo 2026-10-04 11.26.jpg", from the date
 * it was taken (or today). Pure, unit-tested in PickerNameTest.
 */
object PickerName {
    /** Whether [authority] and [path] are a photo picker item (content://media/picker/…, picker_get_content). */
    fun isPicker(authority: String?, path: String?): Boolean =
        authority == "media" && path != null && (path.contains("/picker/") || path.startsWith("/picker") || path.contains("picker_get_content"))

    /** Whether [name] is only a media number, with or without an extension ("1000000063.jpg"). */
    fun isNumberOnly(name: String): Boolean {
        val base = name.substringBeforeLast('.', name)
        return base.length >= 4 && base.all { it.isDigit() }
    }

    /**
     * The name to use: [raw] itself unless it is a picker's media number, else "[word] date.ext"
     * with the date it was taken ([takenMillis], else [nowMillis]) and the extension of [raw] (or
     * from [mime]).
     */
    fun nameFor(raw: String, picker: Boolean, takenMillis: Long?, mime: String?, word: String, nowMillis: Long = System.currentTimeMillis()): String {
        if (!picker || !isNumberOnly(raw)) return raw
        val ext = raw.substringAfterLast('.', "").ifEmpty { extOf(mime) }
        val at = takenMillis?.takeIf { it > 0 } ?: nowMillis
        // Western digits in both languages, as the scanner's names (Gulf usage).
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(at))
        return if (ext.isEmpty()) "$word $stamp" else "$word $stamp.$ext"
    }

    private fun extOf(mime: String?): String = when (mime?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "image/avif" -> "avif"
        "image/gif" -> "gif"
        else -> ""
    }
}
