package com.aamus.powerpdf

import com.aamus.powerpdf.engine.img.ImgFormat

/**
 * Where a file from another app goes ("Open with", decisions §42). Pure logic, unit-tested in
 * OpenRouteTest: the activity reads the intent's action, type, name and first bytes, and this
 * decides.
 */
object OpenRoute {
    enum class Kind { Pdf, Image, Unknown }

    enum class Route {
        /** The PDF in the clean reader. */
        ViewPdf,
        /** The PDF in the reader with the editing tools already open. */
        EditPdf,
        /** The picture in the image viewer. */
        ViewImage,
        /** The picture in the photo editor. */
        EditImage,
    }

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "heic", "heif", "avif", "gif", "bmp", "dng", "ico", "wbmp")

    /**
     * What the file is: its first bytes first (a HEIC photo called .jpg, a PDF sent as
     * application/octet-stream), then the type the other app declared, then the name.
     */
    fun kindOf(mime: String?, name: String?, head: ByteArray?): Kind {
        if (head != null && head.isNotEmpty()) {
            when (ImgFormat.sniff(head, name ?: "")) {
                ImgFormat.PDF -> return Kind.Pdf
                null -> {}
                else -> return Kind.Image
            }
        }
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        if (m == "application/pdf" || m == "application/x-pdf") return Kind.Pdf
        if (m != null && m.startsWith("image/") && m != "image/*") return Kind.Image
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext == "pdf") return Kind.Pdf
        if (ext in IMAGE_EXTS) return Kind.Image
        if (m == "image/*") return Kind.Image
        return Kind.Unknown
    }

    /**
     * VIEW and EDIT, for a PDF or a picture. Anything we cannot tell is tried as a PDF, as before
     * (the PDF engine says clearly when it is not one).
     */
    fun of(edit: Boolean, kind: Kind): Route = when (kind) {
        Kind.Image -> if (edit) Route.EditImage else Route.ViewImage
        Kind.Pdf, Kind.Unknown -> if (edit) Route.EditPdf else Route.ViewPdf
    }
}
