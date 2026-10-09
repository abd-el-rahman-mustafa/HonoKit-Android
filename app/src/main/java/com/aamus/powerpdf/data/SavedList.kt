package com.aamus.powerpdf.data

/**
 * The Files tab's rules (decisions §46; UX review finding 11): what HonoKit made — PDFs from
 * Downloads › HonoKit and pictures from Pictures › HonoKit — newest first, with a PDFs / Pictures
 * filter. Pure, unit-tested in SavedListTest.
 */
object SavedList {
    enum class Kind { Pdf, Picture }

    /** The filter chips: everything, PDFs only, pictures only. */
    enum class Show { All, Pdfs, Pictures }

    private val PICTURE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif", "bmp", "tif", "tiff", "ico")

    /**
     * What a saved file is, from its type and name; null for anything else HonoKit writes there
     * (text and Markdown exports), which this list leaves out.
     */
    fun kindOf(name: String, mime: String?): Kind? {
        val m = mime?.lowercase().orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m == Files.PDF || ext == "pdf" -> Kind.Pdf
            m.startsWith("image/") || ext in PICTURE_EXT -> Kind.Picture
            else -> null
        }
    }

    fun shows(show: Show, kind: Kind): Boolean = when (show) {
        Show.All -> true
        Show.Pdfs -> kind == Kind.Pdf
        Show.Pictures -> kind == Kind.Picture
    }

    /** [items] newest first (by [modified]), only the ones [show] lets through. */
    fun <T> arrange(items: List<T>, show: Show, kind: (T) -> Kind, modified: (T) -> Long): List<T> =
        items.filter { shows(show, kind(it)) }.sortedByDescending(modified)
}
