package com.aamus.powerpdf.data

/**
 * The file chooser's rules (decisions §45; UX review findings 8–9). Pure, unit-tested in
 * ChoosingTest; the sheet is ui/Chooser.kt.
 */
object Choosing {
    /** What a tool takes. */
    enum class Kind { Pdf, Image }

    /** At most this many recent files in the sheet; the rest are a "Browse" away. */
    const val MAX_RECENT = 30

    /**
     * HonoKit's recent files a tool can take: only its kind (PDFs for PDF tools, pictures for image
     * tools), each file once as on Home (RecentList, decisions §48), starred ones first, then the newest.
     */
    fun recentsFor(kind: Kind, all: List<RecentFile>): List<RecentFile> = RecentList.distinct(all)
        .filter { if (kind == Kind.Image) it.isImage else !it.isImage }
        .sortedWith(compareByDescending<RecentFile> { it.starred }.thenByDescending { it.openedAt })
        .take(MAX_RECENT)

    /** Toggles [id] in a multiple choice, keeping the order things were picked in. */
    fun toggle(chosen: List<String>, id: String): List<String> = if (id in chosen) chosen - id else chosen + id
}
